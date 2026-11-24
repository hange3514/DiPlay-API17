package local.headunit.diplay

import java.io.Closeable
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Fresh DNS-SD evidence for one previously verified AirPlay peer on the current hotspot. */
internal class TargetedCarPlayResume(
    val peer: InetAddress,
    bluetoothId: String,
) : Closeable {
    val deadlineNanos: Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_800L)
    val probe = AtomicReference<Socket?>(null)
    private val dns = CarPlayControlDns(peer, bluetoothId)
    private val lock = Object()
    private var closed = false

    fun query(nowMillis: Long): ByteArray = dns.query(nowMillis)

    fun offer(packet: DatagramPacket, nowMillis: Long) {
        if (packet.address != peer || packet.port != MDNS_PORT) return
        if (dns.offer(packet.data, packet.offset, packet.length, packet.address, nowMillis).isEmpty()) return
        synchronized(lock) { if (!closed) lock.notifyAll() }
    }

    fun awaitEndpoint(timeoutMs: Long): CarPlayControlDns.Endpoint? {
        val deadline = minOf(deadlineNanos, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs))
        synchronized(lock) {
            while (!closed) {
                val now = System.nanoTime()
                val remaining = deadline - now
                if (remaining <= 0L) return null
                dns.endpoints(TimeUnit.NANOSECONDS.toMillis(now)).firstOrNull()?.let { return it }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining)
            }
        }
        return null
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        runCatching { probe.getAndSet(null)?.close() }
    }

    private companion object {
        const val MDNS_PORT = 5353
    }
}
