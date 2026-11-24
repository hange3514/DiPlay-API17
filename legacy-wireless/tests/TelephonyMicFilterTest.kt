package local.headunit.diplay

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class TelephonyMicFilterTest {
    @Test fun sustainedVoiceIsNotLearnedAsCabinNoise() {
        val filter = TelephonyMicFilter(SAMPLE_RATE)
        repeat(1_250) { filter.process(tone(1_400)) }
        val ratio = filteredRatio(filter, tone(1_400))
        assertTrue("Sustained speech fell to $ratio of input RMS", ratio >= 0.95)
    }

    @Test fun quietSyllablesAfterStrongSpeechSurviveShortGaps() {
        val filter = TelephonyMicFilter(SAMPLE_RATE)
        repeat(80) { filter.process(tone(2_200)) }
        repeat(3) {
            val ratio = filteredRatio(filter, tone(190))
            assertTrue("Syllable fell to $ratio of input RMS", ratio >= 0.90)
        }
    }

    @Test fun sustainedQuietCabinNoiseStillAttenuatesAfterSpeech() {
        val filter = TelephonyMicFilter(SAMPLE_RATE)
        repeat(80) { filter.process(tone(2_200)) }
        repeat(80) { filter.process(tone(180)) }
        val ratio = filteredRatio(filter, tone(180))
        assertTrue("Quiet background stayed at $ratio of input RMS", ratio in 0.30..0.50)
    }

    @Test fun highPassStillReducesLowFrequencyRumble() {
        val filter = TelephonyMicFilter(SAMPLE_RATE)
        repeat(50) { frame -> filter.process(tone(1_000, 25, frame * FRAME_SAMPLES)) }
        val ratio = filteredRatio(filter, tone(1_000, 25))
        assertTrue("25-Hz rumble stayed at $ratio of input RMS", ratio < 0.18)
    }

    private fun filteredRatio(filter: TelephonyMicFilter, bytes: ByteArray): Double {
        val inputRms = rms(bytes)
        filter.process(bytes)
        return rms(bytes) / inputRms
    }

    private fun rms(bytes: ByteArray): Double {
        var squares = 0L
        for (index in bytes.indices step 2) {
            val sample = ((bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8))
                .toShort().toLong()
            squares += sample * sample
        }
        return sqrt(squares.toDouble() / (bytes.size / 2))
    }

    private fun tone(amplitude: Int, hz: Int = 1_000, startSample: Int = 0): ByteArray =
        ByteArray(FRAME_SAMPLES * 2).also { bytes ->
            for (index in 0 until FRAME_SAMPLES) {
                val sample = (amplitude * sin(2 * PI * hz * (startSample + index) / SAMPLE_RATE)).toInt()
                bytes[index * 2] = sample.toByte()
                bytes[index * 2 + 1] = (sample shr 8).toByte()
            }
        }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960
    }
}
