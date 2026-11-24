package local.headunit.diplay

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import com.shilapi.xcertplay.media.MediaCodecSupport
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class WirelessMusicBufferTest {
    @Test fun prefillCountsAllFramesInsteadOfMultiplyingByFirstPacketSize() {
        val queue = AudioInput(capacity = 96, maxAgeMs = 950) { 0L }
        queue.offer(ByteArray(12), 1, 960)
        val first = queue.poll(0)!!
        repeat(15) { queue.offer(ByteArray(12), it + 2, 1440) }

        assertEquals(22560, queue.awaitPrefill(first, targetFrames = 21600, maxWaitMs = 0))
        assertEquals(15, queue.size())
    }

    @Test fun prefillIgnoresExpiredPacketsAndWillNotStartFromAnExpiredFirstPacket() {
        var now = 0L
        val queue = AudioInput(capacity = 96, maxAgeMs = 950) { now }
        queue.offer(ByteArray(12), 1, 960)
        val first = queue.poll(0)!!
        queue.offer(ByteArray(12), 2, 960)
        now = TimeUnit.MILLISECONDS.toNanos(951)
        queue.offer(ByteArray(12), 3, 480)
        assertEquals(0, queue.awaitPrefill(first, targetFrames = 21600, maxWaitMs = 0))
        val fresh = queue.poll(0)!!
        assertEquals(3, fresh.sample)
        assertEquals(480, queue.awaitPrefill(fresh, targetFrames = 21600, maxWaitMs = 0))
    }

    @Test fun codecFramesAccountForVariablePacketSizes() {
        val header = MicrophonePacketizer.RTP_HEADER_LEN
        val stereoPcm = ByteArray(header + 1920)
        assertEquals(480, WirelessMusicBuffer.packetFrames(stereoPcm, AudioCodecKind.LPCM, 48000, 2))
        assertEquals(1024, WirelessMusicBuffer.packetFrames(ByteArray(header + 1), AudioCodecKind.AAC_LC, 44100, 2))

        val opusPacket = ByteArray(header + 4)
        opusPacket[header] = 0x98.toByte()
        assertEquals(960, WirelessMusicBuffer.packetFrames(opusPacket, AudioCodecKind.OPUS, 48000, 2))
        opusPacket[header] = 0x99.toByte()
        assertEquals(1920, WirelessMusicBuffer.packetFrames(opusPacket, AudioCodecKind.OPUS, 48000, 2))
        assertEquals(0, WirelessMusicBuffer.packetFrames(ByteArray(header), AudioCodecKind.OPUS, 48000, 2))
    }

    @Test fun directCodecWriteSkipsRtpHeaderAndUsesTheSameAdtsLayout() {
        val rtp = ByteArray(12) { 0x7f } + byteArrayOf(11, 12, 13)
        val output = ByteBuffer.allocate(13)
        output.position(3)
        assertEquals(10, MediaCodecSupport.putAdtsFrame(output, rtp, 12, 44100, 2))
        assertArrayEquals(
            byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x50, 0x80.toByte(), 0x01, 0x5f, 0xfc.toByte(), 11, 12, 13),
            output.array().copyOfRange(3, 13),
        )
    }

}
