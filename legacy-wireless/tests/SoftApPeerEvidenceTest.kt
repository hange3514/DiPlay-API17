package local.headunit.diplay

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftApPeerEvidenceTest {
    private val gateway = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 43, 1)) as Inet4Address
    private val header = "IP address       HW type     Flags       HW address            Mask     Device\n"
    private val existing = arp("192.168.43.2")
    private val joined = arp("192.168.43.3", mac = "02:AA:00:00:00:02")

    @Test
    fun countsOnlyCompleteDynamicNeighborsFromTheApSubnet() {
        val table = header + existing + existing + arp("192.168.43.4", iface = "eth0") +
            arp("192.168.44.5") + arp("192.168.43.6", flags = "0x0") +
            arp("192.168.43.7", flags = "0x6") + arp("192.168.43.255") +
            arp("192.168.43.8", mac = "00:00:00:00:00:00") + arp("192.168.43.1")
        val diagnostic = evidence { table }.baseline()
        assertEquals("apArpStatus=available apArpComplete=1 apArpBaseline=1 apArpNew=0 apArpNewPeak=0", diagnostic)
        assertFalse(diagnostic.contains("192.168"))
        assertFalse(diagnostic.contains("02:aa"))
    }

    @Test
    fun retainsEvidenceOfANewPeerEvenAfterItDisappears() {
        var table = header + existing
        val probe = evidence { table }
        probe.baseline()
        table += joined
        assertTrue(probe.sample().contains("apArpNew=1 apArpNewPeak=1"))
        table = header
        assertTrue(probe.sample().contains("apArpNew=0 apArpNewPeak=1"))
    }

    @Test
    fun keepsUnknownBaselineDistinctFromAnEmptyTable() {
        var readable = false
        val probe = evidence { if (!readable) throw IOException("unavailable") else header + joined }
        assertTrue(probe.baseline().startsWith("apArpStatus=unavailable apArpComplete=-1"))
        readable = true
        assertEquals("apArpStatus=available apArpComplete=1 apArpBaseline=-1 apArpNew=-1 apArpNewPeak=-1", probe.sample())
    }

    @Test
    fun rejectsUnusableBindingAndReportsARowCapWithoutUsingPartialCounts() {
        val invalid = SoftApPeerEvidence("wlan0", gateway, "255.0.255.0", { error("must not inspect unusable binding") })
        assertTrue(invalid.baseline().startsWith("apArpStatus=unsupported_binding"))
        val oversized = evidence { header + existing.repeat(257) }
        assertTrue(oversized.baseline().startsWith("apArpStatus=scan_limit apArpComplete=-1"))
    }

    @Test
    fun stopsProcessingWhenItsReadBudgetIsExhausted() {
        var elapsed = 0L
        val probe = SoftApPeerEvidence("wlan0", gateway, "255.255.255.0", { (header + existing).reader().buffered() }, {
            val now = elapsed
            elapsed += 41_000_000L
            now
        })
        assertTrue(probe.baseline().startsWith("apArpStatus=scan_limit apArpComplete=-1"))
    }

    private fun evidence(table: () -> String) =
        SoftApPeerEvidence("wlan0", gateway, "255.255.255.0", { table().reader().buffered() }, { 0L })

    private fun arp(ip: String, flags: String = "0x2", mac: String = "02:aa:00:00:00:01", iface: String = "wlan0") =
        "$ip  0x1  $flags  $mac  *  $iface\n"
}
