package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
    val rtpClockRate: Int = sampleRate,
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
    private val bindAddress: InetAddress = local.headunit.diplay.LegacySockets.anyAddress(),
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onClosed(cause: Throwable) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        /** Errors are still reported; decrypted playback packets need no wire snapshot. */
        fun capturesSuccessfulPackets(): Boolean = true
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private val closed = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var started = false

    @Synchronized fun listen(listener: Listener): Pair<Int, Int> {
        check(!closed.get() && dataSocket == null) { "Audio stream already started or closed" }
        try {
        val data = bindAnyPort()
        dataSocket = data
        // Keep short Wi-Fi bursts in the kernel while decrypting or scheduling pauses
        // the receive thread. The platform may cap this request; log the actual size.
        val originalBufferBytes = runCatching { data.receiveBufferSize }.getOrDefault(0)
        if (originalBufferBytes < AUDIO_RECEIVE_BUFFER_BYTES) {
            runCatching { data.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES }
        }
        val actualBufferBytes = runCatching { data.receiveBufferSize }.getOrDefault(0)
        onDiagnostic(
            "Audio UDP receive buffer eventType=audio_rx_buffer streamType=$streamType " +
                "rxBufferOriginalBytes=$originalBufferBytes rxBufferRequestedBytes=$AUDIO_RECEIVE_BUFFER_BYTES " +
                "rxBufferActualBytes=$actualBufferBytes",
        )
        val control = try { bindAnyPort() } catch(e:Exception) { data.close();throw e }
        dataSocket = data
        controlSocket = control
        dataThread = local.headunit.diplay.MediaWorkers.start("airplay-audio-rx") { runData(data, listener) }
        controlThread = local.headunit.diplay.MediaWorkers.start("airplay-rtcp-rx") { runControl(control, listener) }
        return data.localPort to control.localPort
        } catch (failure: Throwable) { close(); throw failure }
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataSocket?.close()
        controlSocket?.close()
        dataThread?.interrupt()
        controlThread?.interrupt()
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        var invalidPacketCount = 0
        val stats = StreamReceiveStats("audio eventType=audio_rx_window streamType=$streamType", { window ->
            onDiagnostic("$window authFailuresTotal=${authenticationFailures.get()} invalidPacketsTotal=$invalidPacketCount")
        })
        val buffer = ByteArray(DATAGRAM_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        val aad = ByteArray(RTP_HEADER_LEN - 4)
        val nonce = ByteArray(12)
        try {
            while (!closed.get()) {
                packet.length = buffer.size
                try {
                    stats.reading()
                    socket.receive(packet)
                } catch (failure: Exception) {
                    if (closed.get()) return else throw failure
                }
                stats.received(
                    size = packet.length,
                    sequence = if (packet.length >= RTP_HEADER_LEN) {
                        ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                    } else null,
                    timestamp = if (packet.length >= RTP_HEADER_LEN) readU32Be(buffer, 4) else null,
                )
                val wireSize = packet.length
                val packetNumber = receivedPackets.incrementAndGet()
                if (wireSize < RTP_HEADER_LEN + TAIL_LEN || wireSize > MAX_RTP_BYTES + TAIL_LEN) {
                    invalidPacketCount++
                    if (packetNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType invalid packet bytes=$wireSize",
                        )
                    }
                    listener.onPacket(
                        buffer.copyOf(wireSize),
                        null,
                        null,
                        IOException("audio packet size out of bounds"),
                    )
                    stats.processed()
                    continue
                }

                buffer.copyInto(aad, 0, 4, RTP_HEADER_LEN)
                val sealedEnd = wireSize - NONCE_LEN
                val sealed = buffer.copyOfRange(RTP_HEADER_LEN, sealedEnd)
                buffer.copyInto(nonce, 4, sealedEnd, wireSize)
                val sample = readU32Be(buffer, 4)

                val decryptStartNs = System.nanoTime()
                val payload = try {
                    if(local.headunit.diplay.NativeCrypto.available) local.headunit.diplay.NativeCrypto.open(key,nonce,sealed,aad)
                    else AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
                } catch (error: Exception) {
                    val failureNumber = authenticationFailures.incrementAndGet()
                    if (failureNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType first decrypt failure bytes=$wireSize",
                            error,
                        )
                    }
                    listener.onPacket(buffer.copyOf(wireSize), null, sample, error)
                    stats.processed()
                    continue
                }
                stats.decrypted(System.nanoTime() - decryptStartNs, sealed.size)
                val rtp = ByteArray(RTP_HEADER_LEN + payload.size)
                buffer.copyInto(rtp, endIndex = RTP_HEADER_LEN)
                payload.copyInto(rtp, RTP_HEADER_LEN)
                val decryptedNumber = decryptedPackets.incrementAndGet()
                if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                            "wireBytes=$wireSize payloadBytes=${payload.size}",
                    )
                } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType decrypted=$decryptedNumber " +
                            "authFailures=${authenticationFailures.get()}",
                    )
                }
                if (listener.capturesSuccessfulPackets()) {
                    listener.onPacket(buffer.copyOf(wireSize), rtp, sample, null)
                }
                if (!started) {
                    started = true
                    listener.onStarted(sample)
                }
                listener.onRtp(rtp, sample)
                stats.processed()
            }
        } catch (failure: Exception) { failed(failure, listener) }
        finally { stats.flush(ended = true) }
    }

    private fun failed(error: Exception, listener: Listener) {
        val notify = !closed.get() && failureReported.compareAndSet(false, true)
        close()
        if (notify) {
            runCatching { onDiagnostic("audio_receive_failed type=$streamType error=${error.javaClass.simpleName}") }
            runCatching { listener.onClosed(error) }
        }
    }

    private fun runControl(socket: DatagramSocket, listener: Listener) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed.get()) {
            try {
                packet.length = buffer.size
                socket.receive(packet)
            } catch (error: Exception) {
                if (!closed.get()) failed(error, listener)
                return
            }
        }
    }

    private fun bindAnyPort(): DatagramSocket {
        return local.headunit.diplay.LegacySockets.udp(bindAddress)
    }

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_RTP_BYTES = 16 * 1024
        const val AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        // Match the renderer's RTP limit, including crypto overhead. The extra byte
        // lets us reject a truncated oversized datagram rather than misreporting authentication.
        const val DATAGRAM_BYTES = MAX_RTP_BYTES + TAIL_LEN + 1
        const val FIRST_PACKET_LOG_COUNT = 3
        const val PACKET_LOG_INTERVAL = 100
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        val isAacLc = (bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO)) != 0L
        val isOpus = (bits and OPUS_MONO) != 0L
        val pcm = PCM_FORMAT[bits]
        return when {
            isOpus -> AudioFormat(AudioCodecKind.OPUS, 48_000, 1, payloadType, audioType)
            isAacLc -> AudioFormat(
                AudioCodecKind.AAC_LC,
                if ((bits and AAC_LC_48K_STEREO) != 0L) 48_000 else 44_100,
                2,
                payloadType,
                audioType,
            )
            pcm != null -> AudioFormat(AudioCodecKind.LPCM, pcm.first, pcm.second, payloadType, audioType)
            else -> AudioFormat(AudioCodecKind.LPCM, 44_100, 2, payloadType, audioType)
        }
    }

    private const val AAC_LC_44K_STEREO = 0x400000L
    private const val AAC_LC_48K_STEREO = 0x800000L
    private const val OPUS_MONO = 0x10000000L or 0x20000000L or 0x40000000L

    private val PCM_FORMAT = mapOf(
        0x4L to (8_000 to 1),
        0x8L to (8_000 to 2),
        0x10L to (16_000 to 1),
        0x20L to (16_000 to 2),
        0x40L to (24_000 to 1),
        0x80L to (24_000 to 2),
        0x100L to (32_000 to 1),
        0x200L to (32_000 to 2),
        0x400L to (44_100 to 1),
        0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1),
        0x8000L to (48_000 to 2),
    )
}
