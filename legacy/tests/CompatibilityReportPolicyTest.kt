package local.headunit.diplay

import org.junit.Assert.*
import org.junit.Test

class CompatibilityReportPolicyTest {
    @Test fun userlandNetworkEventsAreRecognizedAndLifecycleAnchorsReserved() {
        for (type in listOf("NETWORK_BACKEND_SELECTED", "NETWORK_BACKEND", "NETWORK_LISTENER", "USERLAND_NETWORK")) {
            assertTrue(type, CompatibilityReportPolicy.isNetworkStackEvent(type))
        }
        assertFalse(CompatibilityReportPolicy.isNetworkStackEvent("MEDIA"))
        assertTrue(CompatibilityReportPolicy.isNetworkStackMilestone("USERLAND_NETWORK", "userland stack ready backend=lwIP"))
        assertTrue(CompatibilityReportPolicy.isNetworkStackMilestone("USERLAND_NETWORK", "userland stack create result=ready durationMs=12 nativeErrorCode=0"))
        assertTrue(CompatibilityReportPolicy.isNetworkStackMilestone("USERLAND_NETWORK", "userland stack create result=failed durationMs=12 nativeErrorCode=11"))
        assertTrue(CompatibilityReportPolicy.isNetworkStackMilestone("USERLAND_NETWORK", "userland init failed stage=tcpip_init nativeErrorCode=2011"))
        assertTrue(CompatibilityReportPolicy.isNetworkStackMilestone("USERLAND_NETWORK", "userland socket op failed operation=setsockopt_SO_REUSEADDR errno=92"))
        assertTrue(CompatibilityReportPolicy.isNetworkStackMilestone("USERLAND_NETWORK", "userland stack stopped rxUsbFrames=4"))
        assertTrue(CompatibilityReportPolicy.isNetworkStackMilestone("USERLAND_NETWORK", "ncm ndp prewarm queued result=-110 expected=74 submitted=1 polls=600 durationMs=15000 cancelled=1 actualBytes=0 urbStatus=-110 inbound=false"))
        assertTrue(CompatibilityReportPolicy.isNetworkStackMilestone("USERLAND_NETWORK", "ncm ndp prewarm result=74 expected=74 durationMs=4"))
        assertTrue(CompatibilityReportPolicy.isNetworkStackMilestone("USERLAND_NETWORK", "ncm network readiness observed source=first_ncm_frame ageMs=1200"))
        assertTrue(CompatibilityReportPolicy.isNetworkStackMilestone("NETWORK_LISTENER", "service=airplay_control"))
        assertFalse(CompatibilityReportPolicy.isNetworkStackMilestone("USERLAND_NETWORK", "userland stats rxUsbFrames=4"))
    }

    @Test fun boundedNetworkDetailsKeepCountersAndExcludeUnexpectedText() {
        val detail = "userland stats ageMs=5000 rxUsbFrames=4 rxUsbBytes=2816 rxTimeouts=0 rxRejected=0 rxIpv4EthFrames=1 rxIpv4Tcp=1 rxIpv4Syn=1 rxIpv4SynTrackedPort=1 rxIpv4Udp=0 rxIpv4Fragmented=0 rxIpv4Malformed=0 rxArp=0 rxOtherEtherType=0 rxMdns=3 rxIpv4SynPortPair=1234->7000 rxIpv6SynPortPair=1235->7000 lastTcpSynAgeMs=12 lastIpv4TcpSynAgeMs=14 rxIpv6EthFrames=3 rxIpv6EthBytes=240 rxTcp=1 txIpv6EthFrames=2 txIpv6EthBytes=128 txQueueDrop=0 txOutputCopyError=0 txUsbOk=2 lastRxPortPair=7000->7001 txMdns=3 mdns6InQueryMask=3 mdns6InResponseMask=4 mdns4InQueryMask=1 mdns4InResponseMask=2 mdns6OutQueryMask=2 mdns6OutResponseMask=8 lastRxMdnsServiceAgeMs=11 lastRxMdnsServiceMask=3 lastRxMdnsServiceQueryMask=1 lastRxMdnsServiceResponseMask=2"
        assertEquals(detail, CompatibilityReportPolicy.safeNetworkDetail(detail))
        assertEquals("", CompatibilityReportPolicy.safeNetworkDetail("userland stats payload=secret\nsecond-line"))
        assertEquals("", CompatibilityReportPolicy.safeNetworkDetail("userland stats ageMs=5000 ssid=secret"))
        assertEquals("", CompatibilityReportPolicy.safeNetworkDetail("userland tx gate opened after iAP2 CarPlay session-start token=secret"))
        assertEquals("userland init failed stage=tcpip_init nativeErrorCode=2011",
            CompatibilityReportPolicy.safeNetworkDetail("userland init failed stage=tcpip_init nativeErrorCode=2011"))
        assertEquals("userland socket op failed operation=setsockopt_SO_REUSEADDR errno=92",
            CompatibilityReportPolicy.safeNetworkDetail("userland socket op failed operation=setsockopt_SO_REUSEADDR errno=92"))
        assertEquals("", CompatibilityReportPolicy.safeNetworkDetail("userland socket op failed operation=setsockopt_SO_REUSEADDR errno=92 ssid=secret"))
        assertEquals("", CompatibilityReportPolicy.safeNetworkDetail("userland init failed stage=tcpip_init nativeErrorCode=2011 token=secret"))
        assertEquals(1200, CompatibilityReportPolicy.safeNetworkDetail("userland stats ageMs=5000 rxUsbFrames=" + "1".repeat(1200)).length)
        assertEquals("userland stack ready backend=lwIP ipv4=off ipv6=link-local local=fe80::2 tcp=on udp=on mtu=1500 ncm=USB noVpn=true",
            CompatibilityReportPolicy.safeNetworkDetail("userland stack ready backend=lwIP ipv4=off ipv6=link-local local=fe80::2 tcp=on udp=on mtu=1500 ncm=USB noVpn=true"))
        assertEquals("userland stack create result=ready durationMs=12 nativeErrorCode=0",
            CompatibilityReportPolicy.safeNetworkDetail("userland stack create result=ready durationMs=12 nativeErrorCode=0"))
        assertEquals("ncm ndp prewarm queued result=-110 expected=74 submitted=1 polls=600 durationMs=15000 cancelled=1 actualBytes=0 urbStatus=-110 inbound=false",
            CompatibilityReportPolicy.safeNetworkDetail("ncm ndp prewarm queued result=-110 expected=74 submitted=1 polls=600 durationMs=15000 cancelled=1 actualBytes=0 urbStatus=-110 inbound=false"))
        assertEquals("ncm ndp prewarm queued unsupported before submit; short synchronous fallback",
            CompatibilityReportPolicy.safeNetworkDetail("ncm ndp prewarm queued unsupported before submit; short synchronous fallback"))
        assertEquals("ncm ndp prewarm result=74 expected=74 durationMs=4",
            CompatibilityReportPolicy.safeNetworkDetail("ncm ndp prewarm result=74 expected=74 durationMs=4"))
        assertEquals("ncm bulk-out accepted after 5 not-ready write calls bytes=74 durationMs=2000",
            CompatibilityReportPolicy.safeNetworkDetail("ncm bulk-out accepted after 5 not-ready write calls bytes=74 durationMs=2000"))
        assertEquals("ncm network readiness observed source=carrier ageMs=1200",
            CompatibilityReportPolicy.safeNetworkDetail("ncm network readiness observed source=carrier ageMs=1200"))
        assertEquals("ncm network readiness observed source=first_ncm_frame ageMs=1200",
            CompatibilityReportPolicy.safeNetworkDetail("ncm network readiness observed source=first_ncm_frame ageMs=1200"))
        assertEquals("", CompatibilityReportPolicy.safeNetworkDetail("ncm ndp prewarm result=74 expected=74 durationMs=4 ssid=secret"))
        assertEquals("", CompatibilityReportPolicy.safeNetworkDetail("userland stack create result=ready durationMs=12 nativeErrorCode=0 secret=private"))
        assertEquals("userland final tcpSynIn=1 tcpSynTrackedPort=1 tcpSynAckQueued=1 lastRxPortPair=1234->5353 rxMdns=1 txMdns=3 mdns6InQueryMask=3 mdns6InResponseMask=4 mdns4InQueryMask=1 mdns4InResponseMask=2 mdns6OutQueryMask=2 mdns6OutResponseMask=8 lastRxMdnsServiceAgeMs=11 lastRxMdnsServiceMask=3 lastRxMdnsServiceQueryMask=1 lastRxMdnsServiceResponseMask=2",
            CompatibilityReportPolicy.safeNetworkDetail("userland final tcpSynIn=1 tcpSynTrackedPort=1 tcpSynAckQueued=1 lastRxPortPair=1234->5353 rxMdns=1 txMdns=3 mdns6InQueryMask=3 mdns6InResponseMask=4 mdns4InQueryMask=1 mdns4InResponseMask=2 mdns6OutQueryMask=2 mdns6OutResponseMask=8 lastRxMdnsServiceAgeMs=11 lastRxMdnsServiceMask=3 lastRxMdnsServiceQueryMask=1 lastRxMdnsServiceResponseMask=2"))
    }
}
