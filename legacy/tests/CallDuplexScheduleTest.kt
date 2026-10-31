package local.headunit.diplay

import org.junit.Assert.*
import org.junit.Test

class CallDuplexScheduleTest {
    @Test fun hasAFourStageTimeline() {
        assertEquals(0, CallDuplexSchedule.stage(0))
        assertEquals(0, CallDuplexSchedule.stage(4_999))
        assertEquals(1, CallDuplexSchedule.stage(5_000))
        assertEquals(1, CallDuplexSchedule.stage(12_999))
        assertEquals(2, CallDuplexSchedule.stage(13_000))
        assertEquals(2, CallDuplexSchedule.stage(20_999))
        assertEquals(3, CallDuplexSchedule.stage(21_000))
        assertEquals(3, CallDuplexSchedule.stage(25_999))
        assertEquals(4, CallDuplexSchedule.stage(26_000))
        assertEquals(-1, CallDuplexSchedule.stage(-1))
    }

    @Test fun onlyAAndDStagesReleaseCapture() {
        for (stage in -3..4) {
            assertEquals(stage == -2 || stage == 0 || stage == 3, CallDuplexSchedule.microphoneSuppressed(stage))
        }
    }

    @Test fun plainMicSourceOnlyInStageC() {
        assertEquals(1, CallDuplexSchedule.microphoneSource(2))
        for (stage in listOf(-3, -2, -1, 0, 1, 3, 4)) {
            assertEquals(-1, CallDuplexSchedule.microphoneSource(stage))
        }
    }
}
