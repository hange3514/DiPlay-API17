package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class VehicleMobileDataTest {
    @Test
    fun waitsForNetworkDisconnectEvenAfterTheSettingTurnsOff() {
        val radio = FakeRadio(networkUntil = 1200L)
        var pending = 0

        VehicleMobileData.awaitDisconnected(radio, { false }) { pending++ }

        assertEquals(1, pending)
        assertEquals(1, radio.disableRequests)
        assertEquals(1200L, radio.now())
    }

    @Test
    fun skipsTheSwitchAndWaitWhenMobileDataIsAlreadyOff() {
        val radio = FakeRadio(enabled = false, networkUntil = 0L)

        VehicleMobileData.awaitDisconnected(radio, { false }) { fail("No pending work") }

        assertEquals(0, radio.disableRequests)
        assertEquals(0L, radio.now())
    }

    @Test
    fun givesAnExistingDisconnectTimeWithoutSubmittingAnotherRequest() {
        val radio = FakeRadio(enabled = false, networkUntil = 300L)

        VehicleMobileData.awaitDisconnected(radio, { false }) {}

        assertEquals(0, radio.disableRequests)
        assertEquals(300L, radio.now())
    }

    @Test
    fun rejectsStartingTheHotspotWhileTheMobileNetworkRemainsUp() {
        val radio = FakeRadio(networkUntil = Long.MAX_VALUE)

        try {
            VehicleMobileData.awaitDisconnected(radio, { false }) {}
            fail("Expected timeout")
        } catch (failure: LegacyCarHotspotException) {
            assertEquals("mobile_data_disconnect_timeout", failure.reason)
        }
        assertEquals(1, radio.disableRequests)
        assertEquals(8000L, radio.now())
    }

    @Test
    fun honoursCancellationBeforeChangingTheSystemSetting() {
        val radio = FakeRadio(networkUntil = 400L)
        var cancelled = false

        try {
            VehicleMobileData.awaitDisconnected(radio, { cancelled }) { cancelled = true }
            fail("Expected cancellation")
        } catch (_: InterruptedException) {
            assertEquals(0, radio.disableRequests)
        }
    }

    private class FakeRadio(private var enabled: Boolean = true, private val networkUntil: Long) : VehicleMobileData.Platform {
        private var time = 0L
        var disableRequests = 0
            private set

        override fun now() = time
        override fun sleep(ms: Long) { time += ms }
        override fun settingEnabled() = enabled
        override fun hasActiveMobileNetwork() = time < networkUntil
        override fun requestDisable() {
            disableRequests++
            enabled = false
        }
    }
}
