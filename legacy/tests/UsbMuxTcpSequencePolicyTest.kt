package local.headunit.diplay

import com.shilapi.xcertplay.transport.UsbMuxTcpSequencePolicy
import org.junit.Assert.*
import org.junit.Test

class UsbMuxTcpSequencePolicyTest {
    @Test fun acceptsOnlyTheNextOrderedBytes() {
        val accepted = UsbMuxTcpSequencePolicy.receive(100, 100, 24, false)
        assertTrue(accepted.accepted)
        assertEquals(124, accepted.nextExpected)
        assertFalse(accepted.acceptFin)
    }

    @Test fun dropsDuplicateWithoutAdvancingTlsByteStream() {
        val duplicate = UsbMuxTcpSequencePolicy.receive(124, 100, 24, false)
        assertFalse(duplicate.accepted)
        assertEquals(124, duplicate.nextExpected)
        assertEquals(UsbMuxTcpSequencePolicy.Mismatch.DUPLICATE_OR_OLD, duplicate.mismatch)
    }

    @Test fun dropsGapAndKeepsAckAtMissingByte() {
        val gap = UsbMuxTcpSequencePolicy.receive(124, 148, 24, false)
        assertFalse(gap.accepted)
        assertEquals(124, gap.nextExpected)
        assertEquals(UsbMuxTcpSequencePolicy.Mismatch.GAP_OR_OUT_OF_ORDER, gap.mismatch)
    }

    @Test fun acceptsPayloadAndFinAtTheirExactSequence() {
        val fin = UsbMuxTcpSequencePolicy.receive(100, 100, 24, true)
        assertTrue(fin.accepted)
        assertTrue(fin.acceptFin)
        assertEquals(125, fin.nextExpected)
    }

    @Test fun sequenceArithmeticWrapsAt32Bits() {
        val wrapped = UsbMuxTcpSequencePolicy.receive(-2, -2, 4, false)
        assertTrue(wrapped.accepted)
        assertEquals(2, wrapped.nextExpected)
        val duplicate = UsbMuxTcpSequencePolicy.receive(2, -2, 4, false)
        assertFalse(duplicate.accepted)
        assertEquals(2, duplicate.nextExpected)
    }

    @Test fun sequenceClassificationWrapsAcrossSignedBoundary() {
        val ahead = UsbMuxTcpSequencePolicy.receive(Int.MAX_VALUE, Int.MIN_VALUE, 1, false)
        assertEquals(UsbMuxTcpSequencePolicy.Mismatch.GAP_OR_OUT_OF_ORDER, ahead.mismatch)
        val behind = UsbMuxTcpSequencePolicy.receive(Int.MIN_VALUE, Int.MAX_VALUE, 1, false)
        assertEquals(UsbMuxTcpSequencePolicy.Mismatch.DUPLICATE_OR_OLD, behind.mismatch)
    }
}
