package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferedAudioPacingTest {
    @Test fun initialRendererLeadFillsImmediatelyWithoutChangingTheStartAnchor() {
        val pacing = BufferedAudioPacing(48_000)
        val first = milliseconds(1_000)
        pacing.begin(first, BufferedAudioStream.START_LATENCY_MILLIS)
        val filled = fillRenderer(pacing, first)
        assertEquals(31, filled)
        assertEquals(0L, pacing.playedSamples(milliseconds(1_380)))
        assertFalse(pacing.canFeed(milliseconds(1_380)))
        assertTrue(pacing.canFeed(milliseconds(1_420)))
    }

    @Test fun cachedFramesContinuePlayingDuringFourSecondsWithoutNetworkInput() {
        val pacing = BufferedAudioPacing(48_000)
        val first = milliseconds(1_000)
        pacing.begin(first, 400)
        var reservoir = 48_000 * 6 / 1024
        var submitted = 0
        for (atMillis in 1_000L..5_000L step 20L) {
            val at = milliseconds(atMillis)
            while (reservoir > 0 && pacing.canFeed(at)) {
                pacing.frameFed()
                submitted++
                reservoir--
            }
            assertFalse(pacing.isDepleted(at))
        }
        assertTrue(reservoir > 0)
        assertEquals(172_800L, pacing.playedSamples(milliseconds(5_000)))
        assertTrue(submitted * 1024L - 172_800L <= 48_000L * 650L / 1000L + 1024L)
    }

    @Test fun anEmptyReservoirClampsPlaybackAndResumeNeverCatchesUpToSilence() {
        val pacing = BufferedAudioPacing(48_000)
        pacing.begin(milliseconds(1_000), 400)
        pacing.frameFed()
        pacing.frameFed()
        val resumed = milliseconds(5_000)
        assertTrue(pacing.isDepleted(resumed))
        assertEquals(2048L, pacing.playedSamples(resumed))

        pacing.reset()
        pacing.begin(resumed, 100)
        assertEquals(0L, pacing.playedSamples(resumed))
        assertEquals(31, fillRenderer(pacing, resumed))
        assertFalse(pacing.canFeed(resumed))
        assertTrue(pacing.canFeed(milliseconds(5_120)))
    }

    @Test fun intakeIsUnrestrictedUntilFifteenSecondsAreQueued() {
        val intake = BufferedAudioIntakePacing(48_000)
        val at = milliseconds(1_000)
        intake.frameQueued(703, at)
        assertEquals(0L, intake.delayBeforeRead(703, at))
        intake.frameQueued(704, at)
        assertEquals(5_333_333L, intake.delayBeforeRead(704, at))
        assertEquals(333_333L, intake.delayBeforeRead(704, at + milliseconds(5)))
        assertEquals(0L, intake.delayBeforeRead(704, at + milliseconds(6)))
    }

    @Test fun intakeWaitEndsImmediatelyAfterTheReserveIsDrained() {
        val intake = BufferedAudioIntakePacing(48_000)
        val at = milliseconds(1_000)
        intake.frameQueued(704, at)
        assertTrue(intake.delayBeforeRead(704, at) > 0L)
        assertEquals(0L, intake.delayBeforeRead(600, at))
        intake.frameQueued(600, at)
        assertEquals(0L, intake.delayBeforeRead(600, at))
    }

    @Test fun intakeFollowingAnIdlePeriodDoesNotCompensateWithAnotherBurst() {
        val intake = BufferedAudioIntakePacing(44_100)
        val at = milliseconds(1_000)
        intake.frameQueued(700, at)
        val afterIdle = at + milliseconds(1_000)
        assertEquals(0L, intake.delayBeforeRead(700, afterIdle))
        intake.frameQueued(700, afterIdle)
        assertEquals(5_804_988L, intake.delayBeforeRead(700, afterIdle))
    }

    private fun fillRenderer(pacing: BufferedAudioPacing, now: Long): Int {
        var frames = 0
        while (pacing.canFeed(now)) {
            check(++frames < 100) { "renderer lead did not apply" }
            pacing.frameFed()
        }
        return frames
    }

    private fun milliseconds(value: Long): Long = value * 1_000_000L
}


