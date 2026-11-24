package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import local.headunit.diplay.BoundedPacketIo
import local.headunit.diplay.MediaWorkers
import java.io.IOException
import local.headunit.diplay.Ipv6StartupSummary
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Moves IPv6 packets between an Android VpnService tun and the iPhone NCM Ethernet link.
 *
 * NCM carries Ethernet frames while the tun is a layer-3 device, so this bridge strips and
 * restores the Ethernet II header. Link-local neighbor discovery stays in the Android kernel,
 * mirroring LIVI's reliance on the host kernel for NDP on its TAP interface.
 */
class Ipv6NcmBridge(
    private val ncm: NcmUsbBridge,
    private val tun: ParcelFileDescriptor,
    private val hostMac: ByteArray,
    private val onDiagnostic: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit,
) : Closeable {
    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private var loggedInbound = false
    private var loggedOutbound = false
    private var loggedWaitingForPeer = false
    private var inboundLogBudget = 8
    private var outboundLogBudget = 8
    private var neighborLogBudget = 4
    private var congestionLogBudget = 4
    private fun trace(message:String){Log.i(TAG,message);runCatching{onDiagnostic(message)}}
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val startupGate = local.headunit.diplay.NcmStartupGate()
    private val packets = BoundedPacketIo(tun.fd)
    private lateinit var ncmToTunThread: Thread
    private lateinit var tunToNcmThread: Thread

    @Synchronized fun start() {
        check(!closed.get()) { "bridge is closed" }
        check(running.compareAndSet(false, true)) { "bridge is already started" }
        ncmToTunThread = MediaWorkers.start("ncm-ipv6-in", ::runNcmToTun)
        tunToNcmThread = MediaWorkers.start("ncm-ipv6-out", ::runTunToNcm)
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        startupGate.close()
        try { packets.close() } finally {
            try { ncm.close() } finally {
                tun.close()
                if (::ncmToTunThread.isInitialized) join(ncmToTunThread)
                if (::tunToNcmThread.isInitialized) join(tunToNcmThread)
            }
        }
    }

    private fun runNcmToTun() {
        try {
            while (running.get()) {
                val frame = ncm.recv(READ_TIMEOUT_MILLIS) ?: continue
                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame) ?: continue
                peerMac = ipv6.sourceMac
                if (!loggedInbound) {
                    loggedInbound = true
                    trace(
                        "ncm first inbound ipv6 bytes=${ipv6.payloadLength} " +
                            "peer=${ipv6.sourceMac.macString()}",
                    )
                }
                if (inboundLogBudget > 0) {
                    inboundLogBudget--
                    trace("ncm startup inbound ${frame.summary(ipv6.payloadOffset)}")
                }
                if (!packets.write(frame, ipv6.payloadOffset, ipv6.payloadLength) && running.get() && congestionLogBudget>0){congestionLogBudget--;trace("ncm tun output congested; packet dropped")}
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun runTunToNcm() {
        val buffer = ByteArray(TUN_READ_BYTES)
        try {
            if (!startupGate.await() || !running.get()) return
            while (running.get()) {
                val length = packets.read(buffer)
                if (length == -1) {
                    if (running.get()) onError(IOException("NCM IPv6 tunnel closed"))
                    return
                }
                // Android's TUN fd may transiently report a zero-byte read while its network is
                // being registered. It is neither EOF (-1) nor an IPv6 packet.
                if (length == 0) {
                    LockSupport.parkNanos(ZERO_READ_BACKOFF_NANOS)
                    continue
                }
                val tunPacket = buffer.copyOf(length)
                val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
                if (ipv6.size != tunPacket.size && neighborLogBudget>0) {
                    neighborLogBudget--
                    trace("ncm added target-link-layer option to neighbor advertisement")
                }
                if (outboundLogBudget > 0) {
                    outboundLogBudget--
                    trace("ncm startup outbound ${ipv6.summary(0)}")
                }
                val multicastMac = EthernetIpv6Codec.multicastDestinationMac(ipv6)
                val mac = multicastMac ?: peerMac
                if (mac == null) {
                    if (!loggedWaitingForPeer) {
                        loggedWaitingForPeer = true
                        trace("ncm deferred outbound unicast bytes=$length until peer MAC is learned")
                    }
                    continue
                }
                if (!loggedOutbound) {
                    loggedOutbound = true
                    trace(
                        "ncm first outbound ipv6 bytes=$length destination=${mac.macString()} multicast=${multicastMac != null}",
                    )
                }
                val frame = EthernetIpv6Codec.build(hostMac, mac, ipv6)
                ncm.send(frame, WRITE_TIMEOUT_MILLIS)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            if (running.get()) onError(IOException("NCM outbound startup interrupted",error))
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    fun startOutbound() { startupGate.start() }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ByteArray.summary(offset: Int): String = Ipv6StartupSummary.describe(this,offset)

    private companion object {
        const val TAG = "xcertplay-usb"
        const val READ_TIMEOUT_MILLIS = 1_000L
        const val WRITE_TIMEOUT_MILLIS = 2_000
        const val TUN_READ_BYTES = 4_096
        const val ZERO_READ_BACKOFF_NANOS = 1_000_000L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
    }
}
