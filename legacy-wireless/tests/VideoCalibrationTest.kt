package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCalibrationTest {
    @Test
    fun selectsHighestRateSupportedByMeasuredWindowsOnApi17() {
        val result = VideoCalibration.evaluate(listOf(runAt(30), runAt(15), runAt(20)))

        assertEquals(30, result.recommendedFps)
        assertEquals(VideoCalibration.Evidence.SURFACE_SUBMISSION, result.reference)
        assertEquals(VideoCalibration.Status.READY, result.status)
        assertFalse(result.usesCompositorEvidence)
        assertFalse(result.provisional)
    }

    @Test
    fun rejectsBurstWithGoodAverageAndSeparatesObservedFailureFromMissingData() {
        val burst = runAt(30).copy(lowestWindowSurfaceFpsMilli = 17_000)
        val result = VideoCalibration.evaluate(listOf(runAt(15), runAt(20), burst))

        assertEquals(20, result.recommendedFps)
        assertEquals(VideoCalibration.Reason.LOW_WORST_WINDOW_RATE, result.evaluations.last().reason)
        assertEquals(30_000, result.evaluations.last().measuredFpsMilli)
        assertFalse(result.provisional)
    }

    @Test
    fun rejectsPressureLongPausesAndPipelineDelayEvenWithAnAdequateAverage() {
        val otherRates = listOf(runAt(15), runAt(20))
        val fastRate = runAt(30)
        val failures = listOf(
            fastRate.copy(surfaceGapMaxMs = 180) to VideoCalibration.Reason.LONG_FRAME_GAP,
            fastRate.copy(pressureSkips = 9) to VideoCalibration.Reason.PRESENTATION_PRESSURE,
            fastRate.copy(droppedFrames = 9) to VideoCalibration.Reason.PRESENTATION_PRESSURE,
            fastRate.copy(receiveToSurfaceMaxMs = 300) to VideoCalibration.Reason.HIGH_PIPELINE_DELAY
        )

        for ((sample, expected) in failures) {
            val result = VideoCalibration.evaluate(otherRates + sample)
            assertEquals(20, result.recommendedFps)
            assertEquals(expected, result.evaluations.last().reason)
        }
    }

    @Test
    fun unknownMotionOrIncompleteTimingDoesNotPassAsComparativeEvidence() {
        val result = VideoCalibration.evaluate(listOf(
            runAt(15),
            runAt(20).copy(movingFrames = null),
            runAt(30).copy(lowestWindowSurfaceFpsMilli = null)
        ))

        assertNull(result.recommendedFps)
        assertEquals(VideoCalibration.Status.INSUFFICIENT_COMPARABLE_RUNS, result.status)
        assertEquals(VideoCalibration.Reason.MOTION_UNVERIFIED, result.evaluations[1].reason)
        assertEquals(VideoCalibration.Reason.EVIDENCE_UNAVAILABLE, result.evaluations[2].reason)
    }

    @Test
    fun marksLowerRecommendationProvisionalWhenHigherRunContainsTooLittleMotion() {
        val result = VideoCalibration.evaluate(listOf(runAt(15), runAt(20), runAt(30).copy(movingFrames = 8)))

        assertEquals(20, result.recommendedFps)
        assertTrue(result.provisional)
        assertEquals(VideoCalibration.Reason.INSUFFICIENT_MOTION_EVIDENCE, result.evaluations.last().reason)
    }

    @Test
    fun usesCompositorObservationsWhenComparableAndNeverCombinesTheTwoRateTypes() {
        val result = VideoCalibration.evaluate(listOf(
            runAt(15),
            runAt(20).copy(compositorFrames = 240, lowestWindowCompositorFpsMilli = 18_500, compositorGapMaxMs = 70),
            runAt(30).copy(compositorFrames = 240, lowestWindowCompositorFpsMilli = 18_500, compositorGapMaxMs = 70)
        ))

        assertTrue(result.usesCompositorEvidence)
        assertEquals(20, result.recommendedFps)
        assertEquals(VideoCalibration.Reason.EVIDENCE_UNAVAILABLE, result.evaluations.first().reason)
        assertEquals(VideoCalibration.Reason.LOW_AVERAGE_RATE, result.evaluations.last().reason)
    }

    @Test
    fun avoidsRaisingLimitWithoutARepeatableMaterialGain() {
        val result = VideoCalibration.evaluate(listOf(
            runAt(15).copy(surfaceSubmittedFrames = 240, receivedFrames = 240, movingFrames = 140),
            runAt(20),
            runAt(30).copy(surfaceSubmittedFrames = 240, lowestWindowSurfaceFpsMilli = 19_000)
        ))

        assertEquals(15, result.recommendedFps)
        assertTrue(result.evaluations[1].eligible)
        assertEquals(VideoCalibration.Reason.LOW_AVERAGE_RATE, result.evaluations.last().reason)
    }

    @Test
    fun reportsNoSustainedRateWhenTheCompleteComparableRunsAllFail() {
        val short = runAt(30).copy(durationMs = 3_000)
        val result = VideoCalibration.evaluate(listOf(
            runAt(15).copy(surfaceSubmittedFrames = 60),
            runAt(20).copy(surfaceSubmittedFrames = 60),
            short
        ))

        assertNull(result.recommendedFps)
        assertEquals(VideoCalibration.Status.NO_SUSTAINED_RATE, result.status)
        assertEquals(VideoCalibration.Reason.INCOMPLETE, result.evaluations.last().reason)
    }

    @Test
    fun highRateGroupSelects60WhenFourRatesHaveSustainedMeasuredGains() {
        val highRates = PlaybackOptions.calibrationRates(true)
        val result = VideoCalibration.evaluate(highRates.map { runAt(it) }, highRates)

        assertEquals(60, result.recommendedFps)
        assertEquals(VideoCalibration.Status.READY, result.status)
        assertFalse(result.provisional)
        assertTrue(result.evaluations.all { it.eligible })
    }

    @Test
    fun highRateGroupFallsBackTo30WhenTheReceivePathPlateausAt30() {
        val highRates = PlaybackOptions.calibrationRates(true)
        val result = VideoCalibration.evaluate(highRates.map { runAt(it, receivedRate = 30) }, highRates)

        assertEquals(30, result.recommendedFps)
        assertFalse(result.provisional)
        assertTrue(result.evaluations.drop(1).all {
            it.reason == VideoCalibration.Reason.LOW_SOURCE_FRAME_RATE && it.measuredFpsMilli == 30_000
        })
    }

    @Test
    fun rejectsLongDisplayGapAt60EvenWhenTheAverageAndWindowRatesAreHigh() {
        val highRates = PlaybackOptions.calibrationRates(true)
        val runs = highRates.map { runAt(it) }
        val result = VideoCalibration.evaluate(runs.dropLast(1) + runs.last().copy(surfaceGapMaxMs = 51), highRates)

        assertEquals(50, result.recommendedFps)
        assertEquals(VideoCalibration.Reason.LONG_FRAME_GAP, result.evaluations.last().reason)
        assertEquals(60_000, result.evaluations.last().measuredFpsMilli)
        assertFalse(result.provisional)
    }

    @Test
    fun missing60DoesNotCountAsFailedAndLowMotionCannotEstablishAReceivePlateau() {
        val highRates = PlaybackOptions.calibrationRates(true)
        val result = VideoCalibration.evaluate(listOf(
            runAt(30), runAt(40), runAt(50, receivedRate = 30).copy(movingFrames = 4)
        ), highRates)

        assertEquals(40, result.recommendedFps)
        assertTrue(result.provisional)
        assertEquals(VideoCalibration.Reason.INSUFFICIENT_MOTION_EVIDENCE, result.evaluations.last().reason)
    }

    @Test
    fun samplesOutsideTheSelectedRateGroupDoNotAffectItsRecommendation() {
        val samples = PlaybackOptions.rates().map { runAt(it) }
        val basic = VideoCalibration.evaluate(samples, PlaybackOptions.calibrationRates(false))
        val high = VideoCalibration.evaluate(samples, PlaybackOptions.calibrationRates(true))

        assertEquals(30, basic.recommendedFps)
        assertFalse(basic.provisional)
        assertTrue(basic.evaluations.takeLast(3).all { it.reason == VideoCalibration.Reason.UNSUPPORTED_RATE })
        assertEquals(60, high.recommendedFps)
        assertFalse(high.provisional)
        assertTrue(high.evaluations.take(2).all { it.reason == VideoCalibration.Reason.UNSUPPORTED_RATE })
    }

    private fun runAt(rate: Int, receivedRate: Int = rate) = VideoCalibration.Sample(
        requestedFps = rate,
        durationMs = 12_000,
        receivedFrames = receivedRate * 12,
        surfaceSubmittedFrames = receivedRate * 12,
        compositorFrames = null,
        movingFrames = receivedRate * 9,
        surfaceGapMaxMs = 2_000L / receivedRate,
        pressureSkips = 0,
        droppedFrames = 0,
        receiveToSurfaceMaxMs = 110,
        completed = true,
        lowestWindowSurfaceFpsMilli = receivedRate * 1_000 - 500
    )
}
