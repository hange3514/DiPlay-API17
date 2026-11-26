package local.headunit.diplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationMessages
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import com.shilapi.xcertplay.transport.PascdEncoder
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample

/** Independent GNSS and vehicle-speed inputs for one authenticated iAP2 link. */
internal class LegacyCarPlayLocationProvider(
    context: Context,
    private val vehicleSignals: LegacyVehicleSignals?,
    private val gnssOffered: Boolean,
    private val canSpeedOffered: Boolean,
    private val onProgress: (String) -> Unit = {},
) : Iap2LocationProvider {
    private val appContext = context.applicationContext ?: context
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val lock = Any()
    private val fusion = LegacyPositionFusion()
    private val acquisition = GnssAcquisitionGate()
    private val yaw = if (canSpeedOffered) LegacyYawEstimator() else null
    private var listener: GnssListener? = null
    private var latestFix: TimedGnssFix? = null
    private var includeGga = false
    private var includeRmc = false
    private var includeCanSpeed = false
    private var lastSentTime = 0L
    private var pending: Pair<Long, String>? = null
    private val observedStates = HashSet<String>()
    private val sentStates = HashSet<String>()
    private var lastSpeedAtMs = -1L
    private var pendingSpeedAtMs = -1L
    private var speedSent = false

    override fun onRequested(components: Set<Int>): Unit = synchronized(lock) {
        val defaultSentences = components.isEmpty() || components.all { it == 0 || it == 0x8001 }
        includeGga = gnssOffered && (Iap2LocationMessages.GGA in components || defaultSentences)
        includeRmc = gnssOffered && (Iap2LocationMessages.RMC in components || defaultSentences)
        includeCanSpeed = canSpeedOffered && Iap2LocationMessages.VEHICLE_SPEED_DATA in components
        lastSentTime = 0L
        pending = null
        resetSpeedReporting()
        log("iap2 location requested gga=$includeGga rmc=$includeRmc pascd=$includeCanSpeed")
    }

    override fun start(): Boolean = synchronized(lock) {
        if (!includeGga && !includeRmc) {
            stopGnss()
            return includeCanSpeed
        }
        if (listener != null) return true
        val gps = manager ?: return includeCanSpeed
        val next = GnssListener()
        listener = next
        try {
            gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, next, Looper.getMainLooper())
        } catch (error: RuntimeException) {
            listener = null
            log("iap2 location unavailable error=${error.javaClass.simpleName}")
            return includeCanSpeed
        }
        try {
            gps.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { accept(next, it) }
        } catch (_: RuntimeException) {
            // Current updates can still arrive when the cache cannot be queried.
        }
        log("iap2 location provider started source=gps")
        true
    }

    override fun stop(): Unit = synchronized(lock) {
        includeCanSpeed = false
        resetSpeedReporting()
        stopGnss()
    }

    private fun stopGnss() {
        val previous = listener
        listener = null
        latestFix = null
        pending = null
        lastSentTime = 0L
        fusion.reset()
        acquisition.reset()
        yaw?.resetReference()
        sentStates.clear()
        observedStates.clear()
        if (previous != null) try {
            manager?.removeUpdates(previous)
        } catch (_: RuntimeException) {
            // The provider may already have dropped the listener.
        }
    }

    override fun latestNmea(): String? = synchronized(lock) {
        if (listener == null && yaw == null) return null
        val last = latestFix
        val signals = vehicleSignals?.snapshot()
        val speed = signals?.canSpeed()
        val parking = signals?.let { TimedParkingBrake(it.parkingBrake, it.parkingBrakeAtMs) }
        val steering = signals?.canSteering()
        val now = SystemClock.elapsedRealtime()
        yaw?.diagnostic(speed, steering, now)?.let(::log)
        if (listener == null) return null
        val evaluated = fusion.evaluate(last, speed, parking, now, steering)
        val state = if (evaluated.fix != null && !acquisition.ready) "gnss_acquiring" else evaluated.state
        if (observedStates.add(state)) {
            log("iap2 location state=$state speedSource=${evaluated.source}")
        }
        if (!acquisition.ready) return null
        val fix = evaluated.fix ?: return null
        val time = last?.elapsedMs ?: return null
        if (time <= lastSentTime) return null
        pending = time to "iap2 location sent mode=${evaluated.state} speedSource=${evaluated.source} " +
            "steeringCourseWithheld=${evaluated.courseSuppressedBySteering}"
        NmeaLocationEncoder.encode(fix, includeGga, includeRmc)
    }

    override fun latestVehicleSpeedNmea(): String? = synchronized(lock) {
        if (!includeCanSpeed) return null
        val signals = vehicleSignals?.snapshot() ?: return null
        val at = signals.speedAtMs
        if (at <= lastSpeedAtMs || at < 0 || !signals.speedKph.isFinite() || signals.speedKph < 0) return null
        val sample = VehicleSpeedSample(at % DAY_MS, signals.speedKph / 3.6)
        val sentence = PascdEncoder.encode(VehicleSpeedReading(VehicleGear.UNKNOWN, listOf(sample))) ?: return null
        pendingSpeedAtMs = at
        sentence
    }

    override fun onVehicleSpeedSent(): Unit = synchronized(lock) {
        if (pendingSpeedAtMs < 0) return
        lastSpeedAtMs = pendingSpeedAtMs
        pendingSpeedAtMs = -1L
        if (!speedSent) {
            speedSent = true
            log("iap2 location sent mode=pascd gear=unknown")
        }
    }

    private fun resetSpeedReporting() {
        pendingSpeedAtMs = -1L
        lastSpeedAtMs = -1L
        speedSent = false
    }

    override fun onSent(): Unit = synchronized(lock) {
        val sent = pending ?: return
        pending = null
        lastSentTime = sent.first
        if (sentStates.add(sent.second)) log(sent.second)
    }

    private fun accept(owner: GnssListener, location: Location): Unit = synchronized(lock) {
        if (listener !== owner || location.provider != LocationManager.GPS_PROVIDER) return
        val elapsed = location.elapsedRealtimeNanos / 1_000_000L
        val now = SystemClock.elapsedRealtime()
        if (elapsed <= (latestFix?.elapsedMs ?: 0L) || elapsed > now || location.time <= 0L) return
        val latitude = location.latitude
        val longitude = location.longitude
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return
        val fix = CarPlayLocationFix(
            latitudeDegrees = latitude,
            longitudeDegrees = longitude,
            bearingDegrees = location.bearing.toDouble().takeIf { location.hasBearing() },
            speedMetersPerSecond = location.speed.toDouble().takeIf { location.hasSpeed() },
            accuracyMeters = location.accuracy.toDouble().takeIf { location.hasAccuracy() },
            timestampMillis = location.time,
            satelliteCount = location.extras?.getInt("satellites")?.takeIf { it in 1..99 },
        )
        val timedFix = TimedGnssFix(fix, elapsed)
        latestFix = timedFix
        acquisition.observe(elapsed, fix.accuracyMeters, now)
        if (yaw != null) {
            val signals = vehicleSignals?.snapshot()
            yaw.observe(timedFix, signals?.canSpeed(), signals?.canSteering(), now)
        }
    }

    private fun LegacyVehicleSignals.Snapshot.canSpeed() = TimedCanSpeed(speedKph, speedAtMs, speedSource)

    private fun LegacyVehicleSignals.Snapshot.canSteering() =
        TimedCanSteering(steeringRaw, steeringAtMs, steeringSwingRaw, steeringSwingAtMs)

    private fun log(message: String) {
        try { onProgress(message) } catch (_: RuntimeException) { /* Diagnostics are best effort. */ }
    }

    private inner class GnssListener : LocationListener {
        override fun onLocationChanged(location: Location) = accept(this, location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String): Unit = synchronized(lock) {
            if (listener === this && provider == LocationManager.GPS_PROVIDER) {
                latestFix = null
                fusion.reset()
                acquisition.reset()
                yaw?.resetReference()
            }
        }
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        @JvmStatic fun canReport(context: Context): Boolean {
            if (context.checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) return false
            val gps = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            return try {
                gps.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (_: RuntimeException) {
                false
            }
        }
    }
}
