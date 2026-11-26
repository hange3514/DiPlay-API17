package local.headunit.diplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class VideoPlayoutTimingTest {
    @Test fun firstAndLateOutputsAreImmediate() {
        val clock = VideoPlayoutTiming()
        assertEquals(at(101), clock.deadline(at(1000), at(100), at(101), false))
        assertEquals(at(170), clock.deadline(at(1066), at(169), at(170), false))
    }

    @Test fun modestBurstHoldsAtMostTenMillisecondsWithoutAddingBaseline() {
        val clock = VideoPlayoutTiming()
        clock.deadline(at(1000), at(100), at(100), false)
        assertEquals(at(151), clock.deadline(at(1066), at(140), at(141), false))
        assertEquals(at(232), clock.deadline(at(1132), at(227), at(228), false))
        assertEquals(at(303), clock.deadline(at(1198), at(302), at(303), false))
    }

    @Test fun lateFirstArrivalStopsHoldingOnceLaterFramesArriveSteadily() {
        val clock = VideoPlayoutTiming()
        val holds = (0 until 300).map { index ->
            val sender = at(1_000L + index * 50L)
            val arrival = at(100L + index * 50L + if (index == 0) 40L else 0L)
            val ready = arrival + at(3)
            clock.deadline(sender, arrival, ready, false) - ready
        }
        assertEquals(listOf(0L, at(10), at(10)), holds.take(3))
        assertTrue(holds.drop(3).all { it == 0L })
    }

    @Test fun compressedBurstStillHoldsBrieflyAcrossMultiplePackets() {
        val clock = VideoPlayoutTiming()
        clock.deadline(at(1000), at(100), at(101), false)
        for (index in 1..3) {
            val now = at(105L + index * 2L)
            assertEquals(now + at(10), clock.deadline(at(1000L + index * 50L), now - at(1), now, false))
        }
    }

    @Test fun pressureMissingClocksAndDiscontinuitiesRenderWithoutDelay() {
        val clock = VideoPlayoutTiming()
        clock.deadline(at(1000), at(100), at(100), false)
        assertEquals(at(140), clock.deadline(at(1066), at(139), at(140), true))
        assertEquals(at(141), clock.deadline(0L, at(140), at(141), false))
        assertEquals(at(150), clock.deadline(at(2000), at(149), at(150), false))
        assertEquals(at(152), clock.deadline(at(1900), at(151), at(152), false))
        clock.reset()
        assertEquals(at(161), clock.deadline(at(1900), at(160), at(161), false))
    }

    @Test fun ordinaryDrainKeepsEarlyOutputAndRendersItBeforeDequeueingAnother() {
        val wait=VideoPlayoutTiming.DrainAction.WAIT_FOR_HELD
        val render=VideoPlayoutTiming.DrainAction.RENDER_HELD
        val dequeue=VideoPlayoutTiming.DrainAction.DEQUEUE
        assertEquals(dequeue, VideoPlayoutTiming.drainAction(null, at(110), false))
        assertEquals(wait, VideoPlayoutTiming.drainAction(at(120), at(119), false))
        assertEquals(render, VideoPlayoutTiming.drainAction(at(120), at(120), false))
        assertEquals(dequeue, VideoPlayoutTiming.drainAction(at(120), at(119), true))
    }

    @Test fun pressureNeedsRealBacklogAndNeverCoalescesFirstOutput() {
        assertFalse(VideoPlayoutTiming.underPressure(6, 9, at(500), true))
        assertFalse(VideoPlayoutTiming.underPressure(2, 4, at(249), false))
        assertTrue(VideoPlayoutTiming.underPressure(3, 0, at(15), false))
        assertTrue(VideoPlayoutTiming.underPressure(1, 6, at(100), false))
        assertTrue(VideoPlayoutTiming.underPressure(1, 4, at(250), false))
    }

    @Test fun overflowRetainsNewestIdrAndOnlyItsFollowingDeltas() {
        val input = VideoInput(3)
        input.configure(byteArrayOf(1))
        input.offerOwned(idr, 1000, at(100))
        val pending = input.poll(0L)!!
        input.offerOwned(delta, 1001, at(115))
        input.offerOwned(idr, 1002, at(130))
        input.offerOwned(delta, 1003, at(145))
        assertEquals(VideoInput.OfferResult.REPLACED_WITH_KEYFRAME, input.offerOwned(delta, 1004, at(160)))
        assertFalse(input.reject(pending, at(161)))
        val first = input.poll(0L)!!
        assertTrue(first.startsGop)
        assertEquals(1002L, first.senderNs)
        assertEquals(at(130), first.arrivedNs)
        assertEquals(input.generation, first.generation)
        assertEquals(1003L, input.poll(0L)!!.senderNs)
        assertEquals(1004L, input.poll(0L)!!.senderNs)
        assertNull(input.poll(0L))
        assertEquals(1, input.takeMetrics().dropped)
    }

    @Test fun rejectDoesNotEraseNewerIdrOrConfig() {
        val input = VideoInput()
        val config = byteArrayOf(1)
        input.configure(config)
        input.offerOwned(idr, 1000, at(100))
        val pending = input.poll(0L)!!
        assertFalse(input.configure(config.copyOf()))
        input.offerOwned(delta, 1001, at(140))
        input.offerOwned(idr, 1002, at(150))
        assertFalse(input.reject(pending, at(400)))
        val retained = input.poll(0L)!!
        assertTrue(retained.startsGop)
        assertEquals(1002L, retained.senderNs)
        input.configure(byteArrayOf(2))
        input.offerOwned(idr, 1003, at(402))
        assertFalse(input.reject(retained, at(403)))
        assertEquals(1003L, input.poll(0L)!!.senderNs)
    }

    @Test fun overflowWithoutFreshIdrDemandsNewIndependentFrame() {
        val input = VideoInput(2)
        input.configure(byteArrayOf(1))
        input.offerOwned(idr, 1000, at(100))
        input.offerOwned(delta, 1001, at(200))
        assertEquals(VideoInput.OfferResult.QUEUE_OVERFLOW, input.offerOwned(delta, 1002, at(300)))
        assertEquals(VideoInput.OfferResult.WAITING_FOR_KEYFRAME, input.offerOwned(delta, 1003, at(305)))
        assertEquals(VideoInput.OfferResult.KEYFRAME, input.offerOwned(idr, 1004, at(308)))
        assertTrue(input.poll(0L)!!.startsGop)
    }

    @Test fun retirementWakesAnIdleDecoderAndRefusesOldFrames() {
        val input = VideoInput()
        input.configure(byteArrayOf(1))
        val waiting = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicReference<Any?>(Any())
        val decoder = Thread {
            waiting.countDown()
            try { result.set(input.poll(TimeUnit.SECONDS.toNanos(5))) } finally { finished.countDown() }
        }
        decoder.start()
        try {
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            input.retire()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertNull(result.get())
            assertEquals(VideoInput.OfferResult.WITHOUT_CONFIG, input.offerOwned(idr))
        } finally {
            decoder.interrupt()
            decoder.join(1000)
        }
    }

    private fun at(ms:Long) = TimeUnit.MILLISECONDS.toNanos(ms)

    companion object {
        private val idr = byteArrayOf(0,0,0,1,0x65)
        private val delta = byteArrayOf(0,0,0,1,0x41)
    }
}
