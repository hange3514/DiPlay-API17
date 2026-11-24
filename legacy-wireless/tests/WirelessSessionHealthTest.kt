package local.headunit.diplay

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessSessionHealthTest {
    private val health = WirelessSessionHealth().apply { begin(at(1_000)) }

    @Test fun staticScreenWithHealthyTimingAndSilentPeerWithoutObservedCadenceAreNotFailures() {
        assertNull(health.check(at(120_000), 0, 0, at(1_000), 0))
        assertNull(health.check(at(120_000), 2, at(3_000), at(1_000), 2))
        assertNull(health.check(at(120_000), 25, at(119_000), at(1_000), 25))
        assertNull(health.check(at(120_000), 25, at(90_000), at(100_000), 60))
    }

    @Test fun shortGapKeepsTheSameSessionAndAReplyCancelsReconnectImmediately() {
        assertNull(health.check(at(12_249), 8, at(10_000), at(10_000), 10))
        assertEquals(WirelessSessionHealth.Decision(WirelessSessionHealth.Action.PROBE, at(10_000)),
            health.check(at(12_250), 8, at(10_000), at(10_000), 10))
        assertNull(health.check(at(13_000), 8, at(10_000), at(10_000), 13))
        assertEquals(WirelessSessionHealth.Decision(WirelessSessionHealth.Action.RESUME, at(10_000)),
            health.check(at(13_100), 9, at(13_090), at(10_000), 13))
        assertNull(health.check(at(14_000), 9, at(13_090), at(10_000), 14))
    }

    @Test fun realInboundDataAlsoCancelsThePendingProbeWindow() {
        health.check(at(12_250), 8, at(10_000), at(10_000), 10)
        assertEquals(WirelessSessionHealth.Action.RESUME,
            health.check(at(14_800), 8, at(10_000), at(14_700), 18)?.action)
    }

    @Test fun fullReconnectRequiresACompleteProbeWindowAndTwoActualAttempts() {
        assertEquals(WirelessSessionHealth.Action.PROBE,
            health.check(at(12_250), 8, at(10_000), at(9_000), 10)?.action)
        assertNull(health.check(at(14_749), 8, at(10_000), at(9_000), 20))
        assertNull(health.check(at(14_750), 8, at(10_000), at(9_000), 11))
        assertEquals(WirelessSessionHealth.Action.RECONNECT,
            health.check(at(14_750), 8, at(10_000), at(9_000), 12)?.action)
    }

    @Test fun replacementSessionDoesNotInheritAWirelessGap() {
        health.check(at(12_250), 8, at(10_000), at(9_000), 10)
        health.begin(at(13_000))
        assertNull(health.check(at(16_000), 0, 0, at(13_000), 0))
    }

    private fun at(milliseconds: Long) = TimeUnit.MILLISECONDS.toNanos(milliseconds)
}
