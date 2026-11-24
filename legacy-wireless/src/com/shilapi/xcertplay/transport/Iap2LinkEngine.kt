package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * The iAP2 link layer, deliberately kept independent from I/O, threads, CSM, and media.
 *
 * The caller supplies a monotonic millisecond clock, writes [takeOutput] to its byte stream, and
 * feeds arbitrary receive fragments back through [feed]. It exposes control-session bytes and raw
 * payloads from other negotiated sessions. It does not open a Lockdown connection or own a
 * [BlockingDuplexByteStream].
 *
 * An inbound wire frame is limited to the largest u16 length (`65535` bytes total).  Pending
 * outbound and out-of-order packets are additionally capped by [Iap2LinkConfig] (64 by default).
 * Pending output is capped at 1 MiB and pending events at 256 entries by default, so neither peer
 * input nor a caller that temporarily stops draining the engine can grow memory without bound.
 */
class Iap2LinkEngine(
    private val config: Iap2LinkConfig = Iap2LinkConfig(),
    private val onLinkDiagnostic: ((stage: String, sequence: Int, payloadBytes: Int, retryCount: Int) -> Unit)? = null,
) {
    internal data class OutputBatch(
        val bytes: ByteArray,
        val completedFlushReceipts: List<Iap2TransportFlushReceipt>,
    )

    enum class State {
        IDLE,
        DETECTING,
        NEGOTIATING,
        NORMAL,
        DEAD,
    }

    sealed class Event {
        data class Control(val bytes: ByteArray) : Event()
        data class Session(val sessionId: Int, val bytes: ByteArray) : Event()
        data class Writable(val value: Boolean) : Event()
        data class Dead(val reason: String?) : Event()
    }

    data class SessionDescriptor(
        val id: Int,
        val kind: Int,
        val version: Int,
    ) {
        init {
            require(id in 0..0xff)
            require(kind in 0..0xff)
            require(version in 0..0xff)
        }
    }

    data class SynchronizationPayload(
        val maxOutgoing: Int,
        val maxLength: Int,
        val retransmissionTimeoutMillis: Int,
        val acknowledgementTimeoutMillis: Int,
        val maxRetransmissions: Int,
        val maxAcknowledgements: Int,
        val sessions: List<SessionDescriptor>,
    ) {
        init {
            require(maxOutgoing in 0..0xff)
            require(maxLength in 0..MAX_WIRE_FRAME_BYTES)
            require(retransmissionTimeoutMillis in 0..0xffff)
            require(acknowledgementTimeoutMillis in 0..0xffff)
            require(maxRetransmissions in 0..0xff)
            require(maxAcknowledgements in 0..0xff)
        }

        fun encode(): ByteArray {
            val out = ByteArrayOutputStream(10 + sessions.size * SESSION_DESCRIPTOR_BYTES)
            out.write(SYNCHRONIZATION_VERSION)
            out.write(maxOutgoing)
            writeU16(out, maxLength)
            writeU16(out, retransmissionTimeoutMillis)
            writeU16(out, acknowledgementTimeoutMillis)
            out.write(maxRetransmissions)
            out.write(maxAcknowledgements)
            sessions.forEach {
                out.write(it.id)
                out.write(it.kind)
                out.write(it.version)
            }
            return out.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): SynchronizationPayload? {
                if (bytes.size < SYNCHRONIZATION_FIXED_BYTES || u8(bytes[0]) != SYNCHRONIZATION_VERSION) {
                    return null
                }
                val descriptors = ArrayList<SessionDescriptor>()
                var offset = SYNCHRONIZATION_FIXED_BYTES
                while (offset + SESSION_DESCRIPTOR_BYTES <= bytes.size) {
                    descriptors += SessionDescriptor(u8(bytes[offset]), u8(bytes[offset + 1]), u8(bytes[offset + 2]))
                    offset += SESSION_DESCRIPTOR_BYTES
                }
                return SynchronizationPayload(
                    maxOutgoing = u8(bytes[1]),
                    maxLength = readU16(bytes, 2),
                    retransmissionTimeoutMillis = readU16(bytes, 4),
                    acknowledgementTimeoutMillis = readU16(bytes, 6),
                    maxRetransmissions = u8(bytes[8]),
                    maxAcknowledgements = u8(bytes[9]),
                    sessions = descriptors,
                )
            }
        }
    }

    private data class Header(
        val length: Int,
        val control: Int,
        val sequence: Int,
        val acknowledgement: Int,
        val sessionId: Int,
    )

    private data class Packet(
        var sequence: Int,
        val sessionId: Int,
        val payload: ByteArray,
        var retransmissions: Int = 0,
        var deadlineMillis: Long = 0,
        val flushReceipt: Iap2TransportFlushReceipt? = null,
    )

    private var state = State.IDLE
    private var peerSynchronization = localSynchronization()
    private var peerSynchronizationReceived = false
    private var zeroAcknowledgements = false
    private var sentSequence = INITIAL_SEQUENCE
    private var lastSentAcknowledged = INITIAL_SEQUENCE
    private var lastReceivedInOrder = 0
    private var lastAcknowledgedReceived = 0
    private var pendingAcknowledgements = 0
    private var writable = false
    private var linkDiagnosticCount = 0

    private val unacknowledged = ArrayList<Packet>()
    private val queued = ArrayDeque<Packet>()
    private val outOfOrder = ArrayList<Packet>()
    private val events = ArrayDeque<Event>()
    private val output = ByteArrayOutputStream()
    private val outputFlushReceipts = ArrayList<Iap2TransportFlushReceipt>()
    private val receive = ReceiveBuffer()
    private var awaitingPayload: Header? = null

    private var markerDeadlineMillis: Long? = null
    private var synchronizationDeadlineMillis: Long? = null
    private var sendAcknowledgementDeadlineMillis: Long? = null
    private var receiveAcknowledgementDeadlineMillis: Long? = null

    fun state(): State = state

    fun writable(): Boolean = writable

    fun peerSynchronization(): SynchronizationPayload = peerSynchronization

    internal fun pendingReceiveBytes(): Int = receive.size + if (awaitingPayload == null) 0 else HEADER_BYTES

    fun takeOutput(): ByteArray {
        return takeOutputBatch().bytes
    }

    /** Takes queued bytes and only the receipts for tracked data frames included in those bytes. */
    internal fun takeOutputBatch(): OutputBatch {
        val bytes = output.toByteArray()
        output.reset()
        val receipts = outputFlushReceipts.toList()
        outputFlushReceipts.clear()
        return OutputBatch(bytes, receipts)
    }

    fun pollEvent(): Event? = if (events.isEmpty()) null else events.removeFirst()

    fun nextDeadlineMillis(): Long? = listOfNotNull(
        markerDeadlineMillis,
        synchronizationDeadlineMillis,
        sendAcknowledgementDeadlineMillis,
        receiveAcknowledgementDeadlineMillis,
    ).minOrNull()

    /** Starts a link attempt. Wired CarKit uses [wiredInitiator] = true. */
    fun start(wiredInitiator: Boolean, nowMillis: Long) {
        if (state != State.IDLE) return
        state = State.DETECTING
        if (!appendOutput(IAP2_MARKER)) return
        markerDeadlineMillis = nowMillis + MARKER_RESEND_MILLIS
        if (wiredInitiator && state != State.DEAD) enterNegotiating(nowMillis)
    }

    /** Supplies any received byte fragment; empty fragments are harmless. */
    fun feed(bytes: ByteArray, nowMillis: Long) {
        if (state == State.DEAD || bytes.isEmpty()) return
        var offset = 0
        while (offset < bytes.size && state != State.DEAD) {
            val room = receive.remainingCapacity()
            if (room == 0) {
                if (!parseAvailable(nowMillis)) {
                    die("iAP2 inbound frame exceeds $MAX_WIRE_FRAME_BYTES bytes")
                    return
                }
                continue
            }
            val count = minOf(room, bytes.size - offset)
            receive.append(bytes, offset, count)
            offset += count
            while (parseAvailable(nowMillis) && state != State.DEAD) {
                // Drain all complete frames before accepting more input.
            }
        }
    }

    /** The byte stream ended. */
    fun feedEof() {
        if (state != State.DEAD) {
            emitLinkDiagnostic("link_eof", -1, 0, 0, terminal = true)
        }
        die(null)
    }

    /** Advances all due protocol timers with the caller's monotonic clock. */
    fun advanceTime(nowMillis: Long) {
        while (state != State.DEAD) {
            val deadline = nextDeadlineMillis() ?: return
            if (deadline > nowMillis) return
            when {
                markerDeadlineMillis?.let { it <= nowMillis } == true -> {
                    markerDeadlineMillis = null
                    if (state == State.DETECTING) {
                        if (appendOutput(IAP2_MARKER)) {
                            markerDeadlineMillis = nowMillis + MARKER_RESEND_MILLIS
                        }
                    }
                }

                synchronizationDeadlineMillis?.let { it <= nowMillis } == true -> {
                    synchronizationDeadlineMillis = null
                    if (state == State.NEGOTIATING) {
                        sendSynchronization()
                        if (state != State.DEAD) {
                            synchronizationDeadlineMillis = nowMillis + SYNCHRONIZATION_RESEND_MILLIS
                        }
                    }
                }

                sendAcknowledgementDeadlineMillis?.let { it <= nowMillis } == true -> {
                    sendAcknowledgementDeadlineMillis = null
                    if (state == State.NORMAL) {
                        sendAcknowledgement()
                    }
                }

                receiveAcknowledgementDeadlineMillis?.let { it <= nowMillis } == true -> {
                    receiveAcknowledgementDeadlineMillis = null
                    retransmitDuePacket(nowMillis)
                }
            }
        }
    }

    /** Queues a complete raw control-session payload (session id 10). */
    fun sendControl(bytes: ByteArray, nowMillis: Long) {
        sendSession(CONTROL_SESSION_ID, bytes, nowMillis)
    }

    /** Queues one raw payload on a negotiated iAP2 session. */
    fun sendSession(sessionId: Int, bytes: ByteArray, nowMillis: Long) {
        sendSession(sessionId, bytes, nowMillis, flushReceipt = null)
    }

    /** Queues one session payload whose receipt travels with its exact iAP2 data frame. */
    internal fun sendSessionTracked(
        sessionId: Int,
        bytes: ByteArray,
        nowMillis: Long,
        flushReceipt: Iap2TransportFlushReceipt,
    ) {
        sendSession(sessionId, bytes, nowMillis, flushReceipt)
    }

    private fun sendSession(
        sessionId: Int,
        bytes: ByteArray,
        nowMillis: Long,
        flushReceipt: Iap2TransportFlushReceipt?,
    ) {
        require(sessionId in 0..0xff) { "iAP2 session id must fit in one byte" }
        require(bytes.size <= MAX_PAYLOAD_BYTES) {
            "iAP2 session payload exceeds $MAX_PAYLOAD_BYTES bytes"
        }
        if (peerSynchronizationReceived || state == State.NORMAL) {
            require(peerPayloadIsAcceptable(bytes)) {
                "iAP2 session payload exceeds peer maxLength ${peerSynchronization.maxLength}"
            }
        }
        sendPacket(Packet(0, sessionId, bytes.copyOf(), flushReceipt = flushReceipt), nowMillis)
    }

    private fun parseAvailable(nowMillis: Long): Boolean {
        if (state == State.DEAD) return false
        if (state == State.DETECTING) {
            if (receive.size < IAP2_MARKER.size) return false
            if (!receive.matches(IAP2_MARKER)) {
                die("iAP2 marker was not received")
                return false
            }
            receive.discard(IAP2_MARKER.size)
            enterNegotiating(nowMillis)
            return true
        }

        val pending = awaitingPayload
        if (pending != null) {
            val payloadWithChecksumBytes = pending.length - HEADER_BYTES
            if (receive.size < payloadWithChecksumBytes) return false
            val payloadWithChecksum = receive.take(payloadWithChecksumBytes)
            awaitingPayload = null
            if (checksumValid(payloadWithChecksum)) {
                processFrame(
                    pending,
                    payloadWithChecksum.copyOf(payloadWithChecksum.size - CHECKSUM_BYTES),
                    nowMillis,
                )
            } else {
                emitLinkDiagnostic("link_rx_bad_payload", pending.sequence, payloadWithChecksumBytes - CHECKSUM_BYTES, -1)
            }
            return true
        }

        var discarded = 0
        while (receive.size >= 2 && (receive[0] != LINK_START_HIGH || receive[1] != LINK_START_LOW)) {
            receive.discard(1)
            discarded++
        }
        if (discarded > 0) emitLinkDiagnostic("link_rx_resync", -1, discarded, -1)
        if (receive.size < HEADER_BYTES) return discarded > 0
        val header = parseHeader(receive.peek(HEADER_BYTES))
        if (header == null || header.length < HEADER_BYTES) {
            // An invalid header may overlap the start of the next valid frame.
            receive.discard(1)
            emitLinkDiagnostic("link_rx_bad_header", -1, HEADER_BYTES, -1)
            return true
        }
        receive.discard(HEADER_BYTES)
        if (header.length == HEADER_BYTES) {
            processFrame(header, null, nowMillis)
        } else {
            awaitingPayload = header
        }
        return true
    }

    private fun enterNegotiating(nowMillis: Long) {
        state = State.NEGOTIATING
        markerDeadlineMillis = null
        sendSynchronization()
        if (state != State.DEAD) {
            synchronizationDeadlineMillis = nowMillis + SYNCHRONIZATION_RESEND_MILLIS
        }
    }

    private fun processFrame(header: Header, payload: ByteArray?, nowMillis: Long) {
        if ((header.control and CONTROL_RESET) != 0) {
            emitLinkDiagnostic("link_peer_reset", header.sequence, payload?.size ?: 0, 0, terminal = true)
            die("peer sent iAP2 reset")
            return
        }
        if ((header.control and CONTROL_SYNCHRONIZE) != 0) {
            SynchronizationPayload.decode(payload ?: ByteArray(0))?.let {
                handleSynchronization(it, header.sequence)
            }
        }
        if ((header.control and CONTROL_ACKNOWLEDGEMENT) != 0) {
            val lastTransmitted = sentSequence
            if (handleAcknowledgement(header.acknowledgement, nowMillis) &&
                header.control == CONTROL_EXTENDED_ACK && payload != null
            ) {
                handleExtendedAcknowledgement(payload, lastTransmitted, nowMillis)
            }
        }
        if (state == State.NORMAL && header.control == CONTROL_ACKNOWLEDGEMENT && payload != null && payload.isNotEmpty()) {
            emitLinkDiagnostic("link_rx_data", header.sequence, payload.size, -1)
            handleData(Packet(header.sequence, header.sessionId, payload), nowMillis)
        }
    }

    private fun handleSynchronization(sync: SynchronizationPayload, sequence: Int) {
        if (state != State.NEGOTIATING) return
        peerSynchronization = sync
        peerSynchronizationReceived = true
        zeroAcknowledgements = sync.maxAcknowledgements == 0 && sync.maxRetransmissions == 0 &&
            sync.acknowledgementTimeoutMillis == 0 && sync.retransmissionTimeoutMillis == 0
        lastReceivedInOrder = sequence
        sendAcknowledgement()
    }

    private fun handleAcknowledgement(acknowledgement: Int, nowMillis: Long): Boolean {
        if (state == State.NEGOTIATING) {
            if (!peerSynchronizationReceived || acknowledgement != sentSequence) return false
            if (!peerLimitsAreUsable() || queued.any { !peerPayloadIsAcceptable(it.payload) }) {
                die("peer iAP2 link limits cannot carry pending control packets")
                return false
            }
            state = State.NORMAL
            synchronizationDeadlineMillis = null
            setWritable(true)
        }
        if (state != State.NORMAL) return false
        val previousAcknowledgement = lastSentAcknowledged
        val advance = sequenceDistance(acknowledgement, previousAcknowledgement)
        if (advance > sequenceDistance(sentSequence, previousAcknowledgement)) {
            emitLinkDiagnostic("link_rx_ack_outside_window", acknowledgement, 0, -1)
            return false
        }
        lastSentAcknowledged = acknowledgement

        while (unacknowledged.isNotEmpty()) {
            val first = unacknowledged.first()
            if (sequenceDistance(first.sequence, previousAcknowledgement) > advance) break
            unacknowledged.removeAt(0)
            emitPacketDiagnostic("link_ack", first)
        }
        receiveAcknowledgementDeadlineMillis = unacknowledged.minOfOrNull { it.deadlineMillis }

        while (sequenceDistance(sentSequence, lastSentAcknowledged) < peerSynchronization.maxOutgoing) {
            val packet = queued.pollFirst() ?: break
            sendPacket(packet, nowMillis)
            setWritable(true)
        }
        return state == State.NORMAL
    }

    private fun handleExtendedAcknowledgement(receivedOutOfOrder: ByteArray, lastTransmitted: Int, nowMillis: Long) {
        if (unacknowledged.isEmpty() || receivedOutOfOrder.isEmpty() || receivedOutOfOrder.size > peerSynchronization.maxOutgoing) return
        val sentWindow = sequenceDistance(lastTransmitted, lastSentAcknowledged)
        val received = BooleanArray(sentWindow + 1)
        var highestReceived = 0
        for (value in receivedOutOfOrder) {
            val distance = sequenceDistance(u8(value), lastSentAcknowledged)
            if (distance !in 2..sentWindow) return
            received[distance] = true
            highestReceived = maxOf(highestReceived, distance)
        }
        emitLinkDiagnostic("link_rx_eak", lastSentAcknowledged, receivedOutOfOrder.size, -1)
        for (packet in unacknowledged) {
            val distance = sequenceDistance(packet.sequence, lastSentAcknowledged)
            if (distance >= highestReceived) break
            // Further EAKs for the same gap must not exhaust the retry budget. Keep its timer armed.
            if (!received[distance] && packet.retransmissions == 0) {
                if (!retransmitPacket(packet, nowMillis)) return
            }
        }
        receiveAcknowledgementDeadlineMillis = unacknowledged.minOfOrNull { it.deadlineMillis }
    }

    private fun handleData(packet: Packet, nowMillis: Long) {
        val distance = sequenceDistance(packet.sequence, lastReceivedInOrder)
        if (distance == 0 || distance > config.maxOutgoing) {
            emitLinkDiagnostic("link_rx_outside_window", packet.sequence, packet.payload.size, -1)
            if (!zeroAcknowledgements) sendAcknowledgement()
            return
        }
        if (outOfOrder.any { it.sequence == packet.sequence }) {
            sendExtendedAcknowledgement()
            return
        }
        if (outOfOrder.size >= config.maximumOutOfOrderPackets) {
            die("iAP2 out-of-order packet limit exceeded")
            return
        }
        outOfOrder += packet
        if (distance > 1) {
            emitLinkDiagnostic("link_rx_gap", packet.sequence, packet.payload.size, -1)
            sendExtendedAcknowledgement()
            return
        }

        outOfOrder.sortBy { sequenceDistance(it.sequence, lastReceivedInOrder) }
        while (outOfOrder.isNotEmpty() && sequenceDistance(outOfOrder.first().sequence, lastReceivedInOrder) == 1) {
            val inOrder = outOfOrder.removeAt(0)
            lastReceivedInOrder = inOrder.sequence
            pendingAcknowledgements += 1
            if (inOrder.sessionId == CONTROL_SESSION_ID) {
                enqueueEvent(Event.Control(inOrder.payload))
            } else {
                enqueueEvent(Event.Session(inOrder.sessionId, inOrder.payload))
            }
        }

        if (zeroAcknowledgements) return
        val windowBeforeForcedAck = (config.maxOutgoing - config.maxOutgoingDelta).coerceAtLeast(1)
        if (pendingAcknowledgements >= peerSynchronization.maxAcknowledgements ||
            sequenceDistance(lastReceivedInOrder, lastAcknowledgedReceived) >= windowBeforeForcedAck
        ) {
            sendAcknowledgement()
        } else if (sendAcknowledgementDeadlineMillis == null) {
            sendAcknowledgementDeadlineMillis = nowMillis + peerSynchronization.acknowledgementTimeoutMillis
        }
    }

    private fun sendPacket(packet: Packet, nowMillis: Long) {
        if (state != State.NORMAL ||
            sequenceDistance(sentSequence, lastSentAcknowledged) >= peerSynchronization.maxOutgoing
        ) {
            if (queued.size >= config.maximumQueuedPackets) {
                die("iAP2 outbound queue limit exceeded")
            } else {
                queued += packet
                setWritable(false)
            }
            return
        }
        sentSequence = nextSequence(sentSequence)
        packet.sequence = sentSequence
        packet.retransmissions = 0
        packet.deadlineMillis = nowMillis + peerSynchronization.retransmissionTimeoutMillis
        sendData(packet.sequence, packet.sessionId, packet.payload, packet.flushReceipt)
        if (state != State.DEAD) {
            emitPacketDiagnostic("link_tx", packet)
        }
        if (peerSynchronization.maxRetransmissions > 0) {
            unacknowledged += packet
            receiveAcknowledgementDeadlineMillis = minOf(receiveAcknowledgementDeadlineMillis ?: packet.deadlineMillis, packet.deadlineMillis)
        } else {
            lastSentAcknowledged = sentSequence
        }
    }

    private fun retransmitDuePacket(nowMillis: Long) {
        if (state != State.NORMAL || unacknowledged.isEmpty()) return
        val next = unacknowledged.minByOrNull { it.deadlineMillis } ?: return
        if (retransmitPacket(next, nowMillis)) {
            receiveAcknowledgementDeadlineMillis = unacknowledged.minOfOrNull { it.deadlineMillis }
        }
    }

    private fun retransmitPacket(packet: Packet, nowMillis: Long): Boolean {
        if (packet.retransmissions >= peerSynchronization.maxRetransmissions) {
            emitPacketDiagnostic("link_unacknowledged", packet, terminal = true)
            die("iAP2 packet ${packet.sequence} was not acknowledged")
            return false
        }
        packet.retransmissions += 1
        packet.deadlineMillis = nowMillis + peerSynchronization.retransmissionTimeoutMillis
        sendData(packet.sequence, packet.sessionId, packet.payload, packet.flushReceipt)
        if (state == State.DEAD) return false
        emitPacketDiagnostic("link_retry", packet)
        return true
    }

    private fun emitPacketDiagnostic(stage: String, packet: Packet, terminal: Boolean = false) {
        val name = if (onLinkDiagnostic == null) null else wirelessHandoffFrameLabel(packet.sessionId, packet.payload)
        // These messages are already bounded by the wireless control flow, independently of other diagnostics.
        emitLinkDiagnostic(if (name == null) stage else "${name}_$stage",
            packet.sequence, packet.payload.size, packet.retransmissions, terminal || name != null)
    }

    /** Log packet metadata only. A transmit record means the engine queued bytes for the transport. */
    private fun emitLinkDiagnostic(
        stage: String,
        sequence: Int,
        payloadBytes: Int,
        retryCount: Int,
        terminal: Boolean = false,
    ) {
        val report = onLinkDiagnostic ?: return
        if (!terminal) {
            if (linkDiagnosticCount >= MAX_LINK_DIAGNOSTIC_RECORDS) return
            linkDiagnosticCount += 1
        }
        try {
            report(stage, sequence, payloadBytes, retryCount)
        } catch (_: Exception) {
            // Diagnostics cannot influence link state or timing decisions.
        }
    }

    private fun sendSynchronization() {
        writePacket(localSynchronization().encode(), sentSequence, CONTROL_SYNCHRONIZE, 0)
    }

    private fun sendAcknowledgement() {
        writePacket(null, sentSequence, CONTROL_ACKNOWLEDGEMENT, 0)
    }

    private fun sendExtendedAcknowledgement() {
        if (zeroAcknowledgements) return
        val count = minOf(outOfOrder.size, peerSynchronization.maxLength - HEADER_BYTES - CHECKSUM_BYTES)
        val receivedOutOfOrder = ByteArray(count) { outOfOrder[it].sequence.toByte() }
        writePacket(receivedOutOfOrder, sentSequence, CONTROL_EXTENDED_ACK, 0)
    }

    private fun sendData(
        sequence: Int,
        sessionId: Int,
        payload: ByteArray,
        flushReceipt: Iap2TransportFlushReceipt? = null,
    ) {
        writePacket(payload, sequence, CONTROL_ACKNOWLEDGEMENT, sessionId, flushReceipt)
    }

    private fun writePacket(
        payload: ByteArray?,
        sequence: Int,
        control: Int,
        sessionId: Int,
        flushReceipt: Iap2TransportFlushReceipt? = null,
    ) {
        if (state == State.DEAD) return
        if ((control and CONTROL_ACKNOWLEDGEMENT) != 0) {
            pendingAcknowledgements = 0
            lastAcknowledgedReceived = lastReceivedInOrder
            sendAcknowledgementDeadlineMillis = null
        }
        val length = payload?.size?.plus(HEADER_BYTES + CHECKSUM_BYTES) ?: HEADER_BYTES
        val header = ByteArray(HEADER_BYTES)
        header[0] = LINK_START_HIGH
        header[1] = LINK_START_LOW
        header[2] = (length ushr 8).toByte()
        header[3] = length.toByte()
        header[4] = control.toByte()
        header[5] = sequence.toByte()
        header[6] = lastReceivedInOrder.toByte()
        header[7] = sessionId.toByte()
        header[8] = checksum(header, HEADER_BYTES - CHECKSUM_BYTES).toByte()
        val frame = ByteArrayOutputStream(length)
        frame.write(header)
        if (payload != null) {
            frame.write(payload)
            frame.write(checksum(payload, payload.size))
        }
        appendOutput(frame.toByteArray(), flushReceipt)
    }

    private fun setWritable(value: Boolean) {
        if (writable == value) return
        writable = value
        enqueueEvent(Event.Writable(value))
    }

    private fun die(reason: String?) {
        if (state == State.DEAD) return
        markerDeadlineMillis = null
        synchronizationDeadlineMillis = null
        sendAcknowledgementDeadlineMillis = null
        receiveAcknowledgementDeadlineMillis = null
        state = State.DEAD
        writable = false
        output.reset()
        outputFlushReceipts.clear()
        events.clear()
        events += Event.Dead(reason)
    }

    private fun appendOutput(
        bytes: ByteArray,
        flushReceipt: Iap2TransportFlushReceipt? = null,
    ): Boolean {
        if (state == State.DEAD) return false
        if (bytes.size > config.maximumPendingOutputBytes - output.size()) {
            die("iAP2 pending output limit exceeded")
            return false
        }
        output.write(bytes)
        flushReceipt?.let(outputFlushReceipts::add)
        return true
    }

    private fun enqueueEvent(event: Event) {
        if (state == State.DEAD) return
        if (events.size >= config.maximumPendingEvents) {
            die("iAP2 pending event limit exceeded")
            return
        }
        events += event
    }

    // A usable session-10 link must carry at least one control byte in addition to its frame
    // header and payload checksum.  This also gives higher layers a positive chunk limit.
    private fun peerLimitsAreUsable(): Boolean = peerSynchronization.maxOutgoing in 1..127 &&
        peerSynchronization.maxLength > HEADER_BYTES + CHECKSUM_BYTES &&
        (peerSynchronization.maxRetransmissions == 0 || peerSynchronization.retransmissionTimeoutMillis > 0)

    private fun peerPayloadIsAcceptable(payload: ByteArray): Boolean =
        peerLimitsAreUsable() && payload.size <= peerSynchronization.maxLength - HEADER_BYTES - CHECKSUM_BYTES

    private fun localSynchronization() = SynchronizationPayload(
        maxOutgoing = config.maxOutgoing,
        maxLength = MAX_WIRE_FRAME_BYTES,
        retransmissionTimeoutMillis = if (config.zeroAcknowledgements) 0 else DEFAULT_RETRANSMISSION_TIMEOUT_MILLIS,
        acknowledgementTimeoutMillis = if (config.zeroAcknowledgements) 0 else config.acknowledgementTimeoutMillis,
        maxRetransmissions = if (config.zeroAcknowledgements) 0 else DEFAULT_MAX_RETRANSMISSIONS,
        maxAcknowledgements = if (config.zeroAcknowledgements) 0 else DEFAULT_MAX_ACKNOWLEDGEMENTS,
        sessions = listOf(
            SessionDescriptor(CONTROL_SESSION_ID, 0, config.controlSessionVersion),
            SessionDescriptor(EA_SESSION_ID, 2, 1),
            SessionDescriptor(FILE_TRANSFER_SESSION_ID, 1, 2),
        ),
    )

    private class ReceiveBuffer {
        private val bytes = ByteArray(MAX_WIRE_FRAME_BYTES)
        private var head = 0
        private var tail = 0

        val size: Int get() = tail - head

        fun remainingCapacity(): Int {
            compact()
            return bytes.size - tail
        }

        operator fun get(index: Int): Byte = bytes[head + index]

        fun append(source: ByteArray, offset: Int, count: Int) {
            require(count <= remainingCapacity())
            source.copyInto(bytes, tail, offset, offset + count)
            tail += count
        }

        fun discard(count: Int) {
            require(count in 0..size)
            head += count
            if (head == tail) {
                head = 0
                tail = 0
            }
        }

        fun peek(count: Int): ByteArray {
            require(count in 0..size)
            return bytes.copyOfRange(head, head + count)
        }

        fun take(count: Int): ByteArray = peek(count).also { discard(count) }

        fun matches(expected: ByteArray): Boolean = expected.indices.all { bytes[head + it] == expected[it] }

        private fun compact() {
            if (head == 0) return
            if (head < tail) bytes.copyInto(bytes, 0, head, tail)
            tail -= head
            head = 0
        }
    }

    companion object {
        const val CONTROL_SESSION_ID = 10
        const val EA_SESSION_ID = 11
        const val FILE_TRANSFER_SESSION_ID = 12

        val IAP2_MARKER = byteArrayOf(0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10)

        private const val HEADER_BYTES = 9
        private const val CHECKSUM_BYTES = 1
        const val MAX_WIRE_FRAME_BYTES = 0xffff
        const val MAX_PAYLOAD_BYTES = MAX_WIRE_FRAME_BYTES - HEADER_BYTES - CHECKSUM_BYTES
        private const val SESSION_DESCRIPTOR_BYTES = 3
        private const val SYNCHRONIZATION_FIXED_BYTES = 10
        private const val SYNCHRONIZATION_VERSION = 1
        private const val LINK_START_HIGH: Byte = 0xff.toByte()
        private const val LINK_START_LOW: Byte = 0x5a
        private const val CONTROL_SYNCHRONIZE = 0x80
        private const val CONTROL_ACKNOWLEDGEMENT = 0x40
        private const val CONTROL_EXTENDED_ACKNOWLEDGEMENT = 0x20
        private const val CONTROL_EXTENDED_ACK = CONTROL_ACKNOWLEDGEMENT or CONTROL_EXTENDED_ACKNOWLEDGEMENT
        private const val CONTROL_RESET = 0x10
        private const val INITIAL_SEQUENCE = 99
        private const val MARKER_RESEND_MILLIS = 1_000L
        private const val SYNCHRONIZATION_RESEND_MILLIS = 500L
        private const val DEFAULT_RETRANSMISSION_TIMEOUT_MILLIS = 4_000
        private const val DEFAULT_MAX_RETRANSMISSIONS = 4
        private const val DEFAULT_MAX_ACKNOWLEDGEMENTS = 3
        private const val MAX_LINK_DIAGNOSTIC_RECORDS = 128

        private fun parseHeader(bytes: ByteArray): Header? {
            if (bytes.size != HEADER_BYTES || !checksumValid(bytes)) return null
            if (bytes[0] != LINK_START_HIGH || bytes[1] != LINK_START_LOW) return null
            return Header(
                length = readU16(bytes, 2),
                control = u8(bytes[4]),
                sequence = u8(bytes[5]),
                acknowledgement = u8(bytes[6]),
                sessionId = u8(bytes[7]),
            )
        }

        private fun checksumValid(bytes: ByteArray): Boolean = bytes.fold(0) { sum, byte -> (sum + u8(byte)) and 0xff } == 0

        private fun checksum(bytes: ByteArray, count: Int): Int =
            (-bytes.take(count).sumOf { u8(it) }) and 0xff

        private fun sequenceDistance(sequence: Int, previous: Int): Int = (sequence - previous) and 0xff

        private fun nextSequence(sequence: Int): Int = (sequence + 1) and 0xff

        private fun readU16(bytes: ByteArray, offset: Int): Int = (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])

        private fun u8(byte: Byte): Int = byte.toInt() and 0xff

        private fun writeU16(out: ByteArrayOutputStream, value: Int) {
            out.write(value ushr 8)
            out.write(value)
        }
    }
}

/** Completion marker for one iAP2 data frame reaching its synchronous byte-stream write. */
internal class Iap2TransportFlushReceipt {
    var flushed: Boolean = false
}

/** Configuration advertised by the local link endpoint. */
data class Iap2LinkConfig(
    val maxOutgoing: Int = 30,
    val maxOutgoingDelta: Int = 0,
    val acknowledgementTimeoutMillis: Int = 500,
    val zeroAcknowledgements: Boolean = false,
    val controlSessionVersion: Int = 1,
    val maximumQueuedPackets: Int = 64,
    val maximumOutOfOrderPackets: Int = 64,
    val maximumPendingOutputBytes: Int = 1_048_576,
    val maximumPendingEvents: Int = 256,
) {
    init {
        require(maxOutgoing in 0..0xff)
        require(maxOutgoingDelta in 0..0xff)
        require(acknowledgementTimeoutMillis in 0..0xffff)
        require(controlSessionVersion in 0..0xff)
        require(maximumQueuedPackets in 1..256)
        require(maximumOutOfOrderPackets in 1..256)
        require(maximumPendingOutputBytes >= 1)
        require(maximumPendingEvents >= 1)
    }
}

/** Recognize an entire handoff CSM, never claim ACK for a partial control frame. */
internal fun wirelessHandoffFrameLabel(sessionId: Int, payload: ByteArray): String? {
    if (sessionId != Iap2LinkEngine.CONTROL_SESSION_ID || payload.size < 6) return null
    fun field(at: Int): Int =
        ((payload[at].toInt() and 0xff) shl 8) or (payload[at + 1].toInt() and 0xff)
    if (field(0) != 0x4040 || field(2) != payload.size) return null
    return when (field(4)) {
        0x5703 -> "wifi_config"
        0x4301 -> "carplay_start"
        else -> null
    }
}
