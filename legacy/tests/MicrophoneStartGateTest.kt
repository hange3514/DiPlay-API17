package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MicrophoneStartGateTest {
    @Test fun appAudioStartsAfterGracePeriodWhenThereIsNoDownlink() {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val started = CountDownLatch(1)
        val gate = MicrophoneStartGate({ true }, { events.add("start:$it"); started.countDown() }, { events.add("stop") })
        gate.scheduleFallback(20)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        gate.close()
        assertEquals(listOf("start:no-downlink-timeout", "stop"), events)
    }

    @Test fun firstDownlinkStartsImmediatelyAndCancelsFallback() {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gate = MicrophoneStartGate({ true }, { events.add("start:$it") }, { events.add("stop") })
        gate.scheduleFallback(80)
        gate.onDownlink()
        Thread.sleep(120)
        gate.close()
        assertEquals(listOf("start:downlink", "stop"), events)
    }

    @Test fun teardownBeforeGracePeriodPreventsLateMicrophoneStart() {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gate = MicrophoneStartGate({ true }, { events.add("start:$it") }, { events.add("stop") })
        gate.scheduleFallback(100)
        gate.close()
        Thread.sleep(130)
        assertFalse(events.contains("start:no-downlink-timeout"))
        assertEquals(emptyList<String>(), events)
    }

    @Test fun retiredStreamCannotStartFromFallback() {
        val events = mutableListOf<String>()
        val gate = MicrophoneStartGate({ false }, { events.add("start:$it") }, { events.add("stop") })
        gate.scheduleFallback(10)
        Thread.sleep(40)
        gate.close()
        assertEquals(emptyList<String>(), events)
    }
}
