package local.headunit.diplay

/** Bounded per-channel measurements from the original PCM16 LE, before downmixing. */
internal class MicrophoneInputLevels(private val channels: Int, private val maxFrames: Int) {
    init {
        require(channels in 1..8)
        require(maxFrames > 0)
    }

    private val peaks = IntArray(channels)
    private val rails = IntArray(channels)
    private val nearRails = IntArray(channels)
    private val signedSums = LongArray(channels)
    private val bytesPerFrame = channels * 2
    private var frames = 0

    /** Reads complete frames from this capture chunk without modifying or retaining its samples. */
    fun observe(pcm: ByteArray, capturedFrames: Int) {
        val count = minOf(capturedFrames, pcm.size / bytesPerFrame, maxFrames - frames)
        if (count <= 0) return
        var offset = 0
        repeat(count) {
            for (channel in 0 until channels) {
                val sample = ((pcm[offset].toInt() and 0xff) or
                    (pcm[offset + 1].toInt() shl 8)).toShort().toInt()
                offset += 2
                val magnitude = kotlin.math.abs(sample)
                peaks[channel] = maxOf(peaks[channel], magnitude)
                signedSums[channel] += sample
                if (magnitude >= NEAR_RAIL) nearRails[channel]++
                if (sample == Short.MIN_VALUE.toInt() || sample == Short.MAX_VALUE.toInt()) rails[channel]++
            }
        }
        frames += count
    }

    fun summary(): String? {
        if (frames == 0) return null
        val dc = signedSums.joinToString("/") { (it / frames).toString() }
        return "micInputFrames=$frames micInputChannels=$channels micInputPeak=${peaks.joinToString("/")} " +
            "micInputRails=${rails.joinToString("/")} micInputNearRails=${nearRails.joinToString("/")} micInputDc=$dc"
    }

    private companion object {
        const val NEAR_RAIL = 32700
    }
}
