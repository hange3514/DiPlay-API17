package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Only the phone that owns the control session may open its auxiliary streams. */
internal fun ServerSocket.acceptFromPeer(expectedPeer: InetAddress, onRejected: () -> Unit = {}): Socket {
    val timeoutMillis = soTimeout
    val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
    var rejected = false
    try {
        while (true) {
            if (timeoutMillis > 0) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) throw SocketTimeoutException("Timed out waiting for the control-session peer")
                soTimeout = TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceAtLeast(1L).toInt()
            }
            val candidate = accept()
            // InetAddress compares the address bytes, not the IPv6 scope ID assigned by each side.
            if (candidate.inetAddress == expectedPeer) return candidate
            runCatching { candidate.close() }
            if (!rejected) {
                rejected = true
                runCatching { onRejected() }
            }
        }
    } finally {
        if (timeoutMillis > 0 && !isClosed) runCatching { soTimeout = timeoutMillis }
    }
}
