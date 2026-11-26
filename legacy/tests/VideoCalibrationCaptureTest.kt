package local.headunit.diplay

import org.junit.Assert.*
import org.junit.Test

class VideoCalibrationCaptureTest {
    private val start = 20_000_000_000L

    @Test fun countsSteadyRealFramesAndIgnoresOutsideTheSamplingWindow() {
        val capture = VideoCalibrationCapture(20, start, 4L)
        capture.received(1, start - 1, 4096)
        capture.submitted(start - 2, start - 1)
        repeat(300) { frame ->
            val arrival = start + 50_000_000L * frame
            capture.received(80_000_000_000L + 50_000_000L * frame, arrival, 1200)
            capture.submitted(arrival, arrival + 10_000_000L)
        }
        capture.received(101_000_000_000L, start + 15_000_000_000L, 1200)
        capture.submitted(start, start + 15_000_000_001L)
        val sample = capture.sample(start + 15_200_000_000L, 7L)
        assertTrue(sample.completed)
        assertEquals(15000L, sample.durationMs)
        assertEquals(300, sample.receivedFrames)
        assertEquals(300, sample.surfaceSubmittedFrames)
        assertEquals(300, sample.movingFrames)
        assertEquals(20000, sample.lowestWindowSurfaceFpsMilli)
        assertEquals(50L, sample.surfaceGapMaxMs)
        assertEquals(10L, sample.receiveToSurfaceMaxMs)
        assertEquals(3, sample.droppedFrames)
        assertNull(sample.compositorFrames)
    }

    @Test fun detectsSparseRepeatsAndTailStallAndRejectsShortSamples() {
        val capture = VideoCalibrationCapture(30, start, 0L)
        capture.received(90_000_000_000L, start + 50_000_000L, 128)
        capture.received(90_033_000_000L, start + 100_000_000L, 131)
        capture.submitted(start + 100_000_000L, start + 110_000_000L)
        capture.skipped(start + 120_000_000L)
        val sample = capture.sample(start + 8_000_000_000L, 0L)
        assertFalse(sample.completed)
        assertEquals(0, sample.movingFrames)
        assertEquals(1, sample.pressureSkips)
        assertEquals(0, sample.lowestWindowSurfaceFpsMilli)
        assertEquals(7890L, sample.surfaceGapMaxMs)
        assertEquals(17L, capture.deliveryJitterMaxMs())
    }
}
