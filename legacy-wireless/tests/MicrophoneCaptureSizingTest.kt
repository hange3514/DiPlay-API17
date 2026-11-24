package local.headunit.diplay

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MicrophoneCaptureSizingTest {
    @Test fun lpcmReadsOnePacketInsteadOfABurst() {
        assertEquals(960, config(AudioCodecKind.LPCM, 32_000, 20).captureFramesPerPacket(48_000))
        assertEquals(480, config(AudioCodecKind.LPCM, 48_000, 10).captureFramesPerPacket(48_000))
        assertEquals(240, config(AudioCodecKind.LPCM, 48_000, 5).captureFramesPerPacket(48_000))
    }

    @Test fun usesTheHardwareRateWithoutChangingNegotiatedOpusClockOrFrameSize() {
        val opus = config(AudioCodecKind.OPUS, 48_000, 20).copy(opusClockRate = 24_000)
        assertEquals(882, opus.captureFramesPerPacket(44_100))
        assertEquals(1_920, opus.frameBytes)
        assertEquals(480, opus.rtpSamplesPerPacket)
    }

    @Test fun roundsFractionalHardwareFramesUp() {
        assertEquals(221, config(AudioCodecKind.LPCM, 48_000, 5).captureFramesPerPacket(44_100))
        assertEquals(1_920, config(AudioCodecKind.OPUS, 48_000, 20).captureFramesPerPacket(96_000))
    }

    @Test fun ALSAPartialReadsAreProgressAndResetTheNoDataDeadline() {
        val health = AlsaCaptureReadHealth(0)
        assertFalse(health.isStalled(0, 1_300_000_000))
        assertFalse(health.isStalled(2, 1_400_000_000))
        assertFalse(health.isStalled(0, 2_899_999_999))
        assertTrue(health.isStalled(0, 2_900_000_000))
    }

    @Test fun ALSACaptureDoesNotWaitForAReadCountWhenTheDeviceStops() {
        val health = AlsaCaptureReadHealth(0)
        assertFalse(health.isStalled(0, 100_000_000))
        assertTrue(health.isStalled(0, 1_500_000_000))
    }

    private fun config(codec: AudioCodecKind, sampleRate: Int, frameMillis: Int) =
        MicrophoneConfig(
            audioType = "telephony",
            sampleRate = sampleRate,
            channels = 1,
            payloadType = 96,
            frameMillis = frameMillis,
            host = InetAddress.getByName("127.0.0.1"),
            port = 7000,
            key = ByteArray(32),
            codec = codec,
        )
}
