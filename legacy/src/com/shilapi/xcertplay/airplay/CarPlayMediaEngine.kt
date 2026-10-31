package com.shilapi.xcertplay.airplay

import android.util.Log
import local.headunit.diplay.AudioDeliveryGate
import local.headunit.diplay.AppMicrophoneRequestPolicy
import local.headunit.diplay.MicrophoneStartGate
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.math.BigInteger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Identity of one CarPlay audio stream: the stream type plus its CarPlay audio type. */
data class AudioStreamId(val type: Int, val audioType: String, val owner: Any?) {
    constructor(type: Int, audioType: String): this(type, audioType, null)
}

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}
    /** A frame with the iPhone's frame time and its arrival time (System.nanoTime); see [ScreenStream.Listener]. */
    fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) =
        onVideoFrame(type, naluBytes)
    /** ScreenStream hands over a unique decrypted array; no producer retains or mutates it. */
    fun onOwnedVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) =
        onVideoFrame(type, naluBytes, senderNanos, arrivalNanos)
    fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {}
    fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {}
    fun onScreenStreamActive(type: Int, active: Boolean) {}
    fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun onAudioStopped(id: AudioStreamId) {}
    fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {}
    fun onMicrophoneStopped(id: AudioStreamId) {}
    fun onIapMessage(bytes: ByteArray) {}
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen, audio and iAP2 DataStream ports,
 * decrypts their payloads, and hands decoded media to a [MediaSink]. Telephony and speech
 * streams can additionally return a PCM microphone uplink through the sink.
 */

class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val audioCaptureDirectory: File? = null,
) : AirPlayMediaHandler {
    internal data class StreamKey(
        val session: AirPlaySession,
        val type: Int,
        val audioType: String = "",
    )

    private data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        @Volatile var firstSample: Int? = null,
        @Volatile var originNs: Long? = null,
    )

    private data class PendingIapTunnel(
        val bridge: AirPlayIapTunnelStream,
        val handler: (BlockingDuplexByteStream) -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    private val audioMeta = ConcurrentHashMap<StreamKey, AudioMeta>()
    private data class OwnedScreen(val key: StreamKey, val stream: ScreenStream)
    // One renderer per display type across all control sessions. This lock covers only
    // fast sink delivery/handler assignment, never network writes or socket teardown.
    private val screenDeliveryLock = Any()
    private val screenOwners = HashMap<Int, OwnedScreen>()
    private val retiredScreenSessions = WeakHashMap<AirPlaySession, MutableSet<Int>>()
    private fun screenInactive(owner: OwnedScreen) {
        runCatching { sink.onScreenStreamActive(owner.key.type, false) }.onFailure {
            owner.key.session.logDebug("screen_stop_callback_failed type=${owner.key.type} error=${it.javaClass.simpleName}")
        }
    }
    private fun ownsScreen(owner: OwnedScreen): Boolean = synchronized(screenDeliveryLock) {
        screenOwners[owner.key.type] === owner && !owner.key.session.isClosed
    }
    private inline fun deliverScreen(owner: OwnedScreen, action: () -> Unit) {
        synchronized(screenDeliveryLock) {
            if (screenOwners[owner.key.type] === owner && !owner.key.session.isClosed) action()
        }
    }
    private fun retireScreens(session: AirPlaySession, type: Int? = null) {
        synchronized(screenDeliveryLock) {
            val retiring = screenOwners.values.filter { it.key.session === session && (type == null || it.key.type == type) }
            for (owner in retiring) if (screenOwners[owner.key.type] === owner) {
                screenOwners.remove(owner.key.type)
                screenInactive(owner)
            }
        }
    }
    private val streamOwnershipLock = Any()
    private val audioDeliveries = ConcurrentHashMap<StreamKey, AudioDeliveryGate>()
    private val microphoneStarts = ConcurrentHashMap<StreamKey, MicrophoneStartGate>()
    private val audioCaptures = ConcurrentHashMap<StreamKey, AudioPacketCapture>()
    private val pendingIapTunnels = ConcurrentHashMap<AirPlaySession, PendingIapTunnel>()
    private val videoSettingsChannels = ConcurrentHashMap<AirPlaySession, VideoSettingsChannel>()
    private val bufferedStreams = ConcurrentHashMap<AirPlaySession, BufferedStream>()

    private class BufferedStream(val stream: BufferedAudioStream, val connectionId: Any?)

    // The sink has one type-103 renderer, even while old control sessions remain connected.
    // Never hold this lock when closing/controlling a stream: sink callbacks can reenter us.
    private val bufferedOwnershipLock = Any()
    private var bufferedOwner: AirPlaySession? = null
    private var bufferedTransition: BufferedTransition? = null
    private val retiredBufferedSessions = Collections.newSetFromMap(WeakHashMap<AirPlaySession, Boolean>())
    private class BufferedTransition(val session: AirPlaySession, val candidate: BufferedStream?) {
        var cancelled = false // guarded by bufferedOwnershipLock
        var retiring: BufferedStream? = null
    }

    private fun mayInstallBufferedLocked(session: AirPlaySession): Boolean {
        // A reentrant sink callback cannot wait for itself. Preserve its retirement barrier
        // until cleanup completes; the volatile flag requires no reverse stream-lock order.
        bufferedTransition?.takeIf { it.candidate == null && it.retiring?.stream?.outputCleanupComplete == true }
            ?.let { bufferedTransition = null }
        return !session.isClosed && session !in retiredBufferedSessions && bufferedTransition == null
    }
    @Volatile private var nextRemoteControlStreamId = FIRST_REMOTE_CONTROL_STREAM_ID
    @Volatile private var iapTunnelHandler: ((BlockingDuplexByteStream) -> Boolean)? = null

    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler
    }

    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        if (session.isClosed || synchronized(screenDeliveryLock) { retiredScreenSessions[session]?.contains(type) == true }) return null
        val key = outputKey(session, stream) ?: return null
        val streamKey = StreamKey(session, type)
        Log.i(TAG, "airplay screen key connectionID=${unsignedPlistDecimal(stream["streamConnectionID"])}")
        val screen = ScreenStream(key, session::logDebug, session.localAddress ?: local.headunit.diplay.LegacySockets.anyAddress())
        val owner = OwnedScreen(streamKey, screen)
        var previousAtKey: Closeable? = null
        val registered = synchronized(streamOwnershipLock) {
            if (session.isClosed) false else { previousAtKey = streams.put(streamKey, screen); true }
        }
        if (!registered) { screen.close(); return null }
        var previous: OwnedScreen? = null
        try {
            val activated = synchronized(screenDeliveryLock) {
                if (session.isClosed || streams[streamKey] !== screen || retiredScreenSessions[session]?.contains(type) == true) false else {
                    previous = screenOwners.put(type, owner)
                    previous?.takeIf { it.key.session !== session }?.let {
                        retiredScreenSessions.getOrPut(it.key.session) { HashSet() }.add(type)
                    }
                    sink.setVideoDiagnosticHandler(type) {
                        if (ownsScreen(owner)) {
                            if (it == "first frame rendered") session.videoFrameRendered()
                            session.logDebug("Video: $it")
                        }
                    }
                    if (screenOwners[type] === owner && !session.isClosed) sink.setVideoRecoveryHandler(type) {
                        // Check ownership briefly; never serialize video behind a control write.
                        if (ownsScreen(owner)) {
                            val command = if (type == STREAM_TYPE_ALT_SCREEN) {
                                mapOf("type" to "forceKeyFrame", "params" to mapOf("uuid" to AirPlayInfoPlist.ALT_UUID))
                            } else mapOf("type" to "forceKeyFrame")
                            val sent = session.sendCommand(command)
                            session.logDebug("Video recovery: requested keyframe sent=$sent")
                        }
                    }
                    screenOwners[type] === owner && !session.isClosed
                }
            }
            previous?.let { streams.remove(it.key, it.stream); it.stream.close() }
            previousAtKey?.close()
            if (!activated) { streams.remove(streamKey, screen); screen.close(); return null }
            val port = screen.listen(object : ScreenStream.Listener {
                override fun onCodec(codec: VideoCodec) = deliverScreen(owner) { sink.onVideoCodec(type, codec) }
                override fun onConfig(codecData: ByteArray) = deliverScreen(owner) { sink.onVideoConfig(type, codecData) }
                override fun onFrame(naluBytes: ByteArray) = deliverScreen(owner) { sink.onVideoFrame(type, naluBytes) }
                override fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) =
                    deliverScreen(owner) { sink.onOwnedVideoFrame(type, naluBytes, senderNanos, arrivalNanos) }
                override fun onClosed(cause: Throwable?) {
                    Log.w(TAG, "screen stream ended type=$type reason=${cause?.message ?: "peer EOF"}")
                    val wasCurrent = synchronized(screenDeliveryLock) {
                        if (screenOwners[type] !== owner) false else {
                            screenOwners.remove(type); screenInactive(owner); true
                        }
                    }
                    streams.remove(streamKey, screen)
                    if (wasCurrent) session.close()
                }
            })
            val ready = synchronized(screenDeliveryLock) {
                if (screenOwners[type] !== owner || session.isClosed) false else {
                    sink.onScreenStreamActive(type, true)
                    screenOwners[type] === owner && !session.isClosed
                }
            }
            if (!ready) { streams.remove(streamKey, screen); screen.close(); return null }
            return port
        } catch (failure: Throwable) {
            previous?.let { streams.remove(it.key, it.stream); it.stream.close() }; previousAtKey?.close()
            val stillCurrent = synchronized(screenDeliveryLock) {
                if (screenOwners[type] !== owner) false else {
                    screenOwners.remove(type); screenInactive(owner); true
                }
            }
            streams.remove(streamKey, screen); screen.close()
            if (session.isClosed || !stillCurrent) return null
            throw failure
        }
    }

    override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        val audioType = stream["audioType"]?.toString()?.lowercase() ?: "default"
        // Concurrent streams may share a type (e.g. music, guidance and Siri can all arrive as
        // type 100); only the same (type, audioType) pair replaces a previous stream.
        val streamKey = StreamKey(session, type, audioType)
        if (session.isClosed) return null
        // Every replacement has its own renderer identity, including replacements within one session.
        val streamId = AudioStreamId(type, audioType, Any())
        synchronized(streamOwnershipLock) { audioDeliveries.remove(streamKey) }?.close()
        microphoneStarts.remove(streamKey)?.close()
        streams.remove(streamKey)?.close()
        audioMeta.remove(streamKey)
        audioCaptures.remove(streamKey)?.close()
        sink.onAudioStopped(streamId)

        val key = outputKey(session, stream) ?: return null
        val format = AudioStreamCodec.fromFormatBits(
            (stream["audioFormat"] as? Number)?.toLong() ?: 0L,
            type,
            audioType,
        )
        Log.i(
            TAG,
            "airplay audio format type=$type audioType=$audioType codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "formatBits=0x${java.lang.Long.toHexString((stream["audioFormat"] as? Number)?.toLong() ?: 0L)} " +
                "micPort=${(stream["dataPort"] as? Number)?.toInt() ?: 0}",
        )
        session.logDebug("audio_setup type=$type kind=$audioType codec=${format.codec} rate=${format.sampleRate} channels=${format.channels}")
        val connectionId = stream["streamConnectionID"]
        val latencyMs = (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0
        val meta = AudioMeta(type, format, connectionId, latencyMs)
        val microphone = microphoneConfig(session, type, stream, format)
        if (type == STREAM_TYPE_MAIN_AUDIO && audioType != "media") {
            val input = stream["input"]?.toString() ?: "none"
            val micPort = ((stream["dataPort"] as? Number)?.toInt() ?: 0) in 1..65535
            session.logDebug(
                "Audio: setup type=$type audioType=$audioType input=$input " +
                    "micPort=${if (micPort) "yes" else "no"} " +
                    "microphone=${if (microphone != null) "on" else "off"} " +
                    "keys=${stream.keys.sorted().joinToString(",")}",
            )
        }
        lateinit var microphoneStart: MicrophoneStartGate
        val microphoneGate = microphone?.let { config ->
            microphoneStart = MicrophoneStartGate(
                alive = { !session.isClosed && microphoneStarts[streamKey] === microphoneStart },
                start = { reason ->
                    session.logDebug("Audio: microphone started audioType=$audioType source=$reason")
                    sink.onMicrophoneStarted(streamId, config)
                },
                stop = { sink.onMicrophoneStopped(streamId) },
            )
            microphoneStart
        }
        if (audioType == "telephony") {
            session.logDebug(
                "call_audio_negotiation codec=${format.codec} rate=${format.sampleRate} " +
                    "channels=${format.channels} payloadType=${format.payloadType} " +
                    "formatBits=0x${java.lang.Long.toHexString((stream["audioFormat"] as? Number)?.toLong() ?: 0L)} " +
                    "framesPerPacket=${(stream["framesPerPacket"] as? Number)?.toInt() ?: 0} " +
                    "connectionIdPresent=${connectionId != null} inputPortPresent=${((stream["dataPort"] as? Number)?.toInt() ?: 0) in 1..65535} " +
                    "microphoneConfigured=${microphone != null}" +
                    (microphone?.let { " micCodec=${it.codec} micRate=${it.sampleRate} micChannels=${it.channels} micFrameMs=${it.frameMillis} micFrameBytes=${it.frameBytes} micRtpSamples=${it.rtpSamplesPerPacket}" } ?: ""),
            )
        }

        val capture = audioCaptureDirectory?.let { AudioPacketCapture(it, type) }
        val audio = AudioStream(key, type, session::logDebug, session.localAddress ?: local.headunit.diplay.LegacySockets.anyAddress())
        lateinit var delivery: AudioDeliveryGate
        delivery = AudioDeliveryGate(
            alive = { !session.isClosed && audioDeliveries[streamKey] === delivery },
            start = { firstSample -> meta.firstSample = firstSample; meta.originNs = System.nanoTime(); sink.onAudioStarted(streamId, format, firstSample); microphoneGate?.onDownlink() },
            packet = { rtp, sample -> sink.onAudioRtp(streamId, format, rtp, sample) },
            stop = { try { sink.onAudioStopped(streamId) } finally { microphoneGate?.close() } },
        )
        val installed = synchronized(streamOwnershipLock) {
            if (session.isClosed) false else {
                streams[streamKey] = audio; audioDeliveries[streamKey] = delivery; audioMeta[streamKey] = meta
                if (microphoneGate != null) microphoneStarts[streamKey] = microphoneGate
                if (capture != null) audioCaptures[streamKey] = capture
                true
            }
        }
        if (!installed) { delivery.close(); audio.close(); capture?.close(); return null }
        val ports = try { audio.listen(
            object : AudioStream.Listener {
                private var loggedFailure=false
                override fun onStarted(firstSample: Int) {
                    delivery.onStarted(firstSample)
                }

                override fun onRtp(rtp: ByteArray, sample: Int) =
                    delivery.onPacket(rtp, sample)

                override fun onClosed(cause: Throwable) {
                    if (!session.isClosed && streams[streamKey] === audio) {
                        session.logDebug("audio_stream_failed type=$type kind=$audioType error=${cause.javaClass.simpleName}")
                        session.close()
                    }
                }

                override fun onPacket(
                    wire: ByteArray,
                    rtp: ByteArray?,
                    sample: Int?,
                    error: Throwable?,
                ) {
                    capture?.record(wire, rtp, sample, error)
                    if(error!=null&&!loggedFailure){loggedFailure=true;session.logDebug("audio_decrypt_failed type=$type kind=$audioType error=${error.javaClass.simpleName}")}
                }
            },
        ) } catch (failure: Throwable) {
            synchronized(streamOwnershipLock) {
                if (streams.remove(streamKey, audio)) { audioDeliveries.remove(streamKey, delivery); microphoneStarts.remove(streamKey, microphoneGate); audioMeta.remove(streamKey); audioCaptures.remove(streamKey) }
            }
            delivery.close(); microphoneGate?.close(); audio.close(); capture?.close()
            if (session.isClosed) return null
            throw failure
        }
        if (session.isClosed || streams[streamKey] !== audio) { delivery.close(); microphoneStarts.remove(streamKey, microphoneGate); microphoneGate?.close(); audio.close(); return null }
        if (microphoneGate != null && audioType != "telephony") microphoneGate.scheduleFallback(MICROPHONE_DOWNLINK_GRACE_MS)
        val (dataPort, controlPort) = ports
        return linkedMapOf(
            "type" to type,
            "dataPort" to dataPort,
            "controlPort" to controlPort,
            "streamConnectionID" to unsignedPlistInteger(connectionId ?: 0L),
        )
    }

    override fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        if (!synchronized(bufferedOwnershipLock) { mayInstallBufferedLocked(session) }) return null
        val type = BufferedAudioStream.STREAM_TYPE
        val streamKey = StreamKey(session, type, "media")
        val key = stream["shk"] as? ByteArray
        if (key == null || key.size != 32) {
            Log.w(TAG, "buffered audio SETUP without a 32-byte shk; declined")
            return null
        }
        val format = AudioStreamCodec.fromFormatBits((stream["audioFormat"] as? Number)?.toLong() ?: 0L, type, "media")
        if (format.codec != AudioCodecKind.AAC_LC) {
            Log.w(TAG, "buffered audio format ${stream["audioFormat"]} is not AAC-LC; declined")
            return null
        }
        if (stream["ct"]?.let { it !is Number || it.toInt() != 4 } == true ||
            stream["spf"]?.let { it !is Number || it.toInt() != 1024 } == true) {
            Log.w(TAG, "buffered audio requires AAC and 1024 samples per frame; declined")
            return null
        }
        val peer = session.remoteAddress ?: return null
        val buffered = try {
            BufferedAudioStream(key, format, sink, session::logDebug, expectedPeer = peer)
        } catch (error: Exception) {
            Log.w(TAG, "buffered audio listener failed", error)
            return null
        }
        // Validate before retiring a healthy owner. Detach its registry entries first, but
        // keep a transition barrier until close has finished its final sink callback.
        val candidate = BufferedStream(buffered, stream["streamConnectionID"])
        val transition = BufferedTransition(session, candidate)
        var previous: BufferedStream? = null
        val accepted = synchronized(bufferedOwnershipLock) {
            if (!mayInstallBufferedLocked(session)) false else {
                bufferedTransition = transition
                bufferedOwner?.let { oldSession ->
                    previous = detachBufferedLocked(oldSession)
                    if (oldSession !== session) retiredBufferedSessions.add(oldSession)
                }
                transition.retiring = previous
                true
            }
        }
        if (!accepted) {
            buffered.close()
            return null
        }
        val retired = runCatching { previous?.stream?.close() }.isSuccess &&
            previous?.stream?.outputCleanupComplete != false
        val installed = synchronized(bufferedOwnershipLock) {
            val usable = retired && bufferedTransition === transition && !transition.cancelled &&
                !session.isClosed && session !in retiredBufferedSessions
            if (usable) {
                streams[streamKey] = buffered
                bufferedStreams[session] = candidate
                bufferedOwner = session
            }
            if (bufferedTransition === transition) {
                bufferedTransition = if (retired) null else BufferedTransition(session, null).also { it.retiring = previous }
            }
            usable
        }
        if (!installed) {
            buffered.close()
            return null
        }
        buffered.start()
        if (bufferedStreams[session] !== candidate) return null
        Log.i(TAG, "buffered audio stream client=${stream["clientID"]} rate=${format.sampleRate} port=${buffered.port}")
        return linkedMapOf(
            "type" to type,
            "dataPort" to buffered.port,
            "audioBufferSize" to BufferedAudioStream.AUDIO_BUFFER_BYTES,
        )
    }

    override fun onBufferedAudioControl(
        session: AirPlaySession,
        method: String,
        body: Map<String, Any?>,
    ): Map<String, Any?>? {
        val buffered = synchronized(bufferedOwnershipLock) { bufferedStreams[session]?.stream } ?: return null
        val rtpTime = (body["rtpTime"] as? Number)?.toLong()
        val rate = when ((body["rate"] as? Number)?.toDouble()) {
            0.0 -> 0
            1.0 -> 1
            else -> null
        }
        return when (method) {
            "SETRATE", "SETRATEANCHORTIME" -> {
                if (rate == null || rate !in 0..1) null else buffered.setRate(rtpTime, rate, session.syncedNtp())
            }
            "GETANCHOR" -> buffered.anchor()
            "FLUSHBUFFERED" -> {
                (body["flushUntilTS"] as? Number)?.toLong()?.let { buffered.flush(it) }
                null
            }
            else -> null
        }
    }

    override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        if (session.isClosed) return null
        val uuid = (stream["clientTypeUUID"] as? String)?.uppercase() ?: return null
        if (session.videoInCar) videoDataStream(session, uuid, stream)?.let { return it }
        if (uuid != IAP_DATASTREAM_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        session.logDebug(
            "AirPlay iAP SETUP uuid=$uuid seed=$seed " +
                "streamConnectionID=${unsignedPlistDecimal(stream["streamConnectionID"]) ?: "none"}",
        )
        val key = AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            DATASTREAM_OUTPUT_KEY.toByteArray(Charsets.US_ASCII),
            32,
        )
        val tunnel = IapTunnel(
            readKey = key,
            bindAddress = session.localAddress
                ?: when (session.remoteAddress) {
                    is Inet6Address -> InetAddress.getByName("::")
                    is Inet4Address -> InetAddress.getByName("0.0.0.0")
                    else -> InetAddress.getByName("0.0.0.0")
                },
        )
        val bridge = AirPlayIapTunnelStream(session, tunnel)
        val handler = iapTunnelHandler
        val streamKey = StreamKey(session, STREAM_TYPE_DATA)
        val owned: Closeable = if (handler != null) bridge else tunnel
        var previous: Closeable? = null
        val installed = synchronized(streamOwnershipLock) {
            if (session.isClosed) false else { previous = streams.put(streamKey, owned); true }
        }
        if (!installed) { owned.close(); return null }
        previous?.close()
        val port = try {
            if (handler != null) {
                val boundPort = bridge.listen()
                session.logDebug(
                    "AirPlay iAP tunnel listening address=" +
                        "${session.localAddress?.hostAddress ?: "wildcard"} port=$boundPort",
                )
                if (!replacePendingIapTunnel(session, owned, PendingIapTunnel(bridge, handler))) {
                    streams.remove(streamKey, owned); owned.close(); return null
                }
                boundPort
            } else {
                tunnel.listen(
                    object : IapTunnel.Listener {
                        override fun onIap(bytes: ByteArray) {
                            if (!session.isClosed && streams[streamKey] === owned) sink.onIapMessage(bytes)
                        }

                        override fun onClosed(cause: Throwable?) {
                            Log.w(
                                TAG,
                                "iAP tunnel ended reason=${cause?.message ?: "peer EOF"}",
                            )
                            session.close()
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            streams.remove(streamKey, owned); owned.close()
            if (session.isClosed) return null
            throw error
        }
        if (session.isClosed || streams[streamKey] !== owned) { owned.close(); return null }
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to 1L, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    /**
     * Video in car: the settings channel gets its own encrypted socket; the remote control sessions that
     * carry playback have none (controlType 1), their messages arrive as commands with X-Apple-StreamID.
     */
    private fun videoDataStream(session: AirPlaySession, uuid: String, stream: Map<String, Any?>): Map<String, Any?>? {
        if (uuid in VideoInCar.REMOTE_CONTROL_UUIDS && (stream["controlType"] as? Number)?.toInt() == 1) {
            val streamId = nextRemoteControlStreamId++
            session.logTrace("video remote-control stream accepted uuid=$uuid streamID=$streamId")
            return linkedMapOf("type" to STREAM_TYPE_DATA, "streamID" to streamId)
        }
        if (uuid != VideoInCar.SETTINGS_CHANNEL_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        fun key(label: String) = AirPlayCrypto.hkdfSha512(
            shared, "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII), label.toByteArray(Charsets.US_ASCII), 32,
        )
        val channel = VideoSettingsChannel(key(DATASTREAM_OUTPUT_KEY), key(DATASTREAM_INPUT_KEY)) { session.logDebug(it) }
        var previous: VideoSettingsChannel? = null
        val installed = synchronized(streamOwnershipLock) {
            if (session.isClosed) false else { previous = videoSettingsChannels.put(session, channel); true }
        }
        if (!installed) { channel.close(); return null }
        previous?.close()
        val port = try { channel.listen(session.localAddress ?: local.headunit.diplay.LegacySockets.anyAddress()) }
        catch (failure: Throwable) {
            videoSettingsChannels.remove(session, channel); channel.close()
            if (session.isClosed) return null
            throw failure
        }
        if (session.isClosed || videoSettingsChannels[session] !== channel) { channel.close(); return null }
        session.logTrace("video settings stream listening port=$port")
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to VIDEO_SETTINGS_STREAM_ID, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        val pending = pendingIapTunnels.remove(session) ?: return
        val attached = try {
            pending.handler(pending.bridge)
        } catch (error: Throwable) {
            Log.w(TAG, "iAP tunnel relay attachment failed", error)
            false
        }
        if (!attached) {
            Log.w(TAG, "iAP tunnel relay attachment was rejected after SETUP")
            pending.bridge.close()
            session.close()
        }
    }

    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        val active = audioMeta.entries.filter { it.key.session === session }.map { it.value }
        val buffered = bufferedStreams[session]
        if (active.isEmpty() && buffered == null) return null
        val streams = active.map { meta ->
            val entry = linkedMapOf<String, Any?>(
                "type" to meta.type,
                "sampleRate" to meta.format.sampleRate,
            )
            val firstSample = meta.firstSample
            val originNs = meta.originNs
            if (firstSample != null && originNs != null) {
                val nowNs = System.nanoTime()
                val elapsedSec = Math.max(
                    0.0,
                    (nowNs - originNs) / 1e9 - meta.playoutLatencyMs / 1000.0,
                )
                val firstUnsigned = firstSample.toLong() and 0xffff_ffffL
                val sampleTime = (firstUnsigned + Math.round(elapsedSec * meta.format.sampleRate)) and
                    0xffff_ffffL
                entry["streamConnectionID"] = unsignedPlistInteger(meta.connectionId ?: 0L)
                entry["timestamp"] = session.syncedNtp()
                entry["timestampRawNs"] = nowNs
                entry["sampleTime"] = sampleTime
            }
            entry
        } + listOfNotNull(buffered?.let { it.stream.feedback(session.syncedNtp(), it.connectionId) })
        return linkedMapOf("streams" to streams)
    }

    override fun onTeardown(session: AirPlaySession, type: Int) {
        if (type == STREAM_TYPE_DATA) clearPendingIapTunnel(session)
        if (type == BufferedAudioStream.STREAM_TYPE) {
            retireBuffered(session)
            return
        }
        if (isScreenStreamType(type)) retireScreens(session, type)
        // TEARDOWN carries only the stream type; release every audioType variant of it.
        val tornDown = streams.keys.filter { it.session === session && it.type == type }
        tornDown.forEach { key ->
            synchronized(streamOwnershipLock) { audioDeliveries.remove(key) }?.close()
            microphoneStarts.remove(key)?.close()
            val streamId = AudioStreamId(key.type, key.audioType, key.session)
            audioMeta.remove(key)
            audioCaptures.remove(key)?.close()
            // BufferedAudioStream owns serialized sink cleanup; stopping it first from here
            // could interleave with its in-flight delivery and recreate a stale renderer.
            if (key.type != BufferedAudioStream.STREAM_TYPE) sink.onAudioStopped(streamId)
            streams.remove(key)?.close()
        }
    }

    override fun onSessionClosed(session: AirPlaySession) {
        val deliveries = synchronized(streamOwnershipLock) { audioDeliveries.keys.filter { it.session === session }.mapNotNull { audioDeliveries.remove(it) } }
        deliveries.forEach { it.close() }
        clearPendingIapTunnel(session)
        retireBuffered(session)
        videoSettingsChannels.remove(session)?.close()
        val sessionStreams = streams.keys.filter {
            it.session === session && it.type != BufferedAudioStream.STREAM_TYPE
        }
        retireScreens(session)
        sessionStreams.forEach { streams.remove(it)?.close() }
        // A retired control session must not erase metadata or microphones of its successor.
        for (key in audioMeta.keys.filter { it.session === session }) {
            audioMeta.remove(key)
            sink.onAudioStopped(AudioStreamId(key.type, key.audioType, key.session))
        }
        for (key in microphoneStarts.keys.filter { it.session === session }) microphoneStarts.remove(key)?.close()
        for (key in audioCaptures.keys.filter { it.session === session }) audioCaptures.remove(key)?.close()
    }

    /** Removes ownership before a close that may block on delivery or invoke the sink. */
    private fun detachBufferedLocked(session: AirPlaySession): BufferedStream? {
        val previous = bufferedStreams.remove(session)
        previous?.let { streams.remove(StreamKey(session, BufferedAudioStream.STREAM_TYPE, "media"), it.stream) }
        if (bufferedOwner === session) bufferedOwner = null
        return previous
    }

    private fun retireBuffered(session: AirPlaySession) {
        var pending: BufferedStream? = null
        var retirement: BufferedTransition? = null
        val previous = synchronized(bufferedOwnershipLock) {
            bufferedTransition?.takeIf { it.session === session }?.let {
                it.cancelled = true
                pending = it.candidate
                // The SETUP thread still owns the barrier while it closes the earlier owner.
            }
            detachBufferedLocked(session).also {
                if (it != null && bufferedTransition == null) {
                    retirement = BufferedTransition(session, null).also { barrier -> barrier.retiring = it }
                    bufferedTransition = retirement
                }
            }
        }
        try {
            pending?.stream?.close()
            previous?.stream?.close()
        } finally {
            synchronized(bufferedOwnershipLock) {
                if (retirement != null && bufferedTransition === retirement &&
                    previous?.stream?.outputCleanupComplete == true) bufferedTransition = null
            }
        }
    }

    private fun replacePendingIapTunnel(session: AirPlaySession, owner: Closeable, next: PendingIapTunnel): Boolean {
        var previous: PendingIapTunnel? = null
        val installed = synchronized(streamOwnershipLock) {
            if (session.isClosed || streams[StreamKey(session, STREAM_TYPE_DATA)] !== owner) false
            else { previous = pendingIapTunnels.put(session, next); true }
        }
        previous?.bridge?.close()
        return installed
    }

    private fun clearPendingIapTunnel(session: AirPlaySession? = null) {
        if (session == null) {
            val pending = pendingIapTunnels.values.toList()
            pendingIapTunnels.clear()
            pending.forEach { it.bridge.close() }
            return
        }
        pendingIapTunnels.remove(session)?.bridge?.close()
    }

    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        return dataStreamKey(session, stream, DATASTREAM_OUTPUT_KEY)
    }

    private fun microphoneConfig(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
    ): MicrophoneConfig? {
        if (!microphoneEnabled || type != STREAM_TYPE_MAIN_AUDIO) return null
        if (!AppMicrophoneRequestPolicy.wantsMicrophone(format.audioType, stream)) return null
        val port = (stream["dataPort"] as? Number)?.toInt() ?: return null
        if (port !in 1..65535) return null
        val host = session.remoteAddress ?: return null
        val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val frameMillis = if (format.codec == AudioCodecKind.OPUS) {
            20
        } else if (framesPerPacket > 0) {
            Math.round(framesPerPacket * 1000.0 / format.sampleRate).toInt().coerceIn(5, 60)
        } else {
            MicrophoneConfig.pcmFrameMillis(format.sampleRate, format.channels)
        }
        val opusBitrate = when {
            formatBits and OPUS_48K != 0L -> 96_000
            formatBits and OPUS_24K != 0L -> 64_000
            else -> 48_000
        }
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = format.sampleRate,
            channels = format.channels,
            payloadType = type,
            frameMillis = frameMillis,
            host = host,
            port = port,
            key = key,
            codec = format.codec,
            bitrate = if (format.codec == AudioCodecKind.OPUS) opusBitrate else null,
            opusClockRate = MicrophoneConfig.opusClockRate(formatBits),
        )
    }

    private fun dataStreamKey(
        session: AirPlaySession,
        stream: Map<String, Any?>,
        label: String,
    ): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val connectionId = unsignedPlistDecimal(stream["streamConnectionID"]) ?: return null
        return AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )
    }

    private fun isScreenStreamType(type: Int): Boolean =
        type == STREAM_TYPE_MAIN_SCREEN || type == STREAM_TYPE_ALT_SCREEN

    private companion object {
        const val MICROPHONE_DOWNLINK_GRACE_MS = 500L
        const val TAG = "xcertplay-usb"
        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_DATA = 130
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        const val VIDEO_SETTINGS_STREAM_ID = 2L
        const val FIRST_REMOTE_CONTROL_STREAM_ID = 3L
        const val OPUS_24K = 0x20000000L
        const val OPUS_48K = 0x40000000L
    }
}

internal fun unsignedPlistDecimal(value: Any?): String? = when (value) {
    is Long -> local.headunit.diplay.NumericCompat.unsignedLong(value)
    is Int -> (value.toLong() and 0xffffffffL).toString()
    is Short -> (value.toInt() and 0xffff).toString()
    is Byte -> (value.toInt() and 0xff).toString()
    is BigInteger -> if (value.signum() >= 0) value.toString() else null
    else -> (value as? Number)?.toLong()?.let(local.headunit.diplay.NumericCompat::unsignedLong)
}

internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) BigInteger(local.headunit.diplay.NumericCompat.unsignedLong(value)) else value
    is Int -> if (value < 0) BigInteger((value.toLong() and 0xffffffffL).toString()) else value
    else -> value ?: 0L
}
