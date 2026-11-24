package com.shilapi.xcertplay.usbbt

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class HciCommandFailure(val status: Int, message: String) : IOException(message)

/** One owner of the USB event endpoint, including during controller initialization. */
internal class HciEventDispatcher(
    private val readEvent: (Int) -> HciProtocol.HciEvent?,
    private val writeCommand: (ByteArray, Int) -> Int,
) : Closeable {
    private class Pending(val opcode: Int, val statusCompletes: Boolean) {
        val ready = CountDownLatch(1)
        var result: Result<ByteArray>? = null // guarded by pendingLock
    }

    private val running = AtomicBoolean(true)
    private val commandLock = Any()
    private val pendingLock = Any()
    private var pending: Pending? = null
    private val events = LinkedBlockingQueue<HciProtocol.HciEvent>(256)
    @Volatile private var readFailure: Exception? = null
    private val reader = Thread(::receive, "UsbBtHciEvents").apply {
        isDaemon = true
        start()
    }

    fun sendCommand(opcode: Int, params: ByteArray, timeoutMs: Int): Result<ByteArray> = synchronized(commandLock) {
        require(timeoutMs > 0)
        val request = Pending(
            opcode,
            opcode == HciProtocol.OP_INQUIRY ||
                opcode == HciProtocol.OP_REMOTE_NAME_REQUEST ||
                opcode == HciProtocol.OP_CREATE_CONNECTION ||
                opcode == HciProtocol.OP_ACCEPT_CONNECTION_REQUEST ||
                opcode == HciProtocol.OP_REJECT_CONNECTION_REQUEST ||
                opcode == HciProtocol.OP_DISCONNECT ||
                opcode == HciProtocol.OP_AUTHENTICATION_REQUESTED ||
                opcode == HciProtocol.OP_SET_CONNECTION_ENCRYPTION,
        )
        synchronized(pendingLock) {
            if (!running.get()) return Result.failure(IOException("HCI event reader is closed"))
            pending = request
        }
        try {
            val started = System.nanoTime()
            val payload = HciProtocol.encodeCommand(opcode, params)
            val sent = writeCommand(payload, timeoutMs)
            if (sent != payload.size) {
                return Result.failure(IOException("control transfer sent $sent/${payload.size} bytes for opcode 0x${opcode.toString(16)}"))
            }
            val remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong()) - (System.nanoTime() - started)
            if (remaining > 0) request.ready.await(remaining, TimeUnit.NANOSECONDS)
            synchronized(pendingLock) {
                request.result ?: Result.failure(IOException("timeout waiting for HCI reply (opcode 0x${opcode.toString(16)})"))
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.failure(interrupted)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            synchronized(pendingLock) {
                if (pending === request) pending = null
            }
        }
    }

    fun pollEvent(timeoutMs: Int): HciProtocol.HciEvent? {
        val event = events.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (event == null) readFailure?.let { throw IOException("USB Bluetooth event receiver stopped", it) }
        return event
    }

    private fun receive() {
        try {
            while (running.get()) {
                val event = readEvent(200) ?: continue
                when (event.code) {
                    HciProtocol.EVT_COMMAND_COMPLETE -> {
                        val reply = HciProtocol.parseCommandComplete(event) ?: continue
                        val status = reply.returnParams.firstOrNull()?.toInt()?.and(0xFF) ?: 0xFF
                        finish(reply.opcode, if (status == 0) Result.success(reply.returnParams)
                            else failure("Command Complete", status, reply.opcode))
                    }
                    HciProtocol.EVT_COMMAND_STATUS -> {
                        val reply = HciProtocol.parseCommandStatus(event) ?: continue
                        synchronized(pendingLock) {
                            if (reply.status != 0) {
                                finish(reply.opcode, failure("Command Status", reply.status, reply.opcode))
                            } else if (pending?.statusCompletes == true) {
                                // Async commands finish later via Connection/Disconnection Complete.
                                finish(reply.opcode, Result.success(byteArrayOf(0)))
                            }
                        }
                    }
                    else -> if (!events.offer(event)) {
                        throw IOException("USB Bluetooth event queue exhausted")
                    }
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            readFailure = error
            failPending(error)
        } finally {
            running.set(false)
            failPending(IOException("HCI event reader stopped"))
        }
    }

    private fun failure(kind: String, status: Int, opcode: Int): Result<ByteArray> =
        Result.failure(HciCommandFailure(status, "$kind status=0x${status.toString(16)} for opcode 0x${opcode.toString(16)}"))

    private fun finish(opcode: Int, result: Result<ByteArray>) = synchronized(pendingLock) {
        pending?.takeIf { it.opcode == opcode && it.result == null }?.let {
            it.result = result
            it.ready.countDown()
        }
    }

    private fun failPending(error: Exception) = synchronized(pendingLock) {
        pending?.takeIf { it.result == null }?.let {
            it.result = Result.failure(error)
            it.ready.countDown()
        }
    }

    override fun close() {
        running.set(false)
        failPending(IOException("HCI closed"))
        reader.interrupt()
        if (reader !== Thread.currentThread()) {
            try {
                reader.join(1_000)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
