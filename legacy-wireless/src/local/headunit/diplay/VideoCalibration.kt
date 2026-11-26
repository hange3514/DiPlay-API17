package local.headunit.diplay

object VideoCalibration {
    private val supportedRates = PlaybackOptions.rates().toSet()
    private const val minimumDurationMs = 9_000L
    private const val maximumDurationMs = 25_000L
    private const val minimumMotionFramesPerSecond = 4
    private const val minimumMotionPercent = 35
    private const val minimumReceivedPercent = 85
    private const val minimumAveragePercent = 90
    private const val minimumWindowPercent = 80
    private const val maximumPressurePercent = 2
    private const val maximumPipelineDelayMs = 250L

    enum class Evidence { NONE, SURFACE_SUBMISSION, COMPOSITOR }

    enum class Status { READY, INSUFFICIENT_COMPARABLE_RUNS, NO_SUSTAINED_RATE }

    enum class Reason {
        SUSTAINED,
        INCOMPLETE,
        UNSUPPORTED_RATE,
        DUPLICATE_RATE,
        INVALID_COUNTS,
        MOTION_UNVERIFIED,
        INSUFFICIENT_MOTION_EVIDENCE,
        EVIDENCE_UNAVAILABLE,
        LOW_SOURCE_FRAME_RATE,
        LOW_AVERAGE_RATE,
        LOW_WORST_WINDOW_RATE,
        LONG_FRAME_GAP,
        PRESENTATION_PRESSURE,
        HIGH_PIPELINE_DELAY
    }

    data class Sample @JvmOverloads constructor(
        val requestedFps: Int,
        val durationMs: Long,
        val receivedFrames: Int,
        val surfaceSubmittedFrames: Int,
        val compositorFrames: Int?,
        // The collector's compressed-frame activity proxy, not pixel-level movement.
        val movingFrames: Int?,
        val surfaceGapMaxMs: Long,
        val pressureSkips: Int,
        val droppedFrames: Int,
        val receiveToSurfaceMaxMs: Long,
        val completed: Boolean,
        val lowestWindowSurfaceFpsMilli: Int?,
        val lowestWindowCompositorFpsMilli: Int? = null,
        val compositorGapMaxMs: Long? = null
    )

    data class Evaluation(
        val sample: Sample,
        val reason: Reason,
        val measuredFpsMilli: Int?
    ) {
        val eligible: Boolean get() = reason == Reason.SUSTAINED
    }

    data class Result(
        val recommendedFps: Int?,
        val reference: Evidence,
        val status: Status,
        val evaluations: List<Evaluation>,
        val provisional: Boolean
    ) {
        val usesCompositorEvidence: Boolean get() = reference == Evidence.COMPOSITOR
    }

    @JvmStatic
    fun evaluate(samples: List<Sample>): Result = evaluate(samples, PlaybackOptions.calibrationRates(false))

    @JvmStatic
    fun evaluate(samples: List<Sample>, plannedRates: IntArray): Result {
        val testedRates = plannedRates.filter { it in supportedRates }.toSet()
        val duplicateRates = samples.groupingBy { it.requestedFps }.eachCount()
        val surface = samples.map { evaluate(it, Evidence.SURFACE_SUBMISSION, duplicateRates, testedRates) }
        val compositor = samples.map { evaluate(it, Evidence.COMPOSITOR, duplicateRates, testedRates) }
        val surfaceCoverage = surface.count { it.measuredFpsMilli != null }
        val compositorCoverage = compositor.count { it.measuredFpsMilli != null }
        val useCompositor = compositorCoverage >= 2
        val evaluations = if (useCompositor) compositor else surface
        val coverage = if (useCompositor) compositorCoverage else surfaceCoverage
        val evidence = when {
            coverage == 0 -> Evidence.NONE
            useCompositor -> Evidence.COMPOSITOR
            else -> Evidence.SURFACE_SUBMISSION
        }
        if (coverage < 2) {
            return Result(null, evidence, Status.INSUFFICIENT_COMPARABLE_RUNS, evaluations, false)
        }

        var best: Evaluation? = null
        for (candidate in evaluations.filter { it.eligible }.sortedBy { it.sample.requestedFps }) {
            val previous = best
            if (previous == null || hasMeaningfulGain(previous, candidate)) best = candidate
        }
        if (best == null) return Result(null, evidence, Status.NO_SUSTAINED_RATE, evaluations, false)

        val selectedRate = best.sample.requestedFps
        val provisional = testedRates.any { rate ->
            rate > selectedRate && evaluations.none {
                it.sample.requestedFps == rate && it.measuredFpsMilli != null
            }
        }
        return Result(selectedRate, evidence, Status.READY, evaluations, provisional)
    }

    private fun evaluate(sample: Sample, evidence: Evidence, rateCounts: Map<Int, Int>, testedRates: Set<Int>): Evaluation {
        val motion = sample.movingFrames
        val unavailableReason = when {
            sample.requestedFps !in testedRates -> Reason.UNSUPPORTED_RATE
            rateCounts[sample.requestedFps] != 1 -> Reason.DUPLICATE_RATE
            !sample.completed || sample.durationMs !in minimumDurationMs..maximumDurationMs -> Reason.INCOMPLETE
            sample.receivedFrames < 0 || sample.surfaceSubmittedFrames < 0 ||
                sample.surfaceGapMaxMs < 0 || sample.pressureSkips < 0 || sample.droppedFrames < 0 ||
                sample.receiveToSurfaceMaxMs < 0 -> Reason.INVALID_COUNTS
            motion == null -> Reason.MOTION_UNVERIFIED
            motion !in 0..sample.receivedFrames -> Reason.INVALID_COUNTS
            motion * 1_000L < minimumMotionFramesPerSecond * sample.durationMs ||
                motion * 100L < sample.receivedFrames * minimumMotionPercent.toLong() ->
                Reason.INSUFFICIENT_MOTION_EVIDENCE
            else -> null
        }
        if (unavailableReason != null) return Evaluation(sample, unavailableReason, null)

        val compositor = evidence == Evidence.COMPOSITOR
        val frames = if (compositor) sample.compositorFrames else sample.surfaceSubmittedFrames
        val lowestWindowFps = if (compositor) sample.lowestWindowCompositorFpsMilli else sample.lowestWindowSurfaceFpsMilli
        val longestGapMs = if (compositor) sample.compositorGapMaxMs else sample.surfaceGapMaxMs
        if (frames == null || lowestWindowFps == null || longestGapMs == null) {
            return Evaluation(sample, Reason.EVIDENCE_UNAVAILABLE, null)
        }
        // A few submissions from the preceding interval can finish across a sampling boundary.
        if (frames < 0 || lowestWindowFps < 0 || longestGapMs < 0 ||
            frames > sample.receivedFrames + sample.requestedFps.toLong()
        ) {
            return Evaluation(sample, Reason.INVALID_COUNTS, null)
        }

        val measuredFps = (frames * 1_000_000L / sample.durationMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val receivedFps = sample.receivedFrames * 1_000_000L / sample.durationMs
        val maximumGapMs = 3_000L / sample.requestedFps
        val maxPressure = sample.receivedFrames * maximumPressurePercent.toLong()
        val reason = when {
            // A measured ingress shortfall may come from the sender or delivery path.
            receivedFps < sample.requestedFps * minimumReceivedPercent * 10 -> Reason.LOW_SOURCE_FRAME_RATE
            measuredFps < sample.requestedFps * minimumAveragePercent * 10 -> Reason.LOW_AVERAGE_RATE
            lowestWindowFps < sample.requestedFps * minimumWindowPercent * 10 -> Reason.LOW_WORST_WINDOW_RATE
            longestGapMs > maximumGapMs -> Reason.LONG_FRAME_GAP
            sample.pressureSkips * 100L > maxPressure || sample.droppedFrames * 100L > maxPressure ->
                Reason.PRESENTATION_PRESSURE
            sample.receiveToSurfaceMaxMs > maximumPipelineDelayMs -> Reason.HIGH_PIPELINE_DELAY
            else -> Reason.SUSTAINED
        }
        return Evaluation(sample, reason, measuredFps)
    }

    private fun hasMeaningfulGain(previous: Evaluation, candidate: Evaluation): Boolean {
        val previousRate = checkNotNull(previous.measuredFpsMilli)
        val candidateRate = checkNotNull(candidate.measuredFpsMilli)
        val minimumGain = maxOf(2_000L, previousRate * 12L / 100L)
        return candidateRate.toLong() - previousRate >= minimumGain
    }
}
