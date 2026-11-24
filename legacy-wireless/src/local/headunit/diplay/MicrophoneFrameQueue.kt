package local.headunit.diplay

import java.io.Closeable

/** Three reusable PCM slots between capture and transmission. Delay stays bounded by dropping old audio. */
internal class MicrophoneFrameQueue(private val frameBytes: Int) : Closeable {
    init {
        require(frameBytes > 0)
    }

    private val lock = Object()
    private val frames = Array(CAPACITY) { ByteArray(frameBytes) }
    private val indices = LongArray(CAPACITY)
    private var head = 0
    private var pending = 0
    private var dropped = 0L
    private var lastSourceIndex = -1L
    private var closed = false

    /** Copies [pcm] so the capture thread can reuse it immediately. Returns false after [close]. */
    fun offer(pcm: ByteArray, sourceIndex: Long): Boolean {
        require(pcm.size == frameBytes)
        return synchronized(lock) {
            if (closed) return@synchronized false
            require(sourceIndex > lastSourceIndex) { "Microphone source index must advance" }
            if (pending == CAPACITY) {
                head = (head + 1) % CAPACITY
                pending--
                dropped++
            }
            val tail = (head + pending) % CAPACITY
            pcm.copyInto(frames[tail])
            indices[tail] = sourceIndex
            lastSourceIndex = sourceIndex
            pending++
            lock.notifyAll()
            true
        }
    }

    /** Copies the next complete frame into the caller's scratch buffer, or returns -1 after close. */
    @Throws(InterruptedException::class)
    fun take(into: ByteArray): Long {
        require(into.size == frameBytes)
        return synchronized(lock) {
            while (pending == 0 && !closed) lock.wait()
            removeFirst(into)
        }
    }

    /** Copies a queued frame without waiting. Returns -1 when the queue is empty or closed. */
    fun poll(into: ByteArray): Long {
        require(into.size == frameBytes)
        return synchronized(lock) { removeFirst(into) }
    }

    fun size(): Int = synchronized(lock) { pending }

    /** Counts frames displaced by the capacity limit. */
    fun droppedFrames(): Long = synchronized(lock) { dropped }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending = 0
            lock.notifyAll()
        }
    }

    private fun removeFirst(into: ByteArray): Long {
        if (pending == 0) return -1L
        frames[head].copyInto(into)
        val sourceIndex = indices[head]
        head = (head + 1) % CAPACITY
        pending--
        return sourceIndex
    }

    private companion object {
        const val CAPACITY = 3
    }
}
