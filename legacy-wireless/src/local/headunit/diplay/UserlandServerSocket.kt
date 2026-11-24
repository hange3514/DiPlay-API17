package local.headunit.diplay

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

internal class UserlandServerSocket(
    private val network: UserlandNcmNetwork,
    private val bindAddress: InetAddress,
    private val bindPort: Int,
) : ServerSocket() {
    @Volatile private var fd = -1
    private val closed = AtomicBoolean(false)
    @Volatile private var reuse = true
    @Volatile private var receiveBuffer = 64 * 1024
    @Volatile private var boundAddress: InetAddress? = null
    @Volatile private var boundPort = -1

    init {
        // ServerSocket() creates a platform socket on API 17. Release it immediately; all
        // listening and accepted traffic for this wrapper is handled by lwIP below.
        super.close()
        if (bindPort !in 0..65535) throw IllegalArgumentException("port out of range")
        val socket = network.openSocket(1)
        fd = socket
        try {
            network.setOption(socket, 1, if (reuse) 1 else 0)
            network.setOption(socket, 2, receiveBuffer)
            network.bind(socket, bindAddress, bindPort)
            network.listen(socket, DEFAULT_BACKLOG)
            val local = network.socketName(socket)
            boundAddress = local.address
            boundPort = local.port
        } catch (error: Throwable) {
            fd = -1
            network.closeSocket(socket)
            throw asSocketException(error)
        }
    }

    override fun accept(): Socket {
        checkOpen()
        val accepted = try { network.accept(fd) } catch (error: Throwable) { throw asSocketException(error) }
        return try { UserlandSocket(network, accepted) } catch (error: Throwable) {
            network.closeSocket(accepted)
            throw asSocketException(error)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val old = fd
        fd = -1
        network.closeSocket(old)
    }

    override fun isClosed(): Boolean = closed.get()
    override fun isBound(): Boolean = boundPort >= 0
    override fun getInetAddress(): InetAddress? = boundAddress
    override fun getLocalPort(): Int = if (closed.get()) -1 else boundPort
    override fun getLocalSocketAddress(): SocketAddress? =
        if (closed.get() || boundPort < 0) null else InetSocketAddress(boundAddress, boundPort)

    @Synchronized override fun setReuseAddress(on: Boolean) {
        checkOpen()
        reuse = on
        try { network.setOption(fd, 1, if (on) 1 else 0) } catch (error: Throwable) { throw asSocketException(error) }
    }

    override fun getReuseAddress(): Boolean = reuse

    @Synchronized override fun setReceiveBufferSize(size: Int) {
        if (size <= 0) throw IllegalArgumentException("receive buffer size must be positive")
        checkOpen()
        receiveBuffer = size
        try { network.setOption(fd, 2, size) } catch (error: Throwable) { throw asSocketException(error) }
    }

    override fun getReceiveBufferSize(): Int = receiveBuffer
    override fun setSoTimeout(timeout: Int) {
        if (timeout < 0) throw IllegalArgumentException("timeout < 0")
        throw SocketException("lwIP accept timeout is not configured in this experiment")
    }
    override fun getSoTimeout(): Int = 0

    private fun checkOpen() {
        if (closed.get() || fd < 0) throw SocketException("server socket is closed")
    }

    private fun asSocketException(error: Throwable): SocketException =
        if (error is SocketException) error else SocketException(error.message ?: error.javaClass.simpleName).also { it.initCause(error) }

    companion object { private const val DEFAULT_BACKLOG = 16 }
}
