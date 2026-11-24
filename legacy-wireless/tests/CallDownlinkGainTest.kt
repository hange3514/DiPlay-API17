package local.headunit.diplay

import org.junit.Assert.*
import org.junit.Test

class CallDownlinkGainTest {
    @Test fun liftsQuietSpeechWithinSixDecibels() {
        val gain = CallDownlinkGain(32000, 1)
        var output = ByteArray(0)
        repeat(30) {
            output = pcm(640, 1200)
            gain.apply(output)
        }
        assertTrue(sample(output, 639) in 2300..2400)
    }

    @Test fun leavesLoudSignalAndFullScalePeaksIntactAfterQuietSpeech() {
        val gain = CallDownlinkGain(32000, 1)
        repeat(30) { gain.apply(pcm(640, 1200)) }
        val loud = pcm(640, 20000)
        putSample(loud, 13, -32768)
        putSample(loud, 300, 32767)
        val before = loud.copyOf()
        gain.apply(loud)
        assertArrayEquals(before, loud)
    }

    @Test fun onePeakLimitsGainInBothStereoChannels() {
        val gain = CallDownlinkGain(48000, 2)
        repeat(30) { gain.apply(pcm(960 * 2, 900)) }
        val stereo = pcm(960 * 2, 900)
        putSample(stereo, 25, -24000)
        gain.apply(stereo)
        assertEquals(sample(stereo, 0), sample(stereo, 1))
        assertTrue(sample(stereo, 0) in 900..1107)
        assertTrue(sample(stereo, 25) in -29490..-24000)
    }

    @Test fun silenceAndVeryLowNoiseAreNotBoostedOnANewCall() {
        val gain = CallDownlinkGain(32000, 1)
        val silence = pcm(640, 0)
        val noise = pcm(640, 20)
        gain.apply(silence)
        gain.apply(noise)
        assertArrayEquals(pcm(640, 0), silence)
        assertArrayEquals(pcm(640, 20), noise)
    }

    @Test fun nearSilenceRemainsUnchangedAfterGainHasRisen() {
        val gain = CallDownlinkGain(32000, 1)
        repeat(30) { gain.apply(pcm(640, 1200)) }
        val quiet = pcm(640, 160)

        gain.apply(quiet)

        assertArrayEquals(pcm(640, 160), quiet)
        assertTrue(gain.summary().contains("callSpeechInputRms=1200"))
    }

    @Test fun loudPeakDoesNotTakeAwayGainForTheWholeLongPacket() {
        val gain = CallDownlinkGain(32000, 1)
        repeat(30) { gain.apply(pcm(640, 1200)) }
        val packet = pcm(3840, 1200)
        putSample(packet, 5, -32768)

        gain.apply(packet)

        assertEquals(-32768, sample(packet, 5))
        assertTrue(sample(packet, 3839) in 1900..2400)
    }

    @Test fun stableSpeechEnvelopeDoesNotChaseAlternatingSyllables() {
        val gain = CallDownlinkGain(32000, 1)
        repeat(100) {
            gain.apply(pcm(320, 1600))
            gain.apply(pcm(320, 2750))
        }
        val low = pcm(320, 1600)
        val high = pcm(320, 2750)
        gain.apply(low)
        gain.apply(high)

        val lowGain = sample(low, 319) / 1600f
        val highGain = sample(high, 319) / 2750f
        assertTrue(lowGain in 1.6f..1.95f)
        assertEquals(lowGain, highGain, 0.05f)
    }

    @Test fun gainRisesAtTheSamePaceForDifferentPacketDurations() {
        val short = CallDownlinkGain(32000, 1)
        val long = CallDownlinkGain(32000, 1)
        val longPacket = pcm(3840, 1400)
        val shortPackets = List(48) { pcm(80, 1400) }

        long.apply(longPacket)
        shortPackets.forEach(short::apply)

        assertEquals(sample(longPacket, 3839).toDouble(), sample(shortPackets.last(), 79).toDouble(), 2.0)
    }

    private fun pcm(samples: Int, value: Int): ByteArray =
        ByteArray(samples * 2).also { bytes ->
            repeat(samples) { putSample(bytes, it, value) }
        }

    private fun putSample(bytes: ByteArray, index: Int, value: Int) {
        bytes[index * 2] = value.toByte()
        bytes[index * 2 + 1] = (value shr 8).toByte()
    }

    private fun sample(bytes: ByteArray, index: Int): Int =
        ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)).toShort().toInt()
}
