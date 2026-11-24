package com.shilapi.xcertplay.usbbt

import android.util.Log
import com.shilapi.xcertplay.usbbt.HciProtocol.L2CAP_PSM_RFCOMM
import com.shilapi.xcertplay.usbbt.HciProtocol.RFCOMM_DLCI_CONTROL
import com.shilapi.xcertplay.usbbt.HciProtocol.RFCOMM_DISC
import com.shilapi.xcertplay.usbbt.HciProtocol.RFCOMM_DM
import com.shilapi.xcertplay.usbbt.HciProtocol.RFCOMM_MCC_MSC
import com.shilapi.xcertplay.usbbt.HciProtocol.RFCOMM_MCC_PN
import com.shilapi.xcertplay.usbbt.HciProtocol.RFCOMM_SABM
import com.shilapi.xcertplay.usbbt.HciProtocol.RFCOMM_UA
import com.shilapi.xcertplay.usbbt.HciProtocol.RFCOMM_UIH
import com.shilapi.xcertplay.usbbt.HciProtocol.rfcommDlci
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RFCOMM layer over L2CAP (Phase 3).
 *
 * Bluetooth Core Spec, RFCOMM with TS 07.10. Implements the subset needed for
 * iAP2:
 * - session establishment on DLCI 0 (SABM/UA)
 * - DLC establishment to a server channel: PN negotiation -> SABM/UA -> MSC
 * - Credit-based flow control (PN convergence layer 15/14, P/F bit with credit
 *   octets, replenishment and credit tracking)
 * - UIH data frames with 1-byte or 2-byte length
 * - FCS calculation per TS 07.10 (Address + Control for UIH; + Length for non-UIH)
 * - Inbound UIH dispatch to per-DLC handlers; inbound SABM on a DLC is
 *   answered with UA
 *
 * All public methods block the calling thread; use from a background thread.
 * One instance per L2CAP RFCOMM channel.
 */
class RfcommLayer(
    private val l2cap: L2capLayer,
    private val channel: L2capLayer.Channel,
    private val initiator: Boolean = true,
    private val allowedServerChannel: Int? = null,
) {

    /** An open RFCOMM data link connection to a server channel. */
    data class Dlc(val serverChannel: Int, val dlci: Int)

    sealed class RfcommResult<out T> {
        data class Ok<T>(val value: T) : RfcommResult<T>()
        data class Failed(val reason: String) : RfcommResult<Nothing>()
    }

    companion object {
        private const val TAG = "RfcommLayer"
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val MAX_UIH_PAYLOAD = 100 // keep frames small for old dongles
        private const val INITIAL_CREDITS = 7
    }

    /** dlci -> pending UA latch (for SABM we sent). */
    private val pendingUa = ConcurrentHashMap<Int, CountDownLatch>()
    private val refusedDlcs = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    /** dlci -> pending PN response latch. */
    private val pendingPn = ConcurrentHashMap<Int, CountDownLatch>()

    /** dlci -> data handler. */
    private val dataHandlers = ConcurrentHashMap<Int, (ByteArray) -> Unit>()

    /** dlci -> credit-based flow control state. */
    private val dlcFlows = ConcurrentHashMap<Int, RfcommDlcFlow>()

    /** serverChannel -> active Dlc. */
    val activeDlcs = ConcurrentHashMap<Int, Dlc>()

    /** Callback invoked when an inbound DLC is opened by peer. */
    var onDlcOpened: ((Dlc) -> Unit)? = null
    var onDlcClosed: ((Int) -> Unit)? = null

    @Volatile private var sessionOpen = false
    private val closing = AtomicBoolean(false)
    private val closed: Boolean get() = closing.get()

    init {
        l2cap.onData(channel) { payload -> onRfcommFrame(payload) }
    }

    // ------------------------------------------------------------------
    // Flow control helper.
    // ------------------------------------------------------------------

    private fun flowFor(dlci: Int): RfcommDlcFlow =
        dlcFlows.getOrPut(dlci) { RfcommDlcFlow(INITIAL_CREDITS) }

    // ------------------------------------------------------------------
    // Frame encoding.
    // ------------------------------------------------------------------

    /** Address field: EA=1, C/R bit, DLCI (6 bits). */
    private fun addressField(dlci: Int, command: Boolean): Byte =
        (0x01 or (if (command == initiator) 0x02 else 0x00) or (dlci shl 2)).toByte()

    /** Length field: 1 byte (EA=1, <=127) or 2 bytes (EA=0, <=32767). */
    private fun lengthField(len: Int): ByteArray {
        return if (len <= 127) {
            byteArrayOf(((len shl 1) or 0x01).toByte())
        } else {
            byteArrayOf(((len and 0x7F) shl 1).toByte(), (len ushr 7).toByte())
        }
    }

    /**
     * FCS CRC-8 from TS 07.10 section 5.2.3:
     * - For UIH frames, calculated only over Address and Control fields (2 bytes).
     * - For other frames (SABM, UA, DM, DISC), calculated over Address, Control, and Length fields.
     */
    private fun fcs(bytes: ByteArray, len: Int = bytes.size): Byte {
        var crc = 0xFF
        val end = minOf(len, bytes.size)
        for (i in 0 until end) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x01 != 0) (crc ushr 1) xor 0xE0 else crc ushr 1
            }
        }
        return (0xFF - crc).toByte()
    }

    /**
     * Build a complete RFCOMM frame:
     * address | control | length | [credits if UIH with P/F=1] | [data] | fcs
     */
    private fun buildFrame(
        dlci: Int,
        control: Int,
        data: ByteArray = byteArrayOf(),
        credits: Int? = null,
        command: Boolean = true,
        pf: Boolean = false,
    ): ByteArray {
        val addr = addressField(dlci, command = command)
        val hasPf = pf || (credits != null)
        val ctrl = (((if (hasPf) 0x10 else 0x00) or (control and 0xEF)) and 0xFF).toByte()
        val len = lengthField(data.size)
        val header = byteArrayOf(addr, ctrl) + len
        val fcsByte = fcs(header, if ((control and 0xEF) == RFCOMM_UIH) 2 else header.size)
        val creditBytes = if (credits != null) byteArrayOf((credits and 0xFF).toByte()) else byteArrayOf()
        return header + creditBytes + data + fcsByte
    }

    private fun sendFrame(
        dlci: Int,
        control: Int,
        data: ByteArray = byteArrayOf(),
        credits: Int? = null,
        command: Boolean = true,
        pf: Boolean = false,
        closingFrame: Boolean = false,
    ): Boolean {
        if (closed && !closingFrame) return false
        return l2cap.send(channel, buildFrame(dlci, control, data, credits = credits, command = command, pf = pf))
    }

    // ------------------------------------------------------------------
    // Session and DLC establishment.
    // ------------------------------------------------------------------

    /**
     * Open the RFCOMM session (SABM/UA on DLCI 0). Idempotent.
     */
    fun openSession(): RfcommResult<Unit> {
        if (sessionOpen) return RfcommResult.Ok(Unit)
        if (closed) return RfcommResult.Failed("RFCOMM closed")
        val latch = CountDownLatch(1)
        refusedDlcs.remove(RFCOMM_DLCI_CONTROL)
        pendingUa[RFCOMM_DLCI_CONTROL] = latch
        try {
            if (!sendFrame(RFCOMM_DLCI_CONTROL, RFCOMM_SABM, command = true, pf = true)) {
                return RfcommResult.Failed("failed to send SABM (session)")
            }
            if (!latch.await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return RfcommResult.Failed("SABM/UA timeout (session)")
            }
            if (closed || RFCOMM_DLCI_CONTROL in refusedDlcs) return RfcommResult.Failed("RFCOMM session refused or closed")
            sessionOpen = true
            Log.i(TAG, "RFCOMM session open")
            return RfcommResult.Ok(Unit)
        } finally {
            pendingUa.remove(RFCOMM_DLCI_CONTROL)
        }
    }

    /**
     * Open a DLC to [serverChannel]: PN negotiation, SABM/UA, then MSC.
     * Returns the DLC on success.
     */
    fun openDlc(serverChannel: Int, prepare: ((Dlc) -> Unit)? = null): RfcommResult<Dlc> {
        if (serverChannel !in 1..30) return RfcommResult.Failed("Invalid RFCOMM server channel")
        activeDlcs[serverChannel]?.let { return RfcommResult.Ok(it) }
        val session = openSession()
        if (session is RfcommResult.Failed) return session
        val dlci = rfcommDlci(serverChannel, initiator = initiator)

        // 1) Parameter Negotiation (MCC PN).
        val pnLatch = CountDownLatch(1)
        pendingPn[dlci] = pnLatch
        try {
            // PN request: MCC type 0x20, length 8:
            // dlci | convergence=15(0xF0) | priority | ack_timer | max_frame_size(2 LE) | max_retrans | credits
            val maxFrame = minOf(504, channel.peerMtu - 5)
            val pnReq = mccFrame(
                RFCOMM_MCC_PN,
                byteArrayOf(
                    (dlci and 0x3F).toByte(),
                    0xF0.toByte(), // convergence layer = 15 (credit flow control), frame_type = 0
                    0x00, 0x00,
                    (maxFrame and 0xFF).toByte(),
                    ((maxFrame ushr 8) and 0xFF).toByte(),
                    0x00,
                    INITIAL_CREDITS.toByte(),
                ),
                command = true,
            )
            if (!sendFrame(RFCOMM_DLCI_CONTROL, RFCOMM_UIH, pnReq)) {
                return RfcommResult.Failed("failed to send PN (ch=$serverChannel)")
            }
            if (!pnLatch.await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "PN timeout (ch=$serverChannel); continuing without credit mode")
            }
            if (closed) return RfcommResult.Failed("RFCOMM closed")
        } finally {
            pendingPn.remove(dlci)
        }

        // Install the byte receiver before the peer can reply with UA and its first UIH.
        val dlc = Dlc(serverChannel = serverChannel, dlci = dlci)
        prepare?.invoke(dlc)

        // 2) SABM/UA on the DLC.
        val uaLatch = CountDownLatch(1)
        refusedDlcs.remove(dlci)
        pendingUa[dlci] = uaLatch
        try {
            if (!sendFrame(dlci, RFCOMM_SABM, command = true, pf = true)) {
                return RfcommResult.Failed("failed to send SABM (ch=$serverChannel)")
            }
            if (!uaLatch.await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return RfcommResult.Failed("SABM/UA timeout (ch=$serverChannel)")
            }
            if (closed || dlci in refusedDlcs) return RfcommResult.Failed("RFCOMM DLC refused or closed (ch=$serverChannel)")
        } finally {
            pendingUa.remove(dlci)
        }

        // 3) Modem Status Command (MSC): signal DTR/RTC ready.
        val msc = mccFrame(
            RFCOMM_MCC_MSC,
            byteArrayOf(
                // MSC: dlci | V.24 signals (EA=1, RTC=1, RTR=1, DV=1)
                ((dlci shl 2) or 0x03).toByte(),
                0x8D.toByte(),
            ),
            command = true,
        )
        sendFrame(RFCOMM_DLCI_CONTROL, RFCOMM_UIH, msc)

        activeDlcs[serverChannel] = dlc
        Log.i(TAG, "RFCOMM DLC open: ch=$serverChannel dlci=$dlci")
        return RfcommResult.Ok(dlc)
    }

    /**
     * Wrap an MCC payload according to TS 07.10 section 5.4.6.3:
     * Type byte: (type << 2) | (C/R << 1) | EA(1)
     * Length byte: (length << 1) | EA(1)
     */
    private fun mccFrame(type: Int, value: ByteArray, command: Boolean = true): ByteArray {
        val typeByte = (((type and 0x3F) shl 2) or (if (command) 0x02 else 0x00) or 0x01).toByte()
        val lenByte = (((value.size shl 1) or 0x01) and 0xFF).toByte()
        return byteArrayOf(typeByte, lenByte) + value
    }

    /** Send [data] on [dlc], fragmenting into small UIH frames with credit-based flow control. */
    fun send(dlc: Dlc, data: ByteArray): Boolean {
        if (closed || activeDlcs[dlc.serverChannel] != dlc) return false
        val flow = flowFor(dlc.dlci)
        var offset = 0
        while (offset < data.size) {
            val limit = minOf(MAX_UIH_PAYLOAD, flow.peerFrameSize, channel.peerMtu - 5)
            if (limit <= 0 || !flow.takeTxCredit()) return false
            val end = minOf(offset + limit, data.size)
            if (!sendFrame(dlc.dlci, RFCOMM_UIH, data.copyOfRange(offset, end))) return false
            offset = end
        }
        return true
    }

    /** Register a handler for inbound data on [dlc]. */
    fun onData(dlc: Dlc, handler: (ByteArray) -> Unit) {
        dataHandlers[dlc.dlci] = handler
    }

    fun close(sendDisconnect: Boolean = true) {
        if (!closing.compareAndSet(false, true)) return
        val openDlcs = activeDlcs.values.toList()
        val wasOpen = sessionOpen
        pendingUa.values.forEach { it.countDown() }
        pendingPn.values.forEach { it.countDown() }
        pendingUa.clear()
        pendingPn.clear()
        dataHandlers.clear()
        activeDlcs.clear()
        dlcFlows.values.forEach { it.release() }
        dlcFlows.clear()
        if (sendDisconnect) {
            runCatching {
                openDlcs.forEach { dlc ->
                    sendFrame(dlc.dlci, RFCOMM_DISC, command = true, pf = true, closingFrame = true)
                }
                if (wasOpen) {
                    sendFrame(RFCOMM_DLCI_CONTROL, RFCOMM_DISC, command = true, pf = true, closingFrame = true)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Inbound frame handling.
    // ------------------------------------------------------------------

    fun onRfcommFrame(payload: ByteArray) {
        if (closed || payload.size < 4) return // addr+ctrl+len+fcs minimum
        val addr = payload[0].toInt() and 0xFF
        if ((addr and 0x01) == 0) return // EA bit must be 1 for single-byte DLCI
        val dlci = (addr ushr 2) and 0x3F

        val ctrl = payload[1].toInt() and 0xFF
        val frameType = ctrl and 0xEF // mask off P/F bit 0x10
        val pf = (ctrl and 0x10) != 0

        var at = 2
        val len0 = payload[at].toInt() and 0xFF
        at += 1
        var length = len0 ushr 1
        if ((len0 and 0x01) == 0) { // 2-byte length
            if (at >= payload.size) return
            length = length or ((payload[at].toInt() and 0xFF) shl 7)
            at += 1
        }

        val headerLen = at
        val remaining = payload.size - headerLen - 1 // bytes between header and FCS
        val creditField = remaining == length + 1 && frameType == RFCOMM_UIH && pf && dlci != 0 &&
            dlcFlows[dlci]?.creditBased == true
        if (remaining != length && !creditField) {
            Log.w(TAG, "invalid RFCOMM frame length (dlci=$dlci expected=$length remaining=$remaining)")
            return
        }

        // Verify FCS (TS 07.10 section 5.2.3)
        val expectedFcs = fcs(payload, if (frameType == RFCOMM_UIH) 2 else headerLen)
        val actualFcs = payload[payload.size - 1]
        if (expectedFcs != actualFcs) {
            Log.w(TAG, "RFCOMM FCS mismatch (dlci=$dlci expected=0x%02X actual=0x%02X)".format(expectedFcs, actualFcs))
            return
        }

        var credits: Int? = null
        if (creditField) {
            credits = payload[at].toInt() and 0xFF
            at += 1
        }

        val data = payload.copyOfRange(at, at + length)
        when (frameType) {
            RFCOMM_UA -> {
                pendingUa[dlci]?.countDown()
                if (dlci != RFCOMM_DLCI_CONTROL) Log.i(TAG, "UA received (dlci=$dlci)")
            }
            RFCOMM_DM -> {
                Log.w(TAG, "DM received (dlci=$dlci); peer refused")
                refusedDlcs.add(dlci)
                pendingUa[dlci]?.countDown()
            }
            RFCOMM_DISC -> {
                Log.i(TAG, "DISC received (dlci=$dlci); answering UA")
                sendFrame(dlci, RFCOMM_UA, command = false, pf = true)
                if (dlci == RFCOMM_DLCI_CONTROL) {
                    sessionOpen = false
                    activeDlcs.keys.toList().forEach { onDlcClosed?.invoke(it) }
                    close(false)
                } else {
                    val serverChannel = dlci ushr 1
                    activeDlcs.remove(serverChannel)
                    dataHandlers.remove(dlci)
                    dlcFlows.remove(dlci)?.release()
                    onDlcClosed?.invoke(serverChannel)
                }
            }
            RFCOMM_SABM -> {
                // The inbound host advertises a single iAP2 service.
                if (dlci != RFCOMM_DLCI_CONTROL && allowedServerChannel != null && dlci ushr 1 != allowedServerChannel) {
                    sendFrame(dlci, RFCOMM_DM, command = false, pf = true)
                    return
                }
                sendFrame(dlci, RFCOMM_UA, command = false, pf = true)
                if (dlci == RFCOMM_DLCI_CONTROL) {
                    sessionOpen = true
                } else {
                    val serverChannel = dlci ushr 1
                    val dlc = Dlc(serverChannel, dlci)
                    if (activeDlcs.putIfAbsent(serverChannel, dlc) == null) {
                        Log.i(TAG, "Inbound RFCOMM DLC opened: ch=$serverChannel dlci=$dlci")
                        onDlcOpened?.invoke(dlc)
                    }
                }
            }
            RFCOMM_UIH -> {
                if (dlci == RFCOMM_DLCI_CONTROL) {
                    onMcc(data)
                } else {
                    val handler = dataHandlers[dlci] ?: return
                    val flow = flowFor(dlci)
                    if (credits != null) flow.addTxCredits(credits)
                    if (data.isNotEmpty()) {
                        runCatching { handler(data) }.onFailure { Log.w(TAG, "RFCOMM data handler failed", it) }
                        val topUp = flow.consumeRxCredit()
                        if (topUp > 0 && sendFrame(dlci, RFCOMM_UIH, credits = topUp)) {
                            flow.creditGrantSent(topUp)
                        }
                    }
                }
            }
            else -> Log.w(TAG, "unhandled RFCOMM frameType 0x${frameType.toString(16)}")
        }
    }

    private fun onMcc(data: ByteArray) {
        if (data.size < 2) return
        val typeByte = data[0].toInt() and 0xFF
        val type = (typeByte ushr 2) and 0x3F
        val command = (typeByte and 0x02) != 0
        val len = (data[1].toInt() and 0xFF) ushr 1
        if (2 + len > data.size) return
        val value = data.copyOfRange(2, 2 + len)
        when (type) {
            RFCOMM_MCC_PN -> {
                if (value.size != 8) return
                val dlci = value[0].toInt() and 0x3f
                if (dlci == 0 || (allowedServerChannel != null && dlci ushr 1 != allowedServerChannel)) return
                if (!command && pendingPn[dlci] == null) return
                val peerFrame = (value[4].toInt() and 0xff) or ((value[5].toInt() and 0xff) shl 8)
                if (peerFrame <= 0) return
                val mode = value[1].toInt() and 0xf0
                val creditBased = mode == (if (command) 0xf0 else 0xe0)
                val flow = flowFor(dlci)
                flow.negotiate(creditBased, value[7].toInt() and 0xff, peerFrame)
                if (command) {
                    val frameSize = minOf(peerFrame, 504, channel.peerMtu - 5)
                    val reply = value.copyOf()
                    reply[1] = if (creditBased) 0xe0.toByte() else 0
                    reply[4] = frameSize.toByte()
                    reply[5] = (frameSize ushr 8).toByte()
                    reply[7] = if (creditBased) INITIAL_CREDITS.toByte() else 0
                    if (sendFrame(RFCOMM_DLCI_CONTROL, RFCOMM_UIH, mccFrame(RFCOMM_MCC_PN, reply, command = false)) && creditBased) {
                        flow.creditGrantSent(INITIAL_CREDITS)
                    }
                } else {
                    if (creditBased) flow.creditGrantSent(INITIAL_CREDITS)
                    pendingPn[dlci]?.countDown()
                }
            }
            RFCOMM_MCC_MSC -> {
                if (command) {
                    val rsp = mccFrame(RFCOMM_MCC_MSC, value, command = false)
                    sendFrame(RFCOMM_DLCI_CONTROL, RFCOMM_UIH, rsp)
                }
            }
            else -> Log.w(TAG, "unhandled RFCOMM MCC type 0x${type.toString(16)} (command=$command)")
        }
    }
}

/** One DLC's negotiated credit and payload limits. Safe to close while a writer waits. */
internal class RfcommDlcFlow(private val initialRxCredits: Int) {
    private val lock = java.lang.Object()
    @Volatile var creditBased = false
        private set
    @Volatile var peerFrameSize = 504
        private set
    private var rxCredits = 0
    private var txCredits = 0
    private var closed = false

    fun negotiate(credits: Boolean, granted: Int, peerFrame: Int) = synchronized(lock) {
        creditBased = credits
        peerFrameSize = peerFrame
        txCredits = if (credits) granted else 0
        rxCredits = 0
        lock.notifyAll()
    }

    fun addTxCredits(credits: Int) = synchronized(lock) {
        if (creditBased && !closed) {
            txCredits = (txCredits + credits).coerceAtMost(65535)
            lock.notifyAll()
        }
    }

    fun creditGrantSent(credits: Int) = synchronized(lock) {
        if (creditBased && !closed) rxCredits += credits
    }

    fun consumeRxCredit(): Int = synchronized(lock) {
        if (!creditBased || closed) return 0
        if (rxCredits > 0) rxCredits--
        if (rxCredits <= 3) initialRxCredits - rxCredits else 0
    }

    fun takeTxCredit(timeoutMs: Long = 3000): Boolean = synchronized(lock) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!closed && creditBased && txCredits == 0) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            try {
                lock.wait(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        if (closed) return false
        if (creditBased) txCredits--
        true
    }

    fun release() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }
}
