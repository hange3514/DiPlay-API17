package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPlayoutTuningTest {
    @Test fun primesFirstAudioWithABoundedSpeechReserve() {
        val call = SpeechPlayoutTuning("call")
        val assistant = SpeechPlayoutTuning("assistant")
        val navigation = SpeechPlayoutTuning("navigation")
        assertEquals(48, call.targetMs)
        assertEquals(55L, assistant.maxWaitMs)
        assertEquals(32, navigation.targetMs)
        assertEquals(40L, navigation.maxWaitMs)
        assertTrue(call.prefillFor(ms(10)))
        assertFalse(call.prefillFor(ms(30)))
        assertEquals(0L, call.rebufferCount)
    }

    @Test fun repeatedDryObservationsKeepTheFirstDepletionAndCooldownBoundsDelays() {
        val tuning = SpeechPlayoutTuning("call")
        assertTrue(tuning.prefillFor(ms(0)))
        tuning.markDry(ms(100))
        tuning.markDry(ms(120))
        assertTrue(tuning.prefillFor(ms(121)))
        tuning.markDry(ms(200))
        assertFalse(tuning.prefillFor(ms(250)))
        tuning.markDry(ms(520))
        assertTrue(tuning.prefillFor(ms(571)))
        assertEquals(2L, tuning.rebufferCount)
        assertEquals(3L, tuning.shortDryEvents)
        assertEquals(51L, tuning.maxShortDryMs)
    }

    @Test fun naturalSpeechPauseAndAlreadyReceivedPacketsDoNotAddBuffering() {
        val tuning = SpeechPlayoutTuning("assistant")
        assertTrue(tuning.prefillFor(ms(0)))
        tuning.markDry(ms(100))
        assertFalse(tuning.prefillFor(ms(98)))
        tuning.markDry(ms(200))
        assertFalse(tuning.prefillFor(ms(211)))
        tuning.markDry(ms(500))
        assertFalse(tuning.prefillFor(ms(1_000)))
        assertFalse(tuning.prefillFor(ms(1_020)))
        assertEquals(0L, tuning.shortDryEvents)
    }

    @Test fun focusOrTimelineResetPrimesOnceAndDropsStaleDryAndCooldownState() {
        val tuning = SpeechPlayoutTuning("navigation")
        assertTrue(tuning.prefillFor(ms(0)))
        tuning.markDry(ms(80))
        assertTrue(tuning.prefillFor(ms(100)))
        tuning.markDry(ms(250))
        tuning.reset()
        tuning.markDry(ms(280))
        assertTrue(tuning.prefillFor(ms(300)))
        assertFalse(tuning.prefillFor(ms(320)))
        tuning.markDry(ms(330))
        assertTrue(tuning.prefillFor(ms(360)))
        assertEquals(2L, tuning.rebufferCount)
        assertEquals(2L, tuning.shortDryEvents)
    }

    @Test fun delayedDecodedAudioClearsDryTrackingWithoutRestartingPlayout() {
        val tuning = SpeechPlayoutTuning("call")
        assertTrue(tuning.prefillFor(ms(0)))
        tuning.markDry(ms(100))
        tuning.clearDry()
        assertFalse(tuning.prefillFor(ms(130)))
        assertEquals(0L, tuning.shortDryEvents)
    }

    private fun ms(value: Long): Long = value * 1_000_000L
}
