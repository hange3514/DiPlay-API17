package local.headunit.diplay

import org.junit.Assert.*
import org.junit.Test

class MicMutexScheduleTest {
    @Test fun hasASixStageTimeline() {
        assertEquals(0, MicMutexSchedule.stage(0))
        assertEquals(0, MicMutexSchedule.stage(3_999))
        assertEquals(1, MicMutexSchedule.stage(4_000))
        assertEquals(1, MicMutexSchedule.stage(7_999))
        assertEquals(2, MicMutexSchedule.stage(8_000))
        assertEquals(2, MicMutexSchedule.stage(11_999))
        assertEquals(3, MicMutexSchedule.stage(12_000))
        assertEquals(3, MicMutexSchedule.stage(21_999))
        assertEquals(4, MicMutexSchedule.stage(22_000))
        assertEquals(4, MicMutexSchedule.stage(27_999))
        assertEquals(5, MicMutexSchedule.stage(28_000))
        assertEquals(5, MicMutexSchedule.stage(31_999))
        assertEquals(6, MicMutexSchedule.stage(32_000))
        assertEquals(-1, MicMutexSchedule.stage(-1))
    }

    @Test fun retestsRecognitionAndDefaultSources() {
        assertEquals(-1, MicMutexSchedule.recorderSource(0))
        assertEquals(6, MicMutexSchedule.recorderSource(1))
        assertEquals(0, MicMutexSchedule.recorderSource(2))
        assertEquals(-1, MicMutexSchedule.recorderSource(3))
        assertEquals(-1, MicMutexSchedule.recorderSource(4))
        assertEquals(-1, MicMutexSchedule.recorderSource(5))
    }

    @Test fun alsaCardsOnlyDuringStages3And4() {
        for (stage in -1..6) {
            assertEquals(
                when (stage) { 3 -> 0; 4 -> 1; else -> -1 },
                MicMutexSchedule.alsaCard(stage)
            )
        }
    }

    @Test fun alsaCaptureFitsInsideStageWindow() {
        assertEquals(2_500, MicMutexSchedule.alsaCaptureMillis(3))
        assertEquals(2_000, MicMutexSchedule.alsaCaptureMillis(4))
        assertEquals(0, MicMutexSchedule.alsaCaptureMillis(0))
    }

    @Test fun finishedAtOrBeyondStage6() {
        assertFalse(MicMutexSchedule.finished(5))
        assertTrue(MicMutexSchedule.finished(6))
    }
}
