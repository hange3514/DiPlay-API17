package local.headunit.diplay

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotDiscoveryNetworkTest {
    @Test
    fun acceptsLeasedClientsOnTheMeasuredSubnet() {
        val gateway = ip("192.168.43.1")
        assertTrue(isOnLinkIpv4Peer(gateway, ip("192.168.43.2"), 24))
        assertTrue(isOnLinkIpv4Peer(gateway, ip("192.168.43.254"), 24))
        assertFalse(isOnLinkIpv4Peer(gateway, ip("192.168.44.2"), 24))
        assertFalse(isOnLinkIpv4Peer(gateway, ip("172.20.10.1"), 24))
        assertTrue(isOnLinkIpv4Peer(ip("10.10.11.1"), ip("10.10.10.250"), 23))
        assertFalse(isOnLinkIpv4Peer(ip("10.10.11.1"), ip("10.10.12.2"), 23))
    }

    @Test
    fun rejectsSelfAndOtherSourcesThatAreNotHotspotClients() {
        val gateway = ip("192.168.43.1")
        for (source in listOf("192.168.43.1", "192.168.43.0", "192.168.43.255", "0.0.0.0",
                "127.0.0.1", "169.254.1.2", "224.0.0.251")) {
            assertFalse(source, isOnLinkIpv4Peer(gateway, ip(source), 24))
        }
        assertFalse(isOnLinkIpv4Peer(gateway, ip("192.168.43.2"), 0))
        assertFalse(isOnLinkIpv4Peer(gateway, ip("192.168.43.2"), 32))
    }

    private fun ip(value: String) = InetAddress.getByName(value) as Inet4Address
}
