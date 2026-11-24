package com.shilapi.xcertplay.transport

import java.io.Closeable

/**
 * A blocking, ordered, full-duplex byte stream.
 *
 * [recv] returns `null` when its timeout expires and an empty array after the peer has ended its
 * output. A [WakeableBlockingDuplexByteStream] may also return `null` early after a local wake.
 * Implementations may split or coalesce writes arbitrarily. Calls belong on a worker thread, never
 * Android's main thread.
 */
interface BlockingDuplexByteStream : Closeable {
    fun send(data: ByteArray)

    fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray?

    override fun close()
}

/**
 * Optional wake-up path for streams whose receiver can wait on an internal queue. Producers call
 * [wakeReceiver] after adding outbound work so a duplex protocol pump does not have to wait for its
 * periodic read timeout before flushing that work. The next [recv] may return `null` immediately;
 * this is a local wake, not peer EOF.
 */
interface WakeableBlockingDuplexByteStream : BlockingDuplexByteStream {
    fun wakeReceiver()
}
