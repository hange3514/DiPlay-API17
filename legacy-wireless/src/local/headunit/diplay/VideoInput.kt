package local.headunit.diplay

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** Keeps complete H.264 reference chains or resumes at a retained IDR after a discontinuity. */
class VideoInput(private val capacity: Int = 6) {
    data class Frame(
        val config: ByteArray,
        val bytes: ByteArray,
        val arrivedNs: Long,
        val senderNs: Long,
        val generation: Long,
        val startsGop: Boolean,
    )

    enum class OfferResult(val acceptedKeyframe: Boolean = false, val recoveryReason: String? = null) {
        WITHOUT_CONFIG,
        WAITING_FOR_KEYFRAME(recoveryReason = "waiting_for_keyframe"),
        FRAME_TOO_LARGE(recoveryReason = "frame_too_large"),
        QUEUE_OVERFLOW(recoveryReason = "queue_overflow"),
        ACCEPTED,
        KEYFRAME(acceptedKeyframe = true),
        REPLACED_WITH_KEYFRAME(acceptedKeyframe = true)
    }

    data class Metrics(val depth: Int, val peak: Int, val dropped: Int)

    private val lock = java.lang.Object()
    private val queue = ArrayDeque<Frame>(capacity)
    private var config: ByteArray? = null
    private var waitingForKeyframe = false
    private var retired = false
    private var peak = 0
    private var dropped = 0L
    private var reportedDrops = 0L
    @Volatile var generation = 0L
        private set

    fun configure(bytes: ByteArray): Boolean = synchronized(lock) {
        if (retired || config?.contentEquals(bytes) == true) return false
        config = bytes.copyOf()
        discardQueue()
        advanceGeneration()
        true
    }

    fun offer(bytes: ByteArray, senderNs: Long = 0L, arrivedNs: Long = System.nanoTime()): OfferResult =
        enqueue(bytes, false, senderNs, arrivedNs)

    /** Ownership moves to this queue. The producer must never mutate/reuse bytes after this call. */
    fun offerOwned(bytes: ByteArray, senderNs: Long = 0L, arrivedNs: Long = System.nanoTime()): OfferResult =
        enqueue(bytes, true, senderNs, arrivedNs)

    private fun enqueue(bytes: ByteArray, owned: Boolean, senderNs: Long, arrivedNs: Long): OfferResult =
        synchronized(lock) {
            val currentConfig = config ?: return OfferResult.WITHOUT_CONFIG
            if (bytes.isEmpty() || retired) return OfferResult.WITHOUT_CONFIG
            // A missing access unit invalidates subsequent deltas until the next IDR.
            if (bytes.size > MAX_FRAME_BYTES) {
                dropped++
                discardQueue()
                advanceGeneration()
                return OfferResult.FRAME_TOO_LARGE
            }
            val startsGop = waitingForKeyframe
            if (startsGop && !isKeyframe(bytes)) {
                dropped++
                return OfferResult.WAITING_FOR_KEYFRAME
            }
            val frame = Frame(currentConfig, if (owned) bytes else bytes.copyOf(), arrivedNs, senderNs, generation, startsGop)
            if (startsGop) {
                waitingForKeyframe = false
                append(frame)
                return OfferResult.KEYFRAME
            }
            if (queue.size < capacity) {
                append(frame)
                return OfferResult.ACCEPTED
            }
            recoverAtNewestIdr(frame, arrivedNs - MAX_DELTA_AGE_NS)
            if (waitingForKeyframe) OfferResult.QUEUE_OVERFLOW else OfferResult.REPLACED_WITH_KEYFRAME
        }

    private fun append(frame: Frame) {
        queue.addLast(frame)
        peak = maxOf(peak, queue.size)
        lock.notifyAll()
    }

    fun poll(timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(100)): Frame? = synchronized(lock) {
        val observedGeneration = generation
        val deadline = System.nanoTime() + timeoutNs
        while (queue.isEmpty() && !retired && generation == observedGeneration) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return null
            TimeUnit.NANOSECONDS.timedWait(lock, remaining)
        }
        queue.pollFirst()
    }

    fun size(): Int = synchronized(lock) { queue.size }

    fun discardFrames() = synchronized(lock) {
        if (retired) return
        discardQueue()
        advanceGeneration()
    }

    /** Reject a pending unit without erasing an IDR already delivered by the producer. */
    fun reject(frame: Frame, nowNs: Long): Boolean = synchronized(lock) {
        if (retired || generation != frame.generation) return false
        dropped++
        recoverAtNewestIdr(null, nowNs - MAX_DELTA_AGE_NS)
        waitingForKeyframe
    }

    private fun recoverAtNewestIdr(incoming: Frame?, oldestNs: Long) {
        val candidates = queue.toMutableList()
        incoming?.let { candidates.add(it) }
        val first = candidates.indexOfLast { it.arrivedNs >= oldestNs && isKeyframe(it.bytes) }
            .takeIf { it >= 0 && candidates.size - it <= capacity }
        dropped += first ?: candidates.size
        queue.clear()
        advanceGeneration()
        if (first != null) {
            waitingForKeyframe = false
            for (index in first until candidates.size) {
                append(candidates[index].copy(generation = generation, startsGop = index == first))
            }
        }
    }

    /** Wake an old decoder wait immediately on Surface or stream replacement. */
    fun retire() = synchronized(lock) {
        if (retired) return
        retired = true
        discardQueue()
        config = null
        advanceGeneration()
    }

    fun droppedCount(): Long = synchronized(lock) { dropped }

    fun takeMetrics(): Metrics = synchronized(lock) {
        val windowDrops = (dropped - reportedDrops).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        Metrics(queue.size, peak, windowDrops).also { peak = queue.size; reportedDrops = dropped }
    }

    private fun advanceGeneration() {
        generation++
        waitingForKeyframe = true
        lock.notifyAll()
    }

    private fun discardQueue() {
        dropped += queue.size
        queue.clear()
    }

    companion object {
        const val MAX_FRAME_BYTES = 1024 * 1024
        const val MAX_DELTA_AGE_NS = 250_000_000L
        const val MAX_IDR_AGE_NS = 750_000_000L

        fun isKeyframe(bytes: ByteArray): Boolean {
            val annexB = bytes.size >= 4 && bytes[0].toInt() == 0 && bytes[1].toInt() == 0 &&
                bytes[2].toInt() == 0 && bytes[3].toInt() == 1
            if (!annexB) {
                var offset = 0
                while (offset + 4 < bytes.size) {
                    val size = ((bytes[offset].toInt() and 255) shl 24) or
                        ((bytes[offset + 1].toInt() and 255) shl 16) or
                        ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
                    offset += 4
                    if (size <= 0 || size > bytes.size - offset) return false
                    if (bytes[offset].toInt() and 31 == 5) return true
                    offset += size
                }
                return false
            }
            for (index in 0 until bytes.size - 3) {
                if (bytes[index].toInt() != 0 || bytes[index + 1].toInt() != 0) continue
                val nal = if (bytes[index + 2].toInt() == 1) index + 3
                else if (bytes[index + 2].toInt() == 0 && bytes[index + 3].toInt() == 1) index + 4
                else -1
                if (nal in bytes.indices && bytes[nal].toInt() and 31 == 5) return true
            }
            return false
        }
    }
}
