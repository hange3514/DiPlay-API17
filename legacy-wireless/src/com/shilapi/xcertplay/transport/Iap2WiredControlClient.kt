package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import java.net.Inet6Address
import java.net.InetAddress

object WiredAvailabilityStartReplyPolicy {
    const val MAX_REPLIES = 3
    fun shouldReply(repliesSent: Int): Boolean = repliesSent in 0 until MAX_REPLIES
    fun permitsStart(wiredAvailable: Boolean?): Boolean = wiredAvailable != false
}

/**
 * The wired LIVI control sequence after a CSM channel is ready:
 * Identification, MFi, power announcement, five update subscriptions, then CarPlay availability.
 *
 * The caller retains ownership of [session]. While [run] is active it is the only receiver and
 * forwards each non-availability CSM frame to [onIncoming]; it neither opens NCM nor implements
 * an AirPlay receiver.
 */
class Iap2WiredControlClient(
    private val session: Iap2Session,
    private val mfi: Iap2MfiAuthenticationClient,
) {
    fun run(
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WiredCarPlayEndpoint,
        availableCurrentMilliAmps: Int,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        locationProvider: Iap2LocationProvider? = null,
        vehicleStatusProvider: VehicleStatusProvider? = null,
        onIncoming: (Iap2Frame) -> Unit = {},
        onProgress: (String) -> Unit = {},
        onSessionStartPreparing: () -> Unit = {},
        onSessionStartFlushed: () -> Unit = {},
        onSessionStartSent: () -> Unit = {},
        startSessionAgain: () -> StartAgain = { StartAgain.DONE },
    ): Iap2WiredControlResult {
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(timeoutMillis == NO_TIMEOUT_MILLIS || timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TIMEOUT_MILLIS"
        }

        val deadlineNanos = Iap2ControlDeadline(timeoutMillis)
        val identified = identification.withVehicleStatusFrom(vehicleStatusProvider)
        if (identified.vehicleStatusEnabled != identification.vehicleStatusEnabled) {
            onProgress("iap2 no battery reading: not declaring an electric vehicle")
        }
        Iap2IdentificationClient(session).identify(
            identified,
            requireRemaining(deadlineNanos),
            onProgress = onProgress,
        )
        onProgress("iap2 identification accepted")
        var stage = Iap2WiredControlStage.IDENTIFIED
        mfi.run(session, requireRemaining(deadlineNanos), onProgress)
        stage = Iap2WiredControlStage.AUTHENTICATED
        onProgress("iap2 authentication accepted")
        deadlineNanos.authenticated()

        send(powerSourceUpdate(availableCurrentMilliAmps), deadlineNanos)
        for (subscription in subscriptions()) send(subscription, deadlineNanos)
        stage = Iap2WiredControlStage.SUBSCRIBED
        onProgress("iap2 power/subscriptions sent")

        var forwardedFrames = 0
        var carPlayStartSessions = 0
        var availabilityStartReplies = 0
        var availabilityFloodReported = false
        var startAgainDecided = false
        val location = Iap2LocationReporter(locationProvider, onProgress)
        val vehicleStatus = Iap2VehicleStatusReporter(vehicleStatusProvider, onProgress)
        try {
            while (true) {
                val remaining = remainingMillis(deadlineNanos)
                if (remaining == 0L) {
                    return Iap2WiredControlResult(Iap2WiredControlTerminal.TIMED_OUT, stage, forwardedFrames, carPlayStartSessions)
                }
                location.tick { send(it, deadlineNanos) }
                vehicleStatus.tick { send(it, deadlineNanos) }
                var startAgainPending = false
                if (!startAgainDecided && stage == Iap2WiredControlStage.CARPLAY_START_SENT) {
                    when (startSessionAgain()) {
                        StartAgain.WAIT -> startAgainPending = true
                        StartAgain.DONE -> startAgainDecided = true
                        StartAgain.SEND -> {
                            startAgainDecided = true
                            // Arm the caller's startup watchdog before entering the USBMUX write.
                            // That write can stall independently of the iPhone's AirPlay response.
                            onSessionStartPreparing()
                            sendStartSession(endpoint, deadlineNanos, onProgress, onSessionStartFlushed)
                            carPlayStartSessions++
                            onProgress(START_SESSION_AGAIN_PROGRESS)
                        }
                    }
                }
                val pollTimeout = vehicleStatus.pollTimeout(location.pollTimeout(remaining)).let {
                    if (startAgainPending) Math.min(it, START_AGAIN_POLL_MILLIS) else it
                }
                val incoming = session.recv(pollTimeout)
                if (incoming == null) {
                    if (session.isClosed) {
                        return Iap2WiredControlResult(
                            Iap2WiredControlTerminal.CHANNEL_CLOSED,
                            stage,
                            forwardedFrames,
                            carPlayStartSessions,
                        )
                    }
                    if (remainingMillis(deadlineNanos) == 0L) {
                        return Iap2WiredControlResult(
                            Iap2WiredControlTerminal.TIMED_OUT,
                            stage,
                            forwardedFrames,
                            carPlayStartSessions,
                        )
                    }
                    continue
                }

                when (incoming.messageId) {
                    CARPLAY_AVAILABILITY -> {
                        val wiredAvailable = wiredAvailability(incoming.payload)
                        onProgress(carPlayAvailabilitySummary(incoming.payload))
                        if (!WiredAvailabilityStartReplyPolicy.permitsStart(wiredAvailable)) {
                            if (carPlayStartSessions == 0) {
                                onProgress("iap2 4300 wiredAvailable=false result=withheld")
                                return Iap2WiredControlResult(
                                    Iap2WiredControlTerminal.CARPLAY_UNAVAILABLE,
                                    stage,
                                    forwardedFrames,
                                    carPlayStartSessions,
                                )
                            }
                            onProgress("iap2 4300 wiredAvailable=false result=duplicate_withheld_after_start")
                            continue
                        }
                        if (WiredAvailabilityStartReplyPolicy.shouldReply(availabilityStartReplies)) {
                            onProgress("iap2 rx=0x4300 carplay-availability")
                            // Missing/zero-length availability has been observed from iOS and
                            // remains compatible. Only an explicit false suppresses StartSession.
                            // Cap duplicate notifications independently of the measured retry.
                            if (carPlayStartSessions == 0) onSessionStartPreparing()
                            sendStartSession(endpoint, deadlineNanos, onProgress, onSessionStartFlushed)
                            availabilityStartReplies++
                            stage = Iap2WiredControlStage.CARPLAY_START_SENT
                            carPlayStartSessions++
                            onProgress("iap2 tx=0x4301 carplay-start-session")
                            onSessionStartSent()
                        } else if (!availabilityFloodReported) {
                            availabilityFloodReported = true
                            onProgress("iap2 repeated 0x4300 ignored; StartSession replies capped at ${WiredAvailabilityStartReplyPolicy.MAX_REPLIES}")
                        }
                    }

                    Iap2LocationMessages.START_LOCATION_INFORMATION, Iap2LocationMessages.STOP_LOCATION_INFORMATION -> {
                        location.handle(incoming) { send(it, deadlineNanos) }
                    }

                    Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES, Iap2VehicleStatus.STOP_VEHICLE_STATUS_UPDATES -> {
                        vehicleStatus.handle(incoming) { send(it, deadlineNanos) }
                    }

                    else -> {
                        onProgress("iap2 rx=0x${incoming.messageId.toString(16).padStart(4, '0')}")
                        onIncoming(incoming)
                        forwardedFrames++
                    }
                }
            }
        } finally {
            locationProvider?.stop()
        }
    }

    private fun send(frame: Iap2Frame, deadlineNanos: Iap2ControlDeadline) {
        session.send(frame, requireRemaining(deadlineNanos))
    }

    private fun sendStartSession(
        endpoint: Iap2WiredCarPlayEndpoint,
        deadlineNanos: Iap2ControlDeadline,
        onProgress: (String) -> Unit,
        onTransportFlushed: () -> Unit,
    ) {
        val startedAtNanos = System.nanoTime()
        try {
            session.sendAndAwaitTransportFlush(
                carPlayStartSession(endpoint),
                requireRemaining(deadlineNanos),
            )
        } catch (error: Exception) {
            val durationMillis = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND
            onProgress("iap2 tx=0x4301 transport flush failed durationMs=$durationMillis error=${error.javaClass.simpleName}:${error.message.orEmpty().take(200)}")
            throw error
        }
        onTransportFlushed()
        val durationMillis = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND
        onProgress("iap2 tx=0x4301 transport flushed durationMs=$durationMillis")
    }

    enum class StartAgain { WAIT, SEND, DONE }

    companion object {
        const val NO_TIMEOUT_MILLIS = Long.MAX_VALUE
        const val START_SESSION_AGAIN_PROGRESS =
            "iap2 tx=0x4301 carplay-start-session again: no AirPlay connection since the USB network opened"
        private const val START_AGAIN_POLL_MILLIS = 500L
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_RECV_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Exact LIVI wired PowerSourceUpdate encoding: current and the charge-if-powered flag. */
        fun powerSourceUpdate(availableCurrentMilliAmps: Int): Iap2Frame =
            Iap2ControlMessages.powerSourceUpdate(availableCurrentMilliAmps, charging = true)

        /** Exact five subscription requests emitted by LIVI's wired bring-up. */
        fun subscriptions(): List<Iap2Frame> = Iap2ControlMessages.subscriptions()

        /** Builds the wired-only CarPlayStartSession message; no NCM or AirPlay socket is opened. */
        fun carPlayStartSession(endpoint: Iap2WiredCarPlayEndpoint): Iap2Frame =
            Iap2CarPlayMessages.startSession(
                wiredIpv6Addresses = endpoint.ipv6Addresses,
                airPlayPort = endpoint.airPlayPort,
                deviceIdentifier = endpoint.deviceIdentifier,
                publicKey = endpoint.publicKey,
                sourceVersion = endpoint.sourceVersion,
            )

        fun carPlayAvailabilitySummary(payload: ByteArray): String {
            return try {
                val availability = Iap2CarPlayMessages.availability(payload).wired
                val available = availability?.available
                val transport = availability?.identifier
                "iap2 4300 wiredAvailable=$available usbTransport=${transport ?: "none"}"
            } catch (error: Exception) {
                "iap2 4300 decode failed: ${error.message}"
            }
        }

        /** Null means the phone omitted the optional wired availability field. */
        fun wiredAvailability(payload: ByteArray): Boolean? = try {
            Iap2CarPlayMessages.availability(payload).wired?.available
        } catch (_: Exception) {
            null
        }

        private fun requireRemaining(deadlineNanos: Iap2ControlDeadline): Long = remainingMillis(deadlineNanos).also {
            if (it == 0L) throw IphoneUsbException.TimedOut("Timed out during wired iAP2 control bring-up")
        }

        private fun remainingMillis(deadlineNanos: Iap2ControlDeadline): Long = deadlineNanos.remainingMillis()

    }
}

/** A configured wired AirPlay endpoint; configuration does not claim that either service is live. */
class Iap2WiredCarPlayEndpoint(
    ipv6Addresses: List<String>,
    val airPlayPort: Int,
    val publicKey: String,
    val sourceVersion: String,
    val deviceIdentifier: String? = null,
) {
    /** A stable copy so a caller cannot mutate a validated endpoint before it is encoded. */
    val ipv6Addresses: List<String> = ipv6Addresses.toList()

    init {
        require(ipv6Addresses.isNotEmpty()) { "At least one wired IPv6 address is required" }
        require(ipv6Addresses.all(::isIpv6Literal)) { "Every wired address must be an IPv6 text literal" }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
        deviceIdentifier?.let {
            require(it.isNotEmpty()) { "deviceIdentifier is optional, but must not be empty when provided" }
            require('\u0000' !in it) { "deviceIdentifier must not contain U+0000" }
        }
    }

    private companion object {
        fun isIpv6Literal(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                InetAddress.getByName(value) is Inet6Address
            } catch (_: Exception) {
                false
            }
        }
    }
}

enum class Iap2WiredControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    CARPLAY_START_SENT,
}

enum class Iap2WiredControlTerminal { CHANNEL_CLOSED, TIMED_OUT, CARPLAY_UNAVAILABLE }

/** End state of the control loop only; it is not evidence of NCM or AirPlay availability. */
data class Iap2WiredControlResult(
    val terminal: Iap2WiredControlTerminal,
    val stage: Iap2WiredControlStage,
    val forwardedFrames: Int,
    val carPlayStartSessionsSent: Int,
)
