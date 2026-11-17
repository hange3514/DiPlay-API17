package local.headunit.diplay

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class Iap2WiredAvailabilityDecodeTest {
    @Test fun truncatedParameterHeaderIsReportedWithoutAbortingStartup() {
        val result = Iap2WiredControlClient.carPlayAvailabilitySummary(byteArrayOf(1))
        assertTrue(result, result.startsWith("iap2 4300 decode failed:"))
    }

    @Test fun declaredParameterLengthPastBodyIsReportedWithoutAbortingStartup() {
        val result = Iap2WiredControlClient.carPlayAvailabilitySummary(byteArrayOf(0, 8, 0, 0))
        assertTrue(result, result.startsWith("iap2 4300 decode failed:"))
    }

    @Test fun validWiredAvailabilityKeepsItsNormalDiagnostic() {
        val payload = Iap2BodyBuilder.opaque(0x4300)
            .group(0) { u8(0, 1); string(1, "USB") }
            .build()
            .payload
        val result = Iap2WiredControlClient.carPlayAvailabilitySummary(payload)
        assertTrue(result, result.contains("wiredAvailable=true"))
        assertTrue(result, result.contains("usbTransport=USB"))
        assertTrue(Iap2WiredControlClient.wiredAvailability(payload) == true)
    }

    @Test fun onlyAnExplicitFalseIsTreatedAsUnavailable() {
        val unavailable = Iap2BodyBuilder.opaque(0x4300)
            .group(0) { u8(0, 0) }
            .build()
            .payload
        assertFalse(Iap2WiredControlClient.wiredAvailability(unavailable)!!)

        val omitted = Iap2BodyBuilder.opaque(0x4300)
            .group(0) { void(0) }
            .build()
            .payload
        assertNull(Iap2WiredControlClient.wiredAvailability(omitted))
        assertNull(Iap2WiredControlClient.wiredAvailability(byteArrayOf(1)))
    }
}
