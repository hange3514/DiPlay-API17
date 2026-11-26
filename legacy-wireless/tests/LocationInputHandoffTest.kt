package local.headunit.diplay

import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationMessages
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2LocationReporter
import org.junit.Assert.*
import org.junit.Test

class LocationInputHandoffTest {
    @Test fun coldAndLostGnssRequireTwoFreshFixes() {
        val acquire = GnssAcquisitionGate()
        acquire.observe(1_000, 15.0, 4_000)
        acquire.observe(5_000, 12.0, 5_000)
        acquire.observe(5_000, 12.0, 5_001)
        assertFalse(acquire.ready)
        acquire.observe(6_000, 24.0, 6_000)
        assertTrue(acquire.ready)
        acquire.observe(7_000, 32.0, 7_000)
        assertTrue(acquire.ready)
        acquire.observe(8_000, 60.0, 8_000)
        assertFalse(acquire.ready)
        acquire.observe(9_000, 10.0, 9_000)
        assertFalse(acquire.ready)
        acquire.observe(12_000, 13.0, 12_000)
        assertFalse(acquire.ready)
        acquire.observe(13_000, 18.0, 13_000)
        assertTrue(acquire.ready)
    }

    @Test fun speedOnlyOfferDoesNotAdvertiseGnssSentences() {
        val config = Iap2IdentificationConfig("Test", "Test", "Test", "00", "1", "1", 0,
            locationInformationEnabled = true, gnssPositionEnabled = false, vehicleSpeedEnabled = true)
        val identification = Iap2IdentificationClient.identificationInformation(config)
        val fields = Iap2BodyReader.of(identification).group(22).list().map { it.id }
        assertFalse(fields.any { it == 17 || it == 18 })
        assertTrue(20 in fields)
    }

    @Test fun speedCanReachPhoneWithoutPositionAndStopsAfterUnsubscribe() {
        var now = 0L
        var speedSent = 0
        var gnssSent = 0
        var speedRequested = false
        val speed = "\$PASCD,1.000,C,U,0,1,0.00,2.000*00"
        val provider = object : Iap2LocationProvider {
            override fun onRequested(components: Set<Int>) { speedRequested = 4 in components }
            override fun start() = speedRequested
            override fun stop() { speedRequested = false }
            override fun latestNmea(): String? = null
            override fun latestVehicleSpeedNmea() = speed.takeIf { speedRequested }
            override fun onSent() { gnssSent++ }
            override fun onVehicleSpeedSent() { speedSent++ }
        }
        val reporter = Iap2LocationReporter(provider, {}, { now })
        val sent = mutableListOf<Iap2Frame>()
        reporter.handle(Iap2Messages.build(Iap2Endpoints.START_LOCATION_INFORMATION) { void(4) }, sent::add)
        val frame = sent.single()
        assertEquals(Iap2LocationMessages.LOCATION_INFORMATION, frame.messageId)
        assertEquals("$speed\r\n", Iap2BodyReader.of(frame).string(0))
        assertEquals(1, speedSent)
        assertEquals(0, gnssSent)
        reporter.handle(Iap2Messages.build(Iap2Endpoints.STOP_LOCATION_INFORMATION) {}, sent::add)
        now += 2_000_000_000L
        reporter.tick(sent::add)
        assertEquals(1, sent.size)
    }

    @Test fun positionAndSpeedSentencesUseSeparateParameters() {
        val sentences = listOf("\$GPGGA,example", "\$GPRMC,example", "\$PASCD,example")
        val parameters = Iap2LocationMessages.locationInformation(sentences.joinToString("\r\n"))
            .body().asList()
        assertEquals(listOf(0, 0, 0), parameters.map { it.id })
        assertEquals(sentences.map { "$it\r\n\u0000" }, parameters.map { String(it.payload, Charsets.UTF_8) })
    }
}
