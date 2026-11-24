package local.headunit.diplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneFrameQueueTest {
    @Test
    fun producerAndConsumerCanReuseTheirScratchBuffers() {
        val queue = MicrophoneFrameQueue(2)
        val capture = byteArrayOf(10, 11)
        val scratch = ByteArray(2)
        assertTrue(queue.offer(capture, 1))
        capture.fill(20)
        assertTrue(queue.offer(capture, 4))
        capture.fill(30)

        assertEquals(1L, queue.take(scratch))
        assertArrayEquals(byteArrayOf(10, 11), scratch)
        scratch.fill(40)
        assertEquals(4L, queue.poll(scratch))
        assertArrayEquals(byteArrayOf(20, 20), scratch)
        assertEquals(-1L, queue.poll(scratch))
        assertEquals(0L, queue.droppedFrames())
    }

    @Test
    fun fullQueueDropsOldestWithoutRenumberingRemainingFrames() {
        val queue = MicrophoneFrameQueue(1)
        val scratch = ByteArray(1)
        for (index in listOf(3, 4, 6, 10, 11)) {
            scratch[0] = index.toByte()
            assertTrue(queue.offer(scratch, index.toLong()))
        }

        assertEquals(3, queue.size())
        assertEquals(2L, queue.droppedFrames())
        for (index in listOf(6, 10, 11)) {
            assertEquals(index.toLong(), queue.poll(scratch))
            assertArrayEquals(byteArrayOf(index.toByte()), scratch)
        }
        assertEquals(0, queue.size())
    }

    @Test
    fun closeDiscardsPendingFramesAndRefusesNewFrames() {
        val queue = MicrophoneFrameQueue(1)
        val scratch = byteArrayOf(1)
        assertTrue(queue.offer(scratch, 0))
        queue.close()
        queue.close()

        assertEquals(0, queue.size())
        assertEquals(-1L, queue.take(scratch))
        assertFalse(queue.offer(scratch, 1))
    }

    @Test
    fun closeWakesAWaitingConsumer() {
        val queue = MicrophoneFrameQueue(1)
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val result = AtomicLong(0)
        val consumer = Thread {
            try {
                entered.countDown()
                result.set(queue.take(ByteArray(1)))
            } finally {
                returned.countDown()
            }
        }
        consumer.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (consumer.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.WAITING, consumer.state)
            queue.close()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertEquals(-1L, result.get())
        } finally {
            queue.close()
            consumer.join(2000)
        }
    }
}
