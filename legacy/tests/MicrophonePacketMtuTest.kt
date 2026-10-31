package local.headunit.diplay

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MicrophonePacketMtuTest {
    @Test fun defaultPcmFramesProducePacketsWithinTheIpv6LinkMtu() {
        assertEquals(10, MicrophoneConfig.pcmFrameMillis(48_000, 1))
        assertEquals(10, MicrophoneConfig.pcmFrameMillis(44_100, 1))
        assertEquals(20, MicrophoneConfig.pcmFrameMillis(24_000, 1))
        assertEquals(20, MicrophoneConfig.pcmFrameMillis(16_000, 1))
        assertEquals(5, MicrophoneConfig.pcmFrameMillis(48_000, 2))

        for ((rate, channels) in listOf(48_000 to 1, 48_000 to 2, 44_100 to 1, 24_000 to 1, 16_000 to 1, 8_000 to 1)) {
            val millis = MicrophoneConfig.pcmFrameMillis(rate, channels)
            val config = MicrophoneConfig(
                audioType = "default",
                sampleRate = rate,
                channels = channels,
                payloadType = 100,
                frameMillis = millis,
                host = InetAddress.getByName("::1"),
                port = 49_152,
                key = ByteArray(32),
                codec = AudioCodecKind.LPCM,
            )
            val packet = MicrophonePacketizer.sealPacket(
                config.key,
                config.payloadType,
                MicrophoneCounters(),
                ByteArray(config.frameBytes),
                config.samplesPerPacket,
            )
            assertTrue("$rate Hz x$channels produced ${packet.size} UDP bytes", 40 + 8 + packet.size <= 1500)
        }
    }
}
