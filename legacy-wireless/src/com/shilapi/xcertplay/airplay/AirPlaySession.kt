package com.shilapi.xcertplay.airplay

import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.compat.Base64Compat
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class AirPlayRuntimeEvidence(
    val validatedTimingReplies: Long,
    val lastTimingReplyNs: Long,
    val lastPrimaryVideoNs: Long,
    val lastPrimaryAudioNs: Long,
    val lastControlTrafficNs: Long,
    val lastEventTrafficNs: Long,
    val transportIoFailure: Boolean,
    val timingProbeAttempts: Long = 0L,
    val lastTimingProbeNs: Long = 0L,
) {
    val lastInboundTrafficNs: Long get() =
        maxOf(lastPrimaryVideoNs, lastPrimaryAudioNs, lastControlTrafficNs, lastEventTrafficNs)
}

data class AirPlayDeviceInfo(
    val name: String,
    val deviceId: String,
    val wifiMac: String,
    val model: String,
)

/** Bounded evidence for a request whose AirPlay handler threw before producing a response. */
data class AirPlayFailureEvidence(
    val method: String,
    val path: String,
    val pathTruncated: Boolean,
    val protocol: String,
    val headersJson: String,
    val headersTruncated: Boolean,
    val bodyBase64: String,
    val bodyBytes: Int,
    val bodyCapturedBytes: Int,
    val bodyTruncated: Boolean,
    val errorRaw: String,
    val errorTruncated: Boolean,
    val stackTraceRaw: String,
    val stackTraceTruncated: Boolean,
    val peerName: String,
    val peerDeviceId: String,
    val peerWifiMac: String,
    val peerModel: String,
    val peerOsVersion: String,
    val peerSourceVersion: String,
)

/** Session lifecycle and command callbacks for the driver/UI layer. */
interface AirPlaySessionListener {
    fun onTcpAccepted(event: AirPlayTcpAccepted) {}
    fun claimProjection(session: AirPlaySession): Boolean = true
    fun onSessionActive(session: AirPlaySession) {}
    fun onSessionEnded(session: AirPlaySession) {}
    fun onVideoFrameRendered(session: AirPlaySession) {}
    fun onTransportError(message: String) {}
    fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {}
    fun onProtocolFailure(session: AirPlaySession, evidence: AirPlayFailureEvidence) {}
    fun onHostUiRequested(session: AirPlaySession) {}
    fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {}
    /** Video in car: a playback message on a remote control session (X-Apple-StreamID). */
    fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {}
    /** Video in car: requestUI "videoplayback:", the iPhone asks the car to show its video player. */
    fun onVideoPlaybackUiRequested(session: AirPlaySession) {}
    fun onDebugLog(message: String) {}
}

/** Stream transport seam; media decode/render is supplied by a later layer. */
interface AirPlayMediaHandler {
    fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? = null
    fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    /** CarPlay's main buffered audio (type 103): the SETUP response, or null to decline it. */
    fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    /**
     * SETRATE, SETRATEANCHORTIME, GETANCHOR and FLUSHBUFFERED for the buffered stream: the response
     * plist (the anchor), or null for an empty 200.
     */
    fun onBufferedAudioControl(session: AirPlaySession, method: String, body: Map<String, Any?>): Map<String, Any?>? = null
    fun onFeedback(session: AirPlaySession): Map<String, Any?>? = null
    fun onTeardown(session: AirPlaySession, type: Int) {}
    fun onSessionClosed(session: AirPlaySession) {}
    fun setIapTunnelHandler(handler: ((AirPlaySession, BlockingDuplexByteStream) -> Boolean)?) {}
    fun onSetupResponseSent(session: AirPlaySession) {}
    fun onSetupResponseFailed(session: AirPlaySession) {}
}

/**
 * One CarPlay AirPlay control connection.
 *
 * It owns the RTSP framing, pairing/auth/info routing, the encrypted event channel used for HID
 * input, stream SETUP/TEARDOWN routing, the NTP timing exchange, and the keep-alive socket.
 */
class AirPlaySession(
    private val socket: Socket,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val mfi: MfiAuthenticator?,
    private val listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    private val recoveryControllerId: String? = null,
    private val controlBootstrapTimeoutMs: Int = 0,
) : Closeable {
    internal val pairSetup = PairSetup(identity, pairings)
    internal val pairVerify = PairVerify(identity, pairings)
    internal var cipher: ControlCipher? = null
    internal var encBuf = ByteArray(0)
    internal var deviceBtMac = ""
    @Volatile private var peerName = ""
    @Volatile private var peerWifiMac = ""
    @Volatile private var peerModel = ""
    @Volatile private var peerOsVersion = ""
    @Volatile private var peerSourceVersion = ""
    internal val activeStreams = linkedSetOf<Int>()

    /** Counts the iPhone's cluster stream setups; 0 while no cluster stream is up. */
    @Volatile var clusterStream = 0
        private set
    private var clusterStreamSetups = 0
    @Volatile private var mainScreenToken: Any? = null

    private val closed = AtomicBoolean(false)
    @Volatile var protocolFailure: String? = null
        private set
    internal val isClosed: Boolean get() = closed.get()
    private val notified = AtomicBoolean(false)
    private val failureEvidenceLogged = AtomicBoolean(false)
    private val endpointLock = Any()
    private val startupStages = mutableSetOf<StartupStage>()
    private var eventServer: ServerSocket? = null
    @Volatile private var eventSocket: Socket? = null
    @Volatile private var eventCipher: ControlCipher? = null
    private var eventCseq = 0
    private var pendingNightMode: Boolean? = null
    private val firstTouchSendLogged = AtomicBoolean(false)
    private val touchSendFailureLogged = AtomicBoolean(false)
    private val ntp = NtpClock()
    @Volatile private var lastPrimaryVideoNs = 0L
    private val lastPrimaryAudioNs = AtomicLong()
    @Volatile private var lastControlTrafficNs = 0L
    @Volatile private var lastEventTrafficNs = 0L
    @Volatile private var transportIoFailure = false
    @Volatile private var fullTeardown = false
    private var keepAliveSocket: DatagramSocket? = null
    private var keepAliveThread: Thread? = null
    private val eventWriteLock = Any()
    private val eventThreads = CopyOnWriteArrayList<Thread>()
    @Volatile private var siriSetupEvidence = SiriSetupEvidence()
    private val siriRuntimeEvidence = SiriSessionRuntimeEvidence()

    private data class SiriSetupEvidence(
        val endpointSetups: Int = 0,
        val verifiedEndpointSetups: Int = 0,
        val featureLists: Int = 0,
        val enhancedSiriTokenSeen: Boolean = false,
        val enhancedSiriInReply: Boolean = false,
        val type100: Int = 0,
        val type101: Int = 0,
        val type102: Int = 0,
        val type103: Int = 0,
        val speechRecognition: Int = 0,
        val mainMicRequested: Int = 0,
        val explicitOtherInput: Int = 0,
        val auxInLabelSeen: Boolean = false,
        val auxOutLabelSeen: Boolean = false,
    )

    /** Observed SETUP metadata only. Missing tokens do not establish peer support or entitlement. */
    internal fun siriCapabilitySummary(): String = siriSetupEvidence.run {
        "endpointSetups=$endpointSetups verifiedEndpointSetups=$verifiedEndpointSetups featureLists=$featureLists " +
            "enhancedSiriTokenSeen=$enhancedSiriTokenSeen enhancedSiriInReply=$enhancedSiriInReply audio100=$type100 audio101=$type101 " +
            "audio102=$type102 audio103=$type103 speechRecognition=$speechRecognition " +
            "mainMicRequested=$mainMicRequested explicitOtherInput=$explicitOtherInput " +
            "auxInLabelSeen=$auxInLabelSeen auxOutLabelSeen=$auxOutLabelSeen ${siriRuntimeEvidence.summary()}"
    }

    private fun recordSiriSetupFeatures(proposed: List<*>?, enabled: List<String>) {
        val previous = siriSetupEvidence
        siriSetupEvidence = previous.copy(
            endpointSetups = setupEvidenceCount(previous.endpointSetups),
            verifiedEndpointSetups = setupEvidenceCount(previous.verifiedEndpointSetups, pairVerify.isVerified),
            featureLists = setupEvidenceCount(previous.featureLists, proposed != null),
            enhancedSiriTokenSeen = previous.enhancedSiriTokenSeen || proposed.orEmpty().asSequence().take(64).any {
                (it as? String)?.equals("enhancedSiri", ignoreCase = true) == true
            },
            enhancedSiriInReply = previous.enhancedSiriInReply || "enhancedSiri" in enabled,
        )
    }

    private fun recordSiriStreamSetup(type: Int, stream: Map<String, Any?>) {
        val audioLabel = stream["audioType"] as? String
        val speech = audioLabel?.equals("speechRecognition", ignoreCase = true) == true
        // A literal label is evidence, but numeric stream types are not guessed to be Aux In/Out.
        val auxIn = audioLabel?.equals("auxIn", ignoreCase = true) == true
        val auxOut = audioLabel?.equals("auxOut", ignoreCase = true) == true
        val explicitInput = when (val input = stream["input"]) {
            is Boolean -> input
            is Number -> input.toLong() != 0L
            else -> false
        }
        val mainMic = type == STREAM_TYPE_MAIN_AUDIO && (long(stream["dataPort"]) ?: 0L) in 1L..65535L &&
            local.headunit.diplay.AppMicrophoneRequestPolicy.wantsMicrophone(audioLabel?.lowercase() ?: "default", stream)
        val otherInput = type != STREAM_TYPE_MAIN_AUDIO && explicitInput
        if (type !in STREAM_TYPE_MAIN_AUDIO..STREAM_TYPE_MAIN_BUFFERED_AUDIO && !speech && !auxIn && !auxOut && !otherInput) return
        val previous = siriSetupEvidence
        siriSetupEvidence = previous.copy(
            type100 = setupEvidenceCount(previous.type100, type == STREAM_TYPE_MAIN_AUDIO),
            type101 = setupEvidenceCount(previous.type101, type == STREAM_TYPE_ALT_AUDIO),
            type102 = setupEvidenceCount(previous.type102, type == STREAM_TYPE_MAIN_HIGH_AUDIO),
            type103 = setupEvidenceCount(previous.type103, type == STREAM_TYPE_MAIN_BUFFERED_AUDIO),
            speechRecognition = setupEvidenceCount(previous.speechRecognition, speech),
            mainMicRequested = setupEvidenceCount(previous.mainMicRequested, mainMic),
            explicitOtherInput = setupEvidenceCount(previous.explicitOtherInput, otherInput),
            auxInLabelSeen = previous.auxInLabelSeen || auxIn,
            auxOutLabelSeen = previous.auxOutLabelSeen || auxOut,
        )
    }

    private fun setupEvidenceCount(count: Int, seen: Boolean = true): Int =
        if (seen && count < 255) count + 1 else count

    val host: String = socket.inetAddress?.hostAddress ?: ""
    val localAddress: InetAddress? = socket.localAddress
    private val peerAddress: InetAddress? = socket.inetAddress
    internal val remoteAddress: InetAddress?
        get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address
    val controllerId: String? get() = pairVerify.verifiedControllerId
    val sharedSecret: ByteArray? get() = pairVerify.shared?.copyOf()
    val videoInCar: Boolean get() = config.videoInCar
    private val videoPlaybackAvailability = VideoPlaybackAvailability { allowed ->
        sendCommand(
            linkedMapOf(
                "type" to "setVideoPlaybackAllowed",
                "params" to linkedMapOf("videoPlaybackAllowed" to allowed),
            ),
        )
    }

    fun syncedNtp(): BigInteger = ntp.syncedNtp()

    internal fun runtimeEvidence(): AirPlayRuntimeEvidence {
        val timing = ntp.timingEvidence()
        return AirPlayRuntimeEvidence(
            timing.validatedReplies, timing.lastReplyNs, lastPrimaryVideoNs,
            lastPrimaryAudioNs.get(), lastControlTrafficNs, lastEventTrafficNs, transportIoFailure,
            timing.probeAttempts, timing.lastProbeNs,
        )
    }

    internal fun prepareLinkRecovery(): Boolean = !closed.get() && ntp.prepareLinkRecovery()

    internal fun primaryVideoReceived() {
        lastPrimaryVideoNs = System.nanoTime()
    }

    internal fun primaryAudioReceived() {
        val now = System.nanoTime()
        while (true) {
            val previous = lastPrimaryAudioNs.get()
            if ((previous != 0L && now - previous <= 0L) || lastPrimaryAudioNs.compareAndSet(previous, now)) return
        }
    }

    internal fun logDebug(message: String) = debugLog(message)

    internal val isClosingNormally: Boolean get() = fullTeardown

    internal fun interruptTransportForRecovery(): Boolean {
        if (closed.get() || fullTeardown) return false
        transportIoFailure = true
        // The existing control-reader teardown will release media before notifying the service.
        safeClose(socket)
        return true
    }

    private val firstVideoAtMs = AtomicLong(-1L)
    internal val firstVideoSubmittedAtMs: Long get() = firstVideoAtMs.get()
    internal val hasRenderedVideo: Boolean get() = firstVideoSubmittedAtMs >= 0L

    internal fun videoFrameRendered() {
        if (!closed.get() && firstVideoAtMs.compareAndSet(-1L, SystemClock.elapsedRealtime())) {
            reportStartup(StartupStage.FIRST_FRAME_RENDERED)
            listener.onVideoFrameRendered(this)
        }
    }

    internal fun logTrace(message: String) = trace(message)

    fun start() {
        Thread(::runControl, "airplay-control").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mainScreenToken = null
        safeClose(socket)
        // Unblock HID/keyframe writers before media teardown waits for renderer workers.
        teardown()
        clearClusterContent()
        try {
            media.onSessionClosed(this)
        } catch (error: Exception) {
            Log.w(TAG, "airplay media stream teardown failed", error)
        }
        if (notified.compareAndSet(false, true)) listener.onSessionEnded(this)
    }

    /** Stable identity of the usable primary screen; replaced on SETUP, absent after teardown/close. */
    internal fun mainScreenSessionToken(): Any? = if (closed.get()) null else mainScreenToken

    /**
     * Asks the iPhone to draw CarPlay's cluster UI on the alt screen (showUI with the display's URL,
     * then a keyframe) or to stop drawing it (stopUI). The stream stays up either way; CarKit handles
     * both as car-initiated commands for a screen UUID.
     */
    fun setClusterUiShown(shown: Boolean): Boolean {
        synchronized(clusterCommandLock) {
            if (closed.get() || clusterStream <= 0) return false
            val content = synchronized(clusterContentLock) { clusterContent } ?: return false
            if (!shown) {
                val sent = sendCommand(mapOf("type" to "stopUI",
                    "params" to mapOf("uuid" to AirPlayInfoPlist.ALT_UUID)))
                return synchronized(clusterContentLock) {
                    val current = clusterContent ?: return false
                    if (closed.get() || current.stream != content.stream) {
                        if (sent) clusterContent = current.copy(displayed = null, shown = false, token = Any())
                        return false
                    }
                    if (sent) clusterContent = current.copy(shown = false)
                    sent
                }
            }
            return showClusterUrl(content, content.requested ?: return false)
        }
    }

    private data class ClusterContent(val stream: Int, val requested: String?, val displayed: String?,
        val shown: Boolean = true, val token: Any = Any())
    private val clusterContentLock = Any()
    private val clusterCommandLock = Any()
    @Volatile private var clusterContent: ClusterContent? = null
    private var requestedClusterUrl = config.cluster?.initialUrl // guarded by clusterContentLock

    /** Last delivered content for this stream. Pending selections never grant wheel zoom eligibility. */
    fun clusterUrl(): String? = clusterContentRoute()?.second

    /** One immutable snapshot keeps content and generation checks consistent without locking keys. */
    internal fun clusterContentRoute(): Triple<Int, String?, Any>? {
        val content = clusterContent ?: return null
        if (closed.get() || content.stream != clusterStream) return null
        return Triple(content.stream, content.displayed, content.token)
    }

    /**
     * Switches the dashboard to another of the iPhone's `altScreenURLs` without reconnecting: `showUI`
     * with that URL and a keyframe (on a Tang this switches map, turn card and both at once). With [send]
     * false (the map is paused) the URL is only kept for the next `showUI`. False without a cluster stream.
     */
    fun setClusterUrl(url: String, send: Boolean): Boolean {
        return synchronized(clusterCommandLock) {
            val content = synchronized(clusterContentLock) {
                val current = clusterContent ?: return false
                if (closed.get() || current.stream != clusterStream || config.cluster == null) return false
                requestedClusterUrl = url
                current.copy(requested = url).also { clusterContent = it }
            }
            if (!send) true else showClusterUrl(content, url)
        }
    }

    /** Seeds a retained controller selection even when this phone has not set up its cluster yet. */
    internal fun restoreClusterUrl(url: String, send: Boolean): Boolean = synchronized(clusterCommandLock) {
        val content = synchronized(clusterContentLock) {
            if (closed.get() || config.cluster == null) return false
            requestedClusterUrl = url
            clusterContent?.copy(requested = url)?.also { clusterContent = it }
        }
        if (content == null || !send || (content.shown && content.displayed == url)) true
        else showClusterUrl(content, url)
    }

    private fun showClusterUrl(content: ClusterContent, url: String): Boolean {
        if (closed.get() || clusterStream != content.stream) return false
        val uuid = AirPlayInfoPlist.ALT_UUID
        val uiSent = sendCommand(mapOf("type" to "showUI", "params" to mapOf("uuid" to uuid, "url" to url)))
        val sent = uiSent && clusterStream == content.stream && !closed.get() &&
            sendCommand(mapOf("type" to "forceKeyFrame", "params" to mapOf("uuid" to uuid)))
        return synchronized(clusterContentLock) {
            val current = clusterContent ?: return false
            if (closed.get() || current.stream != content.stream || clusterStream != content.stream) {
                // A command already in flight can outlast re-SETUP. Its displayed result is unknown.
                if (uiSent) clusterContent = current.copy(displayed = null, token = Any())
                return false
            }
            if (uiSent) clusterContent = current.copy(displayed = if (sent) url else null,
                shown = true, token = Any())
            sent
        }
    }

    private fun beginClusterContent() = synchronized(clusterContentLock) {
        val stream = ++clusterStreamSetups
        val initial = config.cluster?.initialUrl
        clusterContent = ClusterContent(stream, requestedClusterUrl, initial)
        clusterStream = stream
    }

    private fun clearClusterContent() = synchronized(clusterContentLock) {
        clusterStream = 0
        clusterContent = null
    }

    // Re-SETUP starts at initialURL. Reapply the saved live selection only after SETUP was answered,
    // or the event channel becomes ready, without claiming it was displayed before delivery.
    private fun reapplyClusterContent() {
        synchronized(clusterCommandLock) {
            val content = clusterContent ?: return
            val requested = content.requested ?: return
            if (content.shown && requested != content.displayed) showClusterUrl(content, requested)
        }
    }

    /**
     * Zooms CarPlay's dashboard map one step, as a car's own zoom controls do: the car command
     * changeMapZoomLevel for the cluster screen's UUID. Seen with Apple Maps on a Tang: zoomDirection 0
     * zooms in, 1 zooms out. Sent only while the cluster stream is up.
     */
    fun changeMapZoomLevel(zoomIn: Boolean): Boolean = clusterStream > 0 && sendCommand(
        linkedMapOf(
            "type" to "changeMapZoomLevel",
            "params" to linkedMapOf(
                "uuid" to AirPlayInfoPlist.ALT_UUID,
                "zoomDirection" to if (zoomIn) ZOOM_DIRECTION_IN else ZOOM_DIRECTION_OUT,
            ),
        ),
    )

    /** Moves CarPlay on the main screen to one of its declared view areas, without reconnecting. */
    fun showViewArea(index: Int): Boolean =
        sendCommand(AirPlayInfoPlist.viewAreaCommand(index, config.main.viewAreas?.size ?: 1))

    fun sendCommand(command: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(command)
    }

    /** Called by a separate recovery worker because a socket write may wait for the network. */
    internal fun requestRecoveryKeyFrame(missingSinceNs: Long): Boolean {
        val token = mainScreenSessionToken() ?: return false
        return synchronized(eventWriteLock) {
            val lastFrameNs = lastPrimaryVideoNs
            if (mainScreenSessionToken() !== token ||
                (lastFrameNs != 0L && (missingSinceNs == 0L || lastFrameNs - missingSinceNs > 0L))
            ) return false
            sendCommandLocked(mapOf("type" to "forceKeyFrame"))
        }
    }

    /**
     * A message on a video in car remote control session, framed like the iPhone's own: POST /command
     * with X-Apple-StreamID and {params: {data: bplist(message)}}.
     */
    fun sendRemoteControlMessage(streamId: Long, message: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(
            linkedMapOf("params" to linkedMapOf("data" to BplistCodec.encode(message))),
            "X-Apple-StreamID: $streamId\r\n",
        )
    }

    /**
     * Video in car: retains whether video may play now and sends the latest value once SETUP has
     * enabled video and the event channel is ready. An iPhone without the feature gets no command.
     */
    internal fun setVideoPlaybackAllowed(allowed: Boolean): VideoPlaybackDelivery =
        videoPlaybackAvailability.setDesired(allowed)

    private fun sendCommandLocked(command: Map<String, Any?>, extraHeaders: String = ""): Boolean {
        val socket = eventSocket ?: return false
        val cipher = eventCipher ?: return false
        eventCseq++
        val body = BplistCodec.encode(command)
        val head = "POST /command RTSP/1.0\r\n" + extraHeaders +
            "Content-Type: $PLIST_CONTENT_TYPE\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "CSeq: $eventCseq\r\n\r\n"
        if (WIRE_TRACE) trace("airplay event tx headers=$head bodyHex=${body.toHex()}")
        return try {
            val bytes = cipher.encrypt(head.toByteArray(Charsets.US_ASCII) + body)
            val output = socket.getOutputStream()
            output.write(bytes)
            output.flush()
            true
        } catch (error: Exception) {
            Log.w(TAG, "airplay event command failed type=${command["type"]}", error)
            if (error is IOException) markTransportIoFailure()
            // An encrypted write cannot be retried. Wake the reader to end the session
            // without tearing down media or taking service locks under eventWriteLock.
            safeClose(socket)
            false
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        val scaled = contacts.map {
            it.copy(x = it.x * config.main.widthPixels, y = it.y * config.main.heightPixels)
        }
        val report = AirPlayHid.touchReport(scaled)
        val sendStartNs = System.nanoTime()
        val sent = sendHidReport(AirPlayHid.TOUCH_HID_UID, report)
        if (sent) com.shilapi.xcertplay.media.TouchLatencyProbe.onTouchSent(sendStartNs, System.nanoTime() - sendStartNs)
        if (sent && firstTouchSendLogged.compareAndSet(false, true)) {
            val first = scaled.firstOrNull()
            Log.i(
                TAG,
                "airplay touch report sent contacts=${scaled.size} first=" +
                    "(${first?.x},${first?.y},down=${first?.down}) report=${report.toHexString()}",
            )
        } else if (!sent && touchSendFailureLogged.compareAndSet(false, true)) {
            Log.w(TAG, "airplay touch dropped: event channel is not ready")
        }
        return sent
    }

    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true) {
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(state))
        if (momentary) sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState()))
    }

    fun sendKnobSelect(down: Boolean) =
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState(select = down)))

    fun sendMedia(index: Int): Boolean = synchronized(eventWriteLock) {
        val down = sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(index))
        val up = sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(0))
        down && up
    }

    fun sendTelephony(index: Int): Boolean = synchronized(eventWriteLock) {
        val down = sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(index))
        val up = sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(0))
        down && up
    }

    internal fun hasReceivedControl(): Boolean = lastControlTrafficNs != 0L

    internal fun canSendInput(): Boolean =
        !closed.get() && eventSocket?.isClosed == false && eventCipher != null

    fun invokeSiri(): Boolean = synchronized(eventWriteLock) {
        siriRuntimeEvidence.beginButton(eventCseq + 1)
        val pressed = sendCommandLocked(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 2)))
        val released = sendCommandLocked(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 3)))
        (pressed && released).also(siriRuntimeEvidence::finishButtonWrite)
    }

    fun sendIapMessage(data: ByteArray, timeoutMillis: Long = 0L): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val command = linkedMapOf<String, Any?>(
            "type" to "iAPSendMessage",
            "params" to linkedMapOf("data" to data),
        )
        if (timeoutMillis == 0L) return sendCommand(command)

        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        while (true) {
            val sent = synchronized(eventWriteLock) {
                if (eventSocket == null || eventCipher == null) {
                    null
                } else {
                    sendCommandLocked(command)
                }
            }
            if (sent != null) return sent
            if (closed.get()) return false

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) return false
            val sleepMillis = minOf(
                EVENT_READY_POLL_MILLIS,
                (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
            ).coerceAtLeast(1)
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    fun setNightMode(night: Boolean): Boolean {
        val written = synchronized(eventWriteLock) {
            pendingNightMode = night
            sendPendingNightModeLocked()
        }
        written?.let(::recordNightModeWritten)
        return written != null
    }

    private fun sendPendingNightModeLocked(): Boolean? {
        val night = pendingNightMode ?: return null
        val sent = sendCommandLocked(
            linkedMapOf("type" to "setNightMode", "params" to linkedMapOf("nightMode" to night)),
        )
        if (!sent) return null
        pendingNightMode = null
        return night
    }

    private fun recordNightModeWritten(night: Boolean) {
        val state = if (night) "night" else "day"
        debugLog("airplay vehicle night_mode_written state=$state")
    }

    private fun sendHidReport(uid: Int, report: ByteArray): Boolean =
        sendCommand(
            linkedMapOf(
                "type" to "hidSendReport",
                "uuid" to uid.toString(16),
                "hidReport" to report,
            ),
        )

    private fun runControl() {
        var accumulated = ByteArray(0)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var closeReason = "session closed"
        var recordNotified = false
        val bootstrapDeadlineNs = if (controlBootstrapTimeoutMs > 0) {
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(controlBootstrapTimeoutMs.toLong())
        } else null
        try {
            com.shilapi.xcertplay.network.TcpLiveness.configure(socket) { debugLog(it) }
            val input = controlSocketIo { BufferedInputStream(socket.getInputStream()) }
            val output = controlSocketIo { BufferedOutputStream(socket.getOutputStream()) }
            reportStartup(StartupStage.CONTROL_STREAMS_READY)
            while (!closed.get()) {
                if (!recordNotified && bootstrapDeadlineNs != null) {
                    val remainingNs = bootstrapDeadlineNs - System.nanoTime()
                    if (remainingNs <= 0L) throw SocketTimeoutException("AirPlay negotiation did not reach RECORD")
                    socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceAtLeast(1L).toInt()
                }
                val count = controlSocketIo { input.read(buffer) }
                if (count < 0) {
                    closeReason = "peer EOF"
                    if (recordNotified) markTransportIoFailure()
                    break
                }
                var plaintext = buffer.copyOf(count)
                val activeCipher = cipher
                if (activeCipher != null) {
                    encBuf += plaintext
                    val decrypted = try {
                        activeCipher.decrypt(encBuf)
                    } catch (error: Exception) {
                        closeReason = "control decrypt failed: ${error.message ?: error.javaClass.simpleName}"
                        Log.e(TAG, "airplay $closeReason encrypted=${encBuf.size}", error)
                        break
                    }
                    encBuf = decrypted.rest
                    plaintext = decrypted.data
                }
                accumulated += plaintext
                val parsed = RtspMessage.parseMessages(accumulated)
                accumulated = parsed.rest
                if (parsed.messages.isNotEmpty()) lastControlTrafficNs = System.nanoTime()
                for (request in parsed.messages) {
                    if (closed.get()) return
                    reportStartup(StartupStage.FIRST_REQUEST)
                    val cseq = request.headers["cseq"] ?: "-"
                    val path = request.path.lowercase()
                    val showInDebugOverlay =
                        !path.endsWith("/feedback") &&
                            !(request.method == "POST" && path.endsWith("/command"))
                    debugLog(
                        "airplay rx ${request.method} ${request.path} cseq=$cseq body=${request.body.size}",
                        showInDebugOverlay,
                    )
                    if (showInDebugOverlay) debugLog(
                        AirPlayControlDiagnostics.request(request.method, request.path, request.body.size),
                        false,
                    )
                    if (WIRE_TRACE) trace(
                        "airplay control rx headers=${request.headers} " +
                            "bodyHex=${request.body.toHex()}",
                    )
                    val response = try {
                        handle(request)
                    } catch (error: Exception) {
                        if (closed.get()) return
                        protocolFailure = "${request.method}: ${error.javaClass.simpleName}: ${error.message?.take(160)}"
                        if (failureEvidenceLogged.compareAndSet(false, true)) {
                            try {
                                listener.onProtocolFailure(this, failureEvidence(request, error))
                            } catch (captureError: Throwable) {
                                Log.w(TAG, "airplay failure evidence callback failed", captureError)
                            }
                        }
                        debugLog(AirPlayControlDiagnostics.failure(request.method, request.path, error), false)
                        debugLog("airplay handler failed method=${request.method} error=${error.javaClass.simpleName}: ${error.message} at=${error.stackTrace.take(4).joinToString()}")
                        Log.e(
                            TAG,
                            "airplay handler failed ${request.method} ${request.path} cseq=$cseq",
                            error,
                        )
                        RtspMessage.Response(status = 500)
                    }
                    debugLog(
                        "airplay tx status=${response.status ?: 200} cseq=$cseq body=${response.body.size}",
                        showInDebugOverlay,
                    )
                    if (showInDebugOverlay) debugLog(
                        "airplay control response status=${response.status ?: 200} contentBytes=${response.body.size}",
                        false,
                    )
                    val wire = RtspMessage.buildResponse(request, response)
                    if (WIRE_TRACE) trace("airplay control tx wireHex=${wire.toHex()}")
                    val successful = (response.status ?: 200) in 200..299
                    if (successful && (request.method == "SETUP" || request.method == "RECORD")) fullTeardown = false
                    val wireResponse = cipher?.encrypt(wire) ?: wire
                    controlSocketIo { output.write(wireResponse) }
                    if (request.method == "SETUP") {
                        // A later SETUP can arrive in the same read. Publish or discard this
                        // request's prepared tunnel before processing the next request.
                        controlSocketIo { output.flush() }
                        if (!closed.get()) notifySetupResponse(successful)
                    } else if (!recordNotified && request.method == "RECORD" && successful) {
                        controlSocketIo { output.flush() }
                        if (!closed.get()) {
                            recordNotified = true
                            if (bootstrapDeadlineNs != null) socket.soTimeout = 0
                            listener.onSessionActive(this)
                        }
                    }
                    if (cipher == null && pairVerify.controlKeys != null) {
                        val keys = pairVerify.controlKeys!!
                        cipher = ControlCipher(keys.readKey, keys.writeKey)
                        debugLog("airplay control encryption enabled")
                    }
                }
                controlSocketIo { output.flush() }
            }
        } catch (error: Exception) {
            closeReason = "control I/O failed: ${error.message ?: error.javaClass.simpleName}"
            if (!closed.get()) Log.e(TAG, "airplay $closeReason", error)
        } finally {
            debugLog("airplay control closing reason=$closeReason activeStreams=$activeStreams")
            close()
        }
    }

    private inline fun <T> controlSocketIo(operation: () -> T): T = try {
        operation()
    } catch (error: IOException) {
        markTransportIoFailure()
        throw error
    }

    private fun markTransportIoFailure() {
        if (!closed.get() && !fullTeardown) transportIoFailure = true
    }

    private fun handle(request: RtspMessage.Request): RtspMessage.Response {
        val path = request.path.lowercase()
        if (recoveryControllerId != null) {
            // A recovery socket must verify the existing link key, not replace it with a new pairing.
            if (path.endsWith("/pair-setup")) return RtspMessage.Response(status = 403)
            val preflight = request.method == "OPTIONS" ||
                ((request.method == "GET" || request.method == "POST") &&
                    (path.endsWith("/info") || path.endsWith("/auth-setup") || path.endsWith("/pair-verify")))
            if (controllerId != recoveryControllerId && !preflight) return RtspMessage.Response(status = 403)
        }
        when (request.method) {
            "SETUP" -> return handleSetup(request)
            "RECORD" -> {
                if (!listener.claimProjection(this)) return RtspMessage.Response(status = 503)
                reportStartup(StartupStage.RECORD_RECEIVED)
                return RtspMessage.Response(status = 200)
            }
            "TEARDOWN" -> return handleTeardown(request)
        }

        if (config.bufferedAudioOutputEnabled && request.method in BUFFERED_AUDIO_METHODS) {
            val body = if (request.body.isEmpty() && request.method == "GETANCHOR") emptyMap() else {
                runCatching { asMap(BplistCodec.decode(request.body)) }.getOrNull()
                    ?: return RtspMessage.Response(status = 400)
            }
            val response = media.onBufferedAudioControl(this, request.method, body)
            if (request.method != "GETANCHOR") debugLog("airplay ${request.method} $body -> ${response ?: "ok"}")
            return if (response == null) RtspMessage.Response(status = 200) else RtspMessage.Response(
                headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                body = BplistCodec.encode(response),
            )
        }

        return when {
            path.endsWith("/pair-setup") -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairSetup.handle(request.body),
            )
            path.endsWith("/pair-verify") -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairVerify.handle(request.body),
            )
            path.endsWith("/auth-setup") -> {
                val body = mfi?.let { MfiSapAuthSetup.handle(request.body, it) }
                if (body == null) RtspMessage.Response(status = 400)
                else RtspMessage.Response(headers = mapOf("Content-Type" to OCTET_CONTENT_TYPE), body = body)
            }
            path.endsWith("/info") -> {
                val info = AirPlayInfoPlist.build(config)
                if (request.body.isNotEmpty()) {
                    val requestInfo = try {
                        BplistCodec.decode(request.body).toString()
                    } catch (_: Exception) {
                        "<unparseable ${request.body.size} bytes>"
                    }
                    debugLog("airplay /info request=$requestInfo")
                }
                Log.i(
                    TAG,
                    "airplay /info features=${info["features"]} " +
                        "audioFormats=${(info["audioFormats"] as? List<*>)?.size ?: 0} " +
                        "audioLatencies=${(info["audioLatencies"] as? List<*>)?.size ?: 0}",
                )
                debugLog(
                    "airplay /info videoInCar=${config.videoInCar} " +
                        "videoPlaybackAllowed=${if (config.videoInCar) VideoInCar.allowed else "not-offered"}",
                )
                debugLog("airplay /info displays=${info["displays"]}")
                RtspMessage.Response(
                    headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                    body = BplistCodec.encode(info),
                )
            }
            request.method == "POST" && path.endsWith("/command") -> handleCommand(request)
            request.method == "POST" && path.endsWith("/feedback") -> {
                val body = media.onFeedback(this)
                if (body == null) {
                    RtspMessage.Response(status = 200)
                } else {
                    RtspMessage.Response(
                        headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                        body = BplistCodec.encode(body),
                    )
                }
            }
            else -> RtspMessage.Response(status = 200)
        }
    }

    private fun notifySetupResponse(successful: Boolean) {
        try {
            if (successful) media.onSetupResponseSent(this) else media.onSetupResponseFailed(this)
        } catch (error: Exception) {
            Log.w(TAG, "airplay SETUP response callback failed", error)
        }
        if (successful) reapplyClusterContent()
    }

    private fun debugLog(message: String, uiVisible: Boolean = true) {
        Log.i(TAG, message)
        if (!uiVisible) return
        try {
            listener.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(TAG, "debug log callback failed", error)
        }
    }

    private fun trace(message: String) {
        try {
            listener.onDebugLog("TRACE $message")
        } catch (error: Exception) {
            Log.w(TAG, "trace log callback failed", error)
        }
    }

    private fun reportStartup(stage: StartupStage) {
        if (synchronized(startupStages) { startupStages.add(stage) }) {
            debugLog("airplay startup stage=${stage.name.lowercase()}")
        }
    }

    private fun handleSetup(request: RtspMessage.Request): RtspMessage.Response {
        val dict = try {
            asMap(BplistCodec.decode(request.body))
        } catch (error: Exception) {
            Log.e(TAG, "airplay SETUP plist decode failed body=${request.body.size}", error)
            null
        }
        if (dict == null) {
            reportStartup(StartupStage.SETUP_INVALID_PLIST)
            return RtspMessage.Response(status = 400)
        }
        debugLog("airplay SETUP keys=${dict.keys.sorted()}")
        val streams = dict["streams"] as? List<*>
        if (streams != null) {
            if (!listener.claimProjection(this)) return RtspMessage.Response(status = 503)
            val responseStreams = handleStreams(streams)
            debugLog("airplay SETUP response streams=$responseStreams")
            val body = BplistCodec.encode(linkedMapOf("streams" to responseStreams))
            if (WIRE_TRACE) trace("airplay SETUP response bplistHex=${body.toHex()}")
            return RtspMessage.Response(headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE), body = body)
        }

        val name = string(dict["name"])
        val deviceId = string(dict["deviceID"])
        val wifiMac = string(dict["macAddress"]).lowercase()
        val model = string(dict["model"])
        peerName = name.take(128)
        peerWifiMac = wifiMac.take(128)
        peerModel = model.take(128)
        peerOsVersion = string(dict["osVersion"]).take(64)
        peerSourceVersion = string(dict["sourceVersion"]).take(64)
        debugLog(AirPlayPeerDiagnostics.summary(model, string(dict["osVersion"]), string(dict["sourceVersion"])))
        if (deviceId.isNotEmpty()) deviceBtMac = deviceId
        if (name.isNotEmpty() || deviceId.isNotEmpty() || wifiMac.isNotEmpty()) {
            listener.onDeviceInfo(this, AirPlayDeviceInfo(name, deviceId, wifiMac, model))
        }

        val peerTimingPort = long(dict["timingPort"])?.toInt() ?: 0
        if (peerTimingPort !in 0..65535) return RtspMessage.Response(status = 400)
        if (!listener.claimProjection(this)) return RtspMessage.Response(status = 503)
        val response = synchronized(endpointLock) {
            check(!closed.get()) { "AirPlay session is closing" }
            val endpoints = linkedMapOf<String, Any?>(
                "timingPort" to openTiming(peerTimingPort),
                "eventPort" to openEvent(),
            )
            if (dict["keepAliveLowPower"] == true || dict["keepAliveLowPower"] == 1L) {
                endpoints["keepAlivePort"] = openKeepAlive()
            }
            endpoints
        }
        reportStartup(StartupStage.SETUP_ENDPOINTS_READY)
        val proposedFeatures = dict["features"] as? List<*>
        val features = setupEnabledFeatures(config, proposedFeatures)
        recordSiriSetupFeatures(proposedFeatures, features)
        val videoPlaybackEnabled = VideoInCar.FEATURE in features
        val videoDelivery = videoPlaybackAvailability.setFeatureEnabled(videoPlaybackEnabled)
        debugLog("airplay video playback negotiated=$videoPlaybackEnabled availability=$videoDelivery")
        response["enabledFeatures"] = features
        protocolFailure = null
        debugLog("airplay SETUP ready timingPort=${response["timingPort"]} eventPort=${response["eventPort"]}")
        return RtspMessage.Response(
            headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
            body = BplistCodec.encode(response),
        )
    }

    private fun handleStreams(streams: List<*>): List<Any?> {
        val result = arrayListOf<Any?>()
        for (entry in streams) {
            val stream = asMap(entry) ?: continue
            val type = long(stream["type"])?.toInt() ?: continue
            recordSiriStreamSetup(type, stream)
            debugLog("airplay SETUP stream type=$type payload=$stream")
            when (type) {
                STREAM_TYPE_MAIN_SCREEN, STREAM_TYPE_ALT_SCREEN -> {
                    val port = media.onScreen(this, type, stream)
                    debugLog("airplay screen stream type=$type dataPort=${port ?: "rejected"}")
                    reportStartup(when {
                        type == STREAM_TYPE_MAIN_SCREEN && port != null -> StartupStage.MAIN_SCREEN_READY
                        type == STREAM_TYPE_MAIN_SCREEN -> StartupStage.MAIN_SCREEN_DECLINED
                        port != null -> StartupStage.ALT_SCREEN_READY
                        else -> StartupStage.ALT_SCREEN_DECLINED
                    })
                    if (port != null) {
                        activeStreams.add(type)
                        if (type == STREAM_TYPE_ALT_SCREEN) beginClusterContent()
                        else mainScreenToken = Any()
                        result.add(linkedMapOf("type" to type, "dataPort" to port))
                    }
                }
                STREAM_TYPE_MAIN_AUDIO, STREAM_TYPE_ALT_AUDIO, STREAM_TYPE_MAIN_HIGH_AUDIO -> {
                    val streamResponse = media.onAudio(this, type, stream)
                    debugLog(
                        "airplay audio stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"} " +
                            "controlPort=${streamResponse?.get("controlPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                STREAM_TYPE_MAIN_BUFFERED_AUDIO -> {
                    val streamResponse = if (config.bufferedAudioOutputEnabled) media.onBufferedAudio(this, stream) else null
                    debugLog("airplay buffered audio stream accepted=${streamResponse != null} " +
                        "dataPort=${streamResponse?.get("dataPort") ?: "none"}")
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                STREAM_TYPE_DATA -> {
                    val streamResponse = media.onDataStream(this, stream)
                    debugLog(
                        "airplay data stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                else -> debugLog("airplay unsupported stream type=$type")
            }
        }
        return result
    }

    private fun failureEvidence(request: RtspMessage.Request, error: Throwable): AirPlayFailureEvidence {
        val maxBodyBytes = 8192
        val capturedBody = request.body.copyOfRange(0, minOf(request.body.size, maxBodyBytes))
        val headers = boundedHeadersJson(request.headers, 4096)
        val stackWriter = java.io.StringWriter()
        error.printStackTrace(java.io.PrintWriter(stackWriter))
        val rawStack = stackWriter.toString()
        val stack = rawStack.take(6144)
        val rawError = error.toString()
        return AirPlayFailureEvidence(
            method = request.method.take(32),
            path = request.path.take(1024),
            pathTruncated = request.path.length > 1024,
            protocol = request.protocol.take(32),
            headersJson = headers.first,
            headersTruncated = headers.second,
            bodyBase64 = Base64Compat.encode(capturedBody),
            bodyBytes = request.body.size,
            bodyCapturedBytes = capturedBody.size,
            bodyTruncated = capturedBody.size != request.body.size,
            errorRaw = rawError.take(2048),
            errorTruncated = rawError.length > 2048,
            stackTraceRaw = stack,
            stackTraceTruncated = rawStack.length > 6144,
            peerName = peerName,
            peerDeviceId = deviceBtMac.take(256),
            peerWifiMac = peerWifiMac,
            peerModel = peerModel,
            peerOsVersion = peerOsVersion,
            peerSourceVersion = peerSourceVersion,
        )
    }

    private fun boundedHeadersJson(headers: Map<String, String>, maxChars: Int): Pair<String, Boolean> {
        val out = StringBuilder("{")
        var truncated = false
        for ((key, value) in headers.toSortedMap()) {
            val pair = "${jsonString(key)}:${jsonString(value)}"
            val extra = pair.length + if (out.length > 1) 1 else 0
            if (out.length + extra + 1 > maxChars) {
                truncated = true
                break
            }
            if (out.length > 1) out.append(',')
            out.append(pair)
        }
        out.append('}')
        return out.toString() to truncated
    }

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (char in value) when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
        append('"')
    }

    private fun handleCommand(request: RtspMessage.Request): RtspMessage.Response {
        val body = try {
            asMap(BplistCodec.decode(request.body)) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap<String, Any?>()
        }
        val type = string(body["type"])
        val params = asMap(body["params"]) ?: emptyMap()
        debugLog("airplay command type=$type keys=${params.keys.sorted()}")
        if (type == "modesChanged") {
            siriRuntimeEvidence.observeSpeech(params)
            debugLog("audio_modes " + local.headunit.diplay.AudioModeEvidence.describe(params))
        }
        val streamId = request.headers["x-apple-streamid"]?.toLongOrNull()
        val data = params["data"] as? ByteArray
        if (streamId != null && data != null) {
            runCatching { asMap(BplistCodec.decode(data)) }.getOrNull()
                ?.let { listener.onRemoteControlMessage(this, streamId, it) }
            return RtspMessage.Response(status = 200)
        }
        if (type == "requestUI" && string(params["url"]) == VideoInCar.UI_URL) {
            listener.onVideoPlaybackUiRequested(this)
        } else if (type == "requestUI") {
            listener.onHostUiRequested(this)
        }
        listener.onCommand(this, type, params)
        return RtspMessage.Response(status = 200)
    }

    private fun handleTeardown(request: RtspMessage.Request): RtspMessage.Response {
        val decodedBody = try {
            BplistCodec.decode(request.body)
        } catch (_: Exception) {
            null
        }
        val types = teardownStreamTypes(decodedBody)

        debugLog(
            "airplay TEARDOWN types=${types ?: "all"} activeBefore=$activeStreams " +
                "body=${request.body.size} bytes payload=$decodedBody",
        )
        if (WIRE_TRACE) trace("airplay TEARDOWN raw${request.body.size}Hex=${request.body.toHex()}")

        // Restore wheel input before releasing media resources, which may take time to close.
        if (types == null) fullTeardown = true
        if (types == null || STREAM_TYPE_MAIN_SCREEN in types) mainScreenToken = null
        if (types == null || STREAM_TYPE_ALT_SCREEN in types) clearClusterContent()
        if (types == null) {
            activeStreams.toList().forEach { media.onTeardown(this, it) }
            activeStreams.clear()
        } else {
            types.forEach { type -> if (activeStreams.remove(type)) media.onTeardown(this, type) }
        }
        if (STREAM_TYPE_ALT_SCREEN !in activeStreams) clearClusterContent()
        return RtspMessage.Response(status = 200)
    }

    private fun openTiming(peerPort: Int): Int {
        val port = ntp.listen(localAddress ?: local.headunit.diplay.LegacySockets.anyAddress())
        if (peerPort > 0) peerAddress?.let { ntp.start(it, peerPort) }
        return port
    }

    private fun openKeepAlive(): Int {
        keepAliveSocket?.let { return it.localPort }
        val socket = local.headunit.diplay.LegacySockets.udp(localAddress ?: local.headunit.diplay.LegacySockets.anyAddress())
        keepAliveSocket = socket
        keepAliveThread = Thread({ runKeepAlive(socket) }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    private fun runKeepAlive(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (!closed.get()) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun openEvent(): Int {
        eventServer?.let { return it.localPort }
        val server = local.headunit.diplay.LegacySockets.tcp(localAddress ?: local.headunit.diplay.LegacySockets.anyAddress())
        eventServer = server
        spawnEvent("airplay-event-accept") { acceptEvent(server) }
        return server.localPort
    }

    private fun teardown() {
        synchronized(endpointLock) {
            ntp.close()
            safeClose(keepAliveSocket)
            keepAliveSocket = null
            keepAliveThread?.interrupt()
            keepAliveThread = null
            safeClose(eventServer)
            eventServer = null
            safeClose(eventSocket)
            eventSocket = null
            eventCipher = null
            eventThreads.forEach { it.interrupt() }
            eventThreads.clear()
        }
        videoPlaybackAvailability.setEventReady(false)
        videoPlaybackAvailability.setFeatureEnabled(false)
    }

    private fun acceptEvent(server: ServerSocket) {
        var accepted: Socket? = null
        try {
            val socket = server.acceptFromPeer(requireNotNull(peerAddress)) {
                debugLog("airplay event rejected sender outside the control session")
            }
            accepted = socket
            socket.setSoLinger(true, 0)
            // Avoid Nagle coalescing on this interactive event connection: small HID/event writes
            // need not wait for earlier unacknowledged data.
            socket.tcpNoDelay = true
            com.shilapi.xcertplay.network.TcpLiveness.configure(socket) { debugLog(it) }
            debugLog("airplay event connection accepted noDelay=${socket.tcpNoDelay}")
            val shared = pairVerify.shared
            if (shared == null) {
                Log.e(TAG, "airplay event rejected: pair-verify shared secret unavailable")
                safeClose(socket)
                close()
                return
            }
            val writeKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".asciiBytes(),
                "Events-Write-Encryption-Key".asciiBytes(),
                32,
            )
            val readKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".asciiBytes(),
                "Events-Read-Encryption-Key".asciiBytes(),
                32,
            )
            val installed = synchronized(endpointLock) {
                if (closed.get() || eventServer !== server) false
                else {
                    eventSocket = socket
                    eventCipher = ControlCipher(readKey, writeKey)
                    true
                }
            }
            if (!installed) return
            val writtenNight = synchronized(eventWriteLock) {
                sendPendingNightModeLocked()
            }
            writtenNight?.let(::recordNightModeWritten)
            val videoDelivery = videoPlaybackAvailability.setEventReady(true)
            debugLog("airplay video event ready availability=$videoDelivery")
            reapplyClusterContent()
            runEventRead(socket)
        } catch (error: Exception) {
            if (!closed.get()) {
                Log.e(TAG, "airplay event accept failed", error)
                close()
            }
        } finally {
            safeClose(accepted)
        }
    }

    private fun runEventRead(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            var encrypted = ByteArray(0)
            var plaintext = ByteArray(0)
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) {
                    markTransportIoFailure()
                    break
                }
                val cipher = eventCipher ?: break
                encrypted += buffer.copyOf(count)
                val decrypted = try {
                    cipher.decrypt(encrypted)
                } catch (error: Exception) {
                    Log.e(TAG, "airplay event decrypt failed encrypted=${encrypted.size}", error)
                    break
                }
                encrypted = decrypted.rest
                plaintext += decrypted.data
                val parsed = RtspMessage.parseMessages(plaintext)
                plaintext = parsed.rest
                if (parsed.messages.isNotEmpty()) lastEventTrafficNs = System.nanoTime()
                for (message in parsed.messages) {
                    if (message.method.startsWith("RTSP/") || message.method.startsWith("HTTP/")) {
                        val cseq = message.headers["cseq"]?.toIntOrNull()
                        val status = message.path.toIntOrNull()
                        if (cseq != null && status != null) siriRuntimeEvidence.observeReply(cseq, status)
                        continue
                    }
                    debugLog(
                        "airplay event rx ${message.method} ${message.path} cseq=${message.headers["cseq"] ?: "-"} body=${message.body.size}",
                    )
                    val response = RtspMessage.buildResponse(message, RtspMessage.Response(status = 200))
                    if (WIRE_TRACE) trace(
                        "airplay event rx headers=${message.headers} " +
                            "bodyHex=${message.body.toHex()}",
                    )
                    if (WIRE_TRACE) trace("airplay event tx wireHex=${response.toHex()}")
                    synchronized(eventWriteLock) {
                        output.write(cipher.encrypt(response))
                        output.flush()
                    }
                }
            }
        } catch (error: Exception) {
            if (error is IOException) markTransportIoFailure()
            if (!closed.get()) Log.e(TAG, "airplay event read failed", error)
        } finally {
            debugLog("airplay event connection closed")
            if (!closed.get()) close()
        }
    }

    private fun spawnEvent(name: String, body: () -> Unit) {
        val thread = Thread(body, name).apply { isDaemon = true }
        eventThreads.add(thread)
        thread.start()
    }

    private enum class StartupStage {
        CONTROL_STREAMS_READY,
        FIRST_REQUEST,
        SETUP_INVALID_PLIST,
        SETUP_ENDPOINTS_READY,
        MAIN_SCREEN_READY,
        MAIN_SCREEN_DECLINED,
        ALT_SCREEN_READY,
        ALT_SCREEN_DECLINED,
        RECORD_RECEIVED,
        FIRST_FRAME_RENDERED,
    }

    private companion object {
        const val WIRE_TRACE = false
        const val TAG = "xcertplay-usb"
        const val PLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        const val PAIRING_CONTENT_TYPE = "application/pairing+tlv8"
        const val OCTET_CONTENT_TYPE = "application/octet-stream"

        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_ALT_AUDIO = 101
        const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
        const val STREAM_TYPE_DATA = 130
        const val STREAM_TYPE_MAIN_BUFFERED_AUDIO = 103
        private val BUFFERED_AUDIO_METHODS = setOf("SETRATE", "SETRATEANCHORTIME", "GETANCHOR", "FLUSHBUFFERED")

        const val READ_CHUNK_BYTES = 16 * 1024
        const val ZOOM_DIRECTION_IN = 0
        const val ZOOM_DIRECTION_OUT = 1
        const val EVENT_READY_POLL_MILLIS = 25L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** The features SETUP enables; video in car only when configured and the iPhone [proposed] it. */
/** The session feature the iPhone proposes for main buffered audio. */
internal const val MAIN_BUFFERED_FEATURE = "mainBuffered"

internal fun setupEnabledFeatures(config: AirPlayConfig, proposed: List<*>?): List<String> {
    val features = mutableListOf<String>()
    if (config.hevc) features.add("hevc")
    features.add("iAPChannel")
    features.add("viewAreas")
    if (config.cluster != null) features.add("altScreen")
    if (config.videoInCar && proposed.orEmpty().contains(VideoInCar.FEATURE)) features.add(VideoInCar.FEATURE)
    if (config.bufferedAudioOutputEnabled && proposed.orEmpty().contains(MAIN_BUFFERED_FEATURE)) features.add(MAIN_BUFFERED_FEATURE)
    return features
}

/**
 * The stream types a TEARDOWN body closes, or null when it names none (the whole session). Several
 * type-130 streams can be open, the iAP tunnel (streamID 1) and video in car's data streams; tearing
 * down one of the latter must not close the iAP tunnel.
 */
internal fun teardownStreamTypes(body: Any?): List<Int>? {
    val entries = ((body as? Map<*, *>)?.get("streams") as? List<*>).orEmpty().mapNotNull { entry ->
        val stream = entry as? Map<*, *> ?: return@mapNotNull null
        val type = (stream["type"] as? Number)?.toInt() ?: return@mapNotNull null
        type to (stream["streamID"] as? Number)?.toLong()
    }
    if (entries.isEmpty()) return null
    return entries.filterNot { (type, streamId) -> type == 130 && streamId != null && streamId != 1L }.map { it.first }
}

internal fun safeClose(closeable: Any?) {
    try {
        when(closeable){is java.net.Socket->closeable.close();is java.net.ServerSocket->closeable.close();is java.net.DatagramSocket->closeable.close();is Closeable->closeable.close()}
    } catch (_: Exception) {
        // Best-effort close.
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun asMap(value: Any?): Map<String, Any?>? {
    val map = value as? Map<*, *> ?: return null
    val result = LinkedHashMap<String, Any?>(map.size)
    for ((key, entry) in map) result[key.toString()] = entry
    return result
}

private fun string(value: Any?): String = value as? String ?: ""

private fun long(value: Any?): Long? = (value as? Number)?.toLong()
