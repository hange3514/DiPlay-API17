package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

internal data class NtpTimingEvidence(
    val validatedReplies: Long = 0L,
    val lastReplyNs: Long = 0L,
    val probeAttempts: Long = 0L,
    val lastProbeNs: Long = 0L,
)

/**
 * CarPlay timing-port clock sync.
 *
 * The accessory drives a two-way RTCP-style exchange with the phone: PT_REQUEST (210) carries our
 * transmit timestamp and PT_RESPONSE (211) echoes it while adding the phone's receive and
 * transmit stamps. The resulting offset and round-trip time steer a local monotonic clock onto
 * the phone's media-clock domain, which /feedback reports.
 */
class NtpClock : Closeable {
    private val running = AtomicBoolean(false)
    private val socketLock = Any()
    private val clockLock = Any()
    private val senderWake = java.lang.Object()
    private var recoveryProbeUntilNs = 0L // guarded by senderWake

    private var socket: DatagramSocket? = null
    private var receiver: Thread? = null
    private var sender: Thread? = null
    @Volatile private var peer: InetSocketAddress? = null
    @Volatile private var timingEvidence = NtpTimingEvidence()

    private var clockOffsetNs = wallClockNtpOffsetNs(System.nanoTime())
    private val delays = DoubleArray(DELAY_WINDOW) { Double.POSITIVE_INFINITY }
    private var delayIndex = 0
    private var pickCount = PICK_COUNT
    private var pickRtt = Double.POSITIVE_INFINITY
    private var pickOffset = 0.0
    private var pendingT1: BigInteger? = null
    private val recentProbes = LinkedHashMap<BigInteger, Long>() // guarded by clockLock
    private var synced = false

    @Synchronized fun listen(address: InetAddress = local.headunit.diplay.LegacySockets.anyAddress()): Int {
        synchronized(socketLock) { socket?.let { return it.localPort } }
        val bound = local.headunit.diplay.LegacySockets.udp(address)
        synchronized(socketLock) { socket = bound }
        running.set(true)
        receiver = Thread(::runReceiver, "airplay-ntp-rx").apply { isDaemon = true; start() }
        return bound.localPort
    }

    @Synchronized fun start(peerAddress: InetAddress, port: Int) {
        require(port in 1..65535) { "timing port out of range" }
        peer = InetSocketAddress(peerAddress, port)
        if (sender?.isAlive == true) return
        sender = Thread(::runSender, "airplay-ntp-tx").apply { isDaemon = true; start() }
    }

    /** Now in the phone's synchronized clock domain, as an unsigned NTP64 value. */
    fun syncedNtp(): BigInteger = synchronized(clockLock) {
        ntpFromNanos(System.nanoTime() + clockOffsetNs)
    }

    internal fun timingEvidence(): NtpTimingEvidence = timingEvidence

    internal fun prepareLinkRecovery(): Boolean = synchronized(senderWake) {
        if (!running.get() || peer == null) return false
        val now = System.nanoTime()
        val end = recoveryProbeUntilNs
        val lastReplyNs = timingEvidence.lastReplyNs
        // Repeated requests during a stall share one short probe burst. Only a verified
        // reply after that burst restores eligibility for a future incident.
        if (end != 0L && (now - end < 0L || lastReplyNs == 0L || lastReplyNs - end <= 0L)) return false
        recoveryProbeUntilNs = now + RECOVERY_DURATION_NS
        senderWake.notifyAll()
        true
    }

    @Synchronized override fun close() {
        running.set(false)
        synchronized(socketLock) {
            socket?.close()
            socket = null
        }
        sender?.interrupt()
        receiver?.interrupt()
    }

    private fun runSender() {
        while (running.get()) {
            try {
                sendRequest()
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                // Reuse the same bounded cadence when the hotspot route is down.
            }
            try {
                waitForNextRequest()
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun waitForNextRequest() = synchronized(senderWake) {
        val completedNs = System.nanoTime()
        while (running.get()) {
            val now = System.nanoTime()
            val interval = if (recoveryProbeUntilNs != 0L && now - recoveryProbeUntilNs < 0L) {
                RECOVERY_INTERVAL_NS
            } else {
                REQUEST_INTERVAL_NS
            }
            val remaining = interval - (now - completedNs)
            if (remaining <= 0L) return
            senderWake.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
    }

    private fun sendRequest() {
        val destination = peer ?: return
        val sending = currentSocket()?.takeUnless { it.isClosed } ?: return
        val packet = ByteArray(NTP_PACKET_BYTES)
        packet[0] = 0x80.toByte()
        packet[1] = PT_REQUEST.toByte()
        writeU16Be(packet, 2, 7)
        val t1 = synchronized(clockLock) {
            val now = System.nanoTime()
            ntpFromNanos(now + clockOffsetNs).also { stamp ->
                pendingT1 = stamp
                recentProbes[stamp] = now
                if (recentProbes.size > MAX_RECENT_PROBES) {
                    val oldest = recentProbes.entries.iterator()
                    oldest.next()
                    oldest.remove()
                }
                val state = timingEvidence
                timingEvidence = state.copy(probeAttempts = state.probeAttempts + 1L, lastProbeNs = now)
            }
        }
        ntpBytes(t1).copyInto(packet, NTP_TRANSMIT_OFFSET)
        try {
            sending.send(DatagramPacket(packet, packet.size, destination))
        } catch (failure: Exception) {
            synchronized(clockLock) {
                recentProbes.remove(t1)
                if (pendingT1 == t1) pendingT1 = null
            }
            throw failure
        }
    }

    private fun runReceiver() {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (running.get()) {
            val sock = currentSocket() ?: return
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (_: Exception) {
                if (running.get()) continue else return
            }
            val message = packet.data.copyOf(packet.length)
            val address = packet.address
            if (message.size >= NTP_PACKET_BYTES && address != null) {
                try {
                    handleMessage(message, InetSocketAddress(address, packet.port))
                } catch (_: IOException) {
                    // The socket may close while responding, or the radio may temporarily lose its route.
                    if (!running.get()) return
                }
            }
        }
    }

    private fun handleMessage(message: ByteArray, source: InetSocketAddress) {
        when (message[1].toInt() and 0xff) {
            PT_REQUEST -> respondToRequest(message, source)
            PT_RESPONSE -> handleResponse(message, source)
        }
    }

    private fun respondToRequest(request: ByteArray, source: InetSocketAddress) {
        val response = ByteArray(NTP_PACKET_BYTES)
        response[0] = 0x80.toByte()
        response[1] = PT_RESPONSE.toByte()
        writeU16Be(response, 2, 7)
        request.copyInto(response, NTP_ORIGINATE_OFFSET, NTP_TRANSMIT_OFFSET, NTP_PACKET_BYTES)
        ntpBytes(syncedNtp()).copyInto(response, NTP_RECEIVE_OFFSET)
        ntpBytes(syncedNtp()).copyInto(response, NTP_TRANSMIT_OFFSET)
        currentSocket()?.send(DatagramPacket(response, response.size, source))
    }

    private fun handleResponse(response: ByteArray, source: InetSocketAddress) {
        val t4 = syncedNtp()
        val t1 = readNtp(response, NTP_ORIGINATE_OFFSET)
        val t2 = readNtp(response, NTP_RECEIVE_OFFSET)
        val t3 = readNtp(response, NTP_TRANSMIT_OFFSET)

        // The recovery burst can have several requests in flight. Verify the echo
        // against recent send times independently of the clock's latest-sample filter.
        if (source == peer && response[0].toInt() and 0xc0 == 0x80 &&
            response[2] == 0.toByte() && response[3] == 7.toByte() &&
            t2.signum() != 0 && t3 >= t2
        ) {
            val now = System.nanoTime()
            synchronized(clockLock) {
                val sent = recentProbes.remove(t1)
                if (sent != null && now - sent in 0L..MAX_PROBE_AGE_NS) {
                    val state = timingEvidence
                    timingEvidence = state.copy(validatedReplies = state.validatedReplies + 1L, lastReplyNs = now)
                }
            }
        }

        synchronized(clockLock) {
            val pending = pendingT1
            if (pending == null || pending != t1) return
            pendingT1 = null
        }

        val offset = t2.subtract(t1).add(t3.subtract(t4)).toDouble() * 0.5 / TWO32_DOUBLE
        val rtt = t4.subtract(t1).subtract(t3.subtract(t2)).toDouble() / TWO32_DOUBLE
        if (rtt < 0.0) return

        if (rtt < pickRtt) {
            pickRtt = rtt
            pickOffset = offset
        }
        if (--pickCount > 0) return

        val selectedRtt = pickRtt
        val selectedOffset = pickOffset
        pickCount = PICK_COUNT
        pickRtt = Double.POSITIVE_INFINITY

        synchronized(clockLock) {
            val useSample = delays.all { selectedRtt <= it }
            delays[delayIndex] = selectedRtt
            delayIndex = (delayIndex + 1) % DELAY_WINDOW
            if (useSample) applyOffset(selectedOffset)
        }
    }

    private fun applyOffset(offsetSec: Double) {
        val stepping = !synced || Math.abs(offsetSec) > STEP_THRESHOLD_SEC
        val applied = if (stepping) offsetSec else offsetSec * SLEW_GAIN
        clockOffsetNs += Math.round(applied * 1e9)
        if (stepping) {
            delays.fill(Double.POSITIVE_INFINITY)
            delayIndex = 0
            pendingT1 = null
            synced = true
        }
    }

    private fun currentSocket(): DatagramSocket? = synchronized(socketLock) { socket }

    private companion object {
        const val PT_REQUEST = 210
        const val PT_RESPONSE = 211
        const val NTP_PACKET_BYTES = 32
        const val NTP_ORIGINATE_OFFSET = 8
        const val NTP_RECEIVE_OFFSET = 16
        const val NTP_TRANSMIT_OFFSET = 24
        const val REQUEST_INTERVAL_NS = 1_000_000_000L
        const val RECOVERY_INTERVAL_NS = 250_000_000L
        const val RECOVERY_DURATION_NS = 2_500_000_000L
        const val MAX_PROBE_AGE_NS = 3_000_000_000L
        const val MAX_RECENT_PROBES = 16
        const val RECEIVE_BUFFER_BYTES = 2_048
        const val STEP_THRESHOLD_SEC = 0.128
        const val SLEW_GAIN = 1.0 / 8.0
        const val DELAY_WINDOW = 8
        const val PICK_COUNT = 2
        const val TWO32_DOUBLE = 0x1_0000_0000L.toDouble()
    }
}

private const val NTP_EPOCH_OFFSET = 2_208_988_800L
private val TWO32 = BigInteger.ONE.shiftLeft(32)
private val NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L)

private fun wallClockNtpOffsetNs(monoNs: Long): Long {
    val ntpNs = ntp64Now()
        .and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))
        .multiply(NANOS_PER_SECOND)
        .shiftRight(32)
    return ntpNs.subtract(BigInteger.valueOf(monoNs)).toLong()
}

private fun ntp64Now(): BigInteger {
    val seconds = System.currentTimeMillis() / 1000.0 + NTP_EPOCH_OFFSET
    val whole = Math.floor(seconds).toLong()
    val fraction = Math.floor((seconds - whole) * TWO32.toDouble()).toLong()
    return BigInteger.valueOf(whole).shiftLeft(32).or(BigInteger.valueOf(fraction))
}

private fun ntpFromNanos(ns: Long): BigInteger {
    val seconds = BigInteger.valueOf(local.headunit.diplay.NumericCompat.floorSeconds(ns))
    val nanos = BigInteger.valueOf(local.headunit.diplay.NumericCompat.floorNanos(ns))
    return seconds.shiftLeft(32).or(nanos.shiftLeft(32).divide(NANOS_PER_SECOND))
}

private fun ntpBytes(value: BigInteger): ByteArray {
    val bytes = value.and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)).toByteArray()
    return ByteArray(8).also { target ->
        val source = if (bytes.size > 8) bytes.copyOfRange(bytes.size - 8, bytes.size) else bytes
        source.copyInto(target, target.size - source.size)
    }
}

private fun readNtp(value: ByteArray, offset: Int): BigInteger =
    BigInteger(1, value.copyOfRange(offset, offset + 8))

private fun writeU16Be(target: ByteArray, offset: Int, value: Int) {
    target[offset] = ((value ushr 8) and 0xff).toByte()
    target[offset + 1] = (value and 0xff).toByte()
}
