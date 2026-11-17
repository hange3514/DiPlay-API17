package local.headunit.diplay

import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.Ntb16Codec
import com.shilapi.xcertplay.transport.Ntb16StreamDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class Ntb16StreamDecoderTest {
    @Test fun alignedBlockCompletesWithoutAnExplicitShortPacketPad() {
        val decoder = Ntb16StreamDecoder(512)
        assertEquals(listOf(alignedFrame.toList()), decoder.append(unpadded(alignedFrame, 1), 512).map { it.toList() })
    }

    @Test fun adjacentAlignedBlocksAndFollowingSmallBlockPreserveAllFrames() {
        val second = ByteArray(484) { (it * 17).toByte() }
        val joined = unpadded(alignedFrame, 1) + unpadded(second, 2) + Ntb16Codec.build(smallFrame, 3)
        val frames = Ntb16StreamDecoder(512).append(joined, joined.size)
        assertEquals(3, frames.size)
        assertArrayEquals(alignedFrame, frames[0])
        assertArrayEquals(second, frames[1])
        assertArrayEquals(smallFrame, frames[2])
    }

    @Test fun explicitZeroPadAndFollowingBlockCanShareACompletion() {
        val joined = Ntb16Codec.build(alignedFrame, 1) + Ntb16Codec.build(smallFrame, 2)
        val frames = Ntb16StreamDecoder(512).append(joined, joined.size)
        assertEquals(2, frames.size)
        assertArrayEquals(alignedFrame, frames[0])
        assertArrayEquals(smallFrame, frames[1])
    }

    @Test fun optionalPadAndNextHeaderCanArriveAcrossUsbCompletions() {
        val decoder = Ntb16StreamDecoder(512)
        assertArrayEquals(alignedFrame, decoder.append(unpadded(alignedFrame, 1), 512).single())
        assertTrue(decoder.append(byteArrayOf(0), 1).isEmpty())
        val following = Ntb16Codec.build(smallFrame, 2)
        assertTrue(decoder.append(following.copyOfRange(0, 5), 5).isEmpty())
        val result = decoder.append(following.copyOfRange(5, following.size), following.size - 5)
        assertArrayEquals(smallFrame, result.single())
    }

    @Test fun absentPadDoesNotConsumeTheNextNtbHeader() {
        val decoder = Ntb16StreamDecoder(512)
        assertArrayEquals(alignedFrame, decoder.append(unpadded(alignedFrame, 1), 512).single())
        val following = Ntb16Codec.build(smallFrame, 2)
        assertArrayEquals(smallFrame, decoder.append(following, following.size).single())
    }

    @Test fun completeIphoneControlTransferIsSkippedBeforeTheFirstNtb() {
        val diagnostics = mutableListOf<String>()
        val decoder = Ntb16StreamDecoder(512, diagnostics::add)
        assertTrue(decoder.append(byteArrayOf(0, 1, 2, 3), 4).isEmpty())
        val block = Ntb16Codec.build(smallFrame, 1)
        assertArrayEquals(smallFrame, decoder.append(block, block.size).single())
        assertEquals(listOf("ncm ignored iPhone bulk-in control transfer count=1"), diagnostics)
    }

    @Test fun repeatedCompleteIphoneControlTransferAtCleanBlockBoundaryIsIgnored() {
        val diagnostics = mutableListOf<String>()
        val decoder = Ntb16StreamDecoder(512, diagnostics::add)
        val first = Ntb16Codec.build(smallFrame, 1)
        val second = Ntb16Codec.build(alignedFrame, 2)
        assertArrayEquals(smallFrame, decoder.append(first, first.size).single())
        assertTrue(decoder.append(byteArrayOf(0, 1, 0x55, 0x66), 4).isEmpty())
        assertArrayEquals(alignedFrame, decoder.append(unpadded(alignedFrame, 3), 512).single())
        assertEquals(listOf("ncm ignored iPhone bulk-in control transfer count=1"), diagnostics)
    }

    @Test fun coalescedAndSplitBlocksSurviveDeterministicIrregularUsbReads() {
        val random = Random(0x4e434dL)
        val expected = (0 until 40).map { index ->
            val size = when (index % 5) {
                0 -> 60
                1 -> 484 // NTB exactly aligned to a 512-byte bulk packet.
                2 -> 996 // Two aligned 512-byte packets.
                3 -> 1500
                else -> 1536
            }
            ByteArray(size) { (index * 31 + it * 17).toByte() }
        }
        val wire = expected.foldIndexed(ByteArray(0)) { index, acc, frame ->
            acc + Ntb16Codec.build(frame, index and 0xffff)
        }
        val decoder = Ntb16StreamDecoder(512)
        val actual = ArrayList<ByteArray>(expected.size)
        var offset = 0
        while (offset < wire.size) {
            val length = minOf(1 + random.nextInt(1200), wire.size - offset)
            actual.addAll(decoder.append(wire.copyOfRange(offset, offset + length), length))
            offset += length
        }
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertArrayEquals(expected[it], actual[it]) }
    }

    @Test fun nonzeroAlignedPadDamageResynchronizesAtTheNextValidNtb() {
        val diagnostics = mutableListOf<String>()
        val decoder = Ntb16StreamDecoder(512, diagnostics::add)
        val first = unpadded(alignedFrame, 1) + byteArrayOf(0x55)
        assertArrayEquals(alignedFrame, decoder.append(first, first.size).single())
        val following = Ntb16Codec.build(smallFrame, 2)
        assertArrayEquals(smallFrame, decoder.append(following, following.size).single())
        assertEquals(
            listOf("NCM resynchronized after Invalid NTB16 short-packet pad; skipped=1 resyncs=1"),
            diagnostics,
        )
    }

    @Test fun packetAlignedPadUsesTheActualEndpointPacketSize() {
        val frame = ByteArray(36) { (it * 7).toByte() }
        val block = Ntb16Codec.build(frame, 1)
        assertEquals(64, block.size)
        val following = Ntb16Codec.build(smallFrame, 2)
        val joined = block + byteArrayOf(0) + following
        val frames = Ntb16StreamDecoder(64).append(joined, joined.size)
        assertEquals(2, frames.size)
        assertArrayEquals(frame, frames[0])
        assertArrayEquals(smallFrame, frames[1])
    }

    @Test fun repeatedDamagedPaddingFailsAfterTheBoundedRecoveryBudget() {
        val decoder = Ntb16StreamDecoder(512)
        val damaged = byteArrayOf(0x55) + Ntb16Codec.build(smallFrame, 1)
        try {
            repeat(17) { decoder.append(damaged, damaged.size) }
            throw AssertionError("Repeated framing corruption was accepted without a limit")
        } catch (error: IphoneUsbException.DeviceUnavailable) {
            assertTrue(error.message!!.contains("(repeated)"))
        }
    }

    private fun unpadded(frame: ByteArray, sequence: Int): ByteArray {
        val block = Ntb16Codec.build(frame, sequence)
        assertEquals(1, block.size % 512)
        return block.copyOf(block.size - 1)
    }

    companion object {
        private val alignedFrame = ByteArray(484) { (it * 31).toByte() }
        private val smallFrame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
    }
}
