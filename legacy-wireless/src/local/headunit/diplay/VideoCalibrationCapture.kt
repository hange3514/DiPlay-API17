package local.headunit.diplay

/** Count real projection activity during one bounded trial. API 17 has no presentation callback. */
internal class VideoCalibrationCapture(
    private val requestedFps: Int,
    private val startNs: Long,
    private val initialDrops: Long,
) {
    private val submittedByWindow = IntArray(WINDOW_COUNT)
    private var receivedFrames = 0
    private var movingFrames = 0
    private var submittedFrames = 0
    private var pressureSkips = 0
    private var lastSubmissionNs = 0L
    private var largestGapNs = 0L
    private var maxAgeNs = 0L
    private var maxDeliveryJitterNs = 0L
    private var previousSenderNs = 0L
    private var previousArrivalNs = 0L

    @Synchronized fun received(senderNs: Long, arrivalNs: Long, bytes: Int) {
        if (!withinTrial(arrivalNs)) return
        receivedFrames++
        // Compressed size is only a proxy for exercise of the video path. Sparse/tiny repeats
        // should not earn a higher rate. No image or payload is saved or decoded a second time.
        if (bytes >= ACTIVE_AU_BYTES) movingFrames++
        if (previousSenderNs != 0L && senderNs > previousSenderNs && arrivalNs > previousArrivalNs) {
            val senderGap = senderNs - previousSenderNs
            val arrivalGap = arrivalNs - previousArrivalNs
            if (senderGap <= 1_000_000_000L) {
                maxDeliveryJitterNs = maxOf(maxDeliveryJitterNs, kotlin.math.abs(arrivalGap - senderGap))
            }
        }
        previousArrivalNs = arrivalNs
        previousSenderNs = senderNs
    }

    @Synchronized fun submitted(arrivalNs: Long, atNs: Long) {
        if (!withinTrial(atNs)) return
        val elapsed = atNs - startNs
        submittedFrames++
        if (lastSubmissionNs != 0L) largestGapNs = maxOf(largestGapNs, atNs - lastSubmissionNs)
        lastSubmissionNs = atNs
        if (arrivalNs != 0L && atNs >= arrivalNs) maxAgeNs = maxOf(maxAgeNs, atNs - arrivalNs)
        val window = (elapsed / WINDOW_NS).toInt()
        if (window in submittedByWindow.indices) submittedByWindow[window]++
    }

    @Synchronized fun skipped(atNs: Long) {
        if (withinTrial(atNs)) pressureSkips++
    }

    @Synchronized fun sample(endNs: Long, dropped: Long): VideoCalibration.Sample {
        val actualEnd = minOf(endNs, startNs + TRIAL_NS)
        val elapsedNs = (actualEnd - startNs).coerceAtLeast(0L)
        val firstFullWindowCount = (elapsedNs / WINDOW_NS).toInt().coerceAtMost(submittedByWindow.size)
        var worstFpsMilli: Int? = null
        for (index in 0 until firstFullWindowCount) {
            val rate = submittedByWindow[index] * 1_000_000 / (WINDOW_NS / 1_000_000).toInt()
            worstFpsMilli = minOf(worstFpsMilli ?: rate, rate)
        }
        val tailGapNs = if (lastSubmissionNs > 0L) (actualEnd - lastSubmissionNs).coerceAtLeast(0L) else elapsedNs
        return VideoCalibration.Sample(
            requestedFps = requestedFps,
            durationMs = elapsedNs / 1_000_000,
            receivedFrames = receivedFrames,
            surfaceSubmittedFrames = submittedFrames,
            compositorFrames = null,
            movingFrames = movingFrames,
            surfaceGapMaxMs = maxOf(largestGapNs, tailGapNs) / 1_000_000,
            pressureSkips = pressureSkips,
            droppedFrames = (dropped - initialDrops).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            receiveToSurfaceMaxMs = maxAgeNs / 1_000_000,
            completed = elapsedNs >= TRIAL_NS,
            lowestWindowSurfaceFpsMilli = worstFpsMilli,
        )
    }

    @Synchronized fun deliveryJitterMaxMs(): Long = maxDeliveryJitterNs / 1_000_000

    private fun withinTrial(atNs: Long): Boolean = atNs - startNs in 0 until TRIAL_NS

    private companion object {
        const val ACTIVE_AU_BYTES = 512
        const val WINDOW_COUNT = 5
        const val WINDOW_NS = 3_000_000_000L
        const val TRIAL_NS = WINDOW_NS * WINDOW_COUNT
    }
}
