package local.headunit.diplay

/** Uses sender cadence only for small early arrivals. The first and late outputs render immediately. */
internal class VideoPlayoutTiming {
    enum class DrainAction { DEQUEUE, WAIT_FOR_HELD, RENDER_HELD }

    private var senderAnchor = 0L
    private var arrivalAnchor = 0L
    private var lastSender = 0L
    private var lastArrival = 0L
    private var consecutiveEarlySteadyIntervals = 0

    fun reset() {
        senderAnchor = 0L
        arrivalAnchor = 0L
        lastSender = 0L
        lastArrival = 0L
        consecutiveEarlySteadyIntervals = 0
    }

    fun deadline(senderNs: Long, arrivalNs: Long, nowNs: Long, bypass: Boolean): Long {
        if (senderNs <= 0L) return nowNs
        val expected = arrivalAnchor + (senderNs - senderAnchor)
        if (lastSender == 0L || senderNs < lastSender || arrivalNs < lastArrival ||
            senderNs - lastSender > RESET_GAP_NS ||
            expected - arrivalNs !in -RESET_GAP_NS..RESET_GAP_NS
        ) {
            anchor(senderNs, arrivalNs)
            return nowNs
        }
        if (senderNs == lastSender) return nowNs
        val arrivalInterval = arrivalNs - lastArrival
        val senderInterval = senderNs - lastSender
        lastSender = senderNs
        lastArrival = arrivalNs
        // A late anchor must not impose a permanent hold once arrivals match the sender cadence.
        // A compressed burst fails the interval check and keeps the existing short hold.
        val steadyEarly = expected > nowNs &&
            kotlin.math.abs(arrivalInterval - senderInterval) <= STEADY_INTERVAL_TOLERANCE_NS
        consecutiveEarlySteadyIntervals = if (steadyEarly) consecutiveEarlySteadyIntervals + 1 else 0
        if (consecutiveEarlySteadyIntervals >= STEADY_INTERVALS_TO_REANCHOR) {
            anchor(senderNs, arrivalNs)
            return nowNs
        }
        return if (bypass) nowNs else expected.coerceIn(nowNs, nowNs + MAX_HOLD_NS)
    }

    private fun anchor(senderNs: Long, arrivalNs: Long) {
        senderAnchor = senderNs
        arrivalAnchor = arrivalNs
        lastSender = senderNs
        lastArrival = arrivalNs
        consecutiveEarlySteadyIntervals = 0
    }

    companion object {
        const val MAX_HOLD_NS = 10_000_000L
        private const val RESET_GAP_NS = 500_000_000L
        private const val STEADY_INTERVAL_TOLERANCE_NS = 6_000_000L
        private const val STEADY_INTERVALS_TO_REANCHOR = 2

        fun drainAction(heldDeadlineNs: Long?, nowNs: Long, pressure: Boolean): DrainAction =
            when {
                heldDeadlineNs == null || pressure -> DrainAction.DEQUEUE
                nowNs < heldDeadlineNs -> DrainAction.WAIT_FOR_HELD
                else -> DrainAction.RENDER_HELD
            }

        fun underPressure(queueDepth: Int, inFlight: Int, oldestAgeNs: Long, awaitingFirst: Boolean): Boolean =
            !awaitingFirst && (queueDepth >= 3 || inFlight >= 6 ||
                (inFlight >= 4 && oldestAgeNs >= VideoInput.MAX_DELTA_AGE_NS))
    }
}
