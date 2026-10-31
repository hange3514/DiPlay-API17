package local.headunit.diplay

/** Bounded recovery for AudioRecord reads that fail or keep returning no bytes. */
class MicrophoneReadRecoveryPolicy(
    private val noDataTimeoutNs: Long = 1_000_000_000L,
    private val maxRestarts: Int = 3
) {
    enum class Action { DATA, WAIT, RESTART, RESTARTS_EXHAUSTED, FATAL }

    var restartAttempts: Int = 0
        private set

    private var noDataSinceNs = NO_DATA
    private var exhaustionReported = false

    fun onReadResult(bytesRead: Int, nowNs: Long): Action {
        if (bytesRead > 0) {
            noDataSinceNs = NO_DATA
            exhaustionReported = false
            return Action.DATA
        }

        if (bytesRead < 0) {
            noDataSinceNs = NO_DATA
            return if (restartAttempts < maxRestarts) {
                restartAttempts++
                Action.RESTART
            } else {
                Action.FATAL
            }
        }

        if (noDataSinceNs == NO_DATA) noDataSinceNs = nowNs
        if (nowNs - noDataSinceNs < noDataTimeoutNs) return Action.WAIT
        if (restartAttempts < maxRestarts) {
            restartAttempts++
            noDataSinceNs = nowNs
            return Action.RESTART
        }
        if (!exhaustionReported) {
            exhaustionReported = true
            return Action.RESTARTS_EXHAUSTED
        }
        return Action.WAIT
    }

    /** Forget an empty-read interval when capture is intentionally stopped or recreated. */
    fun recorderStopped() {
        noDataSinceNs = NO_DATA
        exhaustionReported = false
    }

    private companion object { const val NO_DATA = Long.MIN_VALUE }
}
