package local.headunit.diplay

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiP2pHotspotTest {
    @Test
    fun bindsTheOwnerAddressReportedByTheFirmware() {
        for (address in listOf("192.168.49.1", "172.22.8.1")) {
            val owner = InetAddress.getByName(address)
            assertEquals(owner, WifiP2pHotspot.usableOwnerAddress(owner))
        }
    }

    @Test
    fun rejectsUnusableOwnerAddresses() {
        assertNull(WifiP2pHotspot.usableOwnerAddress(null))
        for (address in listOf("0.0.0.0", "127.0.0.1", "169.254.1.1", "224.0.0.1", "255.255.255.255", "::1")) {
            assertNull(WifiP2pHotspot.usableOwnerAddress(InetAddress.getByName(address)))
        }
    }

    @Test
    fun fallsBackOnlyWhenP2pHasBeenReleasedAndNoOtherNetworkIsInUse() {
        assertTrue(WifiP2pHotspot.allowsSoftApFallback("p2p_create_failed_1", true))
        assertFalse(WifiP2pHotspot.allowsSoftApFallback("p2p_ip_timeout", false))
        for (reason in listOf("system_hotspot_active", "system_tethering_in_use", "p2p_group_in_use")) {
            assertFalse(WifiP2pHotspot.allowsSoftApFallback(reason, true))
        }
    }
}
