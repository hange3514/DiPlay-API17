package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.min

/**
 * Blocking CSM facade over one owned [Iap2LinkChannel].
 *
 * It keeps CSM framing separate from typed control messages.  Sending waits for a completed iAP2
 * negotiation so every complete CSM frame can be split to the peer's advertised session-10 limit.
 * One receiver owns the stateful framer; concurrent senders are serialized so their split frames
 * cannot interleave.  Closing this channel closes its link and the link's owned byte stream.
 */
class Iap2CsmChannel private constructor(
    private val link: Iap2LinkChannel,
    private val onLinkDiagnostic: ((stage: String, sequence: Int, payloadBytes: Int, retryCount: Int) -> Unit)? = null,
) : java.io.Closeable {
    private val stateLock = Object()
    private val sendLock = Object()
    private val receiveLock = Object()
    private val framer = Iap2CsmFramer()
    private val receivedFrames = ArrayDeque<Iap2Frame>()
    private var receivedBytes = 0
    private var resyncDiagnosticCount = 0
    private var partialDiagnosticCount = 0
    private var lastReportedPartialBytes = 0
    private var closed = false
    private var terminalFailure: Throwable? = null

    /** True once this channel is closed or has recorded a terminal transport failure. */
    val isClosed: Boolean
        get() = synchronized(stateLock) {
            closed || terminalFailure != null
        }

    /** Waits for the underlying link to negotiate a writable session 10. */
    fun awaitReady(timeoutMillis: Long): Boolean {
        requireTimeout(timeoutMillis)
        try {
            checkOpen()
        } catch (failure: Error) {
            fatal(failure)
        }
        return try {
            val ready = link.awaitReady(timeoutMillis)
            if (ready && link.peerMaxControlPayloadBytes() == null) {
                throw IOException("iAP2 link became writable without a peer control payload limit")
            }
            ready
        } catch (failure: Throwable) {
            if (failure is Error) fatal(failure)
            failClosed(failure)
        }
    }

    /**
     * Queues one complete CSM frame.  Call [awaitReady] successfully first.
     *
     * A false result from the link can mean that a previous chunk of this frame was accepted.  In
     * that case continuing would corrupt CSM framing, so the owned link is closed and an exception
     * is thrown instead of returning a partial-send result.
     */
    fun send(
        frame: Iap2Frame,
        timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
    ) {
        sendFrame(frame, timeoutMillis, awaitTransportFlush = false)
    }

    /** Sends a complete CSM frame and waits for the underlying stream's write to finish. */
    internal fun sendAndAwaitTransportFlush(
        frame: Iap2Frame,
        timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
    ) {
        sendFrame(frame, timeoutMillis, awaitTransportFlush = true)
    }

    private fun sendFrame(
        frame: Iap2Frame,
        timeoutMillis: Long,
        awaitTransportFlush: Boolean,
    ) {
        requireTimeout(timeoutMillis)
        synchronized(sendLock) {
            try {
                checkOpen()
            } catch (failure: Error) {
                fatal(failure)
            }
            val peerLimit = link.peerMaxControlPayloadBytes()
                ?: throw IllegalStateException("CSM send requires a successful iAP2 awaitReady")
            val chunks = Iap2CsmFramer.splitForLink(frame, peerLimit)
            if (chunks.size > 1) {
                when (frame.messageId) {
                    0x5703 -> reportCsmDiagnostic("wifi_config_csm_fragmented", chunks.sumOf { it.size })
                    0x4301 -> reportCsmDiagnostic("carplay_start_csm_fragmented", chunks.sumOf { it.size })
                }
            }
            val deadlineNanos = deadlineAfter(timeoutMillis)
            for (chunk in chunks) {
                val queued = try {
                    if (awaitTransportFlush) {
                        link.sendControlAwaitTransportFlush(chunk, remainingMillis(deadlineNanos))
                    } else {
                        link.sendControlAwaitCapacity(chunk, remainingMillis(deadlineNanos))
                    }
                } catch (failure: Throwable) {
                    if (failure is Error) fatal(failure)
                    failClosed(failure)
                }
                if (!queued) {
                    val action = if (awaitTransportFlush) "write" else "queue"
                    failClosed(IOException("iAP2 could not $action the complete CSM frame before its timeout"))
                }
            }
        }
    }

    /**
     * Returns the next complete CSM frame, null on timeout or clean close, and throws on failure.
     * A single underlying control payload can decode into several frames; the remainder stays in a
     * bounded queue for later calls.
     */
    fun recv(timeoutMillis: Long): Iap2Frame? {
        requireTimeout(timeoutMillis)
        synchronized(receiveLock) {
            takeReceived()?.let { return it }
            val deadlineNanos = deadlineAfter(timeoutMillis)
            while (true) {
                try {
                    checkOpen()
                } catch (failure: Error) {
                    fatal(failure)
                }
                val control = try {
                    link.recvControl(remainingMillis(deadlineNanos))
                } catch (failure: Throwable) {
                    if (failure is Error) fatal(failure)
                    reportPendingFrame()
                    failClosed(failure)
                }
                if (control == null) {
                    reportPendingFrame()
                    return null
                }

                val discardedBefore = framer.discardedBytes
                val invalidLengthsBefore = framer.invalidLengths
                val frames = try {
                    framer.offer(control)
                } catch (failure: Throwable) {
                    if (failure is Error) fatal(failure)
                    reportCsmDiagnostic("csm_rx_framing_failed", control.size)
                    failClosed(IOException("Invalid CSM control payload", failure))
                }
                val discarded = framer.discardedBytes - discardedBefore
                if (discarded > 0 && resyncDiagnosticCount < MAX_RECEIVE_DIAGNOSTICS) {
                    resyncDiagnosticCount++
                    val stage = if (framer.invalidLengths > invalidLengthsBefore) "csm_rx_bad_length" else "csm_rx_resync"
                    reportCsmDiagnostic(stage, discarded.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }
                if (frames.isNotEmpty() || framer.pendingBytes == 0) lastReportedPartialBytes = 0
                for (frame in frames) enqueue(frame)
                takeReceived()?.let { return it }
                if (remainingMillis(deadlineNanos) == 0L) {
                    reportPendingFrame()
                    return null
                }
            }
        }
    }

    private fun reportPendingFrame() {
        val pending = framer.pendingBytes
        if (pending == 0 || pending == lastReportedPartialBytes || partialDiagnosticCount >= MAX_RECEIVE_DIAGNOSTICS) return
        partialDiagnosticCount++
        lastReportedPartialBytes = pending
        reportCsmDiagnostic("csm_rx_partial", pending)
    }

    private fun reportCsmDiagnostic(stage: String, bytes: Int) {
        try {
            onLinkDiagnostic?.invoke(stage, -1, bytes, -1)
        } catch (_: Exception) {
            // Observing receive progress must not affect framing or retries.
        }
    }

    /** Closes the owned iAP2 link and its owned byte stream. */
    override fun close() {
        val close = synchronized(stateLock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!close) return
        try {
            link.close()
        } catch (failure: Throwable) {
            if (failure is Error) {
                terminalize(failure)
                throw failure
            }
            throw terminalize(failure)
        }
    }

    private fun enqueue(frame: Iap2Frame) {
        val encodedBytes = frame.encodedSize
        if (receivedFrames.size >= MAX_PENDING_FRAMES || encodedBytes > MAX_PENDING_FRAME_BYTES - receivedBytes) {
            reportCsmDiagnostic("csm_rx_queue_full", encodedBytes)
            failClosed(IOException("CSM received-frame queue limit exceeded"))
        }
        receivedFrames += frame
        receivedBytes += encodedBytes
    }

    private fun takeReceived(): Iap2Frame? {
        val frame = receivedFrames.pollFirst() ?: return null
        receivedBytes -= frame.encodedSize
        return frame
    }

    private fun checkOpen() {
        synchronized(stateLock) {
            terminalFailure?.let { throw it }
            if (closed) throw IOException("CSM channel is closed")
        }
    }

    /** Records a terminal error, breaks a blocked receiver, then always throws that error. */
    private fun failClosed(failure: Throwable): Nothing {
        val terminal = terminalize(failure)
        try {
            close()
        } catch (closeFailure: Throwable) {
            if (closeFailure is Error) throw closeFailure
            if (closeFailure !== terminal) terminal.addSuppressed(closeFailure)
        }
        throw terminal
    }

    /** Closes best-effort before propagating the original fatal VM or transport error. */
    private fun fatal(error: Error): Nothing {
        terminalize(error)
        try {
            close()
        } catch (_: Throwable) {
            // The original Error is the failure the caller must observe.
        }
        throw error
    }

    private fun terminalize(failure: Throwable): Throwable {
        synchronized(stateLock) {
            val prior = terminalFailure
            if (prior == null) {
                terminalFailure = failure
                return failure
            }
            if (prior !== failure) prior.addSuppressed(failure)
            return prior
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        return System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return 0
        return min(MAX_TIMEOUT_MILLIS, (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
    }

    private fun requireTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 0..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 0..$MAX_TIMEOUT_MILLIS"
        }
    }

    companion object {
        private const val MAX_PENDING_FRAMES = 64
        private const val MAX_RECEIVE_DIAGNOSTICS = 4
        private const val MAX_PENDING_FRAME_BYTES = 1_048_576
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val DEFAULT_SEND_TIMEOUT_MILLIS = 5_000L

        /** Opens the owned iAP2 link over an owned carkit stream. */
        fun open(
            underlying: BlockingDuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2CsmChannel =
            Iap2CsmChannel(Iap2LinkChannel.open(underlying, onArtwork))

        /** Opens the owned iAP2 link over an owned wireless RFCOMM stream. */
        fun openWireless(
            underlying: BlockingDuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
            onLinkDiagnostic: ((stage: String, sequence: Int, payloadBytes: Int, retryCount: Int) -> Unit)? = null,
        ): Iap2CsmChannel =
            Iap2CsmChannel(Iap2LinkChannel.openWireless(underlying, onArtwork, onLinkDiagnostic), onLinkDiagnostic)

        /** Opens the owned iAP2 link over an AirPlay type-130 tunnel. */
        fun openTunnel(
            underlying: BlockingDuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2CsmChannel =
            Iap2CsmChannel(Iap2LinkChannel.openTunnel(underlying, onArtwork))
    }
}
