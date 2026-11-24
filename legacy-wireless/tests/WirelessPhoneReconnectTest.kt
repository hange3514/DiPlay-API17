package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WirelessPhoneReconnectTest {
    private val adapter = "02:aa:22:33:44:55"
    private val phone = "02:aa:bb:cc:dd:ee"
    private val saved = "$adapter|$phone"

    @Test fun savedPhoneMatchesAdapterCaseInsensitively() {
        val remembered = WirelessPhoneReconnect.choose(saved, adapter.uppercase(Locale.US), reselect = false)
        assertEquals(phone, remembered.remembered)
        assertEquals("remembered", remembered.reason)
    }

    @Test fun oneExplicitScanDoesNotChangeTheSavedPhone() {
        val rescan = WirelessPhoneReconnect.choose(saved, adapter, reselect = true)
        assertNull(rescan.remembered)
        assertEquals("user_requested", rescan.reason)
        assertEquals(phone, WirelessPhoneReconnect.choose(saved, adapter, reselect = false).remembered)
    }

    @Test fun missingInvalidAndOtherAdapterRecordsHaveSeparateScanReasons() {
        assertEquals("no_saved_phone", WirelessPhoneReconnect.choose(null, adapter, false).reason)
        assertEquals("invalid_saved_phone", WirelessPhoneReconnect.choose("$adapter|00:00:00:00:00:00", adapter, false).reason)
        assertEquals("different_adapter", WirelessPhoneReconnect.choose(saved, "02:99:22:33:44:55", false).reason)
    }

    @Test fun onlyTheConfirmedRfcommPeerCanReplaceTheSavedRecord() {
        assertEquals(saved, WirelessPhoneReconnect.confirmedRecord(adapter, phone, "02:AA:BB:CC:DD:EE"))
        assertNull(WirelessPhoneReconnect.confirmedRecord(adapter, phone, null))
        assertNull(WirelessPhoneReconnect.confirmedRecord(adapter, phone, "02:66:77:88:99:00"))
        assertNull(WirelessPhoneReconnect.confirmedRecord(adapter, null, phone))
    }

    @Test fun onlyTheObservedTerminalAclCollisionRetriesAndStopsAtTwoRetries() {
        val collision = "outbound_acl_failed"
        assertEquals(0L, WirelessPhoneReconnect.retryDelayMs(collision, 0x0b, true, 2, 0L))
        assertEquals(0L, WirelessPhoneReconnect.retryDelayMs(collision, 0x0b, false, 0, 0L))
        assertEquals(0L, WirelessPhoneReconnect.retryDelayMs(collision, 0x04, true, 0, 0L))
        assertEquals(0L, WirelessPhoneReconnect.retryDelayMs("outbound_sdp_timeout", 0x08, true, 0, 0L))
        assertEquals(0L, WirelessPhoneReconnect.retryDelayMs("outbound_rfcomm_failed", 0x0b, true, 0, 0L))
    }

    @Test fun reconnectCountsTimeSpentPagingButKeepsQuietPeriodAfterCollision() {
        val collision = "outbound_acl_failed"
        assertEquals(3000L, WirelessPhoneReconnect.retryDelayMs(collision, 0x0b, true, 0, 0L))
        assertEquals(7000L, WirelessPhoneReconnect.retryDelayMs(collision, 0x0b, true, 1, 0L))
        assertEquals(2360L, WirelessPhoneReconnect.retryDelayMs(collision, 0x0b, true, 0, 640L))
        assertEquals(1830L, WirelessPhoneReconnect.retryDelayMs(collision, 0x0b, true, 1, 5170L))
        assertEquals(1500L, WirelessPhoneReconnect.retryDelayMs(collision, 0x0b, true, 0, 16000L))
    }

    @Test fun controllerBusyAfterSelectionOrReconnectRetriesOnceWithoutRescanning() {
        val create = "outbound_acl_failed"
        assertEquals(650L, WirelessPhoneReconnect.retryDelayMs(create, 0x0c, false, 0, 0L))
        assertEquals(650L, WirelessPhoneReconnect.retryDelayMs(create, 0x0c, true, 0, 0L))
        assertEquals(0L, WirelessPhoneReconnect.retryDelayMs(create, 0x0c, true, 1, 0L))
        assertEquals(0L, WirelessPhoneReconnect.retryDelayMs("outbound_auth_failed", 0x0c, true, 0, 0L))
        assertFalse(WirelessPhoneReconnect.isTransientAclFailure(create, 0x0c))
    }

    @Test fun terminalAclBasebandFailuresCanRetryAfterRelease() {
        for (status in listOf(0x04, 0x08, 0x0b)) {
            assertTrue(WirelessPhoneReconnect.isTransientAclFailure("outbound_acl_failed", status))
        }
    }

    @Test fun authenticationAndProtocolFailuresCannotRetryAfterRelease() {
        assertFalse(WirelessPhoneReconnect.isTransientAclFailure("outbound_acl_failed", 0x05))
        assertFalse(WirelessPhoneReconnect.isTransientAclFailure("outbound_acl_failed", 0x82))
        assertFalse(WirelessPhoneReconnect.isTransientAclFailure("outbound_sdp_timeout", 0x08))
        assertFalse(WirelessPhoneReconnect.isTransientAclFailure("outbound_sdp_failed", 0x0b))
        assertFalse(WirelessPhoneReconnect.isTransientAclFailure("outbound_rfcomm_failed", 0x04))
        assertFalse(WirelessPhoneReconnect.isTransientAclFailure("outbound_unavailable", 0x0b))
    }
}
