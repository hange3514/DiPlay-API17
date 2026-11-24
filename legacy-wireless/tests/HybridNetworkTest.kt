package local.headunit.diplay

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class HybridNetworkTest {
    @Test fun activeWifiTetherWinsOverAnotherInterfacesOldGateway() {
        val gateway = candidate("wlan0", "192.168.43.1")
        val tether = candidate("swlan1", "192.168.50.1")
        val usb = candidate("rndis0", "192.168.42.1")
        val binding = HybridNetwork.selectApBinding(listOf(gateway, usb, tether), setOf("swlan1", "rndis0"),
            emptyList(), "192.168.43.1")
        assertEquals(tether, binding?.candidate)
        assertEquals("active_wifi_tether", binding?.source)
    }

    @Test fun knownGatewayFallbackRequiresActualWifiAddressAndNeverBindsRegularWifi() {
        val client = candidate("wlan0", "172.20.10.4")
        val usb = candidate("rndis0", "192.168.43.1")
        val unrelated = candidate("eth0", "192.168.43.1")
        assertNull(HybridNetwork.selectApBinding(listOf(client, usb, unrelated), setOf("rndis0", "eth0"),
            listOf(Regex(".*")), "192.168.43.1"))
        val ap = candidate("wlan1", "192.168.43.1")
        assertEquals(ap, HybridNetwork.selectApBinding(listOf(client, usb, ap), emptySet(),
            emptyList(), "192.168.43.1")?.candidate)
        assertNull(HybridNetwork.selectApBinding(listOf(client, usb, ap), emptySet(), emptyList(), null))
    }

    @Test fun mixedAddressesOnOneTetherRequireVerifiedGateway() {
        val formerAddress = candidate("wlan0", "192.168.1.11")
        val gateway = candidate("wlan0", "192.168.43.1")
        val both = listOf(formerAddress, gateway)
        assertNull(HybridNetwork.selectApBinding(both, setOf("wlan0"), emptyList(), null))
        assertEquals(gateway, HybridNetwork.selectApBinding(both, setOf("wlan0"), emptyList(),
            "192.168.43.1")?.candidate)
        assertNull(HybridNetwork.selectApBinding(listOf(candidate("wlan0", "169.254.1.2")), setOf("wlan0"),
            emptyList(), "192.168.43.1"))
    }

    @Test fun vendorWifiRegexWorksWithoutTreatingP2pAsTheAccessPoint() {
        val candidate = candidate("mlan0", "192.168.50.1", 25)
        val p2p = candidate("p2p0", "192.168.49.1")
        assertEquals(candidate, HybridNetwork.selectApBinding(listOf(candidate, p2p), setOf("mlan0", "p2p0"),
            listOf(Regex("(mlan|p2p)\\d+")), null)?.candidate)
        assertEquals("255.255.255.128", HybridNetwork.prefixMask(25))
        assertEquals("255.255.255.0", HybridNetwork.prefixMask(24))
    }

    @Test fun useActualSystemPasswordAndNeverSendMaskedOrDerivedKey() {
        val wpa2 = Iap2WirelessSecurity.WPA_WPA2
        val system = HybridNetwork.selectCredential(wpa2, "\"ap-secret\"", "stale-password")
        assertEquals("\"ap-secret\"", system.passphrase)
        assertEquals("android_softap_configuration", system.source)
        assertFalse(system.toString().contains("ap-secret"))
        val masked = HybridNetwork.selectCredential(wpa2, "*", "manual-secret")
        assertEquals("manual-secret", masked.passphrase)
        assertEquals("app_softap_credential", masked.source)
        val raw = HybridNetwork.selectCredential(wpa2, "f".repeat(64), null)
        assertFalse(raw.available)
        assertEquals("android_softap_configuration_raw_psk", raw.source)
        val open = HybridNetwork.selectCredential(Iap2WirelessSecurity.NONE, null, "manual-secret")
        assertTrue(open.available)
        assertEquals("", open.passphrase)
    }

    @Test fun apSecurityUsesConfiguredWpaBitsAndRejectsWepOrEnterprise() {
        assertEquals(Iap2WirelessSecurity.WPA_WPA2, HybridNetwork.apSecurity(false, false, true, false, false, true))
        assertEquals(Iap2WirelessSecurity.NONE, HybridNetwork.apSecurity(true, false, false, false, false, false))
        assertNull(HybridNetwork.apSecurity(true, false, false, true, false, false))
        assertNull(HybridNetwork.apSecurity(false, false, false, false, false, true))
    }

    @Test fun validatesLiteralSsidAndDoesNotInventWifiChannelOrBssid() {
        assertEquals("\"car-network\"", HybridNetwork.validSsid("\"car-network\""))
        assertEquals("车".repeat(10), HybridNetwork.validSsid("车".repeat(10)))
        assertNull(HybridNetwork.validSsid("车".repeat(11)))
        assertNull(HybridNetwork.validSsid("ap" + '\u0000'))
        assertEquals(0, HybridNetwork.channelForFrequency(-1))
        assertEquals(6, HybridNetwork.channelForFrequency(2437))
        assertEquals(36, HybridNetwork.channelForFrequency(5180))
        assertFalse(HybridNetwork.validAccessPointBssid(byteArrayOf(2, 0, 0, 0, 0, 0)))
        assertFalse(HybridNetwork.validAccessPointBssid(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertTrue(HybridNetwork.validAccessPointBssid(byteArrayOf(4, 5, 6, 7, 8, 9)))
    }

    @Test fun wifiDirectBindingAllowsLateIpv6ButRejectsAChangedScopeForAnActiveListener() {
        val early = groupSnapshot(null)
        val literal = InetAddress.getByName("fe80::42").address
        val firstScoped = groupSnapshot(Inet6Address.getByAddress(null, literal, 11))
        val secondScoped = groupSnapshot(Inet6Address.getByAddress(null, literal, 12))

        assertEquals(firstScoped, HybridNetwork.requireSameBinding(early, firstScoped, verifyIpv6 = false))
        assertThrows(java.io.IOException::class.java) {
            HybridNetwork.requireSameBinding(firstScoped, secondScoped, verifyIpv6 = true)
        }
    }

    @Test fun wifiDirectBindingRejectsChangedCredentialsAndInterfaceBeforeHandoff() {
        val group = groupSnapshot(null)

        assertThrows(java.io.IOException::class.java) {
            HybridNetwork.requireSameBinding(group, groupSnapshot(null, passphrase = "other-secret"), verifyIpv6 = false)
        }
        assertThrows(java.io.IOException::class.java) {
            HybridNetwork.requireSameBinding(group, groupSnapshot(null, intf = "wlan0"), verifyIpv6 = false)
        }
    }

    private fun groupSnapshot(ipv6: Inet6Address?, intf: String = "p2p0", passphrase: String = "test-secret") =
        HybridNetworkSnapshot("DIRECT-test", InetAddress.getByName("192.168.49.1"), ipv6,
            "02:11:22:33:44:55", null, intf, "255.255.255.0", -1, 0,
            HybridWifiCredential(passphrase, Iap2WirelessSecurity.WPA_WPA2, "wifi_p2p_group", true),
            "unavailable", "wifi_p2p_group")

    private fun candidate(name: String, address: String, prefix: Int = 24) =
        HybridNetwork.ApCandidate(name, InetAddress.getByName(address) as Inet4Address, prefix)
}
