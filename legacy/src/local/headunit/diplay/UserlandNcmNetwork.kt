package local.headunit.diplay

import android.os.SystemClock
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.HashSet

/**
 * IPv6/TCP/UDP stack that consumes Ethernet frames directly from iPhone NCM.
 * It deliberately does not create an Android TUN interface or route packets through Android.
 */
class UserlandNcmNetwork(
    private val ncm: NcmUsbBridge,
    private val hostMac: ByteArray,
    private val onDiagnostic: (String) -> Unit,
    private val trackedTcpSynPort: Int = DEFAULT_TRACKED_TCP_SYN_PORT,
    private val onError: (Throwable) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val outboundGate = NcmStartupGate()
    private val outboundStartReported = AtomicBoolean(false)
    private val neighborWarmupReported = AtomicBoolean(false)
    private val neighborWarmupAttempted = AtomicBoolean(false)
    private val neighborWarmupFrames = AtomicLong()
    private val neighborWarmupBytes = AtomicLong()
    private val neighborWarmupAt = AtomicLong()
    private val rxFrames = AtomicLong()
    private val rxBytes = AtomicLong()
    private val rxTimeouts = AtomicLong()
    private val rxRejected = AtomicLong()
    private val txDequeued = AtomicLong()
    private val txUsbAttempts = AtomicLong()
    private val txUsbSucceeded = AtomicLong()
    private val txUsbNoWrite = AtomicLong()
    private val txUsbErrors = AtomicLong()
    private val tcpSocketOpens = AtomicLong()
    private val udpSocketOpens = AtomicLong()
    private val acceptedTcpSockets = AtomicLong()
    private val socketOpErrors = AtomicLong()
    private var bulkWriteDiagnosticBudget = 24
    private val socketLock = Object()
    private val sockets = HashSet<Int>()
    private var pendingSocketRegistrations = 0
    @Volatile private var handle = 0L
    private var receiveThread: Thread? = null
    private var transmitThread: Thread? = null
    private var statsThread: Thread? = null
    private val startedAt = SystemClock.elapsedRealtime()
    private var lastSnapshot = ""

    init {
        require(hostMac.size == 6) { "host MAC must contain 6 bytes" }
        require(trackedTcpSynPort in 1..65535) { "tracked TCP SYN port must be in 1..65535" }
    }

    @Synchronized fun start() {
        check(running.compareAndSet(false, true)) { "userland network is already started" }
        val nativeCreateStartedAt = SystemClock.elapsedRealtime()
        val created = NativeIpStack.create(LOCAL_IPV6, hostMac.copyOf(), trackedTcpSynPort)
        val nativeCreateDurationMs = (SystemClock.elapsedRealtime() - nativeCreateStartedAt).coerceAtLeast(0)
        if (created == 0L) {
            running.set(false)
            val errorCode = NativeIpStack.createError()
            runCatching { onDiagnostic("userland stack create result=failed durationMs=$nativeCreateDurationMs nativeErrorCode=$errorCode") }
            onDiagnostic("userland init failed stage=tcpip_init nativeErrorCode=$errorCode")
            throw IOException("lwIP TCP/IP initialization failed nativeErrorCode=$errorCode")
        }
        runCatching { onDiagnostic("userland stack create result=ready durationMs=$nativeCreateDurationMs nativeErrorCode=0") }
        handle = created
        try {
            LegacySockets.installUserland(this)
            onDiagnostic("userland stack ready backend=lwIP ipv4=off ipv6=link-local local=$LOCAL_IPV6 tcp=on udp=on mtu=1500 trackedTcpSynPort=$trackedTcpSynPort ncm=USB noVpn=true")
            receiveThread = MediaWorkers.start("ncm-userspace-rx", ::receiveFrames)
            transmitThread = MediaWorkers.start("ncm-userspace-tx", ::transmitFrames)
            statsThread = MediaWorkers.start("ncm-userspace-stats", ::reportStats)
        } catch (error: Throwable) {
            running.set(false)
            LegacySockets.removeUserland(this)
            outboundGate.close()
            val workers = listOf(receiveThread, transmitThread, statsThread).filterNotNull()
            workers.forEach { it.interrupt() }
            workers.forEach {
                if (!MediaWorkers.await(it, CLOSE_JOIN_MS)) {
                    runCatching { onDiagnostic("userland startup cleanup worker still active name=${it.name}") }
                }
            }
            val old = handle
            handle = 0L
            NativeIpStack.stop(old)
            throw error
        }
    }

    fun startOutbound() {
        if (running.get() && outboundStartReported.compareAndSet(false, true) && outboundGate.start()) {
            val warmupAt = neighborWarmupAt.get()
            onDiagnostic("userland NDP warmup summary frames=${neighborWarmupFrames.get()} bytes=${neighborWarmupBytes.get()} durationMs=${if (warmupAt == 0L) 0 else (SystemClock.elapsedRealtime() - warmupAt).coerceAtLeast(0)}")
            onDiagnostic("userland tx gate opened after iAP2 CarPlay session-start")
        }
    }

    /** Passive inbound IPv4/IPv6 SYN evidence for the tracked AirPlay TCP port. */
    fun airPlayTcpEvidence(): AirPlayTcpEvidence? {
        // sys_now() uses CLOCK_MONOTONIC at millisecond resolution. Capture the
        // start of this native query, then derive a conservative lower bound so
        // a SYN observed before the iAP2 flush cannot be rounded into the future.
        val statsWindowStartedAt = System.nanoTime()
        val values = NativeIpStack.stats(handle) ?: return null
        val ageMs = values.getOrNull(IPV6_TRACKED_SYN_AGE_MS_INDEX)?.takeIf { it >= 0L }
        val ipv4AgeMs = values.getOrNull(IPV4_TRACKED_SYN_AGE_MS_INDEX)?.takeIf { it >= 0L }
        val mdnsAgeMs = values.getOrNull(LAST_RX_MDNS_SERVICE_AGE_MS_INDEX)?.takeIf { it >= 0L }
        return AirPlayTcpEvidence(
            synCount = values.getOrNull(TCP_SYN_TRACKED_PORT_STAT_INDEX) ?: -1L,
            lastSynAgeMs = ageMs ?: -1L,
            lastSynAtNanos = conservativeEventAtNanos(statsWindowStartedAt, ageMs),
            lastIpv4SynAgeMs = ipv4AgeMs ?: -1L,
            lastIpv4SynAtNanos = conservativeEventAtNanos(statsWindowStartedAt, ipv4AgeMs),
            lastServiceDiscoveryAtNanos = conservativeEventAtNanos(statsWindowStartedAt, mdnsAgeMs),
            lastServiceDiscoveryMask = values.getOrNull(LAST_RX_MDNS_SERVICE_MASK_INDEX) ?: 0L,
            lastServiceDiscoveryQueryMask = values.getOrNull(LAST_RX_MDNS_SERVICE_QUERY_MASK_INDEX) ?: 0L,
            lastServiceDiscoveryResponseMask = values.getOrNull(LAST_RX_MDNS_SERVICE_RESPONSE_MASK_INDEX) ?: 0L,
        )
    }

    data class AirPlayTcpEvidence(
        val synCount: Long,
        val lastSynAgeMs: Long,
        val lastSynAtNanos: Long?,
        val lastIpv4SynAgeMs: Long,
        val lastIpv4SynAtNanos: Long?,
        val lastServiceDiscoveryAtNanos: Long?,
        val lastServiceDiscoveryMask: Long,
        val lastServiceDiscoveryQueryMask: Long,
        val lastServiceDiscoveryResponseMask: Long,
    )

    /** Allow one IPv6 neighbor-discovery packet while authentication proceeds. TCP/UDP and
     * additional discovery packets remain queued until iAP2 confirms CarPlay session-start. */
    fun startNeighborWarmup() {
        if (running.get() && neighborWarmupReported.compareAndSet(false, true)) {
            neighborWarmupAt.set(SystemClock.elapsedRealtime())
            if (outboundGate.startWarmup()) {
                onDiagnostic("userland NDP warmup started ageMs=${SystemClock.elapsedRealtime() - startedAt}")
            }
        }
    }

    internal fun openTcp(address: InetAddress, port: Int): ServerSocket = UserlandServerSocket(this, address, port)

    internal fun openUdp(address: InetAddress): DatagramSocket = UserlandDatagramSocket(this, address)

    internal fun openSocket(kind: Int): Int {
        synchronized(socketLock) {
            checkRunning()
            pendingSocketRegistrations++
        }
        try {
            val fd = checked(NativeIpStack.socket(kind), "socket")
            val registered = synchronized(socketLock) {
                if (running.get() && handle != 0L) {
                    sockets.add(fd)
                    if (kind == 1) tcpSocketOpens.incrementAndGet() else if (kind == 2) udpSocketOpens.incrementAndGet()
                    true
                } else false
            }
            if (!registered) {
                NativeIpStack.closeSocket(fd)
                throw IOException("userland network closed while creating socket")
            }
            return fd
        } finally {
            finishSocketRegistration()
        }
    }

    internal fun bind(socket: Int, address: InetAddress, port: Int) {
        checkRunning()
        checked(NativeIpStack.bind(socket, ipv6Bytes(address), port), "bind")
    }

    internal fun listen(socket: Int, backlog: Int) {
        checkRunning()
        checked(NativeIpStack.listen(socket, backlog.coerceIn(1, 64)), "listen")
    }

    internal fun accept(socket: Int): Int {
        synchronized(socketLock) {
            checkRunning()
            pendingSocketRegistrations++
        }
        try {
            val accepted = checked(NativeIpStack.accept(socket), "accept")
            val registered = synchronized(socketLock) {
                if (running.get() && handle != 0L) {
                    sockets.add(accepted)
                    acceptedTcpSockets.incrementAndGet()
                    true
                } else false
            }
            if (!registered) {
                NativeIpStack.closeSocket(accepted)
                throw java.net.SocketException("userland network closed while accepting socket")
            }
            return accepted
        } finally {
            finishSocketRegistration()
        }
    }

    internal fun closeSocket(socket: Int) {
        if (socket < 0) return
        synchronized(socketLock) {
            if (sockets.remove(socket)) NativeIpStack.closeSocket(socket)
        }
    }

    internal fun shutdown(socket: Int, how: Int) {
        if (socket >= 0) checked(NativeIpStack.shutdown(socket, how), "shutdown")
    }

    internal fun send(socket: Int, data: ByteArray, offset: Int, length: Int): Int =
        checked(NativeIpStack.send(socket, data, offset, length), "send")

    internal fun receive(socket: Int, data: ByteArray, offset: Int, length: Int): Int =
        checked(NativeIpStack.receive(socket, data, offset, length), "receive", allowEof = true)

    internal fun sendTo(socket: Int, data: ByteArray, offset: Int, length: Int, peer: InetAddress, port: Int): Int =
        checked(NativeIpStack.sendTo(socket, data, offset, length, ipv6Bytes(peer), port), "sendto")

    internal fun receiveFrom(socket: Int, data: ByteArray, offset: Int, length: Int): ReceivedDatagram {
        val peer = ByteArray(18)
        val count = checked(NativeIpStack.receiveFrom(socket, data, offset, length, peer), "recvfrom", allowEof = true)
        val address = InetAddress.getByAddress(peer.copyOfRange(0, 16))
        val port = ((peer[16].toInt() and 0xff) shl 8) or (peer[17].toInt() and 0xff)
        return ReceivedDatagram(count, address, port)
    }

    internal fun setOption(socket: Int, option: Int, value: Int) {
        val name = when (option) {
            1 -> "SO_REUSEADDR"
            2 -> "SO_RCVBUF"
            3 -> "SO_SNDBUF"
            4 -> "SO_KEEPALIVE"
            5 -> "TCP_NODELAY"
            6 -> "SO_RCVTIMEO"
            7 -> "SO_SNDTIMEO"
            8 -> "SO_LINGER"
            else -> "UNKNOWN"
        }
        checked(NativeIpStack.setOption(socket, option, value), "setsockopt_$name")
    }

    internal fun socketName(socket: Int, peer: Boolean = false): InetSocketInfo {
        val data = NativeIpStack.name(socket, peer) ?: throw IOException("lwIP getsockname failed")
        val address = InetAddress.getByAddress(data.copyOfRange(0, 16))
        val port = ((data[16].toInt() and 0xff) shl 8) or (data[17].toInt() and 0xff)
        return InetSocketInfo(address, port)
    }

    private fun receiveFrames() {
        while (running.get()) {
            try {
                val frame = ncm.recv(NCM_POLL_MS)
                if (frame == null) {
                    rxTimeouts.incrementAndGet()
                    continue
                }
                rxFrames.incrementAndGet()
                rxBytes.addAndGet(frame.size.toLong())
                val accepted = NativeIpStack.input(handle, frame)
                if (accepted < 0) {
                    rxRejected.incrementAndGet()
                    if (rxRejected.get() <= 8) onDiagnostic("userland rx rejected frameBytes=${frame.size} error=${errnoName(accepted)}")
                }
            } catch (failure: Throwable) {
                if (running.get()) onError(failure)
                return
            }
        }
    }

    private fun transmitFrames() {
        val buffer = ByteArray(MAX_FRAME_BYTES)
        var deferredFrame: ByteArray? = null
        try {
            if (!outboundGate.awaitWarmup()) return
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (running.get()) onError(IOException("userland transmit gate interrupted", interrupted))
            return
        }
        while (running.get()) {
            try {
                val pending = deferredFrame
                if (pending != null && !outboundGate.isReady()) {
                    if (!outboundGate.awaitReady()) return
                    continue
                }
                val count = if (pending == null) NativeIpStack.nextOutput(handle, buffer, NCM_POLL_MS.toInt()) else pending.size
                if (count == 0) continue
                if (count < 0) throw IOException("lwIP output queue failed: ${errnoName(count)}")
                if (!outboundGate.isReady() && pending == null) {
                    val ndpKind = StartupUsbWrite.neighborDiscoveryKind(buffer, 0, count)
                    if (ndpKind != null && neighborWarmupAttempted.compareAndSet(false, true)) {
                        txDequeued.incrementAndGet()
                        txUsbAttempts.incrementAndGet()
                        val warmup = ncm.sendNeighborWarmup(buffer, 0, count)
                        if (warmup.complete) {
                            txUsbSucceeded.incrementAndGet()
                            neighborWarmupFrames.incrementAndGet()
                            neighborWarmupBytes.addAndGet(count.toLong())
                        } else {
                            txUsbNoWrite.incrementAndGet()
                            // nextOutput() removes this frame from lwIP. Keep the unanswered
                            // neighbor packet until the normal post-0x4301 send path can retry it.
                            deferredFrame = buffer.copyOf(count)
                        }
                        recordNdpWarmup(
                            kind = ndpKind,
                            result = if (warmup.complete) "pre_session_sent" else "pre_session_deferred",
                            usbTransferred = warmup.transferred,
                            expectedBytes = warmup.expectedBytes,
                            durationMs = warmup.durationMs,
                            retained = !warmup.complete,
                        )
                        onDiagnostic("userland NDP warmup attempt result=${if (warmup.complete) "sent" else "not_ready"} durationMs=${warmup.durationMs} retained=${!warmup.complete}")
                        continue
                    }
                    deferredFrame = buffer.copyOf(count)
                    if (!outboundGate.awaitReady()) return
                    continue
                }
                val frame = pending
                txDequeued.incrementAndGet()
                txUsbAttempts.incrementAndGet()
                val packet = frame ?: buffer
                var sent = false
                var writeResult = "exception"
                var writeError = "none"
                val writeStartedAt = System.nanoTime()
                try {
                    sent = ncm.send(packet, 0, count, NCM_WRITE_TIMEOUT_MS)
                    writeResult = if (sent) "bulk_transfer_complete" else "no_transfer"
                } catch (error: Exception) {
                    writeError = error.javaClass.simpleName
                    throw error
                } finally {
                    val durationMs = (System.nanoTime() - writeStartedAt) / 1_000_000
                    if (bulkWriteDiagnosticBudget > 0 || durationMs >= SLOW_BULK_WRITE_LOG_MILLIS) {
                        if (bulkWriteDiagnosticBudget > 0) bulkWriteDiagnosticBudget--
                        val frameSummary = ethernetTransportSummary(packet, count)
                        runCatching {
                            onDiagnostic(
                                "NCM_OUT $frameSummary frameBytes=$count durationMs=$durationMs " +
                                    "result=$writeResult errorType=$writeError",
                            )
                        }
                    }
                }
                if (sent) {
                    txUsbSucceeded.incrementAndGet()
                } else {
                    txUsbNoWrite.incrementAndGet()
                }
                val packetOffset = if (frame == null) 0 else 0
                val ndpKind = StartupUsbWrite.neighborDiscoveryKind(packet, packetOffset, count)
                if (ndpKind != null && pending == null && !sent) {
                    // A transient USB timeout must not discard the NDP reply that lets the
                    // iPhone reach the already-bound AirPlay listener.
                    deferredFrame = packet.copyOf(count)
                    recordNdpWarmup(ndpKind, "post_session_deferred", -1, count, 0, true)
                } else if (pending != null) {
                    if (ndpKind != null) {
                        recordNdpWarmup(
                            ndpKind,
                            if (sent) "retry_sent" else "retry_deferred",
                            if (sent) count else -1,
                            count,
                            0,
                            !sent,
                        )
                    }
                    deferredFrame = if (!sent && ndpKind != null) packet.copyOf(count) else null
                }
            } catch (failure: Throwable) {
                if (running.get()) {
                    txUsbErrors.incrementAndGet()
                    onError(failure)
                }
                return
            }
        }
    }

    private fun recordNdpWarmup(
        kind: String,
        result: String,
        usbTransferred: Int,
        expectedBytes: Int,
        durationMs: Long,
        retained: Boolean,
    ) {
        onDiagnostic(
            "NDP_WARMUP kind=$kind result=$result usbTransferred=$usbTransferred " +
                "frameBytes=$expectedBytes durationMs=${durationMs.coerceAtLeast(0)} " +
                "retained=$retained elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
    }

    /** Bounded header-only context for early physical NCM OUT timing; never records addresses or payload. */
    private fun ethernetTransportSummary(frame: ByteArray, length: Int): String {
        if (length < 14) return "etherType=short"
        val etherType = ((frame[12].toInt() and 0xff) shl 8) or (frame[13].toInt() and 0xff)
        if (etherType == 0x0800 && length >= 34) {
            val ipOffset = 14
            val headerBytes = (frame[ipOffset].toInt() and 0x0f) * 4
            if ((frame[ipOffset].toInt() ushr 4) == 4 && headerBytes >= 20 && ipOffset + headerBytes <= length) {
                val protocol = frame[ipOffset + 9].toInt() and 0xff
                val totalBytes = readU16(frame, ipOffset + 2)
                if (totalBytes < headerBytes || ipOffset + totalBytes > length) {
                    return "etherType=0x0800 ipVersion=4 ipProtocol=$protocol ipHeaderValid=false"
                }
                val fragment = readU16(frame, ipOffset + 6)
                if (protocol == IP_PROTOCOL_TCP && (fragment and IPV4_FRAGMENT_OFFSET_MASK) == 0 &&
                    totalBytes >= headerBytes + TCP_MIN_HEADER_BYTES
                ) {
                    val tcpOffset = ipOffset + headerBytes
                    val tcpHeaderBytes = ((frame[tcpOffset + 12].toInt() ushr 4) and 0x0f) * 4
                    if (tcpHeaderBytes >= TCP_MIN_HEADER_BYTES && tcpOffset + tcpHeaderBytes <= ipOffset + totalBytes) {
                        return "etherType=0x0800 ipVersion=4 ipProtocol=$protocol " +
                            "tcpSourcePort=${readU16(frame, tcpOffset)} tcpDestinationPort=${readU16(frame, tcpOffset + 2)} " +
                            "tcpFlags=0x${(frame[tcpOffset + 13].toInt() and 0xff).toString(16).padStart(2, '0')}"
                    }
                }
                return "etherType=0x0800 ipVersion=4 ipProtocol=$protocol"
            }
        }
        if (etherType == 0x86dd && length >= 54 && (frame[14].toInt() ushr 4) == 6) {
            val protocol = frame[20].toInt() and 0xff
            val payloadBytes = readU16(frame, 18)
            if (payloadBytes + IPV6_HEADER_BYTES > length - 14) {
                return "etherType=0x86dd ipVersion=6 ipProtocol=$protocol ipHeaderValid=false"
            }
            if (protocol == IP_PROTOCOL_TCP && payloadBytes >= TCP_MIN_HEADER_BYTES) {
                val tcpOffset = 54
                if (tcpOffset + TCP_MIN_HEADER_BYTES <= length) {
                    val tcpHeaderBytes = ((frame[tcpOffset + 12].toInt() ushr 4) and 0x0f) * 4
                    if (tcpHeaderBytes >= TCP_MIN_HEADER_BYTES && tcpHeaderBytes <= payloadBytes && tcpOffset + tcpHeaderBytes <= length) {
                        return "etherType=0x86dd ipVersion=6 ipProtocol=$protocol " +
                            "tcpSourcePort=${readU16(frame, tcpOffset)} tcpDestinationPort=${readU16(frame, tcpOffset + 2)} " +
                            "tcpFlags=0x${(frame[tcpOffset + 13].toInt() and 0xff).toString(16).padStart(2, '0')}"
                    }
                }
            }
            return "etherType=0x86dd ipVersion=6 ipProtocol=$protocol"
        }
        return "etherType=0x${etherType.toString(16).padStart(4, '0')}"
    }

    private fun reportStats() {
        var lastReported = SystemClock.elapsedRealtime()
        while (running.get()) {
            try { Thread.sleep(STATS_INTERVAL_MS) } catch (_: InterruptedException) { if (!running.get()) return }
            if (!running.get()) return
            val now = SystemClock.elapsedRealtime()
            val native = NativeIpStack.stats(handle)
            val snapshot = "rxUsbFrames=${rxFrames.get()} rxUsbBytes=${rxBytes.get()} rxTimeouts=${rxTimeouts.get()} rxRejected=${rxRejected.get()} " +
                "rxIpv4EthFrames=${native?.getOrNull(24) ?: -1} rxIpv4Tcp=${native?.getOrNull(25) ?: -1} rxIpv4Syn=${native?.getOrNull(26) ?: -1} " +
                "rxIpv4SynTrackedPort=${native?.getOrNull(27) ?: -1} rxIpv4Udp=${native?.getOrNull(28) ?: -1} rxIpv4Fragmented=${native?.getOrNull(29) ?: -1} " +
                "rxIpv4Malformed=${native?.getOrNull(30) ?: -1} rxArp=${native?.getOrNull(31) ?: -1} rxOtherEtherType=${native?.getOrNull(32) ?: -1} rxMdns=${native?.getOrNull(33) ?: -1} rxIpv6Mdns=${native?.getOrNull(34) ?: -1} rxIpv4Mdns=${native?.getOrNull(35) ?: -1} " +
                "rxIpv6SynPortPair=${formatPortPair(native, IPV6_SYN_PORT_PAIR_INDEX)} rxIpv4SynPortPair=${formatPortPair(native, IPV4_SYN_PORT_PAIR_INDEX)} lastTcpSynAgeMs=${native?.getOrNull(IPV6_TRACKED_SYN_AGE_MS_INDEX) ?: -1} lastIpv4TcpSynAgeMs=${native?.getOrNull(IPV4_TRACKED_SYN_AGE_MS_INDEX) ?: -1} " +
                "rxIpv6EthFrames=${native?.getOrNull(0) ?: -1} rxIpv6EthBytes=${native?.getOrNull(1) ?: -1} " +
                "rxIpv6=${native?.getOrNull(2) ?: -1} rxOther=${native?.getOrNull(3) ?: -1} rxNdp=${native?.getOrNull(4) ?: -1} rxNs=${native?.getOrNull(5) ?: -1} rxNa=${native?.getOrNull(6) ?: -1} " +
                "rxTcp=${native?.getOrNull(7) ?: -1} rxUdp=${native?.getOrNull(8) ?: -1} rxDrop=${native?.getOrNull(9) ?: -1} " +
                "txIpv6EthFrames=${native?.getOrNull(10) ?: -1} txIpv6EthBytes=${native?.getOrNull(11) ?: -1} txQueueDrop=${native?.getOrNull(12) ?: -1} txOutputCopyError=${native?.getOrNull(13) ?: -1} " +
                "txDequeued=${txDequeued.get()} txUsbAttempt=${txUsbAttempts.get()} txUsbOk=${txUsbSucceeded.get()} " +
                "txUsbNoWrite=${txUsbNoWrite.get()} txUsbError=${txUsbErrors.get()} tcpSocketOpens=${tcpSocketOpens.get()} tcpAccepted=${acceptedTcpSockets.get()} udpSocketOpens=${udpSocketOpens.get()} socketOpErrors=${socketOpErrors.get()} " +
                (if (native == null) "lwip=unavailable" else nativeSummary(native))
            val line = "userland stats ageMs=${now - startedAt} $snapshot"
            if (snapshot != lastSnapshot || now - lastReported >= STATS_HEARTBEAT_MS) {
                onDiagnostic(line)
                lastSnapshot = snapshot
                lastReported = now
            }
        }
    }

    private fun nativeSummary(values: LongArray): String =
        "tcpSynIn=${values.getOrNull(14) ?: -1} tcpSynTrackedPort=${values.getOrNull(TCP_SYN_TRACKED_PORT_STAT_INDEX) ?: -1} tcpSynAckQueued=${values.getOrNull(15) ?: -1} tcpRst=${values.getOrNull(16) ?: -1} " +
            "tcpFin=${values.getOrNull(17) ?: -1} udpIn=${values.getOrNull(18) ?: -1} udpOutQueued=${values.getOrNull(19) ?: -1} " +
        "lastRxPortPair=${values.getOrNull(RX_SOURCE_PORT_INDEX) ?: -1}->${values.getOrNull(RX_DESTINATION_PORT_INDEX) ?: -1} " +
        "lastTxPortPair=${values.getOrNull(TX_SOURCE_PORT_INDEX) ?: -1}->${values.getOrNull(TX_DESTINATION_PORT_INDEX) ?: -1} " +
        "rxIpv4EthFrames=${values.getOrNull(24) ?: -1} rxIpv4Tcp=${values.getOrNull(25) ?: -1} rxIpv4Syn=${values.getOrNull(26) ?: -1} " +
        "rxIpv4SynTrackedPort=${values.getOrNull(27) ?: -1} rxIpv4Udp=${values.getOrNull(28) ?: -1} rxIpv4Fragmented=${values.getOrNull(29) ?: -1} " +
        "rxIpv4Malformed=${values.getOrNull(30) ?: -1} rxArp=${values.getOrNull(31) ?: -1} rxOtherEtherType=${values.getOrNull(32) ?: -1} rxMdns=${values.getOrNull(33) ?: -1} rxIpv6Mdns=${values.getOrNull(34) ?: -1} rxIpv4Mdns=${values.getOrNull(35) ?: -1} " +
        "rxIpv6SynPortPair=${formatPortPair(values, IPV6_SYN_PORT_PAIR_INDEX)} rxIpv4SynPortPair=${formatPortPair(values, IPV4_SYN_PORT_PAIR_INDEX)} lastTcpSynAgeMs=${values.getOrNull(IPV6_TRACKED_SYN_AGE_MS_INDEX) ?: -1} lastIpv4TcpSynAgeMs=${values.getOrNull(IPV4_TRACKED_SYN_AGE_MS_INDEX) ?: -1} " +
            "txMdns=${values.getOrNull(TX_MDNS_PACKET_INDEX) ?: -1} " +
            "mdns6InQueryMask=${values.getOrNull(MDNS6_IN_QUERY_MASK_INDEX) ?: -1} mdns6InResponseMask=${values.getOrNull(MDNS6_IN_RESPONSE_MASK_INDEX) ?: -1} " +
            "mdns4InQueryMask=${values.getOrNull(MDNS4_IN_QUERY_MASK_INDEX) ?: -1} mdns4InResponseMask=${values.getOrNull(MDNS4_IN_RESPONSE_MASK_INDEX) ?: -1} " +
            "mdns6OutQueryMask=${values.getOrNull(MDNS6_OUT_QUERY_MASK_INDEX) ?: -1} mdns6OutResponseMask=${values.getOrNull(MDNS6_OUT_RESPONSE_MASK_INDEX) ?: -1} " +
            "lastRxMdnsServiceAgeMs=${values.getOrNull(LAST_RX_MDNS_SERVICE_AGE_MS_INDEX) ?: -1} lastRxMdnsServiceMask=${values.getOrNull(LAST_RX_MDNS_SERVICE_MASK_INDEX) ?: -1} " +
            "lastRxMdnsServiceQueryMask=${values.getOrNull(LAST_RX_MDNS_SERVICE_QUERY_MASK_INDEX) ?: -1} lastRxMdnsServiceResponseMask=${values.getOrNull(LAST_RX_MDNS_SERVICE_RESPONSE_MASK_INDEX) ?: -1}"

    private fun formatPortPair(values: LongArray?, index: Int): String {
        val value = values?.getOrNull(index) ?: return "-1"
        val pair = value.toLong()
        if (pair == 0L) return "-1"
        return "${(pair ushr 16) and 0xffff}->${pair and 0xffff}"
    }

    private fun checked(result: Int, operation: String, allowEof: Boolean = false): Int {
        if (result >= 0 || allowEof && result == 0) return result
        socketOpErrors.incrementAndGet()
        val errno = -result
        if (errno == EAGAIN || errno == EWOULDBLOCK) throw java.net.SocketTimeoutException("lwIP $operation timeout")
        if (operation == "socket" || operation == "bind" || operation == "listen" || operation == "accept" || operation.startsWith("setsockopt_")) {
            onDiagnostic("userland socket op failed operation=$operation errno=$errno")
        }
        throw IOException("lwIP $operation failed errno=$errno")
    }

    private fun checkRunning() {
        if (!running.get() || handle == 0L) throw IOException("userland network is closed")
    }

    private fun finishSocketRegistration() {
        synchronized(socketLock) {
            pendingSocketRegistrations--
            if (pendingSocketRegistrations == 0) socketLock.notifyAll()
        }
    }

    @Synchronized override fun close() {
        if (!running.compareAndSet(true, false)) return
        outboundGate.close()
        receiveThread?.interrupt()
        transmitThread?.interrupt()
        statsThread?.interrupt()
        listOf(receiveThread, transmitThread, statsThread).filterNotNull().forEach { MediaWorkers.await(it, CLOSE_JOIN_MS) }
        val leaked = synchronized(socketLock) { sockets.toList().also { sockets.clear() } }
        leaked.forEach { NativeIpStack.closeSocket(it) }
        var interrupted = false
        synchronized(socketLock) {
            while (pendingSocketRegistrations != 0) {
                try { socketLock.wait() } catch (_: InterruptedException) { interrupted = true }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        val lateSockets = synchronized(socketLock) { sockets.toList().also { sockets.clear() } }
        lateSockets.forEach { NativeIpStack.closeSocket(it) }
        if (leaked.isNotEmpty() || lateSockets.isNotEmpty()) {
            onDiagnostic("userland forced socket cleanup count=${leaked.size + lateSockets.size}")
        }
        LegacySockets.removeUserland(this)
        val old = handle
        handle = 0L
        val finalNative = if (old != 0L) NativeIpStack.stats(old) else null
        if (old != 0L) {
            finalNative?.let { onDiagnostic("userland final ${nativeSummary(it)}") }
            NativeIpStack.stop(old)
        }
        onDiagnostic("userland stack stopped rxUsbFrames=${rxFrames.get()} rxUsbBytes=${rxBytes.get()} rxTimeouts=${rxTimeouts.get()} rxRejected=${rxRejected.get()} " +
            "rxIpv4EthFrames=${finalNative?.getOrNull(24) ?: -1} rxIpv4Tcp=${finalNative?.getOrNull(25) ?: -1} rxIpv4Syn=${finalNative?.getOrNull(26) ?: -1} rxIpv4SynTrackedPort=${finalNative?.getOrNull(27) ?: -1} " +
            "rxIpv4Udp=${finalNative?.getOrNull(28) ?: -1} rxIpv4Fragmented=${finalNative?.getOrNull(29) ?: -1} rxIpv4Malformed=${finalNative?.getOrNull(30) ?: -1} rxArp=${finalNative?.getOrNull(31) ?: -1} rxOtherEtherType=${finalNative?.getOrNull(32) ?: -1} " +
            "rxMdns=${finalNative?.getOrNull(33) ?: -1} rxIpv6Mdns=${finalNative?.getOrNull(34) ?: -1} rxIpv4Mdns=${finalNative?.getOrNull(35) ?: -1} " +
            "rxIpv6SynPortPair=${formatPortPair(finalNative, IPV6_SYN_PORT_PAIR_INDEX)} rxIpv4SynPortPair=${formatPortPair(finalNative, IPV4_SYN_PORT_PAIR_INDEX)} lastTcpSynAgeMs=${finalNative?.getOrNull(IPV6_TRACKED_SYN_AGE_MS_INDEX) ?: -1} lastIpv4TcpSynAgeMs=${finalNative?.getOrNull(IPV4_TRACKED_SYN_AGE_MS_INDEX) ?: -1} " +
            "rxIpv6EthFrames=${finalNative?.getOrNull(0) ?: -1} rxIpv6EthBytes=${finalNative?.getOrNull(1) ?: -1} rxIpv6=${finalNative?.getOrNull(2) ?: -1} " +
            "txIpv6EthFrames=${finalNative?.getOrNull(10) ?: -1} txIpv6EthBytes=${finalNative?.getOrNull(11) ?: -1} txQueueDrop=${finalNative?.getOrNull(12) ?: -1} txOutputCopyError=${finalNative?.getOrNull(13) ?: -1} " +
            "txDequeued=${txDequeued.get()} txUsbAttempt=${txUsbAttempts.get()} txUsbOk=${txUsbSucceeded.get()} txUsbNoWrite=${txUsbNoWrite.get()} txUsbError=${txUsbErrors.get()} tcpSocketOpens=${tcpSocketOpens.get()} tcpAccepted=${acceptedTcpSockets.get()} udpSocketOpens=${udpSocketOpens.get()} socketOpErrors=${socketOpErrors.get()}")
    }

    private fun ipv6Bytes(address: InetAddress): ByteArray {
        if (address.address.size == 16) return address.address
        if (address.address.size == 4 && address.address.all { it.toInt() == 0 }) return ByteArray(16)
        throw IOException("lwIP experiment requires IPv6 addresses")
    }

    private fun errnoName(value: Int): String = "errno=${-value}"

    data class ReceivedDatagram(val length: Int, val address: InetAddress, val port: Int)
    data class InetSocketInfo(val address: InetAddress, val port: Int)

    companion object {
        // Native STAT_COUNT=36; value 23 counts IPv6 SYNs to the AirPlay port.
        // Values 24..35 record passive IPv4/EtherType and address-family-specific mDNS evidence;
        // values 36..39 are the latest RX/TX port pairs, 40..41 are family-specific SYN pairs,
        // 42..43 are tracked-port SYN ages, 44 is outbound mDNS packets,
        // 45..50 are address-family query/response service masks,
        // 51..52 are last inbound recognized mDNS service age and union mask;
        // 53..54 preserve whether that packet was a query or a response.
        private const val TCP_SYN_TRACKED_PORT_STAT_INDEX = 23
        private const val RX_SOURCE_PORT_INDEX = 36
        private const val RX_DESTINATION_PORT_INDEX = 37
        private const val TX_SOURCE_PORT_INDEX = 38
        private const val TX_DESTINATION_PORT_INDEX = 39
        private const val IPV6_SYN_PORT_PAIR_INDEX = 40
        private const val IPV4_SYN_PORT_PAIR_INDEX = 41
        private const val IPV6_TRACKED_SYN_AGE_MS_INDEX = 42
        private const val IPV4_TRACKED_SYN_AGE_MS_INDEX = 43
        private const val TX_MDNS_PACKET_INDEX = 44
        private const val MDNS6_IN_QUERY_MASK_INDEX = 45
        private const val MDNS6_IN_RESPONSE_MASK_INDEX = 46
        private const val MDNS4_IN_QUERY_MASK_INDEX = 47
        private const val MDNS4_IN_RESPONSE_MASK_INDEX = 48
        private const val MDNS6_OUT_QUERY_MASK_INDEX = 49
        private const val MDNS6_OUT_RESPONSE_MASK_INDEX = 50
        private const val LAST_RX_MDNS_SERVICE_AGE_MS_INDEX = 51
        private const val LAST_RX_MDNS_SERVICE_MASK_INDEX = 52
        private const val LAST_RX_MDNS_SERVICE_QUERY_MASK_INDEX = 53
        private const val LAST_RX_MDNS_SERVICE_RESPONSE_MASK_INDEX = 54
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        internal fun conservativeEventAtNanos(queryStartedAtNanos: Long, ageMs: Long?): Long? =
            ageMs?.takeIf { it >= 0L }?.let { queryStartedAtNanos - (it + 1L) * NANOS_PER_MILLISECOND }
        private const val DEFAULT_TRACKED_TCP_SYN_PORT = 7000
        const val LOCAL_IPV6 = "fe80::2"
        private const val NCM_POLL_MS = 250L
        private const val NCM_WRITE_TIMEOUT_MS = 1_000
        private const val SLOW_BULK_WRITE_LOG_MILLIS = 100L
        private const val IP_PROTOCOL_TCP = 6
        private const val TCP_MIN_HEADER_BYTES = 20
        private const val IPV6_HEADER_BYTES = 40
        private const val IPV4_FRAGMENT_OFFSET_MASK = 0x1fff
        private const val STATS_INTERVAL_MS = 5_000L
        private const val STATS_HEARTBEAT_MS = 5_000L
        private const val CLOSE_JOIN_MS = 2_000L
        private const val MAX_FRAME_BYTES = 16 * 1024
        private const val EAGAIN = 11
        private const val EWOULDBLOCK = 11

        private fun readU16(source: ByteArray, offset: Int): Int =
            ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)
    }
}
