package local.headunit.diplay

import java.io.Closeable

/** Starts an app/Siri microphone on downlink audio, or after a bounded setup grace period. */
class MicrophoneStartGate(
    private val alive: () -> Boolean,
    private val start: (String) -> Unit,
    private val stop: () -> Unit,
) : Closeable {
    private var closed = false
    private var started = false
    private var fallback: Thread? = null

    @Synchronized fun scheduleFallback(delayMs: Long) {
        require(delayMs >= 0)
        if (closed || started || fallback != null) return
        val worker = Thread({
            try {
                Thread.sleep(delayMs)
                startOnce("no-downlink-timeout")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "api17-mic-start-fallback").apply { isDaemon = true }
        fallback = worker
        worker.start()
    }

    @Synchronized fun onDownlink() {
        startOnce("downlink")
        fallback?.interrupt()
        fallback = null
    }

    @Synchronized private fun startOnce(reason: String) {
        if (closed || started) return
        if (!alive()) {
            closed = true
            fallback = null
            return
        }
        started = true
        start(reason)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        fallback?.interrupt()
        fallback = null
        if (started) stop()
    }
}
