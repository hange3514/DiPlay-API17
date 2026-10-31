package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class Pcm16RateConverterTest {
    @Test
    fun telephony32kToMedia48kIsPacketBoundaryInvariant() {
        val input = ByteArray(6400 * 4)
        for (frame in 0 until 6400) {
            val sample = ((frame * 37) % 30000 - 15000).toShort().toInt()
            val left = frame * 4
            input[left] = sample.toByte()
            input[left + 1] = (sample shr 8).toByte()
            input[left + 2] = (-sample).toByte()
            input[left + 3] = ((-sample) shr 8).toByte()
        }

        val whole = Pcm16RateConverter(32000, 48000, 2).convert(input)
        val packetized = Pcm16RateConverter(32000, 48000, 2)
        val pieces = ArrayList<ByteArray>()
        for (offset in 0 until input.size step 2560) {
            pieces.add(packetized.convert(input.copyOfRange(offset, offset + 2560)))
        }
        val joined = ByteArray(pieces.sumOf { it.size })
        var at = 0
        for (piece in pieces) {
            System.arraycopy(piece, 0, joined, at, piece.size)
            at += piece.size
        }

        assertArrayEquals(whole, joined)
        assertEquals(9599, whole.size / 4)
    }

    @Test
    fun sameRateConversionPreservesSamples() {
        val input = byteArrayOf(0, 0, 0x34, 0x12, 0, 0x80.toByte(), 0xff.toByte(), 0x7f)
        val converted = Pcm16RateConverter(48000, 48000, 2).convert(input)
        assertArrayEquals(input, converted)
    }
}
