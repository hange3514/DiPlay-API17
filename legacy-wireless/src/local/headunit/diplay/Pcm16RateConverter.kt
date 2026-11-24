package local.headunit.diplay

import java.io.ByteArrayOutputStream

/** Stateful linear PCM16LE rate conversion. Keeps packet-boundary history to avoid clicks. */
class Pcm16RateConverter(
    private val inputRate: Int,
    private val outputRate: Int,
    private val channels: Int,
) {
    private var totalInputFrames = 0L
    private var nextOutputPosition = 0L
    private var history = ByteArray(0)

    init {
        require(inputRate in 8000..48000 && outputRate in 8000..48000)
        require(channels in 1..2)
    }

    @Synchronized
    fun convert(input: ByteArray): ByteArray {
        val frameBytes = channels * 2
        require(input.size % frameBytes == 0 && input.size <= 1024 * 1024)
        if (input.isEmpty()) return ByteArray(0)

        val inputFrames = input.size / frameBytes
        val historyFrames = history.size / frameBytes
        val bufferStart = totalInputFrames - historyFrames
        val endFrame = totalInputFrames + inputFrames
        val source = ByteArray(history.size + input.size)
        System.arraycopy(history, 0, source, 0, history.size)
        System.arraycopy(input, 0, source, history.size, input.size)
        val estimatedFrames = ((inputFrames.toLong() * outputRate + inputRate - 1) / inputRate + 2).toInt()
        val out = ByteArrayOutputStream(estimatedFrames * frameBytes)
        val pair = ByteArray(frameBytes)

        while (true) {
            val sourceFrame = nextOutputPosition / outputRate
            val fraction = (nextOutputPosition % outputRate).toInt()
            if (sourceFrame < bufferStart || sourceFrame >= endFrame) break
            if (fraction != 0 && sourceFrame + 1 >= endFrame) break
            val firstOffset = ((sourceFrame - bufferStart) * frameBytes).toInt()
            val secondOffset = if (fraction == 0) firstOffset else firstOffset + frameBytes
            for (channel in 0 until channels) {
                val at = channel * 2
                val first = read16(source, firstOffset + at)
                val value = if (fraction == 0) first else {
                    val second = read16(source, secondOffset + at)
                    (first + ((second - first).toLong() * fraction / outputRate).toInt())
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                }
                pair[at] = value.toByte()
                pair[at + 1] = (value shr 8).toByte()
            }
            out.write(pair)
            nextOutputPosition += inputRate.toLong()
        }

        val keepFrames = minOf(2, inputFrames + historyFrames)
        history = source.copyOfRange(source.size - keepFrames * frameBytes, source.size)
        totalInputFrames = endFrame
        return out.toByteArray()
    }

    private fun read16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
}
