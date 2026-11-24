package local.headunit.diplay

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal class UserlandSocket(
    private val network: UserlandNcmNetwork,
    acceptedFd: Int,
) : Socket() {
    private val closed = AtomicBoolean(false)
    @Volatile private var fd = acceptedFd
    init {
        // Release the unused API 17 platform descriptor before any lwIP lookup can throw.
        super.close()
    }

    @Volatile private var inputShutdown = false
    @Volatile private var outputShutdown = false
    @Volatile private var timeoutMs = 0
    @Volatile private var tcpNoDelay = false
    @Volatile private var keepAlive = false
    @Volatile private var receiveBuffer = 64 * 1024
    @Volatile private var sendBuffer = 64 * 1024
    @Volatile private var lingerSeconds = -1
    private val remote = network.socketName(acceptedFd, peer = true)
    private val local = network.socketName(acceptedFd)
    private val input = SocketInput()
    private val output = SocketOutput()

    override fun getInputStream(): InputStream {
        checkOpen()
        return input
    }

    override fun getOutputStream(): OutputStream {
        checkOpen()
        return output
    }

    override fun getInetAddress(): InetAddress = remote.address
    override fun getPort(): Int = remote.port
    override fun getLocalAddress(): InetAddress = local.address
    override fun getLocalPort(): Int = local.port
    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(remote.address, remote.port)
    override fun getLocalSocketAddress(): SocketAddress = InetSocketAddress(local.address, local.port)
    override fun isConnected(): Boolean = fd >= 0
    override fun isBound(): Boolean = fd >= 0
    override fun isClosed(): Boolean = closed.get()
    override fun isInputShutdown(): Boolean = inputShutdown || closed.get()
    override fun isOutputShutdown(): Boolean = outputShutdown || closed.get()
    override fun getChannel(): java.nio.channels.SocketChannel? = null

    override fun setTcpNoDelay(on: Boolean) {
        checkOpen()
        setOption(5, if (on) 1 else 0)
        tcpNoDelay = on
    }
    override fun getTcpNoDelay(): Boolean = tcpNoDelay

    override fun setKeepAlive(on: Boolean) {
        checkOpen()
        setOption(4, if (on) 1 else 0)
        keepAlive = on
    }
    override fun getKeepAlive(): Boolean = keepAlive

    override fun setSoTimeout(timeout: Int) {
        if (timeout < 0) throw IllegalArgumentException("timeout < 0")
        checkOpen()
        timeoutMs = timeout
        setOption(6, timeout)
    }
    override fun getSoTimeout(): Int = timeoutMs

    override fun setSendBufferSize(size: Int) {
        if (size <= 0) throw IllegalArgumentException("send buffer size must be positive")
        checkOpen()
        sendBuffer = size
        setOption(3, size)
    }
    override fun getSendBufferSize(): Int = sendBuffer

    override fun setReceiveBufferSize(size: Int) {
        if (size <= 0) throw IllegalArgumentException("receive buffer size must be positive")
        checkOpen()
        receiveBuffer = size
        setOption(2, size)
    }
    override fun getReceiveBufferSize(): Int = receiveBuffer

    override fun setSoLinger(on: Boolean, linger: Int) {
        if (on && linger < 0) throw IllegalArgumentException("linger < 0")
        if (on && linger > 65535) throw IllegalArgumentException("linger > 65535")
        checkOpen()
        setOption(8, if (on) linger else -1)
        lingerSeconds = if (on) linger else -1
    }
    override fun getSoLinger(): Int = lingerSeconds

    override fun setTrafficClass(tc: Int) {
        if (tc !in 0..255) throw IllegalArgumentException("traffic class out of range")
        checkOpen()
    }
    override fun getTrafficClass(): Int = 0

    override fun shutdownInput() {
        checkOpen()
        network.shutdown(fd, SHUT_RD)
        inputShutdown = true
    }

    override fun shutdownOutput() {
        checkOpen()
        network.shutdown(fd, SHUT_WR)
        outputShutdown = true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val old = fd
        fd = -1
        network.closeSocket(old)
    }

    override fun connect(endpoint: SocketAddress?) {
        throw SocketException("outbound TCP connect is not used by this AirPlay listener")
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        throw SocketException("outbound TCP connect is not used by this AirPlay listener")
    }

    override fun bind(bindpoint: SocketAddress?) {
        throw SocketException("accepted lwIP sockets cannot be rebound")
    }

    private fun setOption(option: Int, value: Int) {
        try { network.setOption(fd, option, value) } catch (error: IOException) { throw asSocketException(error) }
    }

    private fun checkOpen() {
        if (closed.get() || fd < 0) throw SocketException("socket is closed")
    }

    private fun asSocketException(error: IOException): SocketException =
        if (error is SocketException) error else SocketException(error.message ?: error.javaClass.simpleName).also { it.initCause(error) }

    private inner class SocketInput : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            checkOpen()
            if (inputShutdown) return -1
            return try {
                network.receive(fd, buffer, offset, length).let { if (it == 0) -1 else it }
            } catch (timeout: SocketTimeoutException) {
                throw timeout
            } catch (error: IOException) {
                if (closed.get()) -1 else throw error
            }
        }

        override fun available(): Int = 0
        override fun close() { if (!closed.get() && !inputShutdown) shutdownInput() }
    }

    private inner class SocketOutput : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
            checkOpen()
            if (outputShutdown) throw SocketException("socket output is shut down")
            var sent = 0
            while (sent < length) {
                val count = network.send(fd, buffer, offset + sent, length - sent)
                if (count <= 0) throw IOException("lwIP send returned $count")
                sent += count
            }
        }

        override fun flush() = Unit
        override fun close() { if (!closed.get() && !outputShutdown) shutdownOutput() }
    }

    companion object {
        private const val SHUT_RD = 0
        private const val SHUT_WR = 1
    }
}
