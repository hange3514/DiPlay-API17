package com.shilapi.xcertplay.usbbt

import java.util.concurrent.TimeUnit

/** Controller ACL buffer credits, scoped to the lifetime of each ACL handle. */
internal class HciAclWindow {
    class Link(val handle: Int) {
        var outstanding = 0
    }

    private val lock = java.lang.Object()
    private val links = HashMap<Int, Link>()
    private var limit = 0
    private var available = 0
    private var closed = false

    fun initialize(packetCount: Int) = synchronized(lock) {
        require(packetCount > 0)
        limit = packetCount
        available = packetCount
        lock.notifyAll()
    }

    fun connected(handle: Int) = synchronized(lock) {
        if (closed) return@synchronized
        links.put(handle, Link(handle))?.let { release(it, it.outstanding) }
        lock.notifyAll()
    }

    fun disconnected(handle: Int) = synchronized(lock) {
        val link = links.remove(handle) ?: return@synchronized
        release(link, link.outstanding)
        lock.notifyAll()
    }

    fun completed(handle: Int, count: Int) = synchronized(lock) {
        val link = links[handle] ?: return@synchronized
        release(link, count)
        lock.notifyAll()
    }

    fun find(handle: Int): Link? = synchronized(lock) { links[handle] }

    fun isCurrent(link: Link): Boolean = synchronized(lock) { links[link.handle] === link }

    fun acquire(link: Link, deadlineNanos: Long): Boolean = synchronized(lock) {
        while (links[link.handle] === link) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) break
            if (available > 0) {
                available--
                link.outstanding++
                return true
            }
            TimeUnit.NANOSECONDS.timedWait(lock, remaining)
        }
        false
    }

    fun returnUnsent(link: Link) = synchronized(lock) {
        if (links[link.handle] === link) {
            release(link, 1)
            lock.notifyAll()
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        links.clear()
        lock.notifyAll()
    }

    private fun release(link: Link, completed: Int) {
        val released = minOf(link.outstanding, completed)
        link.outstanding -= released
        available = (available + released).coerceAtMost(limit)
    }
}
