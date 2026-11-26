package local.headunit.diplay

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationMessages
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2LocationReporter
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import org.junit.Assert.*
import org.junit.Test

class LegacyPositionFusionTest {
    private val timestamp = 1_795_660_800_000L

    private fun gnss(at: Long, speed: Double? = 10.0, bearing: Double? = 90.0,
                     accuracy: Double = 6.0, latitude: Double = 22.5): TimedGnssFix =
        TimedGnssFix(CarPlayLocationFix(latitude, 114.0, bearingDegrees = bearing,
            speedMetersPerSecond = speed, accuracyMeters = accuracy, timestampMillis = timestamp), at)

    @Test fun freshGpsWorksWithoutVehicleSpeed() {
        val fusion = LegacyPositionFusion()
        val direct = fusion.evaluate(gnss(1000, speed = null), null, null, 1100)
        assertEquals("gnss", direct.state)
        val rmc = NmeaLocationEncoder.encode(direct.fix!!, includeGga = false).trimEnd().substringBefore('*').split(',')
        assertEquals("\$GPRMC", rmc[0])
        assertEquals("", rmc[7])
        assertEquals("", rmc[8])
        assertEquals("gnss_weak", fusion.evaluate(gnss(1000, accuracy = 36.0), null, null, 1100).state)
        assertEquals("gnss_expired", fusion.evaluate(gnss(1000), null, null, 3000).state)
        assertEquals("gnss_expired", fusion.evaluate(gnss(1101), null, null, 1100).state)
    }

    @Test fun speedRequiresKnownFreshAlignedAndCorroboratedSource() {
        val fusion = LegacyPositionFusion()
        val fix = gnss(2000)
        val valid = TimedCanSpeed(39.6, 1950, "can_080")
        val result = fusion.evaluate(fix, valid, null, 2050)
        assertEquals("gnss_can", result.state)
        assertEquals("can_080", result.source)
        assertEquals(10.265, result.fix!!.speedMetersPerSecond!!, 0.001)
        assertEquals(10.7, fusion.evaluate(gnss(2000, accuracy = 35.0), valid, null, 2050).fix!!.speedMetersPerSecond!!, 0.001)
        for (bad in listOf(valid.copy(kph = 0.0), valid.copy(source = "unknown"),
            valid.copy(elapsedMs = 900), valid.copy(elapsedMs = 500), valid.copy(elapsedMs = 2051))) {
            assertEquals(10.0, fusion.evaluate(fix, bad, null, 2050).fix!!.speedMetersPerSecond!!, 0.001)
        }
        assertNull(fusion.evaluate(gnss(2000, speed = null), valid, null, 2050).fix!!.speedMetersPerSecond)
        assertEquals(1.0, fusion.evaluate(gnss(2000, speed = 1.0), valid.copy(kph = 0.0), null, 2050).fix!!.speedMetersPerSecond!!, 0.001)
    }

    @Test fun freshBrakeAndBothLowSpeedsStabilizePosition() {
        val fusion = LegacyPositionFusion()
        val parked = TimedParkingBrake(true, 1000)
        val slow = TimedCanSpeed(0.2, 1000, "can_121")
        val first = fusion.evaluate(gnss(1000, speed = 0.4), slow, parked, 1100)
        assertEquals("stationary", first.state)
        assertEquals(0.0, first.fix!!.speedMetersPerSecond!!, 0.001)
        val jitter = fusion.evaluate(gnss(2000, speed = 0.3, latitude = 22.50002), slow.copy(elapsedMs = 1900), parked, 2050)
        assertEquals(22.5, jitter.fix!!.latitudeDegrees, 0.000001)
        assertNull(jitter.fix.bearingDegrees)
        val moved = fusion.evaluate(gnss(3000, speed = 8.0), slow.copy(elapsedMs = 2950), parked, 3100)
        assertEquals(8.0, moved.fix!!.speedMetersPerSecond!!, 0.001)
        val staleBrake = fusion.evaluate(gnss(7000, speed = 0.4), slow.copy(elapsedMs = 6950), parked, 7100)
        assertEquals("gnss_can", staleBrake.state)
    }

    @Test fun staleGpsCannotBeReplacedByCanAndNmeaChecksumsAreCorrect() {
        val fix = gnss(1000)
        val speed = TimedCanSpeed(36.0, 2801, "can_agreement")
        val brake = TimedParkingBrake(false, 2801)
        val fusion = LegacyPositionFusion()
        assertEquals("gnss_expired", fusion.evaluate(fix, speed, brake, 2801).state)
        assertNull(fusion.evaluate(fix, speed, brake, 2801).fix)
        val nmea = NmeaLocationEncoder.encode(fix.fix)
        assertTrue(nmea.lineSequence().first().startsWith("\$GPGGA"))
        assertTrue(nmea.lineSequence().drop(1).first().startsWith("\$GPRMC"))
        for (sentence in nmea.lineSequence().filter { it.isNotEmpty() }) {
            val checksum = sentence.substringAfterLast('*').toInt(16)
            assertEquals(checksum, sentence.substringBefore('*').drop(1).fold(0) { value, char -> value xor char.code })
        }
    }

    @Test fun freshSteeringSwingWithholdsOnlyTheWeakCourseField() {
        val fusion = LegacyPositionFusion()
        val steering = TimedCanSteering(raw = -800, elapsedMs = 2000, swingRaw = 350, swingElapsedMs = 1950)
        val speed = TimedCanSpeed(39.6, 2000, "can_121")
        val weak = gnss(2000, bearing = 65.0, accuracy = 25.0)
        val reference = fusion.evaluate(weak, speed, null, 2100)
        val turned = fusion.evaluate(weak, speed, null, 2100, steering)
        assertEquals(reference.state, turned.state)
        assertEquals(reference.source, turned.source)
        assertEquals(reference.fix!!.copy(bearingDegrees = null), turned.fix)
        assertTrue(turned.courseSuppressedBySteering)
        val rmc = NmeaLocationEncoder.encode(turned.fix!!, includeGga = false).trimEnd().substringBefore('*').split(',')
        assertTrue(rmc[7].isNotEmpty())
        assertEquals("", rmc[8])

        val accurate = fusion.evaluate(gnss(2000, bearing = 65.0, accuracy = 15.0), speed, null, 2100, steering)
        assertFalse(accurate.courseSuppressedBySteering)
        assertEquals(65.0, accurate.fix!!.bearingDegrees!!, 0.001)
        val parked = fusion.evaluate(gnss(2000, speed = 0.3, accuracy = 25.0), null, null, 2100, steering)
        assertFalse(parked.courseSuppressedBySteering)
        assertNull(parked.fix!!.bearingDegrees)
    }

    @Test fun steadyTurnMissingDataAndTimeMismatchDoNotSuppressCourse() {
        val fusion = LegacyPositionFusion()
        val weak = gnss(2000, bearing = 65.0, accuracy = 25.0)
        val steering = TimedCanSteering(raw = -800, elapsedMs = 2000, swingRaw = 350, swingElapsedMs = 1950)
        for (ineligible in listOf(
            null,
            steering.copy(raw = -3_500, swingRaw = 249),
            steering.copy(raw = null),
            steering.copy(raw = 7_201),
            steering.copy(elapsedMs = 999, swingElapsedMs = 980),
            steering.copy(elapsedMs = 2101),
            steering.copy(swingElapsedMs = 2001),
            steering.copy(swingElapsedMs = 990),
        )) {
            val result = fusion.evaluate(weak, null, null, 2100, ineligible)
            assertFalse(result.courseSuppressedBySteering)
            assertEquals(65.0, result.fix!!.bearingDegrees!!, 0.001)
        }
        val afterFix = fusion.evaluate(weak, null, null, 2400, steering.copy(elapsedMs = 2350, swingElapsedMs = 2301))
        assertFalse(afterFix.courseSuppressedBySteering)
        val expired = fusion.evaluate(weak, null, null, 3_201, steering.copy(elapsedMs = 3180))
        assertFalse(expired.courseSuppressedBySteering)
        assertEquals(65.0, expired.fix!!.bearingDegrees!!, 0.001)
        assertTrue(fusion.evaluate(weak, null, null, 2050, steering.copy(swingRaw = 250)).courseSuppressedBySteering)
    }

    @Test fun controlLinkOnlyReportsOnItsOwnSubscriptionAndConfirmsSend() {
        val sample = NmeaLocationEncoder.encode(gnss(1000).fix)
        var now = 0L
        var started = false
        var accepted = 0
        val provider = object : Iap2LocationProvider {
            override fun onRequested(components: Set<Int>) { assertEquals(setOf(0, 1, 2, 0x8001), components) }
            override fun start(): Boolean { started = true; return true }
            override fun stop() { started = false }
            override fun latestNmea(): String? = sample.takeIf { started }
            override fun onSent() { accepted += 1 }
        }
        val active = Iap2LocationReporter(provider, {}, { now })
        val inactive = Iap2LocationReporter(null, {}, { now })
        val sent = ArrayList<Iap2Frame>()
        val start = Iap2Messages.buildRaw(Iap2LocationMessages.START_LOCATION_INFORMATION) {
            u16(0, 0)
            void(1)
            void(2)
            u32(0x8001, 1_000)
        }
        val stop = Iap2Messages.buildRaw(Iap2LocationMessages.STOP_LOCATION_INFORMATION) {}
        inactive.tick(sent::add)
        assertTrue(active.handle(start, sent::add))
        assertTrue(started)
        now += 1_000_000_000L
        active.tick(sent::add)
        assertTrue(active.handle(stop, sent::add))
        assertFalse(started)
        now += 1_000_000_000L
        active.tick(sent::add)
        assertEquals(2, accepted)
        assertEquals(listOf(0xfffb, 0xfffb), sent.map { it.messageId })
    }
}
