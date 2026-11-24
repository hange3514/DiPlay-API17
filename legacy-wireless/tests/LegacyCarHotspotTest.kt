package local.headunit.diplay

import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LegacyCarHotspotTest {
    @Test
    fun usesAnAlreadyActiveHotspotWithoutAccessToItsMutator() {
        val radio = FakeRadio(ap = 13)
        radio.validationFailure = LegacyCarHotspotException("ap_api_unavailable", "Unavailable")

        assertEquals("ready", prepare(radio) { "ready" })
        assertEquals(listOf("ap_existing", "ap_enabled_ready"), radio.events)
    }

    @Test
    fun offersTheSystemSwitchOnlyForRadioStartupFailures() {
        assertTrue(LegacyCarHotspot.shouldOpenSystemSettings("ap_start_rejected"))
        assertTrue(LegacyCarHotspot.shouldOpenSystemSettings("ap_api_unavailable"))
        assertTrue(LegacyCarHotspot.shouldOpenSystemSettings("ap_start_timeout"))
        assertFalse(LegacyCarHotspot.shouldOpenSystemSettings("ap_passphrase_missing"))
        assertFalse(LegacyCarHotspot.shouldOpenSystemSettings("ap_security_unsupported"))
        assertFalse(LegacyCarHotspot.shouldOpenSystemSettings("ap_cancelled"))
        assertFalse(LegacyCarHotspot.shouldOpenSystemSettings("ap_auto_disabled"))
    }

    @Test
    fun waitsForAlreadyStartedHotspotAndDoesNotTouchRadio() {
        val radio = FakeRadio(ap = 12)
        radio.afterSleep = { radio.ap = 13 }

        assertEquals("ready", prepare(radio) { "ready" })
        assertEquals(listOf("ap_existing", "ap_enabled_ready"), radio.events)
    }

    @Test
    fun disablesStationBeforeUsingAnAlreadyActiveHotspotWithoutTogglingIt() {
        val radio = FakeRadio(ap = 13, station = WifiManager.WIFI_STATE_ENABLED)
        radio.afterSleep = { radio.station = WifiManager.WIFI_STATE_DISABLED }

        assertEquals("ready", prepare(radio) {
            assertEquals(WifiManager.WIFI_STATE_DISABLED, radio.station)
            "ready"
        })
        assertEquals(13, radio.ap)
        assertEquals(listOf(
            "validate", "ap_station_disabling", "station:false", "ap_existing", "ap_enabled_ready",
        ), radio.events)
    }

    @Test
    fun reopensAnExistingHotspotThatDropsAfterStationShutdown() {
        val radio = FakeRadio(ap = 13, station = WifiManager.WIFI_STATE_ENABLED)
        radio.afterSleep = {
            if (radio.station == WifiManager.WIFI_STATE_DISABLING) {
                radio.station = WifiManager.WIFI_STATE_DISABLED
                radio.ap = 11
            } else if (radio.ap == 12) {
                radio.ap = 13
            }
        }

        assertEquals("ready", prepare(radio) { "ready" })
        assertEquals(listOf(WifiManager.WIFI_STATE_DISABLED), radio.stationAtHotspotStart)
        assertTrue(radio.now() < 1000L)
        assertEquals(listOf(
            "validate", "ap_station_disabling", "station:false", "hotspot:true",
            "ap_enable_requested", "ap_enabled_ready",
        ), radio.events)
    }

    @Test
    fun waitsForStationShutdownIfHotspotStartsOnItsOwnDuringRadioSwitch() {
        val radio = FakeRadio(station = WifiManager.WIFI_STATE_ENABLED)
        var waits = 0
        radio.afterSleep = {
            when (++waits) {
                1 -> radio.ap = 13
                2 -> radio.station = WifiManager.WIFI_STATE_DISABLED
            }
        }

        assertEquals("ready", prepare(radio) {
            assertEquals(2, waits)
            assertEquals(WifiManager.WIFI_STATE_DISABLED, radio.station)
            "ready"
        })
        assertEquals(listOf(
            "validate", "ap_station_disabling", "station:false", "ap_existing", "ap_enabled_ready",
        ), radio.events)
    }

    @Test(timeout = 2000L)
    fun restartsTheHotspotOnceIfDriverIgnoresStationDisableWhileItIsOn() {
        val radio = FakeRadio(ap = 13, station = WifiManager.WIFI_STATE_ENABLED)
        radio.ignoreStationStopWhileHotspotUp = true
        radio.stopLeavesApDisabling = true
        radio.afterSleep = {
            if (radio.station == WifiManager.WIFI_STATE_DISABLING) {
                radio.station = WifiManager.WIFI_STATE_DISABLED
            }
            when (radio.ap) {
                10 -> radio.ap = 11
                12 -> radio.ap = 13
            }
        }

        assertEquals("ready", prepare(radio, timeoutMs = 10000L) {
            assertEquals(WifiManager.WIFI_STATE_DISABLED, radio.station)
            "ready"
        })
        assertEquals(listOf("station:false", "hotspot:false", "station:false", "hotspot:true"),
            radio.events.filter { it.startsWith("station:") || it.startsWith("hotspot:") })
        assertEquals(listOf(13, 11), radio.hotspotAtStationDisable)
        assertEquals(listOf(WifiManager.WIFI_STATE_DISABLED), radio.stationAtHotspotStart)
        assertEquals(1, radio.events.count { it == "ap_radio_conflict_restarting" })
        assertTrue(radio.events.indexOf("ap_radio_conflict_restarting") < radio.events.indexOf("hotspot:false"))
        assertEquals("ap_enabled_ready", radio.events.last())
    }

    @Test
    fun abortsIfStationEnablesWhileWaitingForTheHotspotAddress() {
        val radio = FakeRadio(ap = 13)
        var reads = 0
        radio.afterSleep = { radio.station = WifiManager.WIFI_STATE_ENABLING }

        assertFailure("ap_station_not_disabled") {
            prepare(radio) { if (++reads == 1) null else "ready" }
        }
        assertEquals(1, reads)
        assertEquals(listOf("ap_existing"), radio.events)
    }

    @Test
    fun rejectsReadyAddressIfStationEnablesDuringItsRead() {
        val radio = FakeRadio(ap = 13)

        assertFailure("ap_station_not_disabled") {
            prepare(radio) {
                radio.station = WifiManager.WIFI_STATE_ENABLING
                "ready"
            }
        }
        assertEquals(listOf("ap_existing"), radio.events)
    }

    @Test
    fun disablesStationBeforeRequestingHotspotAndWaitsForItsAddress() {
        val radio = FakeRadio(station = WifiManager.WIFI_STATE_ENABLED)
        radio.afterSleep = {
            if (radio.station == WifiManager.WIFI_STATE_DISABLING) {
                radio.station = WifiManager.WIFI_STATE_DISABLED
            }
            if (radio.ap == 12) radio.ap = 13
        }
        var reads = 0

        assertEquals("ready", prepare(radio) {
            if (++reads == 2) "ready" else null
        })
        assertEquals(listOf(
            "validate", "ap_station_disabling", "station:false", "hotspot:true",
            "ap_enable_requested", "ap_enabled_ready",
        ), radio.events)
    }

    @Test
    fun validatesConfigurationBeforeTakingOverStation() {
        val radio = FakeRadio(station = WifiManager.WIFI_STATE_ENABLED)
        radio.validationFailure = LegacyCarHotspotException("ap_passphrase_missing", "Missing passphrase")

        assertFailure("ap_passphrase_missing") { prepare(radio) { "ready" } }
        assertEquals(listOf("validate"), radio.events)
    }

    @Test
    fun rollsBackStationIfTheSystemRejectsStartingHotspot() {
        val radio = FakeRadio(station = WifiManager.WIFI_STATE_ENABLED)
        radio.refuseStart = true
        radio.afterSleep = { radio.station = WifiManager.WIFI_STATE_DISABLED }

        assertFailure("ap_start_rejected") { prepare(radio) { "ready" } }
        assertEquals(listOf(
            "validate", "ap_station_disabling", "station:false", "hotspot:true",
            "station:true", "ap_rollback",
        ), radio.events)
    }

    @Test
    fun cancelsPendingEnableAndRestoresStationWhenTheAttemptStops() {
        val radio = FakeRadio(ap = 11, station = WifiManager.WIFI_STATE_ENABLED)
        radio.afterSleep = { radio.station = WifiManager.WIFI_STATE_DISABLED }

        assertFailure("ap_cancelled") {
            prepare(radio, cancelled = { radio.ap == 12 }) { "ready" }
        }
        assertEquals(listOf(
            "validate", "ap_station_disabling", "station:false", "hotspot:true",
            "ap_enable_requested", "hotspot:false", "station:true", "ap_rollback",
        ), radio.events)
    }

    @Test
    fun preservesAnExternallyStartedHotspotOnAddressTimeout() {
        val radio = FakeRadio(ap = 13)

        assertFailure("ap_start_timeout") { prepare(radio) { null } }
        assertEquals(listOf("ap_existing"), radio.events)
    }

    @Test
    fun stopsOnlyItsOwnHotspotAfterAddressTimeout() {
        val radio = FakeRadio()
        radio.afterSleep = { radio.ap = 13 }

        assertFailure("ap_start_timeout") { prepare(radio) { null } }
        assertEquals(listOf(
            "validate", "hotspot:true", "ap_enable_requested", "hotspot:false", "ap_rollback",
        ), radio.events)
    }

    @Test(timeout = 2000L)
    fun leavesStationOffIfItsHotspotIsStillDisablingAfterRollback() {
        val radio = FakeRadio(station = WifiManager.WIFI_STATE_ENABLED)
        radio.stopLeavesApDisabling = true
        radio.afterSleep = {
            if (radio.station == WifiManager.WIFI_STATE_DISABLING) {
                radio.station = WifiManager.WIFI_STATE_DISABLED
            }
            if (radio.ap == 12) radio.ap = 13
        }

        assertFailure("ap_start_timeout") { prepare(radio) { null } }
        assertEquals(WifiManager.WIFI_STATE_DISABLED, radio.station)
        assertEquals(10, radio.ap)
        assertEquals(listOf(
            "validate", "ap_station_disabling", "station:false", "hotspot:true",
            "ap_enable_requested", "hotspot:false", "ap_rollback_incomplete",
        ), radio.events)
    }

    @Test(timeout = 2000L)
    fun restoresStationOnlyAfterItsHotspotFinishesDisabling() {
        val radio = FakeRadio(station = WifiManager.WIFI_STATE_ENABLED)
        radio.stopLeavesApDisabling = true
        radio.afterSleep = {
            if (radio.station == WifiManager.WIFI_STATE_DISABLING) {
                radio.station = WifiManager.WIFI_STATE_DISABLED
            }
            when (radio.ap) {
                12 -> radio.ap = 13
                10 -> radio.ap = 11
            }
        }

        assertFailure("ap_start_timeout") { prepare(radio) { null } }
        assertEquals(listOf(11), radio.hotspotAtStationRestore)
        assertEquals(listOf(
            "validate", "ap_station_disabling", "station:false", "hotspot:true",
            "ap_enable_requested", "hotspot:false", "station:true", "ap_rollback",
        ), radio.events)
    }

    @Test
    fun doesNotRestartAnExternallyStoppingHotspot() {
        val radio = FakeRadio(ap = 10, station = WifiManager.WIFI_STATE_ENABLED)

        assertFailure("ap_state_unavailable") { prepare(radio) { "ready" } }
        assertEquals(emptyList<String>(), radio.events)
    }

    @Test
    fun runtimeRecoveryReopensAsSoonAsTheSystemFinishesClosingTheAp() {
        val radio = FakeRadio(ap = 10)
        radio.afterSleep = { radio.ap = if (radio.ap == 10) 11 else 13 }

        assertEquals("ready", prepare(radio, recovering = true) { "ready" })
        assertEquals(150L, radio.now())
        assertEquals(listOf("validate", "hotspot:true", "ap_enable_requested", "ap_enabled_ready"), radio.events)
    }

    @Test
    fun runtimeRecoveryGivesStartupItsOwnBudgetAfterSlowShutdown() {
        val radio = FakeRadio(ap = 10)
        radio.afterSleep = {
            if (radio.ap == 10 && radio.now() >= 5900L) radio.ap = 11
            if (radio.ap == 12 && radio.now() >= 7700L) radio.ap = 13
        }

        assertEquals("ready", prepare(radio, timeoutMs = 6500L, recovering = true) { "ready" })
        assertEquals(listOf("validate", "hotspot:true", "ap_enable_requested", "ap_enabled_ready"), radio.events)
        assertTrue(radio.now() > 6500L)
    }

    @Test
    fun runtimeRecoveryStopsWaitingIfSystemNeverFinishesClosing() {
        val radio = FakeRadio(ap = 10)

        assertFailure("ap_stop_timeout") { prepare(radio, recovering = true) { "ready" } }
        assertEquals(LegacyCarHotspot.RECOVERY_AP_STOP_TIMEOUT_MS, radio.now())
        assertEquals(emptyList<String>(), radio.events)
    }

    @Test
    fun runtimeRecoveryOnlyRollsBackItsOwnNewStartAfterStartupTimeout() {
        val radio = FakeRadio(ap = 10)
        radio.afterSleep = { if (radio.ap == 10 && radio.now() >= 5900L) radio.ap = 11 }

        assertFailure("ap_start_timeout") { prepare(radio, timeoutMs = 6500L, recovering = true) { null } }
        assertEquals(5925L + 6500L, radio.now())
        assertEquals(listOf("validate", "hotspot:true", "ap_enable_requested", "hotspot:false", "ap_rollback"), radio.events)
    }

    @Test
    fun runtimeRecoveryWaitsForAnExternallyRestartingHotspotWithoutReissuingTheRequest() {
        val radio = FakeRadio(ap = 10)
        radio.afterSleep = { radio.ap = if (radio.ap == 10) 12 else 13 }

        assertEquals("ready", prepare(radio, recovering = true) { "ready" })
        assertEquals(listOf("ap_existing", "ap_enabled_ready"), radio.events)
    }

    @Test
    fun runtimeRecoveryDoesNotOverrideTheUserSwitchingToWifiClientMode() {
        val radio = FakeRadio(ap = 10)
        radio.afterSleep = { radio.station = WifiManager.WIFI_STATE_ENABLING }

        assertFailure("ap_station_not_disabled") { prepare(radio, recovering = true) { "ready" } }
        assertEquals(emptyList<String>(), radio.events)
    }

    private fun prepare(
        radio: FakeRadio,
        cancelled: () -> Boolean = { false },
        timeoutMs: Long = 1000L,
        recovering: Boolean = false,
        readReady: () -> String?,
    ) = LegacyCarHotspot.prepare(radio, timeoutMs, cancelled, { radio.events.add(it) }, recovering, readReady)

    private fun assertFailure(reason: String, operation: () -> Unit) {
        try {
            operation()
            fail("Expected failure: " + reason)
        } catch (failure: LegacyCarHotspotException) {
            assertEquals(reason, failure.reason)
        }
    }

    private class FakeRadio(var ap: Int = 11, var station: Int = WifiManager.WIFI_STATE_DISABLED) :
        LegacyCarHotspot.Platform {
        val events = mutableListOf<String>()
        val stationAtHotspotStart = mutableListOf<Int>()
        val hotspotAtStationDisable = mutableListOf<Int>()
        val hotspotAtStationRestore = mutableListOf<Int>()
        private var elapsed = 0L
        var afterSleep: () -> Unit = {}
        var validationFailure: LegacyCarHotspotException? = null
        var refuseStart = false
        var stopLeavesApDisabling = false
        var ignoreStationStopWhileHotspotUp = false

        override fun now(): Long = elapsed
        override fun sleep(ms: Long) {
            elapsed += ms
            afterSleep()
        }
        override fun apState(): Int = ap
        override fun stationState(): Int = station
        override fun setApEnabled(enabled: Boolean): Boolean {
            events.add("hotspot:" + enabled)
            if (enabled) stationAtHotspotStart.add(station)
            if (enabled && refuseStart) return false
            ap = when {
                enabled -> 12
                stopLeavesApDisabling -> 10
                else -> 11
            }
            return true
        }
        override fun setStationEnabled(enabled: Boolean): Boolean {
            events.add("station:" + enabled)
            if (enabled) hotspotAtStationRestore.add(ap)
            if (!enabled) {
                hotspotAtStationDisable.add(ap)
                if (ignoreStationStopWhileHotspotUp && ap == 13) return true
            }
            station = if (enabled) WifiManager.WIFI_STATE_ENABLING else WifiManager.WIFI_STATE_DISABLING
            return true
        }
        override fun validateStartup() {
            events.add("validate")
            validationFailure?.let { throw it }
        }
    }
}
