package local.headunit.diplay

import local.headunit.diplay.CallPlayoutBuffer.Arrival
import org.junit.Assert.*
import org.junit.Test

class CallPlayoutBufferTest {
    @Test fun firstPacketGetsAReserveAndOrdinaryPacketCadenceDoesNotReprime() {
        val buffer = CallPlayoutBuffer(32_000)
        assertEquals(1536, buffer.targetFrames)
        assertEquals(60L, buffer.maxWaitMs)
        assertTrue(buffer.shouldPrefill(buffer.packetArrived(0L, 240), -1L))
        assertEquals(Arrival.CONTINUE, buffer.packetArrived(7_500_000L, 240))
        assertFalse(buffer.shouldPrefill(buffer.packetArrived(15_000_000L, 240), 0L))
    }

    @Test fun ringCadenceAfterVerifiedTrackDepletionReprimesWithoutChangingTheTrack() {
        val buffer = CallPlayoutBuffer(48_000)
        buffer.packetArrived(0L, 480)
        val firstGap = buffer.packetArrived(450_000_000L, 480)
        assertEquals(Arrival.CHECK_TRACK, firstGap)
        assertFalse(buffer.shouldPrefill(firstGap, 240L))
        assertFalse(buffer.shouldPrefill(firstGap, -1L))
        assertTrue(buffer.shouldPrefill(firstGap, 0L))
        assertEquals(Arrival.CONTINUE, buffer.packetArrived(460_000_000L, 480))
        assertEquals(Arrival.CHECK_TRACK, buffer.packetArrived(5_000_000_000L, 480))
    }

    @Test fun regularLongPacketsAreNotMistakenForAnInterruption() {
        val buffer = CallPlayoutBuffer(48_000)
        buffer.packetArrived(0L, 1920)
        assertEquals(Arrival.CONTINUE, buffer.packetArrived(40_000_000L, 1920))
        assertEquals(Arrival.CHECK_TRACK, buffer.packetArrived(120_000_000L, 1920))
    }
}
