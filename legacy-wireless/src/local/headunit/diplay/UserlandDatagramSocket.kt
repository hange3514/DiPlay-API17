package local.headunit.diplay

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal class UserlandDatagramSocket(
    private val network: UserlandNcmNetwork,
    bindAddress: InetAddress,
) : DatagramSocket(null as SocketAddress?) {
    private val closed = AtomicBoolean(false)
    @Volatile private var fd = -1
    @Volatile private var reuse = true
    @Volatile private var timeoutMs = 0
    @Volatile private var receiveBuffer = 64 * 1024
    @Volatile private var sendBuffer = 64 * 1024
    @Volatile private var localAddress: InetAddress? = null
    @Volatile private var localPort = -1

    init {
        // DatagramSocket(null) still creates a platform socket implementation on API 17.
        // This wrapper uses lwIP exclusively, so release the unused Android socket first.
        super.close()
        val socket = network.openSocket(2)
        fd = socket
        try {
            network.setOption(socket, 1, if (reuse) 1 else 0)
            network.setOption(socket, 2, receiveBuffer)
            // The bundled lwIP socket API has no SO_SNDBUF implementation. UDP sends are
            // submitted directly to lwIP's bounded packet/output queues, so do not fail
            // socket creation by asking lwIP for this unsupported, nonessential option.
            network.bind(socket, bindAddress, 0)
            val local = network.socketName(socket)
            localAddress = local.address
            localPort = local.port
        } catch (error: Throwable) {
            fd = -1
            network.closeSocket(socket)
            throw asSocketException(error)
        }
    }

    override fun send(packet: DatagramPacket) {
        checkOpen()
        val target = packet.address ?: throw SocketException("datagram destination address is missing")
        val port = packet.port
        if (port !in 1..65535) throw SocketException("datagram destination port is invalid")
        try {
            val sent = network.sendTo(fd, packet.data, packet.offset, packet.length, target, port)
            if (sent != packet.length) throw IOException("lwIP sent $sent of ${packet.length} UDP bytes")
        } catch (error: IOException) {
            throw asSocketException(error)
        }
    }

    override fun receive(packet: DatagramPacket) {
        checkOpen()
        try {
            val result = network.receiveFrom(fd, packet.data, packet.offset, packet.length)
            packet.length = result.length
            packet.address = result.address
            packet.port = result.port
        } catch (timeout: SocketTimeoutException) {
            throw timeout
        } catch (error: IOException) {
            throw asSocketException(error)
        }
    }

    override fun bind(address: SocketAddress?) {
        if (address != null) throw SocketException("lwIP datagram socket is already bound")
        checkOpen()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val old = fd
        fd = -1
        network.closeSocket(old)
    }

    override fun isClosed(): Boolean = closed.get()
    override fun isBound(): Boolean = localPort >= 0
    override fun isConnected(): Boolean = false
    override fun getLocalAddress(): InetAddress = localAddress ?: InetAddress.getByName("::")
    override fun getLocalPort(): Int = if (closed.get()) -1 else localPort
    override fun getLocalSocketAddress(): SocketAddress? =
        if (closed.get() || localPort < 0) null else InetSocketAddress(localAddress, localPort)
    override fun getPort(): Int = -1
    override fun getInetAddress(): InetAddress? = null
    override fun getRemoteSocketAddress(): SocketAddress? = null
    override fun getChannel(): java.nio.channels.DatagramChannel? = null

    override fun setReuseAddress(on: Boolean) {
        checkOpen()
        reuse = on
        setOption(1, if (on) 1 else 0)
    }
    override fun getReuseAddress(): Boolean = reuse

    override fun setSoTimeout(timeout: Int) {
        if (timeout < 0) throw IllegalArgumentException("timeout < 0")
        checkOpen()
        timeoutMs = timeout
        setOption(6, timeout)
    }
    override fun getSoTimeout(): Int = timeoutMs

    override fun setReceiveBufferSize(size: Int) {
        if (size <= 0) throw IllegalArgumentException("receive buffer size must be positive")
        checkOpen()
        receiveBuffer = size
        setOption(2, size)
    }
    override fun getReceiveBufferSize(): Int = receiveBuffer

    override fun setSendBufferSize(size: Int) {
        if (size <= 0) throw IllegalArgumentException("send buffer size must be positive")
        checkOpen()
        sendBuffer = size
        // Retain the DatagramSocket API value. lwIP does not implement SO_SNDBUF for UDP.
    }
    override fun getSendBufferSize(): Int = sendBuffer

    override fun setBroadcast(on: Boolean) {
        // API 17's DatagramSocket constructor enables broadcast on its new socket. This wrapper
        // is IPv6-only and AirPlay timing/event traffic is unicast, so SO_BROADCAST has no
        // meaning here. Ignore the platform default rather than failing socket construction.
    }
    override fun getBroadcast(): Boolean = false
    override fun setTrafficClass(tc: Int) {
        if (tc !in 0..255) throw IllegalArgumentException("traffic class out of range")
    }
    override fun getTrafficClass(): Int = 0

    override fun connect(address: InetAddress?, port: Int) {
        throw SocketException("connected UDP is not used by the wired AirPlay path")
    }
    override fun connect(address: SocketAddress?) {
        throw SocketException("connected UDP is not used by the wired AirPlay path")
    }
    override fun disconnect() = Unit

    private fun setOption(option: Int, value: Int) {
        try { network.setOption(fd, option, value) } catch (error: IOException) { throw asSocketException(error) }
    }

    private fun checkOpen() {
        if (closed.get() || fd < 0) throw SocketException("datagram socket is closed")
    }

    private fun asSocketException(error: Throwable): SocketException =
        if (error is SocketException) error else SocketException(error.message ?: error.javaClass.simpleName).also { it.initCause(error) }
}
