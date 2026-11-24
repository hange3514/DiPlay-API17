package local.headunit.diplay

import org.junit.Assert.*
import org.junit.Test

class MusicPlayoutTuningTest {
    @Test fun sizesTheBufferForBurstsAndLowersItWhenArrivalBecomesSteady() {
        val tuning = MusicPlayoutTuning()
        for (ms in 0..16000 step 400) tuning.packetArrived(ms * NS_PER_MS)
        assertEquals(465, tuning.targetMs)
        for (ms in 16020..64000 step 20) tuning.packetArrived(ms * NS_PER_MS)
        assertEquals(MusicPlayoutTuning.MIN_MS, tuning.targetMs)
    }

    @Test fun repeatUnderrunsRaiseTheTargetButPauseAndLateDuplicatesDoNot() {
        val tuning = MusicPlayoutTuning()
        tuning.packetArrived(1000 * NS_PER_MS)
        tuning.packetArrived(980 * NS_PER_MS)
        assertNull(tuning.afterDry(119 * NS_PER_MS, 1100 * NS_PER_MS))
        assertEquals(450, tuning.afterDry(1100 * NS_PER_MS, 2200 * NS_PER_MS))
        assertEquals(535, tuning.afterDry(120 * NS_PER_MS, 2320 * NS_PER_MS))
        repeat(6) { tuning.afterDry(140 * NS_PER_MS, (2500L + it * 300) * NS_PER_MS) }
        assertEquals(MusicPlayoutTuning.MAX_MS, tuning.targetMs)
        for (ms in 4020..80000 step 20) tuning.packetArrived(ms * NS_PER_MS)
        assertEquals(MusicPlayoutTuning.MIN_MS, tuning.targetMs)
    }

    @Test fun onlyRepeatedShortDryPeriodsCloseTogetherIncreaseTheCushion() {
        val tuning = MusicPlayoutTuning()
        fun afterDry(dryMs: Long, atMs: Long) = tuning.afterDry(dryMs * NS_PER_MS, atMs * NS_PER_MS)
        assertNull(afterDry(29, 100))
        assertNull(afterDry(35, 500))
        assertNull(afterDry(50, 500))
        assertNull(afterDry(60, 10501))
        assertEquals(495, afterDry(35, 14000))
        assertNull(afterDry(110, 15000))
        assertEquals(495, afterDry(1200, 18000))
        assertNull(afterDry(75, 20000))
    }

    @Test fun longInactivityDoesNotAgeTheCushionOut() {
        val tuning = MusicPlayoutTuning()
        for (ms in 0..16000 step 400) tuning.packetArrived(ms * NS_PER_MS)
        assertEquals(550, tuning.afterDry(120 * NS_PER_MS, 16400 * NS_PER_MS))
        for (ms in 76400..96400 step 400) tuning.packetArrived(ms * NS_PER_MS)
        assertEquals(550, tuning.targetMs)
        for (ms in 96800..100400 step 400) tuning.packetArrived(ms * NS_PER_MS)
        assertEquals(510, tuning.targetMs)
    }

    @Test fun senderBurstsAloneDoNotTuneThePlaybackRate() {
        assertNull(simulateClock(trackPpm = 0, irregularBursts = true))
    }

    @Test fun aStableNominalClockStopsRequestingSamples() {
        val clock = MusicClockCalibration(48000, 48000)
        for (ms in 0..40000 step 20) {
            val head = ms * 48L
            clock.received(head.toInt(), ms * NS_PER_MS)
            clock.playedBeforeWrite(head, 4800L, ms * NS_PER_MS)
            assertNull(clock.correction())
        }
        assertFalse(clock.needsObservation)
    }

    @Test fun correctsAConsistentlyFastPlaybackClockWithoutTreatingOpusTicksAsPcmFrames() {
        val correction = simulateClock(trackPpm = 7600, packetClock = 24000)!!
        assertTrue(correction.rate.toString(), correction.rate in 47633..47643)
        assertTrue(correction.sourcePpm.toString(), kotlin.math.abs(correction.sourcePpm) < 100)
    }

    @Test fun correctsASlowClockAcrossTheRtpAndTrackCounterWrap() {
        val correction = simulateClock(trackPpm = -7600)!!
        assertTrue(correction.rate.toString(), correction.rate in 48362..48374)
    }

    @Test fun pauseAndDiscontinuitiesPreventPrematureRateCorrection() {
        val clock = MusicClockCalibration(48000, 48000)
        for (segment in 0 until 5) {
            for (ms in 0..8000 step 20) {
                val now = (segment * 10000 + ms) * NS_PER_MS
                clock.received((ms * 48L).toInt(), now)
                clock.playedBeforeWrite(ms * 48365L / 1000, 1440, now)
            }
            assertNull(clock.correction())
            clock.reset()
        }
    }

    @Test fun briefPeriodicStarvationCannotMasqueradeAsASlowClock() {
        // Losing just 3 ms at each 400 ms burst looks exactly like a 7500 ppm slow clock
        // if the playback head is sampled just after writing into the empty track.
        assertNotNull(simulateClock(trackPpm = 0, starvationMs = 3, reportEmptyTrack = false))
        assertNull(simulateClock(trackPpm = 0, starvationMs = 3))
    }

    @Test fun validatedTimelineChangeRearmsAfterOneCorrection() {
        val clock = MusicClockCalibration(48000, 48000)
        assertNotNull(simulateClock(trackPpm = 7600, clock = clock))
        assertFalse(clock.needsObservation)
        clock.reset()
        assertFalse(clock.needsObservation)
        clock.rearm()
        assertTrue(clock.needsObservation)
        assertNull(clock.correction())
    }

    private fun simulateClock(
        trackPpm: Int,
        packetClock: Int = 48000,
        irregularBursts: Boolean = false,
        starvationMs: Int = 0,
        reportEmptyTrack: Boolean = true,
        clock: MusicClockCalibration = MusicClockCalibration(48000, packetClock),
    ): MusicClockCalibration.Correction? {
        val counterOffset = 0xfffffff0L
        for (ms in 0..50000 step 20) {
            val elapsedPlayingMs = ms - (ms / 400) * starvationMs
            val head = (counterOffset + elapsedPlayingMs * 48L * (1_000_000 + trackPpm) / 1_000_000) and 0xffffffffL
            val queued = if (reportEmptyTrack && starvationMs > 0 && ms % 400 == 0) 0L else 1440L
            clock.playedBeforeWrite(head, queued, ms * NS_PER_MS)
            if (ms % 400 == 0) {
                val delay = if (irregularBursts) ((ms / 400) % 4) * 23 else 0
                repeat(20) { index ->
                    val timestamp = counterOffset + (ms + index * 20L) * packetClock / 1000
                    clock.received(timestamp.toInt(), (ms + delay) * NS_PER_MS + index * 200_000L)
                }
            }
            clock.correction()?.let { return it }
        }
        return null
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}
