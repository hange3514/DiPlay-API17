package local.headunit.diplay

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-latency, call-uplink-only PCM cleanup. It runs after microphone capture
 * and resampling, immediately before packetization; it never changes routing,
 * capture source, timing, or the received call audio.
 */
internal class TelephonyMicFilter(private val sampleRate: Int) {
    private val cutoffHz = min(90.0, sampleRate * 0.025).coerceAtLeast(40.0)
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    private val noiseHistory = IntArray(64)
    private val sortedHistory = IntArray(64)
    private var historyCount = 0
    private var historyAt = 0
    private var packetsSinceEstimate = 0
    private var noiseFloor = 180.0
    private var gain = 1.0
    private val speechHoldSamples = sampleRate * 80 / 1_000
    private var speechRemaining = 0
    private var lastFilteredRms = 0
    private var lastOutputRms = 0

    init {
        require(sampleRate in 8000..48000)
        // 2nd order Butterworth high-pass: attenuates DC, wind, and low engine rumble.
        val omega = 2.0 * PI * cutoffHz / sampleRate
        val c = cos(omega)
        val alpha = sin(omega) / (2.0 * 0.7071067811865476)
        val a0 = 1.0 + alpha
        b0 = ((1.0 + c) * 0.5) / a0
        b1 = (-(1.0 + c)) / a0
        b2 = b0
        a1 = (-2.0 * c) / a0
        a2 = (1.0 - alpha) / a0
    }

    /** Process signed PCM16 little-endian mono samples in place. */
    fun process(pcm: ByteArray) {
        val samples = pcm.size / 2
        if (samples <= 0) return

        var sumSquares = 0.0
        for (i in 0 until samples) {
            val at = i * 2
            val input = ((pcm[at].toInt() and 0xff) or (pcm[at + 1].toInt() shl 8)).toShort().toDouble()
            val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = output
            val bounded = output.coerceIn(-32768.0, 32767.0)
            pcm[at] = bounded.toInt().toByte()
            pcm[at + 1] = (bounded.toInt() shr 8).toByte()
            sumSquares += bounded * bounded
        }

        val rms = sqrt(sumSquares / samples).toInt().coerceAtMost(32767)
        lastFilteredRms = rms
        // A gentle downward expander reduces noise between syllables without hard gating.
        val lower = max(110.0, noiseFloor * 1.45)
        val upper = max(lower + 1.0, noiseFloor * 3.0)
        val voiced = rms >= upper
        val holdSpeech = voiced || speechRemaining > 0
        speechRemaining = if (voiced) speechHoldSamples else (speechRemaining - samples).coerceAtLeast(0)
        // RMS alone cannot distinguish a loud cabin from ongoing speech. Learn only nearby
        // quiet levels outside the speech hold, so a long phrase cannot raise its own gate.
        if (!holdSpeech && rms in 1..lower.toInt()) updateNoiseEstimate(rms)
        val target = when {
            holdSpeech -> 1.0
            rms <= lower -> 0.36
            else -> 0.36 + 0.64 * ((rms - lower) / (upper - lower))
        }
        val startGain = gain
        if (target != gain) {
            val timeConstantSeconds = if (target < gain) 0.042 else 0.016
            val smoothing = 1.0 - exp(-samples / (sampleRate * timeConstantSeconds))
            gain += (target - gain) * smoothing
            if (target == 1.0 && gain > 0.99999) gain = 1.0
        }
        if (startGain == 1.0 && gain == 1.0) {
            lastOutputRms = rms
            return
        }

        var outputSquares = 0.0
        val gainPerSample = (gain - startGain) / samples
        for (i in 0 until samples) {
            val at = i * 2
            val input = ((pcm[at].toInt() and 0xff) or (pcm[at + 1].toInt() shl 8)).toShort().toDouble()
            // Ramp gain across each packet to avoid clicks at packet boundaries.
            val sampleGain = startGain + gainPerSample * (i + 1)
            val bounded = (input * sampleGain).coerceIn(-32768.0, 32767.0).toInt()
            pcm[at] = bounded.toByte()
            pcm[at + 1] = (bounded shr 8).toByte()
            outputSquares += bounded.toDouble() * bounded
        }
        lastOutputRms = sqrt(outputSquares / samples).toInt().coerceAtMost(32767)
    }

    private fun updateNoiseEstimate(rms: Int) {
        noiseHistory[historyAt] = rms
        historyAt = (historyAt + 1) % noiseHistory.size
        historyCount = min(historyCount + 1, noiseHistory.size)
        if (++packetsSinceEstimate < 8 || historyCount < 8) return
        packetsSinceEstimate = 0
        for (i in 0 until historyCount) sortedHistory[i] = noiseHistory[i]
        sortedHistory.sort(0, historyCount)
        val percentile = sortedHistory[(historyCount / 10).coerceAtMost(historyCount - 1)].toDouble()
        if (percentile <= 0.0) return
        val target = percentile.coerceIn(40.0, 1800.0)
        // Adapt slowly upward even when the low-energy window admits new background noise.
        val smoothing = if (target < noiseFloor) 0.18 else 0.025
        noiseFloor += (target - noiseFloor) * smoothing
    }

    fun diagnosticSummary(): String =
        "hpfHz=${cutoffHz.toInt()} noiseFloorRms=${noiseFloor.toInt()} gateGain=${(gain * 100).toInt()} filteredRms=$lastFilteredRms outputRms=$lastOutputRms"
}
