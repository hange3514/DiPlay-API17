package local.headunit.diplay

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** Bounded jitter queue for one audio worker. */
class AudioInput(
    private val capacity: Int = 12,
    private val maxQueuedBytes: Int = 512 * 1024,
    private val now: () -> Long = System::nanoTime,
) {
    data class Packet(val bytes: ByteArray, val sample: Int, val arrivedNs: Long, val audioFrames: Int)

    init {
        require(capacity > 0)
        require(maxQueuedBytes >= 12)
    }

    private val queue = ArrayDeque<Packet>(capacity)
    private val monitor = java.lang.Object()
    private val maxAgeNs = TimeUnit.MILLISECONDS.toNanos(250)
    private var queuedFrames = 0L
    private var queuedBytes = 0
    private var dropped = 0L
    private var clearGeneration = 0L

    fun offer(bytes: ByteArray, sample: Int) = enqueue(bytes, sample, 0, copy = true)

    /** Transfers an RTP buffer that the producer will leave unmodified. */
    fun offerOwned(bytes: ByteArray, sample: Int, audioFrames: Int) =
        enqueue(bytes, sample, audioFrames, copy = false)

    private fun enqueue(bytes: ByteArray, sample: Int, audioFrames: Int, copy: Boolean) {
        if (bytes.size !in 12..minOf(16384, maxQueuedBytes)) {
            drop()
            return
        }
        val data = if (copy) bytes.copyOf() else bytes
        synchronized(monitor) {
            val packet = Packet(data, sample, now(), audioFrames.coerceAtLeast(0))
            while (queue.size == capacity || data.size > maxQueuedBytes - queuedBytes) {
                removeFirst()
                dropped++
            }
            queue.addLast(packet)
            queuedFrames += packet.audioFrames
            queuedBytes += packet.bytes.size
            monitor.notifyAll()
        }
    }

    fun expired(packet: Packet): Boolean = now() - packet.arrivedNs > maxAgeNs

    /** Hold the first packet outside the queue while waiting for the rest of the reserve. */
    internal fun awaitPrefill(first: Packet, targetFrames: Int, maxWaitMs: Long): Int = synchronized(monitor) {
        if (Thread.interrupted()) throw InterruptedException()
        val generation = clearGeneration
        val started = System.nanoTime()
        val firstAge = (now() - first.arrivedNs).coerceAtLeast(0L)
        if (firstAge > maxAgeNs) return@synchronized 0
        val budget = minOf(TimeUnit.MILLISECONDS.toNanos(maxWaitMs), maxAgeNs - firstAge)
        while (generation == clearGeneration) {
            val current = now()
            if (current - first.arrivedNs > maxAgeNs) return@synchronized 0
            discardExpired(current)
            val buffered = minOf(Int.MAX_VALUE.toLong(), first.audioFrames + queuedFrames).toInt()
            if (buffered >= targetFrames) return@synchronized buffered
            val remaining = budget - (System.nanoTime() - started)
            if (remaining <= 0) return@synchronized buffered
            TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
        }
        0
    }

    fun drop() {
        synchronized(monitor) { dropped++ }
    }

    fun drops(): Long = synchronized(monitor) { dropped }
    fun size(): Int = synchronized(monitor) { queue.size }
    fun clear() {
        synchronized(monitor) {
            queue.clear()
            queuedFrames = 0
            queuedBytes = 0
            clearGeneration++
            monitor.notifyAll()
        }
    }

    fun poll(timeoutMs: Long): Packet? = synchronized(monitor) {
        if (Thread.interrupted()) throw InterruptedException()
        val generation = clearGeneration
        val started = System.nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (queue.isEmpty() && generation == clearGeneration) {
            val remaining = budget - (System.nanoTime() - started)
            if (remaining <= 0) return@synchronized null
            TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
        }
        if (generation != clearGeneration) return@synchronized null
        discardExpired(now())
        removeFirst()
    }

    private fun discardExpired(current: Long) {
        while (true) {
            val packet = queue.peekFirst() ?: return
            if (current - packet.arrivedNs <= maxAgeNs) return
            removeFirst()
            dropped++
        }
    }

    private fun removeFirst(): Packet? = queue.pollFirst()?.also {
        queuedFrames -= it.audioFrames
        queuedBytes -= it.bytes.size
    }
}
