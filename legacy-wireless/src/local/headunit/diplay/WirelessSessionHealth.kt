package local.headunit.diplay

import java.util.concurrent.TimeUnit

/** Hold the session through a short radio outage, and confirm prolonged silence with fresh probes. */
internal class WirelessSessionHealth {
    enum class Action { PROBE, RESUME, RECONNECT }

    data class Decision(val action: Action, val lastActivityBeforeGapNs: Long)
    private data class Gap(val startedNs: Long, val lastActivityNs: Long, val probeAttempts: Long)

    private var activeSinceNs = 0L
    private var gap: Gap? = null

    fun begin(nowNs: Long) {
        activeSinceNs = nowNs
        gap = null
    }

    fun check(
        nowNs: Long,
        timingReplies: Long,
        lastTimingReplyNs: Long,
        lastInboundNs: Long,
        timingProbeAttempts: Long,
    ): Decision? {
        val latestActivity = maxOf(activeSinceNs, lastTimingReplyNs, lastInboundNs)
        gap?.let { current ->
            if (latestActivity > current.lastActivityNs) {
                gap = null
                return Decision(Action.RESUME, current.lastActivityNs)
            }
            if (nowNs - current.startedNs >= CONFIRM_NS &&
                timingProbeAttempts - current.probeAttempts >= MIN_CONFIRM_PROBES) {
                return Decision(Action.RECONNECT, current.lastActivityNs)
            }
            return null
        }

        // Timing may be negotiated but unused, or may stop long before otherwise healthy data.
        // In either case let TCP report a real transport failure.
        if (timingReplies < MIN_TIMING_REPLIES || lastTimingReplyNs == 0L ||
            latestActivity - lastTimingReplyNs > MAX_TIMING_SKEW_NS ||
            nowNs - latestActivity < SUSPECT_NS) return null

        gap = Gap(nowNs, latestActivity, timingProbeAttempts)
        return Decision(Action.PROBE, latestActivity)
    }

    private companion object {
        const val MIN_TIMING_REPLIES = 3L
        const val MIN_CONFIRM_PROBES = 2L
        val MAX_TIMING_SKEW_NS = TimeUnit.SECONDS.toNanos(4)
        val SUSPECT_NS = TimeUnit.MILLISECONDS.toNanos(2_250)
        val CONFIRM_NS = TimeUnit.MILLISECONDS.toNanos(2_500)
    }
}
