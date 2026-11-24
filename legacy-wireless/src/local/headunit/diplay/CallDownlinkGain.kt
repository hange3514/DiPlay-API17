package local.headunit.diplay

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Lifts quiet received speech in place without changing the vehicle's volume or call route. */
internal class CallDownlinkGain(private val sampleRate: Int, private val channels: Int) {
    private val frameBytes = channels * 2
    private val analysisBytes = (sampleRate / 100) * frameBytes
    private val forgetAfterFrames = sampleRate / 2
    private var speechPower = 0.0
    private var quietFrames = 0
    private var gain = 1f
    private var maxGain = 1f
    private var peakLimitedBlocks = 0
    private var inputPeak = 0
    private var outputPeak = 0
    private var speechSamples = 0L
    private var speechInputEnergy = 0.0
    private var speechOutputEnergy = 0.0

    fun apply(pcm: ByteArray) {
        if (pcm.size % frameBytes != 0) return
        for (offset in pcm.indices step analysisBytes) {
            val end = minOf(offset + analysisBytes, pcm.size)
            process(pcm, offset, end)
        }
    }

    private fun process(pcm: ByteArray, offset: Int, end: Int) {
        val frames = (end - offset) / frameBytes
        val samples = frames * channels
        var peak = 0
        var energy = 0L
        for (index in offset until end step 2) {
            val value = sample(pcm, index)
            peak = maxOf(peak, abs(value))
            energy += value.toLong() * value
        }
        inputPeak = maxOf(inputPeak, peak)

        val power = energy.toDouble() / samples
        val rms = sqrt(power).toFloat()
        if (rms <= NOISE_FLOOR_RMS) {
            // Preserve background and silence even immediately after amplified speech.
            quietFrames = minOf(forgetAfterFrames, quietFrames + frames)
            gain = if (quietFrames == forgetAfterFrames) 1f else
                gain + (1f - gain) * coefficient(frames, 0.14)
            if (quietFrames == forgetAfterFrames) speechPower = 0.0
            outputPeak = maxOf(outputPeak, peak)
            return
        }
        quietFrames = 0
        val confidence = ((rms - NOISE_FLOOR_RMS) / NOISE_FLOOR_RMS).coerceIn(0f, 1f)
        // Smooth speech energy over elapsed audio time, independent of RTP packet duration.
        speechPower = if (speechPower == 0.0) power else
            speechPower + (power - speechPower) * coefficient(frames, 0.35) * confidence
        val desired = (SPEECH_TARGET_RMS / sqrt(speechPower)).toFloat().coerceIn(1f, MAX_GAIN)

        // Cap linked stereo gain on unexpected peaks and loud inputs before rendering.
        // Input already above the headroom target is preserved at unity.
        val peakGain = (PEAK_HEADROOM / peak.coerceAtLeast(1)).coerceAtLeast(1f)
        if (maxOf(gain, desired) > peakGain) peakLimitedBlocks++
        val rmsGain = (LOUD_SIGNAL_RMS / rms).coerceAtLeast(1f)
        val limit = minOf(peakGain, rmsGain, 1f + (MAX_GAIN - 1f) * confidence)
        val start = minOf(gain, limit)
        val target = minOf(desired, limit)
        val transitionSeconds = if (target > start) 0.08 else 0.045
        gain = start + (target - start) * coefficient(frames, transitionSeconds)
        maxGain = maxOf(maxGain, gain)
        speechSamples += samples
        speechInputEnergy += energy
        if (start == 1f && gain == 1f) {
            outputPeak = maxOf(outputPeak, peak)
            speechOutputEnergy += energy
            return
        }

        val step = (gain - start) / frames
        var outputEnergy = 0L
        for (frame in 0 until frames) {
            val frameGain = start + step * (frame + 1)
            val base = offset + frame * frameBytes
            for (index in base until base + frameBytes step 2) {
                val boosted = (sample(pcm, index) * frameGain).roundToInt().coerceIn(-32768, 32767)
                outputPeak = maxOf(outputPeak, abs(boosted))
                outputEnergy += boosted.toLong() * boosted
                pcm[index] = boosted.toByte()
                pcm[index + 1] = (boosted shr 8).toByte()
            }
        }
        speechOutputEnergy += outputEnergy
    }

    fun summary(): String {
        val measuredSamples = speechSamples.coerceAtLeast(1L)
        val inputRms = sqrt(speechInputEnergy / measuredSamples).roundToInt()
        val outputRms = sqrt(speechOutputEnergy / measuredSamples).roundToInt()
        return "callInputPeak=$inputPeak callOutputPeak=$outputPeak " +
            "callSpeechInputRms=$inputRms callSpeechOutputRms=$outputRms " +
            "callGainMaxPercent=${(maxGain * 100f).roundToInt()} callGainLimitedBlocks=$peakLimitedBlocks"
    }

    private fun coefficient(frames: Int, seconds: Double): Float =
        (1.0 - exp(-frames / (sampleRate * seconds))).toFloat()

    private fun sample(pcm: ByteArray, offset: Int): Int =
        ((pcm[offset].toInt() and 255) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()

    private companion object {
        const val MAX_GAIN = 2f
        const val SPEECH_TARGET_RMS = 4100f
        const val LOUD_SIGNAL_RMS = 5500f
        const val NOISE_FLOOR_RMS = 180f
        const val PEAK_HEADROOM = 29490f
    }
}
