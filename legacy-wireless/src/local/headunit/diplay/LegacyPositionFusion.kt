package local.headunit.diplay

import com.shilapi.xcertplay.transport.CarPlayLocationFix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal data class TimedGnssFix(val fix: CarPlayLocationFix, val elapsedMs: Long)

internal data class TimedCanSpeed(val kph: Double, val elapsedMs: Long, val source: String)

internal data class TimedParkingBrake(val engaged: Boolean?, val elapsedMs: Long)

internal data class TimedCanSteering(
    val raw: Int?,
    val elapsedMs: Long,
    val swingRaw: Int,
    val swingElapsedMs: Long,
)

internal data class PositionEvaluation(
    val state: String,
    val source: String = "none",
    val fix: CarPlayLocationFix? = null,
    val courseSuppressedBySteering: Boolean = false,
)

/** Fuses independently sampled GNSS and vehicle state on the same elapsed-realtime clock. */
internal class LegacyPositionFusion {
    private var stationaryReference: CarPlayLocationFix? = null

    fun reset() {
        stationaryReference = null
    }

    fun evaluate(
        gnss: TimedGnssFix?,
        speed: TimedCanSpeed?,
        parkingBrake: TimedParkingBrake?,
        nowMs: Long,
        steering: TimedCanSteering? = null,
    ): PositionEvaluation {
        val timedFix = gnss ?: return unavailable("waiting_gnss")
        val age = nowMs - timedFix.elapsedMs
        if (timedFix.elapsedMs <= 0 || age !in 0L..DIRECT_FIX_MAX_AGE_MS) {
            return unavailable("gnss_expired")
        }
        val fix = timedFix.fix
        val accuracy = fix.accuracyMeters
        if (accuracy == null || !accuracy.isFinite() || accuracy <= 0.0 || accuracy > MAX_ACCURACY_METERS) {
            return unavailable("gnss_weak")
        }
        val can = speed?.takeIf {
            it.source in VERIFIED_CAN_SOURCES && it.elapsedMs > 0 &&
                nowMs - it.elapsedMs in 0L..CAN_MAX_AGE_MS && it.kph.isFinite() && it.kph in 0.0..MAX_KPH
        }
        val gnssSpeed = fix.speedMetersPerSecond?.takeIf { it.isFinite() && it in 0.0..MAX_KPH / 3.6 }
        val direction = fix.bearingDegrees?.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 }
        val parking = parkingBrake?.takeIf { it.elapsedMs > 0 && nowMs - it.elapsedMs in 0L..PARKING_MAX_AGE_MS }
        val carSpeed = can?.kph?.div(3.6)
        val speedCorroborated = carSpeed != null && gnssSpeed != null &&
            (carSpeed <= LOW_GNSS_SPEED) == (gnssSpeed <= LOW_GNSS_SPEED) &&
            abs(carSpeed - gnssSpeed) <= max(1.0, gnssSpeed * 0.2)
        val canInSync = can != null && abs(can.elapsedMs - timedFix.elapsedMs) <= CAN_FIX_SKEW_MS
        val useCan = canInSync && speedCorroborated
        if (parking?.engaged == true && canInSync && carSpeed != null && carSpeed <= LOW_CAN_SPEED &&
            gnssSpeed != null && gnssSpeed <= LOW_GNSS_SPEED) {
            return PositionEvaluation("stationary", can?.source ?: "none", steadyParkedFix(fix, accuracy))
        }
        reset()
        val combinedSpeed = if (useCan && carSpeed != null && gnssSpeed != null) {
            // API 17 has horizontal fix accuracy but no speed variance. Use it as a heuristic
            // after the GNSS and CAN speeds have independently agreed on movement and magnitude.
            val uncertainty = ((accuracy - 5.0) / (MAX_ACCURACY_METERS - 5.0)).coerceIn(0.0, 1.0)
            val canWeight = 0.25 + 0.45 * uncertainty
            gnssSpeed + (carSpeed - gnssSpeed) * canWeight
        } else gnssSpeed
        val movingCourse = direction?.takeIf { combinedSpeed != null && combinedSpeed >= MIN_COURSE_SPEED }
        // API 17 exposes horizontal fix accuracy but no bearing uncertainty. Use a sharp steering
        // change only to withhold a weak course estimate. Absolute angle, zero and ratio are unused.
        val suppressCourse = movingCourse != null && accuracy > STRONG_GNSS_ACCURACY_METERS &&
            steering?.recentSwing(timedFix.elapsedMs, nowMs) == true
        return PositionEvaluation(
            if (useCan) "gnss_can" else "gnss",
            if (useCan) can?.source ?: "none" else "none",
            fix.copy(speedMetersPerSecond = combinedSpeed,
                bearingDegrees = if (suppressCourse) null else movingCourse),
            courseSuppressedBySteering = suppressCourse,
        )
    }

    private fun TimedCanSteering.recentSwing(fixAtMs: Long, nowMs: Long): Boolean =
        raw != null && raw in -MAX_STEERING_RAW..MAX_STEERING_RAW && elapsedMs > 0 &&
            nowMs - elapsedMs in 0L..STEERING_MAX_AGE_MS && swingRaw in MIN_SHARP_SWING_RAW..MAX_SWING_RAW &&
            swingElapsedMs in 1L..elapsedMs && nowMs - swingElapsedMs <= STEERING_SWING_MAX_AGE_MS &&
            fixAtMs - swingElapsedMs in -MAX_SWING_AFTER_FIX_MS..MAX_SWING_BEFORE_FIX_MS

    private fun unavailable(state: String): PositionEvaluation {
        reset()
        return PositionEvaluation(state)
    }

    private fun steadyParkedFix(fix: CarPlayLocationFix, accuracy: Double): CarPlayLocationFix {
        val reference = stationaryReference
        val referenceAccuracy = reference?.accuracyMeters ?: accuracy
        if (reference == null || accuracy < referenceAccuracy * 0.5 || !withinParkedJitter(reference, fix)) {
            stationaryReference = fix
            return fix.copy(speedMetersPerSecond = 0.0, bearingDegrees = null)
        }
        return fix.copy(latitudeDegrees = reference.latitudeDegrees, longitudeDegrees = reference.longitudeDegrees,
            speedMetersPerSecond = 0.0, bearingDegrees = null, accuracyMeters = max(accuracy, referenceAccuracy))
    }

    private fun withinParkedJitter(a: CarPlayLocationFix, b: CarPlayLocationFix): Boolean {
        val deltaLatitude = Math.toRadians(b.latitudeDegrees - a.latitudeDegrees)
        val deltaLongitude = Math.toRadians(b.longitudeDegrees - a.longitudeDegrees)
        val latA = Math.toRadians(a.latitudeDegrees)
        val latB = Math.toRadians(b.latitudeDegrees)
        val halfLat = sin(deltaLatitude / 2.0)
        val halfLon = sin(deltaLongitude / 2.0)
        val squared = halfLat * halfLat + cos(latA) * cos(latB) * halfLon * halfLon
        // Equivalent to a 12 m spherical distance threshold without an inverse trig call.
        return squared <= PARKED_JITTER_SQUARED
    }

    private companion object {
        val VERIFIED_CAN_SOURCES = setOf("can_080", "can_121", "can_agreement")
        const val CAN_MAX_AGE_MS = 1_500L
        const val CAN_FIX_SKEW_MS = 1_000L
        const val PARKING_MAX_AGE_MS = 5_000L
        const val DIRECT_FIX_MAX_AGE_MS = 1_800L
        const val STRONG_GNSS_ACCURACY_METERS = 15.0
        const val MAX_ACCURACY_METERS = 35.0
        const val STEERING_MAX_AGE_MS = 1_000L
        const val STEERING_SWING_MAX_AGE_MS = 1_250L
        const val MAX_SWING_AFTER_FIX_MS = 300L
        const val MAX_SWING_BEFORE_FIX_MS = 1_000L
        const val MAX_STEERING_RAW = 7_200
        const val MAX_SWING_RAW = 2 * MAX_STEERING_RAW
        const val MIN_SHARP_SWING_RAW = 250
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val MAX_KPH = 252.0
        const val MIN_COURSE_SPEED = 3.0
        const val LOW_CAN_SPEED = 0.6
        const val LOW_GNSS_SPEED = 0.7
        val PARKED_JITTER_SQUARED = sin(12.0 / (2.0 * EARTH_RADIUS_METERS)).let { it * it }
    }
}
