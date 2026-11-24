package local.headunit.diplay

/** Bounds the wait for iPhone AirPlay after the wired iAP2 start request. */
internal class WiredStartWatchdog(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val secondRequestCanStillGoOut: () -> Boolean = { false },
    private val onHold: () -> Unit = {},
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private var armed = false
    private var finished = false
    private var rearmed = false
    private var held = false
    private var generation = 0
    private var cancelTimer: (() -> Unit)? = null

    @Synchronized fun startSessionSent() {
        if (armed || finished) return
        armed = true
        arm(timeoutMillis)
    }

    @Synchronized fun startSessionSentAgain() {
        if (!armed || finished || rearmed) return
        rearmed = true
        cancel()
        arm(timeoutMillis)
    }

    @Synchronized fun sessionActive() {
        finished = true
        cancel()
    }

    @Synchronized fun terminate() {
        finished = true
        cancel()
    }

    private fun arm(delayMillis: Long) {
        val armedGeneration = ++generation
        cancelTimer = schedule(delayMillis) { expire(armedGeneration) }
    }

    private fun expire(armedGeneration: Int) {
        val holding = synchronized(this) {
            if (finished || armedGeneration != generation) return
            if (!held && !rearmed && secondRequestCanStillGoOut()) {
                held = true
                arm(HELD_TIMEOUT_MILLIS - timeoutMillis)
                true
            } else {
                finished = true
                cancelTimer = null
                false
            }
        }
        if (holding) onHold() else onTimeout()
    }

    private fun cancel() {
        cancelTimer?.invoke()
        cancelTimer = null
    }

    companion object {
        const val TIMEOUT_MILLIS = 30_000L
        const val HELD_TIMEOUT_MILLIS = 45_000L
    }
}
