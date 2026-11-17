package local.headunit.diplay

import com.shilapi.xcertplay.airplay.AirPlayControlDiagnostics
import org.junit.Assert.*
import org.junit.Test

class AirPlayControlDiagnosticsTest {
    @Test fun protocolFailureSummaryContainsOnlyBoundedRequestAndStackTokens() {
        val error = IllegalArgumentException("private request payload must not escape")
        error.stackTrace = arrayOf(StackTraceElement(
            "com.shilapi.xcertplay.airplay.BplistCodec", "decode", "BplistCodec.kt", 42,
        ))
        val detail = AirPlayControlDiagnostics.failure("SETUP", "/stream?secret=1", error)
        assertEquals(
            "airplay protocol failure method=SETUP route=stream errorType=IllegalArgumentException frameClass=BplistCodec frameMethod=decode",
            detail,
        )
        assertFalse(detail.contains("private request payload"))
        assertFalse(detail.contains("secret"))
    }

    @Test fun uploaderPreservesSafeAirPlayFailureSummaryOnly() {
        val transport = CompatibilityTools::class.java.getDeclaredMethod("isTransportDiagnostic", String::class.java)
        transport.isAccessible = true
        assertEquals(true, transport.invoke(null, "AIRPLAY"))
        assertEquals(true, transport.invoke(null, "MUX"))
        assertEquals(false, transport.invoke(null, "START_SESSION_QUEUE"))
        assertEquals(true, transport.invoke(null, "START_SESSION_RECOVERY"))
        val highValue = CompatibilityTools::class.java.getDeclaredMethod("isHighValueDiagnostic", String::class.java)
        highValue.isAccessible = true
        assertEquals(true, highValue.invoke(null, "USBMUX_WRITE_TIMING"))
        assertEquals(true, highValue.invoke(null, "NCM_WRITE_TIMING"))
        assertEquals(true, highValue.invoke(null, "START_SESSION_QUEUE"))
        assertEquals(true, highValue.invoke(null, "CONNECTION_PREWARM"))

        val numericFields = CompatibilityTools::class.java.getDeclaredField("NUMERIC_FIELDS")
        numericFields.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val numericAllowlist = numericFields.get(null) as Set<String>
        assertTrue(numericAllowlist.containsAll(setOf("startFlushedAgoMs", "firstNcmBulkOutAcceptedAgoMs", "ncmNetworkReadyAgoMs", "tcpSynToPort")))
        val booleanFields = CompatibilityTools::class.java.getDeclaredField("BOOLEAN_FIELDS")
        booleanFields.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val booleanAllowlist = booleanFields.get(null) as Set<String>
        assertTrue(booleanAllowlist.containsAll(setOf("tcpSynSinceStart", "tcpSynAfterStartFlushAndNetworkReady", "ipv4TcpSynSinceStart", "ipv4TcpSynAfterStartFlushAndNetworkReady", "anyTcpSynAfterStartFlushAndNetworkReady")))

        val summarize = CompatibilityTools::class.java.getDeclaredMethod(
            "diagnosticSummary", String::class.java, String::class.java,
        )
        summarize.isAccessible = true
        val raw = "airplay protocol failure method=SETUP route=stream errorType=IllegalArgumentException frameClass=BplistCodec frameMethod=decode private=secret"
        val safe = summarize.invoke(null, "AIRPLAY", raw) as String
        assertEquals(
            "method=SETUP route=stream errorType=IllegalArgumentException frameClass=BplistCodec frameMethod=decode",
            safe,
        )
        assertFalse(safe.contains("secret"))

        val queueSummary = summarize.invoke(
            null,
            "START_SESSION_QUEUE",
            "iap2 tx=0x4301 transport flushed durationMs=12",
        ) as String
        assertEquals("durationMs=12", queueSummary)

        assertEquals(
            "readyBeforeUse=true networkStartDurationMs=37 result=ready nativeStatus=ready",
            summarize.invoke(
                null,
                "CONNECTION_PREWARM",
                "at=native_start_complete readyBeforeUse=true networkStartDurationMs=37 result=ready nativeStatus=ready",
            ),
        )
        assertEquals(
            "decision=SEND firstNcmBulkOutAcceptedAgoMs=2000 tcpSynToPort=7000 startFlushedAgoMs=2100 tcpSynBaseline=0 tcpSynCurrent=7 lastTcpSynAgeMs=18000 lastIpv4TcpSynAgeMs=24000 tcpSynSinceStart=true tcpSynAfterStartFlushAndNetworkReady=false ipv4TcpSynSinceStart=false ipv4TcpSynAfterStartFlushAndNetworkReady=false anyTcpSynAfterStartFlushAndNetworkReady=false lastRxMdnsServiceAgeMs=125 lastRxMdnsServiceMask=3 lastRxMdnsServiceQueryMask=1 lastRxMdnsServiceResponseMask=2 serviceDiscoverySinceStart=true serviceDiscoveryQuiet=true",
            summarize.invoke(
                null,
                "START_SESSION_RECOVERY",
                "decision=SEND firstNcmBulkOutAcceptedAgoMs=2000 tcpSynToPort=7000 startFlushedAgoMs=2100 tcpSynBaseline=0 tcpSynCurrent=7 lastTcpSynAgeMs=18000 lastIpv4TcpSynAgeMs=24000 tcpSynSinceStart=true tcpSynAfterStartFlushAndNetworkReady=false ipv4TcpSynSinceStart=false ipv4TcpSynAfterStartFlushAndNetworkReady=false anyTcpSynAfterStartFlushAndNetworkReady=false lastRxMdnsServiceAgeMs=125 lastRxMdnsServiceMask=3 lastRxMdnsServiceQueryMask=1 lastRxMdnsServiceResponseMask=2 serviceDiscoverySinceStart=true serviceDiscoveryQuiet=true airplayAccepted=false sessionActive=false statsReliable=true serviceName=secret",
            ),
        )
        assertEquals(
            "ageMs=10 rxMdns=2",
            summarize.invoke(null, "USERLAND_NETWORK", "userland stats ageMs=10 rxMdns=2 payload=private"),
        )

        val usbMuxWrite = "USBMUX_OUT protocol=0x00000006 payloadBytes=20 transferBytes=36 durationMs=3 result=bulk_transfer_complete"
        assertEquals(
            "protocol=0x00000006 payloadBytes=20 transferBytes=36 durationMs=3 result=bulk_transfer_complete",
            summarize.invoke(null, "MUX", usbMuxWrite),
        )
        val ncmWrite = "NCM_OUT etherType=0x86dd ipVersion=6 ipProtocol=6 tcpSourcePort=1234 tcpDestinationPort=7000 tcpFlags=0x12 frameBytes=74 durationMs=14 result=bulk_transfer_complete errorType=none"
        assertEquals(
            "etherType=0x86dd ipVersion=6 ipProtocol=6 tcpSourcePort=1234 tcpDestinationPort=7000 tcpFlags=0x12 frameBytes=74 durationMs=14 result=bulk_transfer_complete errorType=none",
            summarize.invoke(null, "USERLAND_NETWORK", ncmWrite),
        )

        assertEquals(true, transport.invoke(null, "NCM"))
        assertEquals(true, highValue.invoke(null, "START_SESSION_QUEUE"))
        assertEquals(
            "notificationIndex=4 matchesDataInterface=true payloadLength=10 linkWord=0xffffffff ageMs=48 carrierMutation=false",
            summarize.invoke(
                null,
                "NCM",
                "ncm apple link notification observed code=0x2b notificationIndex=4 matchesDataInterface=true payloadLength=10 linkWord=0xffffffff ageMs=48 carrierMutation=false",
            ),
        )
        assertEquals(
            "notifications=3 readTimeouts=4 readErrors=1 appleLinkNotifications=1 carrier=true lastCarrierAgeMs=5 lastAppleLinkWord=0xffffffff lastAppleLinkAgeMs=6 controlInterface=3 dataInterface=4",
            summarize.invoke(
                null,
                "NCM",
                "ncm status summary notifications=3 readTimeouts=4 readErrors=1 appleLinkNotifications=1 carrier=true lastCarrierAgeMs=5 lastAppleLinkWord=0xffffffff lastAppleLinkAgeMs=6 controlInterface=3 dataInterface=4",
            ),
        )
        assertEquals(
            "skipped=1 resyncs=1",
            summarize.invoke(
                null,
                "NCM",
                "NCM resynchronized after Invalid NTB16 short-packet pad; skipped=1 resyncs=1",
            ),
        )
        assertEquals(
            "count=2",
            summarize.invoke(null, "NCM", "ncm ignored iPhone bulk-in control transfer count=2"),
        )
        assertEquals(
            "phase=availability wiredAvailable=false result=withheld",
            summarize.invoke(null, "START_SESSION_QUEUE", "phase=availability wiredAvailable=false result=withheld"),
        )
        assertEquals(
            "phase=availability wiredAvailable=true usbTransport=USB",
            summarize.invoke(null, "START_SESSION_QUEUE", "phase=availability wiredAvailable=true usbTransport=USB"),
        )
    }
}
