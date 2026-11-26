package local.headunit.diplay

import com.shilapi.xcertplay.transport.CarPlayLocationFix
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyYawEstimatorTest {
    @Test fun learnsClockwiseAndCounterclockwiseRatesAtKnownSteeringAndSpeed() {
        val car = TestDrive()
        car.train()
        assertTrue(car.crossedNorthClockwise)

        val right = car.advance(CENTER + 600, metersPerSecond = 7.0)
        assertEquals("steering_speed", right.state)
        assertEquals(expectedRate(CENTER + 600, 7.0), right.rateDegPerSec!!, 0.5)

        val left = car.advance(CENTER - 600, metersPerSecond = 7.0)
        assertEquals("steering_speed", left.state)
        assertEquals(expectedRate(CENTER - 600, 7.0), left.rateDegPerSec!!, 0.5)

        val straight = car.advance(CENTER, metersPerSecond = 7.0)
        assertEquals("steering_speed", straight.state)
        assertEquals(0.0, straight.rateDegPerSec!!, 0.3)
    }

    @Test fun learnsReversedSensorPolarityFromMotionRatherThanAssumingIt() {
        val car = TestDrive(polarity = -1)
        car.train()
        val reading = car.advance(CENTER + 600, metersPerSecond = 7.0)

        assertEquals("steering_speed", reading.state)
        assertEquals(-expectedRate(CENTER + 600, 7.0), reading.rateDegPerSec!!, 0.5)
    }

    @Test fun incompleteOrUntrustworthyTrainingLeavesYawUnavailable() {
        val oneSide = TestDrive()
        repeat(15) { oneSide.advance(CENTER, metersPerSecond = 6.0) }
        repeat(35) { oneSide.advance(CENTER + 800, metersPerSecond = 6.0) }
        assertNull(oneSide.estimate(CENTER + 600).rateDegPerSec)

        val weakGps = TestDrive()
        weakGps.train(accuracyMeters = 40.0)
        assertNull(weakGps.estimate(CENTER + 600).rateDegPerSec)

        val differentSpeeds = TestDrive()
        differentSpeeds.train(gnssSpeedFactor = 0.2)
        assertNull(differentSpeeds.estimate(CENTER + 600).rateDegPerSec)
    }

    @Test fun stationaryWithTurnedWheelCannotReportANonzeroRate() {
        val car = TestDrive()
        car.train()
        val reading = car.estimate(CENTER + 800, metersPerSecond = 0.0)

        assertEquals("stationary", reading.state)
        assertTrue(reading.rateDegPerSec == null || reading.rateDegPerSec == 0.0)
    }

    @Test fun rejectsMissingStaleUnalignedAndOutOfRangeInputsAfterTraining() {
        val car = TestDrive()
        car.train()
        val raw = CENTER + 600
        assertEquals("steering_speed", car.estimate(raw).state)
        val speed = car.canSpeed(7.0)
        val steering = car.canSteering(raw)

        assertNull(car.estimator.estimate(null, steering, car.nowMs).rateDegPerSec)
        assertNull(car.estimator.estimate(speed, null, car.nowMs).rateDegPerSec)
        assertNull(car.estimator.estimate(speed.copy(source = "unknown"), steering, car.nowMs).rateDegPerSec)
        assertNull(car.estimator.estimate(speed.copy(elapsedMs = car.nowMs + 1), steering, car.nowMs).rateDegPerSec)
        assertNull(car.estimator.estimate(speed, steering, car.nowMs + 5_000).rateDegPerSec)
        assertNull(car.estimator.estimate(speed.copy(elapsedMs = car.nowMs - 850), steering, car.nowMs).rateDegPerSec)
        assertNull(car.estimate(7_201).rateDegPerSec)
        assertNull(car.estimate(CENTER + 2_500).rateDegPerSec)
        assertNull(car.estimate(raw, metersPerSecond = 3.0).rateDegPerSec)
        assertNull(car.estimate(CENTER, metersPerSecond = 40.0).rateDegPerSec)
    }

    @Test fun resettingMotionReferenceRequiresFreshEvidenceOfTravel() {
        val car = TestDrive()
        car.train()
        assertEquals("steering_speed", car.estimate(CENTER + 600).state)

        car.estimator.resetReference()

        assertNull(car.estimate(CENTER + 600).rateDegPerSec)
    }

    @Test fun reportKeepsTheEstimatedValueAndItsSource() {
        val car = TestDrive()
        car.train()
        val summary = requireNotNull(car.estimator.diagnostic(
            car.canSpeed(7.0), car.canSteering(CENTER + 600), car.nowMs))
        assertTrue(summary.contains("source=kinematic direction=forward_assumed"))
        assertTrue(summary.contains("modelReady=true"))
        assertTrue(summary.contains("yawDegPerSec="))
        val event = "VEHICLE_YAW_ESTIMATE " + JSONObject()
            .put("stage", "yaw_estimate").put("diagnosticSummary", summary)
        assertTrue(DiagnosticPolicy.routine(event))
        val entry = DiagnosticLog.Entry(JSONObject().put("time", 12_000L).put("id", "estimated-yaw")
            .put("event", event))
        val filter = CompatibilityTools::class.java.getDeclaredMethod("filterEvents", List::class.java)
        filter.isAccessible = true
        val result = filter.invoke(null, listOf(entry))
        val field = result.javaClass.getDeclaredField("events")
        field.isAccessible = true
        val data = (field.get(result) as JSONArray).getJSONObject(0).getJSONObject("data")
        assertEquals("yaw_estimate", data.getString("stage"))
        assertEquals(summary, data.getString("diagnosticSummary"))
    }

    private class TestDrive(private val polarity: Int = 1) {
        val estimator = LegacyYawEstimator()
        var nowMs = 10_000L
            private set
        var crossedNorthClockwise = false
            private set
        private var bearing = 358.0
        private var latitude = 22.5
        private var longitude = 114.0

        fun train(accuracyMeters: Double = 5.0, gnssSpeedFactor: Double = 1.0) {
            val courses = listOf(850, 0, -850, 625, 0, -625, 725, 0, -725)
            for ((index, turn) in courses.withIndex()) {
                val speed = if (index % 2 == 0) 6.0 else 8.0
                repeat(8) { advance(CENTER + turn, speed, accuracyMeters, gnssSpeedFactor) }
            }
        }

        fun advance(
            raw: Int,
            metersPerSecond: Double,
            accuracyMeters: Double = 5.0,
            gnssSpeedFactor: Double = 1.0,
        ): YawReading {
            val yaw = polarity * expectedRate(raw, metersPerSecond)
            val middleCourse = Math.toRadians(bearing + yaw / 2.0)
            val latitudeScale = 111_319.5
            val longitudeScale = latitudeScale * cos(Math.toRadians(latitude))
            latitude += metersPerSecond * cos(middleCourse) / latitudeScale
            longitude += metersPerSecond * sin(middleCourse) / longitudeScale
            val next = (bearing + yaw + 360.0) % 360.0
            if (yaw > 0 && next < bearing) crossedNorthClockwise = true
            bearing = next
            nowMs += 1_000
            val fix = CarPlayLocationFix(
                latitudeDegrees = latitude,
                longitudeDegrees = longitude,
                timestampMillis = 1_795_660_800_000L + nowMs,
                bearingDegrees = bearing,
                speedMetersPerSecond = metersPerSecond * gnssSpeedFactor,
                accuracyMeters = accuracyMeters,
            )
            estimator.observe(TimedGnssFix(fix, nowMs), canSpeed(metersPerSecond), canSteering(raw), nowMs)
            return estimate(raw, metersPerSecond)
        }

        fun estimate(raw: Int, metersPerSecond: Double = 7.0): YawReading =
            estimator.estimate(canSpeed(metersPerSecond), canSteering(raw), nowMs)

        fun canSpeed(metersPerSecond: Double): TimedCanSpeed =
            TimedCanSpeed(metersPerSecond * 3.6, nowMs, "can_121")

        fun canSteering(raw: Int): TimedCanSteering =
            TimedCanSteering(raw, nowMs, swingRaw = 0, swingElapsedMs = -1)
    }

    private companion object {
        const val CENTER = -960

        fun expectedRate(raw: Int, speedMetersPerSecond: Double): Double =
            Math.toDegrees(tan(0.00012 * (raw - CENTER)) * speedMetersPerSecond / 2.660)
    }
}
