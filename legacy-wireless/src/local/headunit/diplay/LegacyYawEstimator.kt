package local.headunit.diplay

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.tan

internal data class YawReading(val state: String, val rateDegPerSec: Double? = null)

/** Calibrates the raw OEM steering signal against GNSS course changes during a connected drive. */
internal class LegacyYawEstimator {
    private data class Observation(val atMs: Long, val course: Double, val speedMps: Double, val raw: Int)
    private data class Point(val raw: Double, val frontAngle: Double, val heldOut: Boolean)
    private data class Calibration(val zero: Double, val radiansPerCount: Double, val minRaw: Double,
                                   val maxRaw: Double, val holdoutRms: Double)

    private val points = ArrayDeque<Point>()
    private var previous: Observation? = null
    private var calibration: Calibration? = null
    private var pairs = 0
    private var referenceYaw: Double? = null
    private var referenceAtMs = -1L
    private var lastCorroboratedMotionAtMs = -1L
    private var lastLoggedAtMs = -1L
    private var lastLoggedState: String? = null
    private var lastLoggedTurn = 0

    fun resetReference() {
        previous = null
        referenceYaw = null
        referenceAtMs = -1
        lastCorroboratedMotionAtMs = -1
    }

    fun observe(fix: TimedGnssFix, speed: TimedCanSpeed?, steering: TimedCanSteering?, nowMs: Long) {
        val sample = observation(fix, speed, steering, nowMs)
        val other = previous
        previous = sample
        referenceYaw = null
        if (sample == null) return
        lastCorroboratedMotionAtMs = sample.atMs
        if (other == null) return
        val interval = sample.atMs - other.atMs
        if (interval !in 700L..1600L || abs(sample.raw - other.raw) > 150 ||
            abs(sample.speedMps - other.speedMps) > 1.5) return
        val yawDegPerSec = ((sample.course - other.course + 540.0) % 360.0 - 180.0) * 1000.0 / interval
        if (abs(yawDegPerSec) > 45.0) return
        val angle = atan(WHEELBASE_METERS * Math.toRadians(yawDegPerSec) /
            ((sample.speedMps + other.speedMps) / 2.0))
        if (abs(angle) > 0.5) return
        referenceYaw = yawDegPerSec
        referenceAtMs = sample.atMs
        if (calibration != null) return
        // Fitting and held-out intervals must not share a GNSS course sample.
        previous = null
        if (points.size == 90) points.removeFirst()
        points.addLast(Point((sample.raw + other.raw) / 2.0, angle, pairs++ % 3 == 2))
        if (points.size >= 18 && pairs % 3 == 0) calibration = fitCalibration()
    }

    private fun observation(fix: TimedGnssFix, speed: TimedCanSpeed?, steering: TimedCanSteering?,
                            nowMs: Long): Observation? {
        val position = fix.fix
        val accuracy = position.accuracyMeters ?: return null
        val course = position.bearingDegrees ?: return null
        val gnssSpeed = position.speedMetersPerSecond ?: return null
        if (fix.elapsedMs <= 0 || nowMs - fix.elapsedMs !in 0L..650L ||
            accuracy <= 0 || accuracy > 15 || !accuracy.isFinite() ||
            !course.isFinite() || course < 0 || course >= 360 ||
            gnssSpeed !in MIN_MOVING_MPS..MAX_CALIBRATION_MPS) return null
        if (speed == null || steering == null) return null
        val mps = usableSpeed(speed, nowMs) ?: return null
        val raw = usableSteering(steering, nowMs) ?: return null
        if (mps !in MIN_MOVING_MPS..MAX_CALIBRATION_MPS || abs(mps - gnssSpeed) > max(0.8, gnssSpeed * 0.15) ||
            abs(fix.elapsedMs - speed.elapsedMs) > 650 || abs(fix.elapsedMs - steering.elapsedMs) > 650) return null
        return Observation(fix.elapsedMs, course, mps, raw)
    }

    private fun fitCalibration(): Calibration? {
        val training = points.filterNot { it.heldOut }
        val validation = points.filter { it.heldOut }
        if (!hasTurnsAndStraights(training, 3) || !hasTurnsAndStraights(validation, 1)) return null
        val meanRaw = training.map { it.raw }.average()
        val meanAngle = training.map { it.frontAngle }.average()
        var covariance = 0.0
        var variance = 0.0
        for (point in training) {
            val difference = point.raw - meanRaw
            covariance += difference * (point.frontAngle - meanAngle)
            variance += difference * difference
        }
        val minRaw = training.minOf { it.raw }
        val maxRaw = training.maxOf { it.raw }
        if (maxRaw - minRaw < 250.0 || variance <= 0.0) return null
        val scale = covariance / variance
        if (abs(scale) !in 0.00002..0.0005) return null
        val zero = meanRaw - meanAngle / scale
        if (zero !in minRaw..maxRaw) return null
        val errors = validation.map { abs(it.frontAngle - scale * (it.raw - zero)) }
        val rms = sqrt(errors.map { it * it }.average())
        if (rms > 0.022 || errors.any { it > 0.055 }) return null
        return Calibration(zero, scale, minRaw, maxRaw, rms)
    }

    private fun hasTurnsAndStraights(samples: List<Point>, atLeast: Int): Boolean =
        samples.count { it.frontAngle >= 0.025 } >= atLeast &&
            samples.count { it.frontAngle <= -0.025 } >= atLeast &&
            samples.count { abs(it.frontAngle) <= 0.012 } >= atLeast

    fun estimate(speed: TimedCanSpeed?, steering: TimedCanSteering?, nowMs: Long): YawReading {
        if (speed == null) return YawReading("no_can_speed")
        val mps = usableSpeed(speed, nowMs) ?: return YawReading("no_can_speed")
        if (steering == null) return YawReading("no_steering")
        val raw = usableSteering(steering, nowMs) ?: return YawReading("no_steering")
        if (abs(speed.elapsedMs - steering.elapsedMs) > 650) return YawReading("signals_unsynced")
        if (mps == 0.0) return YawReading("stationary", 0.0)
        if (mps < MIN_MOVING_MPS) return YawReading("direction_unconfirmed")
        val model = calibration ?: return YawReading("needs_calibration")
        if (lastCorroboratedMotionAtMs < 0 || nowMs - lastCorroboratedMotionAtMs !in 0L..3000L) {
            return YawReading("direction_unconfirmed")
        }
        if (mps > MAX_CALIBRATION_MPS || raw.toDouble() !in model.minRaw..model.maxRaw) {
            return YawReading("outside_calibration")
        }
        val frontAngle = model.radiansPerCount * (raw - model.zero)
        val rate = Math.toDegrees(mps * tan(frontAngle) / WHEELBASE_METERS)
        if (!rate.isFinite() || abs(rate) > 45.0) return YawReading("outside_calibration")
        return YawReading("steering_speed", rate)
    }

    fun diagnostic(speed: TimedCanSpeed?, steering: TimedCanSteering?, nowMs: Long): String? {
        val reading = estimate(speed, steering, nowMs)
        val rate = reading.rateDegPerSec
        val turn = when {
            rate == null -> 0
            rate >= 2.0 -> 1
            rate <= -2.0 -> -1
            else -> 0
        }
        val motionChanged = reading.state == "steering_speed" &&
            (lastLoggedState != reading.state || turn != lastLoggedTurn)
        val minInterval = if (motionChanged) 1_000 else 4_000
        if (lastLoggedAtMs >= 0 && nowMs - lastLoggedAtMs < minInterval) return null
        val maxInterval = if (reading.state == "steering_speed") 5_000 else 15_000
        if (!motionChanged && lastLoggedState == reading.state && nowMs - lastLoggedAtMs < maxInterval) return null
        lastLoggedAtMs = nowMs
        lastLoggedState = reading.state
        lastLoggedTurn = turn
        val model = calibration
        val reference = referenceYaw?.takeIf { nowMs - referenceAtMs in 0L..1500L }
        val direction = if (reading.state == "stationary") "stationary" else "forward_assumed"
        return "yaw_estimate state=${reading.state} source=kinematic direction=$direction wheelbaseM=2.660" +
            " modelReady=${model != null} gpsPairs=$pairs retainedPairs=${points.size}" +
            (usableSpeed(speed, nowMs)?.let { " speedKph=${rounded(it * 3.6)} speedSource=${speed?.source}" } ?: "") +
            (usableSteering(steering, nowMs)?.let { " steeringRaw=$it" } ?: "") +
            (reading.rateDegPerSec?.let { " yawDegPerSec=${rounded(it)}" } ?: "") +
            (reference?.let { " gpsCourseRateDegPerSec=${rounded(it)}" } ?: "") +
            (model?.let { " zeroRaw=${rounded(it.zero)} scaleMicroradPerCount=${rounded(it.radiansPerCount * 1e6)}" +
                " rawMin=${it.minRaw} rawMax=${it.maxRaw} holdoutAngleRmsDeg=${rounded(Math.toDegrees(it.holdoutRms))}" } ?: "")
    }

    private fun usableSpeed(speed: TimedCanSpeed?, nowMs: Long): Double? =
        speed?.takeIf { it.elapsedMs > 0 && nowMs - it.elapsedMs in 0L..1000L &&
            it.kph in 0.0..252.0 && it.source in OEM_SPEED_SOURCES }?.kph?.div(3.6)

    private fun usableSteering(steering: TimedCanSteering?, nowMs: Long): Int? =
        steering?.takeIf { it.elapsedMs > 0 && nowMs - it.elapsedMs in 0L..1000L }
            ?.raw?.takeIf { it in -7200..7200 }

    private fun rounded(value: Double): Double = round(value * 100.0) / 100.0

    private companion object {
        const val MIN_MOVING_MPS = 5.0
        const val MAX_CALIBRATION_MPS = 27.0
        const val WHEELBASE_METERS = 2.660
        val OEM_SPEED_SOURCES = setOf("can_121", "can_080", "can_agreement")
    }
}
