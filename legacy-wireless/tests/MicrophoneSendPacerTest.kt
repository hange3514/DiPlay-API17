package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneSendPacerTest {
    @Test fun fullCadenceWhenCaptureKeepsUp() {
        val pacer = MicrophoneSendPacer(frameNanos = 20_000_000L)
        assertEquals(0L, pacer.waitNanos(1_000_000L))
        assertFalse(pacer.isLate(1_000_000L))

        pacer.onPacketSent(sendStartedNanos = 1_000_000L, queuedFrames = 0)
        assertEquals(20_000_000L, pacer.waitNanos(1_000_000L))
        assertEquals(0L, pacer.waitNanos(21_000_000L))
        assertFalse(pacer.isLate(30_000_000L))
        assertTrue(pacer.isLate(31_000_000L))

        pacer.onPacketSent(sendStartedNanos = 60_000_000L, queuedFrames = 1)
        assertEquals(20_000_000L, pacer.waitNanos(60_000_000L))
    }

    @Test fun catchUpKeepsFifteenMillisecondGapUntilQueueHasRoom() {
        val pacer = MicrophoneSendPacer(frameNanos = 20_000_000L)
        pacer.onPacketSent(sendStartedNanos = 117_000_000L, queuedFrames = 3)
        assertEquals(15_000_000L, pacer.waitNanos(117_000_000L))

        pacer.onPacketSent(sendStartedNanos = 132_000_000L, queuedFrames = 2)
        assertEquals(15_000_000L, pacer.waitNanos(132_000_000L))

        pacer.onPacketSent(sendStartedNanos = 147_000_000L, queuedFrames = 1)
        assertEquals(20_000_000L, pacer.waitNanos(147_000_000L))
    }

    @Test fun realisticEncodingStallsCauseFewerLocalQueueDrops() {
        val normal = simulate(allowRecovery = true) { 6L }
        assertEquals(0, normal.first)
        assertEquals(20L, normal.second)

        val encodedMillis: (Int) -> Long = { frame ->
            when {
                frame == 0 -> 117L
                frame % 13 == 7 -> 50L
                else -> 6L
            }
        }
        val old = simulate(allowRecovery = false, encodedMillis)
        val recovered = simulate(allowRecovery = true, encodedMillis)
        assertTrue(recovered.first < old.first)
        assertEquals(15L, recovered.second)
    }

    /** Fixed 20 ms capture cadence and three PCM slots, with a single encode/send worker. */
    private fun simulate(allowRecovery: Boolean, encodeMillis: (Int) -> Long): Pair<Int, Long> {
        val frameMillis = 20L
        val frames = 250
        val queued = java.util.ArrayDeque<Int>()
        var nextFrame = 0
        var drops = 0
        var clock = 0L
        var previousPacket = -1L
        var shortestGap = Long.MAX_VALUE
        val pacer = MicrophoneSendPacer(frameMillis * 1_000_000L)

        fun captureThrough(now: Long) {
            while (nextFrame < frames && nextFrame * frameMillis <= now) {
                if (queued.size == 3) {
                    queued.removeFirst()
                    drops++
                }
                queued.addLast(nextFrame++)
            }
        }

        while (nextFrame < frames || queued.isNotEmpty()) {
            captureThrough(clock)
            if (queued.isEmpty()) {
                clock = nextFrame * frameMillis
                captureThrough(clock)
            }
            clock += encodeMillis(queued.removeFirst())
            clock += pacer.waitNanos(clock * 1_000_000L) / 1_000_000L
            captureThrough(clock)
            if (previousPacket >= 0) shortestGap = minOf(shortestGap, clock - previousPacket)
            previousPacket = clock
            pacer.onPacketSent(clock * 1_000_000L, if (allowRecovery) queued.size else 0)
        }
        return drops to shortestGap
    }
}
