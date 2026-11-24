package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class MusicDepletionPollingTest {
    @Test fun waitsAreBoundedAroundThePredictedDrainTime() {
        assertEquals(30L, musicDepletionPollMs(4800, 48000))
        assertEquals(11L, musicDepletionPollMs(480, 48000))
        assertEquals(12L, musicDepletionPollMs(481, 48000))
        // Once confirmed dry (or an unknown read), inbound packets can still wake this normal wait.
        assertEquals(30L, musicDepletionPollMs(0, 48000))
        assertEquals(30L, musicDepletionPollMs(-1, 48000))
    }

    @Test fun repeatShortDepletionsRemainVisibleBetweenBursts() {
        val tuning = MusicPlayoutTuning()
        val arrivalMs = 45L
        val wakeAtMs = musicDepletionPollMs(480, 48000)
        // The simulated hardware is empty at the scheduled wake, 11 ms after probing.
        val dryNs = TimeUnit.MILLISECONDS.toNanos(arrivalMs - wakeAtMs)
        assertNull(tuning.afterDry(dryNs, TimeUnit.MILLISECONDS.toNanos(arrivalMs)))
        assertEquals(495, tuning.afterDry(dryNs, TimeUnit.MILLISECONDS.toNanos(arrivalMs + 450)))
    }
}
