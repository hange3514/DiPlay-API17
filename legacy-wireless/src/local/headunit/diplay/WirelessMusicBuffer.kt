package local.headunit.diplay

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import org.concentus.OpusPacketInfo
import java.util.concurrent.TimeUnit

internal object WirelessMusicBuffer {
    const val START_MS = 450
    const val MAX_PREFILL_WAIT_MS = 575L
    val DRY_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(120)

    fun packetFrames(rtp: ByteArray, codec: AudioCodecKind, rate: Int, channels: Int): Int {
        val headerSize = MicrophonePacketizer.RTP_HEADER_LEN
        val payloadSize = rtp.size - headerSize
        if (payloadSize <= 0) return 0
        return when (codec) {
            AudioCodecKind.AAC_LC -> 1024
            AudioCodecKind.LPCM -> payloadSize / (2 * channels)
            AudioCodecKind.OPUS -> OpusPacketInfo.getNumSamples(
                rtp, headerSize, payloadSize, rate,
            ).coerceAtLeast(0)
        }
    }

}
