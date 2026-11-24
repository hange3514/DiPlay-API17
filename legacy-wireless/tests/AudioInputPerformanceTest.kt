package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioInputPerformanceTest {
    @Test fun byteLimitKeepsNewestPacketsWithinTheBufferBudget() {
        val input = AudioInput(capacity = 320, maxQueuedBytes = 64) { 0L }
        input.offerOwned(ByteArray(20), 1, 240)
        input.offerOwned(ByteArray(20), 2, 480)
        input.offerOwned(ByteArray(40), 3, 960)
        assertEquals(1440, input.bufferedFrames())
        assertEquals(1L, input.drops())
        assertEquals(2, input.poll(0)!!.sample)
        assertEquals(3, input.poll(0)!!.sample)
        assertNull(input.poll(0))
    }

    @Test fun a700MillisecondTargetCanHoldShortOpusPackets() {
        val input = AudioInput(capacity = 320) { 0L }
        repeat(280) { input.offerOwned(ByteArray(20), it, 120) }
        assertEquals(0L, input.drops())
        assertEquals(33600, input.awaitPrefill(input.poll(0)!!, 33600, 0))
    }

    @Test fun boundedQueueTracksFramesAcrossOverflowPollAndClear() {
        val input = AudioInput(capacity = 3) { 0L }
        input.offerOwned(ByteArray(12), 1, 480)
        input.offerOwned(ByteArray(12), 2, 240)
        input.offerOwned(ByteArray(12), 3, 960)
        assertEquals(1680, input.bufferedFrames())

        input.offerOwned(ByteArray(12), 4, 480)
        assertEquals(1680, input.bufferedFrames())
        assertEquals(1L, input.drops())
        assertEquals(2, input.poll(0)!!.sample)
        assertEquals(1440, input.bufferedFrames())

        input.clear()
        assertEquals(0, input.bufferedFrames())
        assertEquals(1L, input.drops())
        assertNull(input.poll(0))
    }

    @Test fun bufferedFramesDiscardsOnlyTheExpiredPrefix() {
        var nowNs = 0L
        val input = AudioInput(maxAgeMs = 30) { nowNs }
        input.offerOwned(ByteArray(12), 1, 240)
        nowNs = TimeUnit.MILLISECONDS.toNanos(20)
        input.offerOwned(ByteArray(12), 2, 960)
        nowNs = TimeUnit.MILLISECONDS.toNanos(31)

        assertEquals(960, input.bufferedFrames())
        assertEquals(1L, input.drops())
        assertEquals(1, input.size())
        assertEquals(2, input.poll(0)!!.sample)
        assertEquals(0, input.bufferedFrames())
    }

    @Test fun ownershipIsExplicitAndInvalidPacketsDoNotCorruptFrameCount() {
        val input = AudioInput { 0L }
        val bytes = ByteArray(12)
        input.offer(bytes, 1, 480)
        input.offerOwned(bytes, 2, 480)
        input.offerOwned(ByteArray(11), 3, 480)
        assertEquals(960, input.bufferedFrames())
        assertEquals(1L, input.drops())
        assertNotSame(bytes, input.poll(0)!!.bytes)
        assertSame(bytes, input.poll(0)!!.bytes)
    }

    @Test fun clearEndsPendingPrefillWithoutCountingClearedPackets() {
        val enteredPrefill = CountDownLatch(1)
        val input = AudioInput {
            if (Thread.currentThread().name == "test-audio-prefill") enteredPrefill.countDown()
            0L
        }
        input.offerOwned(ByteArray(12), 1, 480)
        val first = input.poll(0)!!
        input.offerOwned(ByteArray(12), 2, 480)

        val executor = Executors.newSingleThreadExecutor { worker -> Thread(worker, "test-audio-prefill") }
        try {
            val wait = executor.submit<Int> { input.awaitPrefill(first, targetFrames = 2400, maxWaitMs = 5000) }
            assertTrue(enteredPrefill.await(2, TimeUnit.SECONDS))
            input.clear()
            assertEquals(0, wait.get(2, TimeUnit.SECONDS).toInt())
            assertEquals(0, input.bufferedFrames())
            assertEquals(0L, input.drops())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test(timeout = 2000) fun deadlinesUseRealTimeEvenWithFrozenAgeClock() {
        val input = AudioInput { 0L }
        assertNull(input.poll(2))
        input.offerOwned(ByteArray(12), 1, 480)
        assertEquals(480, input.awaitPrefill(input.poll(0)!!, targetFrames = 960, maxWaitMs = 2))
    }

    @Test(timeout = 2000) fun staleFirstPacketCannotConsumeTheWholePrefillBudget() {
        var nowNs = 0L
        val input = AudioInput(maxAgeMs = 100) { nowNs }
        input.offerOwned(ByteArray(12), 1, 480)
        val first = input.poll(0)!!
        nowNs = TimeUnit.MILLISECONDS.toNanos(95)
        assertEquals(480, input.awaitPrefill(first, targetFrames = 960, maxWaitMs = 5000))
    }
}
