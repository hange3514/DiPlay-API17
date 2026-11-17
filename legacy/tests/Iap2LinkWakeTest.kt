package local.headunit.diplay

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2LinkConfig
import com.shilapi.xcertplay.transport.Iap2LinkEngine
import com.shilapi.xcertplay.transport.Iap2TransportFlushReceipt
import com.shilapi.xcertplay.transport.Iap2LinkChannel
import com.shilapi.xcertplay.transport.WakeableBlockingDuplexByteStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

class Iap2LinkWakeTest {
    @Test fun queuedControlWakesTheUsbMuxReceiveWaitImmediately() {
        val stream = BlockingWakeStream()
        val link = Iap2LinkChannel.openTunnel(stream)
        try {
            assertTrue("link worker did not enter recv", stream.recvEntered.await(1, TimeUnit.SECONDS))
            assertTrue(link.sendControl(byteArrayOf(0x01)))
            assertTrue("outbound command did not wake recv", stream.woken.await(100, TimeUnit.MILLISECONDS))
            assertTrue("recv stayed blocked after the local wake", stream.wakeReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            link.close()
        }
    }

    @Test fun startSessionFlushWaitDoesNotReturnBeforeUnderlyingWriteCompletes() {
        val stream = LoopbackIap2Stream()
        val link = Iap2LinkChannel.openTunnel(stream)
        try {
            assertTrue("iAP2 negotiation did not finish", link.awaitReady(1_000))
            stream.blockNextWrite()
            val result = AtomicReference<Boolean>()
            val returned = AtomicBoolean(false)
            val sender = Thread {
                result.set(link.sendControlAwaitTransportFlush(byteArrayOf(0x22), 2_000))
                returned.set(true)
            }
            sender.start()
            assertTrue("iAP2 payload was not sent to the underlying stream", stream.blockedWrite.await(1, TimeUnit.SECONDS))
            assertFalse("flush wait returned while the underlying send was blocked", returned.get())
            stream.releaseWrite.countDown()
            sender.join(1_000)
            assertFalse("sender thread remained blocked after write completion", sender.isAlive)
            assertTrue("flush wait did not report the completed write", result.get() == true)
        } finally {
            stream.releaseWrite.countDown()
            link.close()
        }
    }

    @Test fun unrelatedAckOutputDoesNotCompleteAQueuedDataFrameReceipt() {
        val config = Iap2LinkConfig(maxOutgoing = 1, maxOutgoingDelta = 0, controlSessionVersion = 2)
        val local = Iap2LinkEngine(config)
        val peer = Iap2LinkEngine(config)
        local.start(wiredInitiator = true, nowMillis = 0)
        peer.start(wiredInitiator = false, nowMillis = 0)
        peer.takeOutput() // The peer's detection marker is not needed after the local initiator starts.
        peer.feed(local.takeOutput(), nowMillis = 1)
        local.feed(peer.takeOutput(), nowMillis = 2)
        peer.feed(local.takeOutput(), nowMillis = 3)
        assertTrue("local iAP2 link did not negotiate", local.writable())
        assertTrue("peer iAP2 link did not negotiate", peer.writable())

        local.sendSession(Iap2LinkEngine.CONTROL_SESSION_ID, byteArrayOf(0x11), nowMillis = 4)
        val first = local.takeOutput()
        local.sendSession(Iap2LinkEngine.CONTROL_SESSION_ID, byteArrayOf(0x12), nowMillis = 5)
        val second = local.takeOutput()
        val receipt = Iap2TransportFlushReceipt()
        local.sendSessionTracked(Iap2LinkEngine.CONTROL_SESSION_ID, byteArrayOf(0x13), nowMillis = 6, flushReceipt = receipt)
        val queued = local.takeOutputBatch()
        assertTrue("third packet should wait for the peer's ACK window", queued.bytes.isEmpty())
        assertTrue(queued.completedFlushReceipts.isEmpty())

        peer.sendSession(Iap2LinkEngine.CONTROL_SESSION_ID, byteArrayOf(0x55), nowMillis = 7)
        local.feed(peer.takeOutput(), nowMillis = 8)
        local.advanceTime(nowMillis = 508)
        val unrelatedAck = local.takeOutputBatch()
        assertTrue("the peer packet should produce a local ACK", unrelatedAck.bytes.isNotEmpty())
        assertTrue("an unrelated local ACK must not complete the queued data receipt", unrelatedAck.completedFlushReceipts.isEmpty())

        peer.feed(first, nowMillis = 9)
        peer.feed(second, nowMillis = 10)
        peer.advanceTime(nowMillis = 510)
        local.feed(peer.takeOutput(), nowMillis = 510)
        val transmittedTarget = local.takeOutputBatch()
        assertTrue("the tracked data frame should be emitted after the peer ACK", transmittedTarget.bytes.isNotEmpty())
        assertTrue("only the batch containing the tracked frame may carry its receipt", transmittedTarget.completedFlushReceipts.contains(receipt))
    }

    private class BlockingWakeStream : WakeableBlockingDuplexByteStream {
        val recvEntered = CountDownLatch(1)
        val woken = CountDownLatch(1)
        val wakeReturned = CountDownLatch(1)
        private val lock = Object()
        private var wakePending = false
        private var closed = false

        override fun send(data: ByteArray) = Unit

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            synchronized(lock) {
                recvEntered.countDown()
                if (!wakePending && !closed) lock.wait(timeoutMillis)
                if (wakePending && !closed) {
                    wakePending = false
                    wakeReturned.countDown()
                    return null
                }
                return if (closed) ByteArray(0) else null
            }
        }

        override fun wakeReceiver() {
            synchronized(lock) {
                wakePending = true
                woken.countDown()
                lock.notifyAll()
            }
        }

        override fun close() {
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
        }
    }

    private class LoopbackIap2Stream : WakeableBlockingDuplexByteStream {
        val blockedWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        private val lock = Object()
        private val peer = Iap2LinkEngine(
            Iap2LinkConfig(maxOutgoing = 4, controlSessionVersion = 2, zeroAcknowledgements = true),
        )
        private val incoming = ArrayDeque<ByteArray>()
        private var closed = false
        private var wakePending = false
        private var blockWrite = false

        init {
            peer.start(wiredInitiator = false, nowMillis = 0)
            incoming.addLast(peer.takeOutput())
        }

        override fun send(data: ByteArray) {
            val shouldBlock = synchronized(lock) {
                if (closed) throw IOException("loopback stream is closed")
                if (blockWrite) {
                    blockWrite = false
                    true
                } else false
            }
            if (shouldBlock) {
                blockedWrite.countDown()
                releaseWrite.await(1, TimeUnit.SECONDS)
            }
            synchronized(lock) {
                if (closed) throw IOException("loopback stream is closed")
                val now = System.nanoTime() / 1_000_000L
                peer.feed(data, now)
                peer.advanceTime(now)
                peer.takeOutput().takeIf { it.isNotEmpty() }?.let(incoming::addLast)
                lock.notifyAll()
            }
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            synchronized(lock) {
                val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
                while (incoming.isEmpty() && !closed && !wakePending) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return null
                    lock.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                }
                if (closed) return ByteArray(0)
                if (incoming.isEmpty() && wakePending) {
                    wakePending = false
                    return null
                }
                val next = incoming.removeFirst()
                if (next.size <= maxBytes) return next
                incoming.addFirst(next.copyOfRange(maxBytes, next.size))
                return next.copyOf(maxBytes)
            }
        }

        override fun wakeReceiver() {
            synchronized(lock) {
                wakePending = true
                lock.notifyAll()
            }
        }

        fun blockNextWrite() {
            synchronized(lock) { blockWrite = true }
        }

        override fun close() {
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
            releaseWrite.countDown()
        }
    }
}
