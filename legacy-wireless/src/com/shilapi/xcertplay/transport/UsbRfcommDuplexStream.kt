package com.shilapi.xcertplay.transport

import local.headunit.diplay.UsbBtHostStack
import java.io.IOException

/** Ordered iAP2 byte stream backed by the app-owned USB HCI/RFCOMM adapter. */
class UsbRfcommDuplexStream(private val host: UsbBtHostStack) : WakeableBlockingDuplexByteStream {
    @Volatile private var closed = false
    private val sendLock = Any()

    override fun send(data: ByteArray) {
        synchronized(sendLock) {
            if (closed) throw IOException("USB RFCOMM stream is closed")
            host.send(data)
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        if (closed) return EMPTY
        val chunk = host.receive(maxBytes, timeoutMillis)
        return if (closed) EMPTY else chunk
    }

    override fun wakeReceiver() = host.wakeReceiver()

    /** The service owns the Bluetooth host and USB connection. */
    override fun close() {
        closed = true
        wakeReceiver()
    }

    private companion object { val EMPTY = ByteArray(0) }
}
