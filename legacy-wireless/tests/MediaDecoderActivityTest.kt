package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDecoderActivityTest {
    @Test fun releasedOrUnfedDecoderHasNoPendingWork() {
        assertFalse(MediaDecoderActivity.waiting(0, 100, 100))
        assertFalse(MediaDecoderActivity.waiting(1, null, 100))
    }

    @Test fun aRetainedFrameDoesNotRequireFastPollingForever() {
        val lastActivity = 1_000_000_000L
        assertTrue(MediaDecoderActivity.waiting(1, lastActivity, lastActivity + 159_000_000L))
        assertFalse(MediaDecoderActivity.waiting(1, lastActivity, lastActivity + 160_000_000L))
        val nextInput = lastActivity + 250_000_000L
        assertTrue(MediaDecoderActivity.waiting(2, nextInput, nextInput + 50_000_000L))
    }

    @Test fun staticVideoCanRetainAFrameWithoutKeepingTheFastDrainAlive() {
        val firstFrameQueued = 50_000_000L
        assertTrue(MediaDecoderActivity.waiting(1, firstFrameQueued, firstFrameQueued + 5_000_000L))
        for (ms in 160L..2000L step 100) {
            assertFalse(MediaDecoderActivity.waiting(1, firstFrameQueued, firstFrameQueued + ms * 1_000_000L))
        }
        val nextFrameQueued = firstFrameQueued + 2_000_000_000L
        assertTrue(MediaDecoderActivity.waiting(2, nextFrameQueued, nextFrameQueued))
    }

    @Test fun initialVideoAllowsSlowDecoderStartupWithABoundedGracePeriod() {
        val lastInput = 300_000_000L
        assertTrue(MediaDecoderActivity.waiting(1, lastInput, lastInput + 600_000_000L, true))
        assertFalse(MediaDecoderActivity.waiting(1, lastInput, lastInput + 1_000_000_000L, true))
    }

    @Test fun codecWaitClaimsOnlyPartOfTheIdleSlotWhenMusicRunwayIsLow() {
        assertEquals(2_000L, MediaDecoderActivity.lowMusicRunwayWaitUs(0, 48000))
        assertEquals(2_000L, MediaDecoderActivity.lowMusicRunwayWaitUs(240, 48000))
        assertEquals(0L, MediaDecoderActivity.lowMusicRunwayWaitUs(241, 48000))
        assertEquals(0L, MediaDecoderActivity.lowMusicRunwayWaitUs(-1, 48000))
        assertEquals(0L, MediaDecoderActivity.lowMusicRunwayWaitUs(221, 44100))
    }
}
