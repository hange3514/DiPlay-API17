package com.shilapi.xcertplay.transport

import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.min

/**
 * Blocking facade for an iAP2 link over a caller-supplied byte stream.
 *
 * [open] starts one worker.  That worker is the only code that drives [Iap2LinkEngine] or calls
 * [BlockingDuplexByteStream.send]/[BlockingDuplexByteStream.recv].  Callers may concurrently wait
 * for readiness, queue complete session-10 payloads, receive session-10 payloads, or close this
 * channel.  This class owns and closes [underlying].
 *
 * It deliberately does not parse CSM control messages, EA, media, UI, or Lockdown setup. The link
 * worker does handle the bounded request/reply flow for Now Playing artwork file transfers so those
 * replies cannot be delayed behind a blocking control-session consumer.
 */
class Iap2LinkChannel private constructor(
    private val underlying: BlockingDuplexByteStream,
    private val linkConfig: Iap2LinkConfig,
    private val initiateNegotiation: Boolean,
    private val onArtwork: (Iap2ArtworkTransfer) -> Unit,
    private val onLinkDiagnostic: ((stage: String, sequence: Int, payloadBytes: Int, retryCount: Int) -> Unit)? = null,
) : java.io.Closeable {
    private data class Command(
        val sessionId: Int,
        val payload: ByteArray,
        val flushReceipt: Iap2TransportFlushReceipt? = null,
    )

    private val lock = Object()
    private val commands = ArrayDeque<Command>()
    private var commandBytes = 0
    private val controls = ArrayDeque<ByteArray>()
    private var controlBytes = 0
    private val fileTransfers = Iap2FileTransferReceiver()

    private var ready = false
    private var peerMaxControlPayloadBytes: Int? = null
    private var closing = false
    private var terminated = false
    private var terminalFailure: Throwable? = null
    private var streamCloseStarted = false
    @Volatile private var receivedTransportBytes = 0
    private var transportChunkRecords = 0
    private var readyWaitTimeoutReported = false

    private val worker = Thread(::runPump, "xcertplay-iap2-link").apply {
        isDaemon = true
    }

    /** Waits until session 10 is writable; returns false on timeout or clean close, and throws on failure. */
    fun awaitReady(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        var reportTimeout = false
        val linkReady = synchronized(lock) {
            val waitCompleted = waitFor(timeoutMillis) { (ready && !terminated) || terminated }
            throwTerminalFailureLocked()
            if (!waitCompleted && timeoutMillis > 0 && !Thread.currentThread().isInterrupted && !readyWaitTimeoutReported) {
                readyWaitTimeoutReported = true
                reportTimeout = true
            }
            ready && !terminated
        }
        if (reportTimeout) reportTransport("rfcomm_rx_ready_timeout", receivedTransportBytes)
        return linkReady
    }

    /**
     * The peer's negotiated maximum session-10 payload length, available after [awaitReady]
     * returns true.  It excludes the nine-byte iAP2 header and one-byte payload checksum.
     */
    fun peerMaxControlPayloadBytes(): Int? = synchronized(lock) { peerMaxControlPayloadBytes }

    /**
     * Copies and queues one raw session-10 payload.  Returns false after close/termination or when
     * the bounded command queue is full.  A peer LSP length violation is discovered by the worker
     * when it reaches the engine and terminates the channel.
     */
    fun sendControl(bytes: ByteArray): Boolean {
        require(bytes.size <= Iap2LinkEngine.MAX_PAYLOAD_BYTES) {
            "iAP2 control payload exceeds ${Iap2LinkEngine.MAX_PAYLOAD_BYTES} bytes"
        }
        val copy = bytes.copyOf()
        val queued = synchronized(lock) {
            if (terminated || closing) return false
            enqueueCommandLocked(Iap2LinkEngine.CONTROL_SESSION_ID, copy)
        }
        if (queued) wakeReceiverForPendingWork()
        return queued
    }

    /**
     * Like [sendControl], but waits for command-queue capacity.  CSM uses this to queue every
     * fragment of one frame without converting link backpressure into a partial CSM write.
     */
    internal fun sendControlAwaitCapacity(bytes: ByteArray, timeoutMillis: Long): Boolean {
        require(bytes.size <= Iap2LinkEngine.MAX_PAYLOAD_BYTES) {
            "iAP2 control payload exceeds ${Iap2LinkEngine.MAX_PAYLOAD_BYTES} bytes"
        }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val copy = bytes.copyOf()
        val queued = synchronized(lock) {
            waitFor(timeoutMillis) {
                terminated || closing || hasCommandCapacityLocked(copy.size)
            }
            throwTerminalFailureLocked()
            if (terminated || closing) return false
            enqueueCommandLocked(Iap2LinkEngine.CONTROL_SESSION_ID, copy)
        }
        if (queued) wakeReceiverForPendingWork()
        return queued
    }

    /**
     * Queues one control payload and waits until its iAP2 bytes have been accepted by the
     * underlying transport's synchronous send call. This is used for the wired StartSession
     * handoff, where opening NCM outbound before 0x4301 reaches USB can race the phone's bring-up.
     */
    internal fun sendControlAwaitTransportFlush(bytes: ByteArray, timeoutMillis: Long): Boolean {
        require(bytes.size <= Iap2LinkEngine.MAX_PAYLOAD_BYTES) {
            "iAP2 control payload exceeds ${Iap2LinkEngine.MAX_PAYLOAD_BYTES} bytes"
        }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val copy = bytes.copyOf()
        val deadlineNanos = deadlineAfter(timeoutMillis)
        val receipt = Iap2TransportFlushReceipt()
        val queued = synchronized(lock) {
            waitFor(remainingMillis(deadlineNanos)) {
                terminated || closing || hasCommandCapacityLocked(copy.size)
            }
            throwTerminalFailureLocked()
            if (terminated || closing || !hasCommandCapacityLocked(copy.size)) return false
            enqueueCommandLocked(Iap2LinkEngine.CONTROL_SESSION_ID, copy, receipt)
        }
        if (!queued) return false
        wakeReceiverForPendingWork()
        synchronized(lock) {
            waitFor(remainingMillis(deadlineNanos)) { receipt.flushed || terminated }
            throwTerminalFailureLocked()
            return receipt.flushed && !terminated
        }
    }

    /** Returns the next control payload; returns null on timeout or clean close, and throws on failure. */
    fun recvControl(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        synchronized(lock) {
            waitFor(timeoutMillis) { controls.isNotEmpty() || terminated }
            throwTerminalFailureLocked()
            if (controls.isEmpty()) return null
            val bytes = controls.removeFirst()
            controlBytes -= bytes.size
            return bytes
        }
    }

    /**
     * Marks the channel closed, directly closes its owned stream to break a blocking recv, then
     * waits a bounded time for the worker.  Stream or worker failures are rethrown, including
     * [Error]s; they are never silently discarded.
     */
    override fun close() {
        synchronized(lock) {
            if (!closing) {
                closing = true
                terminalLocked(null)
            }
        }

        var failure = closeUnderlyingOnce()
        if (failure != null) {
            synchronized(lock) {
                terminalFailure = combineFailures(terminalFailure, failure)
                lock.notifyAll()
            }
        }
        try {
            worker.join(CLOSE_JOIN_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = combineFailures(failure, interrupted)
        }
        if (worker.isAlive) {
            failure = combineFailures(failure, IOException("iAP2 link worker did not stop after close"))
        }
        synchronized(lock) {
            failure = combineFailures(failure, terminalFailure)
        }
        if (failure != null) throw failure
    }

    private fun runPump() {
        val engine = Iap2LinkEngine(linkConfig, onLinkDiagnostic)
        try {
            engine.start(wiredInitiator = initiateNegotiation, nowMillis = nowMillis())
            while (!isClosing()) {
                drainCommands(engine)
                engine.advanceTime(nowMillis())
                flush(engine)
                if (!drainEvents(engine)) return
                if (isClosing()) return

                val next = engine.nextDeadlineMillis()
                val waitMillis = min(
                    RECEIVE_POLL_MILLIS,
                    next?.let { (it - nowMillis()).coerceAtLeast(0) } ?: RECEIVE_POLL_MILLIS,
                ).coerceAtLeast(MINIMUM_RECEIVE_POLL_MILLIS)
                val received = try {
                    underlying.recv(RECEIVE_CHUNK_BYTES, waitMillis)
                } catch (failure: Exception) {
                    if (!isClosing()) reportTransport("rfcomm_rx_failed", receivedTransportBytes)
                    throw failure
                }
                if (isClosing()) return
                when {
                    received == null -> Unit
                    received.isEmpty() -> {
                        reportTransport("rfcomm_rx_eof", receivedTransportBytes)
                        engine.feedEof()
                    }
                    else -> {
                        receivedTransportBytes += min(received.size, Int.MAX_VALUE - receivedTransportBytes)
                        if (transportChunkRecords < MAX_TRANSPORT_CHUNK_RECORDS) {
                            transportChunkRecords++
                            reportTransport("rfcomm_rx_chunk", received.size)
                        }
                        engine.feed(received, nowMillis())
                    }
                }
            }
            finish(null)
        } catch (failure: Throwable) {
            if (failure is Error) {
                finish(failure)
                throw failure
            }
            if (isClosing()) finish(null) else finish(failure)
        } finally {
            reportTransport("rfcomm_rx_total", receivedTransportBytes)
            val pending = engine.pendingReceiveBytes()
            if (pending > 0) reportTransport("link_rx_partial", pending)
        }
    }

    private fun reportTransport(stage: String, bytes: Int) {
        try {
            onLinkDiagnostic?.invoke(stage, -1, bytes, -1)
        } catch (_: Exception) {
            // A diagnostic callback cannot terminate the receive pump.
        }
    }

    private fun drainCommands(engine: Iap2LinkEngine) {
        while (engine.writable()) {
            val command = synchronized(lock) {
                if (commands.isEmpty()) {
                    null
                } else {
                    commands.removeFirst().also {
                        commandBytes -= it.payload.size
                        lock.notifyAll()
                    }
                }
            } ?: return
            if (command.flushReceipt == null) {
                engine.sendSession(command.sessionId, command.payload, nowMillis())
            } else {
                engine.sendSessionTracked(command.sessionId, command.payload, nowMillis(), command.flushReceipt)
            }
            if (isClosing()) return
        }
    }

    private fun flush(engine: Iap2LinkEngine) {
        val batch = engine.takeOutputBatch()
        if (batch.bytes.isEmpty()) {
            check(batch.completedFlushReceipts.isEmpty()) { "iAP2 flush receipt has no corresponding output bytes" }
            return
        }
        underlying.send(batch.bytes)
        if (batch.completedFlushReceipts.isNotEmpty()) synchronized(lock) {
            batch.completedFlushReceipts.forEach { it.flushed = true }
            lock.notifyAll()
        }
    }

    /** Returns false when a link terminal event has been observed. */
    private fun drainEvents(engine: Iap2LinkEngine): Boolean {
        while (true) {
            when (val event = engine.pollEvent() ?: return true) {
                is Iap2LinkEngine.Event.Writable -> {
                    val peerLimit = synchronized(lock) {
                        if (event.value) {
                            // The engine only emits writable after validating this is positive.
                            peerMaxControlPayloadBytes = engine.peerSynchronization().maxLength - 10
                        }
                        ready = event.value && !terminated
                        lock.notifyAll()
                        peerMaxControlPayloadBytes.takeIf { ready }
                    }
                    if (peerLimit != null) {
                        onLinkDiagnostic?.invoke("peer_control_payload_limit", -1, peerLimit, -1)
                    }
                }

                is Iap2LinkEngine.Event.Control -> {
                    val overflow = synchronized(lock) {
                        if (controls.size >= MAX_PENDING_CONTROLS ||
                            event.bytes.size > MAX_PENDING_CONTROL_BYTES - controlBytes
                        ) {
                            true
                        } else {
                            controls += event.bytes
                            controlBytes += event.bytes.size
                            lock.notifyAll()
                            false
                        }
                    }
                    if (overflow) {
                        finish(IOException("iAP2 received-control queue limit exceeded"))
                        return false
                    }
                }

                is Iap2LinkEngine.Event.Session -> {
                    if (event.sessionId != Iap2LinkEngine.FILE_TRANSFER_SESSION_ID) continue
                    val outcome = fileTransfers.accept(event.bytes)
                    outcome.replies.forEach { reply ->
                        engine.sendSession(Iap2LinkEngine.FILE_TRANSFER_SESSION_ID, reply, nowMillis())
                    }
                    outcome.completed?.let { completed ->
                        try {
                            onArtwork(completed)
                        } catch (_: Exception) {
                            // A UI/consumer callback cannot terminate the control link.
                        }
                    }
                }

                is Iap2LinkEngine.Event.Dead -> {
                    finish(IOException(event.reason ?: "iAP2 link ended"))
                    return false
                }
            }
        }
    }

    private fun finish(failure: Throwable?) {
        synchronized(lock) {
            finishLocked(failure)
        }
        closeUnderlyingOnce()?.let { closeFailure ->
            synchronized(lock) {
                terminalFailure = combineFailures(terminalFailure, closeFailure)
                lock.notifyAll()
            }
        }
    }

    private fun finishLocked(failure: Throwable?) {
        if (!terminated) terminalLocked(failure) else if (failure != null) {
            terminalFailure = combineFailures(terminalFailure, failure)
            lock.notifyAll()
        }
    }

    private fun terminalLocked(failure: Throwable?) {
        terminated = true
        ready = false
        commands.clear()
        commandBytes = 0
        controls.clear()
        controlBytes = 0
        fileTransfers.clear()
        terminalFailure = combineFailures(terminalFailure, failure)
        lock.notifyAll()
    }

    /** lock must already be held. */
    private fun throwTerminalFailureLocked() {
        terminalFailure?.let { throw it }
    }

    private fun isClosing(): Boolean = synchronized(lock) { closing || terminated }

    private fun wakeReceiverForPendingWork() {
        (underlying as? WakeableBlockingDuplexByteStream)?.wakeReceiver()
    }

    private fun closeUnderlyingOnce(): Throwable? {
        synchronized(lock) {
            if (streamCloseStarted) return null
            streamCloseStarted = true
        }
        return try {
            underlying.close()
            null
        } catch (failure: Throwable) {
            failure
        }
    }

    /** lock must already be held. */
    private fun hasCommandCapacityLocked(bytes: Int): Boolean =
        commands.size < MAX_PENDING_COMMANDS && bytes <= MAX_PENDING_COMMAND_BYTES - commandBytes

    /** lock must already be held. */
    private fun enqueueCommandLocked(
        sessionId: Int,
        bytes: ByteArray,
        flushReceipt: Iap2TransportFlushReceipt? = null,
    ): Boolean {
        if (!hasCommandCapacityLocked(bytes.size)) return false
        commands += Command(sessionId, bytes, flushReceipt)
        commandBytes += bytes.size
        lock.notifyAll()
        return true
    }

    private fun deadlineAfter(timeoutMillis: Long): Long =
        System.nanoTime() + timeoutMillis.coerceAtMost(MAX_WAIT_MILLIS) * NANOS_PER_MILLISECOND

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0) return 0
        return (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
    }

    /** lock must already be held. */
    private fun waitFor(timeoutMillis: Long, readyPredicate: () -> Boolean): Boolean {
        if (readyPredicate()) return true
        if (timeoutMillis == 0L) return false
        val budgetNanos = timeoutMillis.coerceAtMost(MAX_WAIT_MILLIS) * NANOS_PER_MILLISECOND
        val startedNanos = System.nanoTime()
        while (!readyPredicate()) {
            val elapsedNanos = System.nanoTime() - startedNanos
            if (elapsedNanos >= budgetNanos) return false
            val remainingNanos = budgetNanos - elapsedNanos
            try {
                lock.wait(
                    remainingNanos / NANOS_PER_MILLISECOND,
                    (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                )
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    private fun combineFailures(first: Throwable?, second: Throwable?): Throwable? {
        if (first == null) return second
        if (second != null && second !== first) first.addSuppressed(second)
        return first
    }

    companion object {
        private const val RECEIVE_CHUNK_BYTES = 8_192
        private const val MAX_TRANSPORT_CHUNK_RECORDS = 4
        private const val RECEIVE_POLL_MILLIS = 100L
        private const val MINIMUM_RECEIVE_POLL_MILLIS = 1L
        private const val CLOSE_JOIN_MILLIS = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_WAIT_MILLIS = Long.MAX_VALUE / NANOS_PER_MILLISECOND
        private const val MAX_PENDING_COMMANDS = 64
        private const val MAX_PENDING_COMMAND_BYTES = 1_048_576
        private const val MAX_PENDING_CONTROLS = 64
        private const val MAX_PENDING_CONTROL_BYTES = 1_048_576

        private val WIRED_LINK_CONFIG = Iap2LinkConfig(
            maxOutgoing = 4,
            controlSessionVersion = 2,
            zeroAcknowledgements = true,
        )
        private val WIRELESS_LINK_CONFIG = Iap2LinkConfig(
            maxOutgoing = 4,
            controlSessionVersion = 2,
        )

        /** Opens and immediately starts a wired iAP2 link, taking ownership of the supplied stream. */
        fun open(
            underlying: BlockingDuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2LinkChannel =
            Iap2LinkChannel(underlying, WIRED_LINK_CONFIG, initiateNegotiation = true, onArtwork)
                .also { it.worker.start() }

        /**
         * Opens a wireless RFCOMM link. LIVI sends the iAP2 marker but lets the phone initiate
         * synchronization, and keeps acknowledgements enabled for Bluetooth.
         */
        fun openWireless(
            underlying: BlockingDuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
            onLinkDiagnostic: ((stage: String, sequence: Int, payloadBytes: Int, retryCount: Int) -> Unit)? = null,
        ): Iap2LinkChannel =
            Iap2LinkChannel(underlying, WIRELESS_LINK_CONFIG, initiateNegotiation = false, onArtwork, onLinkDiagnostic)
                .also { it.worker.start() }

        /**
         * Opens the iAP2 link carried by an AirPlay type-130 tunnel. The accessory initiates
         * synchronization and zero-acknowledgement mode matches the Wi-Fi transport.
         */
        fun openTunnel(
            underlying: BlockingDuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2LinkChannel =
            Iap2LinkChannel(underlying, WIRED_LINK_CONFIG, initiateNegotiation = true, onArtwork)
                .also { it.worker.start() }

        private fun nowMillis(): Long = System.nanoTime() / NANOS_PER_MILLISECOND
    }
}
