package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min

/** One Android location sample converted into the fields carried by the NMEA pair. */
data class CarPlayLocationFix(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val altitudeMeters: Double? = null,
    val bearingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val accuracyMeters: Double? = null,
    val timestampMillis: Long? = null,
    val satelliteCount: Int? = null,
) {
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0) {
            "latitudeDegrees must be between -90 and 90"
        }
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0) {
            "longitudeDegrees must be between -180 and 180"
        }
    }
}

/** Supplies location data only while the phone has subscribed to iAP2 LocationInformation. */
interface Iap2LocationProvider : java.io.Closeable {
    /** The 0xFFFA parameter ids, i.e. the sentence types the iPhone asked for; called before [start]. */
    fun onRequested(components: Set<Int>) = Unit

    /** Starts location updates and returns whether at least one source was subscribed. */
    fun start(): Boolean

    fun stop()

    fun latestNmea(): String?

    fun onSent() = Unit

    /** An independently subscribed PASCD sentence can be sent without a GNSS fix. */
    fun latestVehicleSpeedNmea(): String? = null

    fun onVehicleSpeedSent() = Unit

    override fun close() {
        stop()
    }
}

/** LIVI-compatible $GPGGA + $GPRMC encoding used by iAP2 0xFFFB. */
object NmeaLocationEncoder {
    fun encode(fix: CarPlayLocationFix, includeGga: Boolean = true, includeRmc: Boolean = true): String {
        val timestamp = fix.timestampMillis
            ?.takeIf { it > 0 }
            ?.let(::ofEpochMilli)
            ?: ofEpochMilli(System.currentTimeMillis())
        val time = format("%02d%02d%02d.00", timestamp.hour, timestamp.minute, timestamp.second)
        val date = format(
            "%02d%02d%02d",
            timestamp.day,
            timestamp.month,
            timestamp.year % 100,
        )

        val latitude = degreesToNmea(fix.latitudeDegrees, latitude = true)
        val longitude = degreesToNmea(fix.longitudeDegrees, latitude = false)
        val altitude = fix.altitudeMeters
            ?.takeIf { it.isFinite() }
            ?.let { format("%.1f", it) }
            .orEmpty()
        val satellites = fix.satelliteCount
            ?.takeIf { it in 0..99 }?.let { format("%02d", it) }.orEmpty()

        val ggaBody = "GPGGA,$time,${latitude.value},${latitude.hemisphere}," +
            "${longitude.value},${longitude.hemisphere},1,$satellites,," +
            "$altitude,M,,M,,"
        val speedKnots = fix.speedMetersPerSecond
            ?.takeIf { it.isFinite() && it >= 0 }
            ?.let { format("%.2f", it * KNOTS_PER_METER_PER_SECOND) }
            .orEmpty()
        val course = fix.bearingDegrees
            ?.takeIf { it.isFinite() }
            ?.let { format("%.2f", it) }
            .orEmpty()
        val rmcBody = "GPRMC,$time,A,${latitude.value},${latitude.hemisphere}," +
            "${longitude.value},${longitude.hemisphere},$speedKnots,$course,$date,,"

        return buildString {
            if (includeGga) append("$${ggaBody}*${checksum(ggaBody)}\r\n")
            if (includeRmc) append("$${rmcBody}*${checksum(rmcBody)}\r\n")
        }
    }

    private fun degreesToNmea(value: Double, latitude: Boolean): NmeaCoordinate {
        val absolute = abs(value)
        var degrees = absolute.toInt()
        var minutes = (absolute - degrees) * MINUTES_PER_DEGREE
        if (minutes >= MINUTES_PER_DEGREE - MINUTE_ROUNDING_TOLERANCE) {
            degrees += 1
            minutes = 0.0
        }
        val degreeText = if (latitude) format("%02d", degrees) else format("%03d", degrees)
        val hemisphere = if (latitude) {
            if (value >= 0) "N" else "S"
        } else {
            if (value >= 0) "E" else "W"
        }
        return NmeaCoordinate("$degreeText${format("%07.4f", minutes)}", hemisphere)
    }

    private fun checksum(body: String): String {
        var value = 0
        for (character in body) value = value xor character.code
        return format("%02X", value)
    }

    private fun ofEpochMilli(millis: Long): Timestamp = Timestamp(millis)

    private fun format(format: String, vararg arguments: Any): String =
        String.format(Locale.US, format, *arguments)

    private data class NmeaCoordinate(val value: String, val hemisphere: String)

    private class Timestamp(millis: Long) {
        // java.time needs API 26; a proleptic Gregorian calendar in UTC gives the same fields.
        private val fields = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            gregorianChange = Date(Long.MIN_VALUE)
            timeInMillis = millis
        }

        val hour: Int = fields.get(Calendar.HOUR_OF_DAY)
        val minute: Int = fields.get(Calendar.MINUTE)
        val second: Int = fields.get(Calendar.SECOND)
        val day: Int = fields.get(Calendar.DAY_OF_MONTH)
        val month: Int = fields.get(Calendar.MONTH) + 1
        val year: Int = fields.get(Calendar.YEAR)
    }

    private const val KNOTS_PER_METER_PER_SECOND = 1.94384449
    private const val MINUTES_PER_DEGREE = 60.0
    private const val MINUTE_ROUNDING_TOLERANCE = 0.00005
}

/**
 * Accessory side of iAP2 LocationInformation on one link: starts on 0xFFFA, sends the latest fix
 * on every [tick] (about once a second), and stops on 0xFFFC. Subscription state belongs to this
 * iAP2 link: a request received on Bluetooth must never authorize output on the Wi-Fi tunnel.
 */
class Iap2LocationReporter(
    private val provider: Iap2LocationProvider?,
    private val onProgress: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var active = false
    private var sentLogged = false
    private var lastAttemptNanos = 0L

    /** Handles 0xFFFA/0xFFFC; returns false for any other message. */
    fun handle(frame: Iap2Frame, send: (Iap2Frame) -> Unit): Boolean = when (frame.messageId) {
        Iap2LocationMessages.START_LOCATION_INFORMATION -> {
            val components = Iap2LocationMessages.requestedComponents(frame)
            onProgress("iap2 rx=0xfffa start-location-information components=$components")
            provider?.onRequested(components)
            start(send)
            true
        }
        Iap2LocationMessages.STOP_LOCATION_INFORMATION -> {
            onProgress("iap2 rx=0xfffc stop-location-information")
            active = false
            sentLogged = false
            provider?.stop()
            true
        }
        else -> false
    }

    /** Sends the latest fix once a second while this link has an active request. */
    fun tick(send: (Iap2Frame) -> Unit) {
        if (active && sinceAttemptMillis() >= POLL_INTERVAL_MILLIS) sendLatest(send)
    }

    /** Wakes the loop when the next requested fix is due. */
    fun pollTimeout(remainingMillis: Long): Long =
        if (active) min(remainingMillis, (POLL_INTERVAL_MILLIS - sinceAttemptMillis()).coerceAtLeast(1))
        else remainingMillis

    private fun sinceAttemptMillis() = (nanoTime() - lastAttemptNanos) / 1_000_000

    private fun start(send: (Iap2Frame) -> Unit) {
        active = startProvider()
        sentLogged = false
        if (active) sendLatest(send)
    }

    private fun startProvider(): Boolean {
        if (provider == null) return false
        return try {
            provider.start().also { started -> if (!started) onProgress("iap2 location provider did not start") }
        } catch (error: Exception) {
            onProgress("iap2 location provider start failed: ${error.message}")
            false
        }
    }

    private fun sendLatest(send: (Iap2Frame) -> Unit) {
        lastAttemptNanos = nanoTime()
        val source = provider ?: return
        val position = readSentence(source::latestNmea)
        val speed = readSentence(source::latestVehicleSpeedNmea)
        if (position == null && speed == null) return
        val announcements = listOfNotNull(position, speed).joinToString("\r\n")
        send(Iap2LocationMessages.locationInformation(announcements))
        if (position != null) source.onSent()
        if (speed != null) source.onVehicleSpeedSent()
        if (!sentLogged) {
            sentLogged = true
            onProgress("iap2 tx=0xfffb location-information")
        }
    }

    private fun readSentence(read: () -> String?): String? = try {
        read()
    } catch (error: Exception) {
        onProgress("iap2 location provider read failed: ${error.javaClass.simpleName}")
        null
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_000L
    }
}

object Iap2LocationMessages {
    const val START_LOCATION_INFORMATION = 0xfffa
    const val LOCATION_INFORMATION = 0xfffb
    const val STOP_LOCATION_INFORMATION = 0xfffc

    const val GGA = 1
    const val RMC = 2

    /** 0xFFFA selector for `$PASCD`; the matching IdentificationInformation flag is 20. */
    const val VEHICLE_SPEED_DATA = 4

    /** The parameter ids of a 0xFFFA request (the sentence types asked for), or none if unreadable. */
    fun requestedComponents(frame: Iap2Frame): Set<Int> =
        runCatching { frame.body().asList().map { it.id }.toSortedSet() }.getOrDefault(emptySet())

    fun locationInformation(nmeaSentence: String): Iap2Frame {
        require(nmeaSentence.isNotEmpty()) { "NMEA sentence must not be empty" }
        return Iap2ControlMessages.locationInformation(nmeaSentence)
    }
}
