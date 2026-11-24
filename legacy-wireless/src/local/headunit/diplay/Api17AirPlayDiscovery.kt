package local.headunit.diplay

import android.content.Context
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** API 17 Bonjour path: publish AirPlay TXT records in user space and browse iPhone control service. */
internal class Api17AirPlayDiscovery(
    context: Context,
    private val address: InetAddress,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val ipv6Address: Inet6Address? = null,
    private val phoneBluetoothAddress: () -> String? = { null },
    private val report: (String, JSONObject) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext ?: context
    private var multicastLock: WifiManager.MulticastLock? = null
    private val closed = AtomicBoolean(false)
    private val transportReady = AtomicBoolean(false)
    private val handoffRequested = AtomicBoolean(false)
    private val handoffStarted = AtomicBoolean(false)
    private val handoffFinished = AtomicBoolean(false)
    private val activeProbe = AtomicReference<Socket?>(null)
    private val phoneResume = AtomicReference<TargetedCarPlayResume?>(null)
    private val controlWorker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "hybrid-carplay-control-discovery").apply { isDaemon = true }
    }
    // The control worker owns candidates. Only interface-bound Bonjour exposes the paired phone TXT on API 17.
    private val candidates = LinkedHashMap<String, ControlCandidate>()
    private var handoffDeadlineNanos = 0L
    private val queryCount = AtomicInteger()
    private val responseCount = AtomicInteger()
    private val announceCount = AtomicInteger()
    private val ipv4AnnounceFailures = AtomicInteger()
    private val controlFoundCount = AtomicInteger()
    private val probeAttemptCount = AtomicInteger()
    private val probeSuccessCount = AtomicInteger()
    private val controlQueries = AtomicInteger()
    private val controlReplies = AtomicInteger()
    private val ipv4Packets = AtomicInteger()
    private val ipv4OffLinkRejects = AtomicInteger()
    private val ipv4Resolved = AtomicInteger()
    private val ipv6Packets = AtomicInteger()
    private val ipv6ScopeRejects = AtomicInteger()
    private val ipv6Announcements = AtomicInteger()
    private val ipv6Responses = AtomicInteger()
    private val ipv6BrowseQueries = AtomicInteger()
    private val ipv6ControlReplies = AtomicInteger()
    private val ipv6Resolved = AtomicInteger()
    private val ipv6Probes = AtomicInteger()
    private val ipv6ProbeSuccesses = AtomicInteger()
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var ipv6Group: Inet6Address? = null
    @Volatile private var ipv4PrefixLength = 0
    private val peerLock = Any()
    private val ipv4Peers = LinkedHashMap<Inet4Address, CarPlayControlDns>(4, 0.75f, true)
    private val ipv6Peers = LinkedHashMap<Inet6Address, CarPlayControlDns>(4, 0.75f, true)
    private var cachedBluetoothAddress: String? = null // guarded by peerLock
    private var nextControlQueryNanos = 0L
    private var multicastInterface: NetworkInterface? = null
    @Volatile private var advertisedIpv6Address: Inet6Address? = null
    private var worker: Thread? = null
    @Volatile private var lastProbe = "not_started"
    private val receiverInstanceName = "${config.deviceName.replace('.', '-')}.$AIRPLAY_SERVICE"
    private val receiverHostName: String = run {
        val id = config.deviceId.replace(":", "").lowercase(Locale.US).replace(Regex("[^a-f0-9]"), "")
            .take(12).ifEmpty { "receiver" }
        "diplay-$id.local."
    }
    private val requestedNames = setOf(AIRPLAY_SERVICE, DNS_SD_META, receiverInstanceName, receiverHostName)
        .map { it.lowercase(Locale.US) }.toSet()
    // Start fixes the advertised addresses before any announcement can initialize these caches.
    private val regularResponse by lazy { CarPlayControlDns.encodeResponse(buildRecords(TTL_SECONDS, false), null) }
    private val legacyRecords by lazy { buildRecords(minOf(TTL_SECONDS, 10), true) }
    private val goodbyeResponse by lazy { CarPlayControlDns.encodeResponse(buildRecords(0, false), null) }

    @Synchronized
    fun start() {
        check(!closed.get()) { "AirPlay discovery is closed" }
        check(socket == null) { "AirPlay discovery is already running" }
        if (address !is Inet4Address) throw IOException("AirPlay discovery requires the hotspot IPv4 address")
        val iface = NetworkInterface.getByInetAddress(address)
            ?: throw IOException("No hotspot interface owns the advertised AirPlay address")
        if (!iface.isUp || iface.isLoopback) throw IOException("The hotspot interface is down")
        ipv4PrefixLength = iface.interfaceAddresses.firstOrNull { it.address == address }?.networkPrefixLength?.toInt()
            ?.takeIf { it in 1..31 } ?: throw IOException("The hotspot IPv4 subnet is unavailable")
        advertisedIpv6Address = ipv6Address?.let { requested ->
            if (!requested.isLinkLocalAddress || iface.inetAddresses.asSequence().none { it == requested }) return@let null
            runCatching { Inet6Address.getByAddress(null, requested.address, iface) }.getOrNull()?.takeIf {
                it.scopeId != 0 && (requested.scopeId == 0 || it.scopeId == requested.scopeId)
            }
        }
        if (ipv6Address != null && advertisedIpv6Address == null) emit("ipv6_scope_unavailable", false)
        val mdns = MulticastSocket(null)
        try {
            mdns.reuseAddress = true
            mdns.bind(InetSocketAddress(MDNS_PORT))
            mdns.networkInterface = iface
            mdns.timeToLive = 255
            mdns.soTimeout = 800
            mdns.joinGroup(MDNS_MEMBERSHIP, iface)
            acquireMulticastLock()
            multicastInterface = iface
            socket = mdns
            announceIpv4(mdns)
            startIpv6(mdns, iface)
            worker = Thread({ serve(mdns) }, "hybrid-airplay-mdns").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            runCatching { mdns.close() }
            multicastInterface = null
            socket = null
            releaseMulticastLock()
            throw IOException("Could not start Bonjour on the hotspot interface", error)
        }
        transportReady.set(true)
        launchPhoneHandoff()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi == null) {
                emit("multicast_lock_unavailable", false)
                return
            }
            val lock = wifi.createMulticastLock("hybrid-airplay-mdns")
            lock.setReferenceCounted(false)
            multicastLock = lock
            lock.acquire()
            emit("multicast_lock_acquired", true)
        } catch (_: RuntimeException) {
            releaseMulticastLock()
            // SoftAP sends and receives on the joined interface, regardless of the station filter.
            emit("multicast_lock_unavailable", false)
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    private fun announceIpv4(mdns: MulticastSocket) {
        try {
            sendAnnouncement(mdns, ttl = TTL_SECONDS)
            if (announceCount.incrementAndGet() == 1) {
                report("HYBRID_MDNS", JSONObject()
                    .put("stage", "airplay_service_published")
                    .put("success", true)
                    .put("localAddress", address.hostAddress)
                    .put("localPort", config.port)
                    .put("addressFamily", "ipv4"))
            }
        } catch (error: IOException) {
            if (!closed.get() && ipv4AnnounceFailures.incrementAndGet() == 1) {
                report("HYBRID_MDNS", JSONObject()
                    .put("stage", "ipv4_announcement_failed").put("success", false)
                    .put("addressFamily", "ipv4").put("errorType", error.javaClass.simpleName.take(48)))
            }
        }
    }

    private fun startIpv6(mdns: MulticastSocket, iface: NetworkInterface) {
        val local = advertisedIpv6Address ?: return
        try {
            val group = Inet6Address.getByAddress(null, MDNS_IPV6_BYTES, local.scopeId)
            mdns.joinGroup(InetSocketAddress(group, MDNS_PORT), iface)
            ipv6Group = group
        } catch (error: Exception) {
            reportIpv6Error("ipv6_multicast_join_failed", error)
            return
        }
        emit("ipv6_multicast_joined", true)
        announceIpv6(mdns)
    }

    private fun announceIpv6(mdns: MulticastSocket) {
        val group = ipv6Group ?: return
        try {
            sendAnnouncement(mdns, TTL_SECONDS, group)
            if (ipv6Announcements.incrementAndGet() == 1) {
                report("HYBRID_MDNS", JSONObject()
                    .put("stage", "airplay_service_published")
                    .put("success", true)
                    .put("localAddress", advertisedIpv6Address?.hostAddress?.substringBefore('%'))
                    .put("localPort", config.port)
                    .put("addressFamily", "ipv6"))
            }
        } catch (error: Exception) {
            if (!closed.get()) reportIpv6Error("ipv6_announcement_failed", error)
        }
    }

    private fun reportIpv6Error(stage: String, error: Exception) {
        if (closed.get()) return
        report("HYBRID_MDNS", JSONObject()
            .put("stage", stage).put("success", false).put("addressFamily", "ipv6")
            .put("errorType", error.javaClass.simpleName.take(48)))
    }

    /** Advertisements may start early, but the phone's control service is contacted only after iAP2 auth. */
    fun beginPhoneHandoff() {
        handoffRequested.set(true)
        launchPhoneHandoff()
    }

    private fun launchPhoneHandoff() {
        if (!transportReady.get() || !handoffRequested.get() || closed.get() || handoffFinished.get() ||
            !handoffStarted.compareAndSet(false, true)) return
        postControl {
            handoffDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANDOFF_TIMEOUT_MILLIS)
            nextControlQueryNanos = System.nanoTime()
            emit("control_handoff_started", true)
            controlTick()
        }
    }

    /** Stops outbound invitations after AirPlay takes over while keeping the Bonjour advertiser running. */
    fun stopPhoneHandoff() {
        if (!handoffFinished.compareAndSet(false, true)) return
        activeProbe.getAndSet(null)?.let { probe -> runCatching { probe.close() } }
        controlWorker.shutdown()
    }

    /**
     * After the original connection ends, use one verified phone's current Bonjour service to
     * request a new AirPlay connection. A successful HTTP reply is not an AirPlay session.
     */
    fun tryResumePhone(
        lastActivePeer: InetAddress?,
        verifiedBluetoothAddress: String,
        hotspotReopened: Boolean,
        awaitReadyToInvite: (Long, () -> Boolean) -> Boolean,
        onRequestFinished: (Boolean) -> Unit,
    ): Boolean {
        if (closed.get() || !transportReady.get() || !handoffFinished.get()) return false
        val mdns = socket?.takeUnless { it.isClosed } ?: return false
        val bluetoothId = normalizeBluetoothAddress(verifiedBluetoothAddress) ?: return false
        if (normalizeBluetoothAddress(phoneBluetoothAddress()) != bluetoothId) return false
        val peer = when (lastActivePeer) {
            is Inet4Address -> lastActivePeer.takeIf { isOnLinkIpv4Peer(address as Inet4Address, it, ipv4PrefixLength) }
            is Inet6Address -> {
                val scope = ipv6Group?.scopeId ?: return false
                if (!lastActivePeer.isLinkLocalAddress || lastActivePeer == advertisedIpv6Address ||
                    (lastActivePeer.scopeId != 0 && lastActivePeer.scopeId != scope)) return false
                runCatching { Inet6Address.getByAddress(null, lastActivePeer.address, scope) }.getOrNull()
            }
            else -> null
        } ?: return false
        val attempt = if (hotspotReopened) TargetedCarPlayResume(peer, bluetoothId, timeoutMs = 3_300L)
            else TargetedCarPlayResume(peer, bluetoothId)
        if (!phoneResume.compareAndSet(null, attempt)) return false
        if (closed.get()) {
            phoneResume.compareAndSet(attempt, null)
            attempt.close()
            return false
        }
        Thread({
            val queries = if (hotspotReopened) RESUME_DNS_QUERIES_AFTER_AP else RESUME_DNS_QUERIES
            val accepted = runCatching { resumePhone(mdns, attempt, queries, awaitReadyToInvite) }.getOrDefault(false)
            if (!closed.get() && phoneResume.get() === attempt) {
                emit(if (accepted) "control_resume_accepted" else "control_resume_unconfirmed", accepted)
                runCatching { onRequestFinished(accepted) }
            }
        }, "hybrid-carplay-control-resume").apply { isDaemon = true; start() }
        return true
    }

    fun cancelPhoneResume() {
        phoneResume.getAndSet(null)?.close()
    }

    private fun resumePhone(
        mdns: MulticastSocket,
        attempt: TargetedCarPlayResume,
        queryCount: Int,
        awaitReadyToInvite: (Long, () -> Boolean) -> Boolean,
    ): Boolean {
        val group = if (attempt.peer is Inet6Address) ipv6Group ?: return false else MDNS_GROUP
        if (group is Inet6Address) announceIpv6(mdns) else announceIpv4(mdns)
        for (index in 0 until queryCount) {
            if (closed.get() || phoneResume.get() !== attempt || System.nanoTime() >= attempt.deadlineNanos) return false
            val query = attempt.query(elapsedMillis())
            mdns.send(DatagramPacket(query, query.size, group, MDNS_PORT))
            val endpoint = attempt.awaitEndpoint(RESUME_QUERY_INTERVAL_MS)
            if (endpoint != null) {
                if (!awaitReadyToInvite(attempt.deadlineNanos) { !closed.get() && phoneResume.get() === attempt }) {
                    return false
                }
                return probeControl(endpoint.host, endpoint.port, index + 1, attempt)
            }
        }
        return false
    }

    fun diagnosticSnapshot(): String = "mdns4Rx=${ipv4Packets.get()} mdns4OffLink=${ipv4OffLinkRejects.get()} ctrl4IdentityMatched=${ipv4Resolved.get()} " +
        "mdnsQueries=${queryCount.get()} mdnsResponses=${responseCount.get()} " +
        "mdnsAnnouncements=${announceCount.get()} mdns4AnnounceFailures=${ipv4AnnounceFailures.get()} controlServices=${controlFoundCount.get()} " +
        "controlResolves=${ipv4Resolved.get() + ipv6Resolved.get()} controlProbes=${probeAttemptCount.get()} " +
        "controlProbe2xx=${probeSuccessCount.get()} lastProbe=$lastProbe " +
        "controlDnsQueries=${controlQueries.get()} controlDnsReplies=${controlReplies.get()} " +
        "mdns6Rx=${ipv6Packets.get()} mdns6ScopeRejects=${ipv6ScopeRejects.get()} " +
        "mdns6Announce=${ipv6Announcements.get()} mdns6Responses=${ipv6Responses.get()} " +
        "ctrl6Queries=${ipv6BrowseQueries.get()} ctrl6Replies=${ipv6ControlReplies.get()} " +
        "ctrl6Resolved=${ipv6Resolved.get()} ctrl6Probes=${ipv6Probes.get()} ctrl6Probe2xx=${ipv6ProbeSuccesses.get()}"

    private fun browseControl(nowNanos: Long) {
        val mdns = socket ?: return
        val nowMillis = TimeUnit.NANOSECONDS.toMillis(nowNanos)
        syncControlEndpoints(rawControlEndpoints(nowMillis))
        if (nowNanos - nextControlQueryNanos >= 0) {
            nextControlQueryNanos = nowNanos + CONTROL_QUERY_INTERVAL_NANOS
            // The phone can join the hotspot after the first unsolicited announcements.
            announceIpv4(mdns)
            announceIpv6(mdns)
            val queries = synchronized(peerLock) {
                refreshPeerIdentity()
                val pending = ArrayList<ByteArray>(MAX_IPV4_PEERS)
                for (peer in ipv4Peers.values) {
                    val query = peer.query(nowMillis)
                    if (pending.none { it.contentEquals(query) }) pending.add(query)
                }
                if (pending.isEmpty()) pending.add(CarPlayControlDns.browseQuery())
                pending
            }
            for (query in queries) {
                try {
                    mdns.send(DatagramPacket(query, query.size, MDNS_GROUP, MDNS_PORT))
                    if (controlQueries.incrementAndGet() == 1) emit("control_dns_browse", true)
                } catch (error: IOException) {
                    if (!closed.get()) emit("control_dns_browse_failed", false)
                }
            }
            browseIpv6Control(mdns, nowMillis)
        }
    }

    private fun offerIpv4ControlReply(packet: DatagramPacket, source: Inet4Address) {
        if (packet.port != MDNS_PORT || packet.length < DNS_HEADER_BYTES ||
            (packet.data[packet.offset + 2].toInt() and 0x80) == 0
        ) return
        controlReplies.incrementAndGet()
        synchronized(peerLock) {
            val id = refreshPeerIdentity() ?: return
            learnIpv4ControlPeer(ipv4Peers, source, id, packet, elapsedMillis())
        }
    }

    private fun browseIpv6Control(mdns: MulticastSocket, nowMillis: Long) {
        val group = ipv6Group ?: return
        val queries = synchronized(peerLock) {
            refreshPeerIdentity()
            val result = ArrayList<ByteArray>(MAX_IPV6_PEERS)
            for (peer in ipv6Peers.values) {
                val query = peer.query(nowMillis)
                if (result.none { it.contentEquals(query) }) result.add(query)
            }
            if (result.isEmpty()) result.add(CarPlayControlDns.browseQuery())
            result
        }
        try {
            for (query in queries) {
                mdns.send(DatagramPacket(query, query.size, group, MDNS_PORT))
                if (ipv6BrowseQueries.incrementAndGet() == 1) emit("control_dns_browse_ipv6", true)
            }
        } catch (error: Exception) {
            reportIpv6Error("control_dns_browse_failed", error)
        }
    }

    private fun refreshPeerIdentity(): String? {
        val pairedAddress = normalizeBluetoothAddress(phoneBluetoothAddress())
        if (cachedBluetoothAddress != pairedAddress) {
            ipv4Peers.clear()
            ipv6Peers.clear()
            cachedBluetoothAddress = pairedAddress
        }
        return pairedAddress
    }

    private fun offerIpv6ControlReply(packet: DatagramPacket, source: Inet6Address) {
        if (packet.port != MDNS_PORT || packet.length < DNS_HEADER_BYTES ||
            (packet.data[packet.offset + 2].toInt() and 0x80) == 0
        ) return
        ipv6ControlReplies.incrementAndGet()
        synchronized(peerLock) {
            val id = refreshPeerIdentity() ?: return
            val dns = ipv6Peers[source] ?: run {
                if (ipv6Peers.size >= MAX_IPV6_PEERS) {
                    val iterator = ipv6Peers.entries.iterator()
                    iterator.next()
                    iterator.remove()
                }
                CarPlayControlDns(source, id).also { ipv6Peers[source] = it }
            }
            dns.offer(packet.data, packet.offset, packet.length, source, elapsedMillis())
        }
    }

    private fun elapsedMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

    private fun rawControlEndpoints(nowMillis: Long): List<CarPlayControlDns.Endpoint> = synchronized(peerLock) {
        refreshPeerIdentity()
        ipv4Peers.values.flatMap { it.endpoints(nowMillis) } + ipv6Peers.values.flatMap { it.endpoints(nowMillis) }
    }

    private fun controlKey(endpoint: CarPlayControlDns.Endpoint): String =
        "${if (endpoint.host is Inet6Address) "ipv6" else "ipv4"}/${endpoint.host.hostAddress}/${endpoint.key}"

    private fun syncControlEndpoints(endpoints: List<CarPlayControlDns.Endpoint>) {
        val endpointsByKey = endpoints.associateBy(::controlKey)
        candidates.keys.retainAll(endpointsByKey.keys)
        for ((key, endpoint) in endpointsByKey) {
            val previous = candidates[key]
            if (previous?.host == endpoint.host && previous.port == endpoint.port) continue
            if (previous == null) {
                if (candidates.size >= MAX_CONTROL_SERVICES ||
                    candidates.values.any { it.host == endpoint.host && it.port == endpoint.port }
                ) continue
                controlFoundCount.incrementAndGet()
            }
            candidates[key] = ControlCandidate(endpoint.host, endpoint.port)
            val ipv6 = endpoint.host is Inet6Address
            if (ipv6) ipv6Resolved.incrementAndGet() else ipv4Resolved.incrementAndGet()
            report("HYBRID_MDNS", JSONObject()
                .put("stage", "control_dns_service_resolved")
                .put("success", true)
                .put("addressFamily", if (ipv6) "ipv6" else "ipv4")
                .put("localPort", endpoint.port))
        }
    }

    private fun postControl(action: () -> Unit) {
        if (closed.get() || handoffFinished.get()) return
        try {
            controlWorker.execute { if (!closed.get() && !handoffFinished.get()) action() }
        } catch (_: RejectedExecutionException) {
            // Close can race handoff scheduling.
        }
    }

    private fun controlTick() {
        if (closed.get() || handoffFinished.get()) return
        val tickStartedAt = System.nanoTime()
        if (tickStartedAt - handoffDeadlineNanos >= 0L) {
            emit("control_handoff_expired", false)
            stopPhoneHandoff()
            return
        }
        browseControl(tickStartedAt)

        // Browsing can add a candidate and set its first retry time within this tick.
        val now = System.nanoTime()
        val ready = candidates.values.filter {
            it.probeAttempts < MAX_PROBE_ATTEMPTS && now - it.retryAtNanos >= 0L
        }.minByOrNull { it.probeAttempts }
        if (ready != null && probeAttemptCount.get() < MAX_TOTAL_PROBES) {
            val attempt = probeAttemptCount.incrementAndGet()
            ready.probeAttempts++
            // HTTP 2xx confirms the request, but the iPhone may still never open an AirPlay session.
            val accepted = probeControl(ready.host, ready.port, attempt)
            val retryDelay = if (accepted) ACCEPTED_PROBE_RETRY_DELAY_NANOS else RETRY_DELAY_NANOS
            ready.retryAtNanos = System.nanoTime() + retryDelay
        }

        if (handoffFinished.get() || closed.get()) return
        try {
            controlWorker.schedule({ controlTick() }, CONTROL_POLL_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // Discovery has already closed.
        }
    }

    private fun serve(mdns: MulticastSocket) {
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        var nextAnnouncement = System.nanoTime() + 1_000_000_000L
        while (!closed.get() && !mdns.isClosed) {
            val now = System.nanoTime()
            // Busy multicast traffic can keep receive() from ever timing out.
            if (now - nextAnnouncement >= 0L) {
                announceIpv4(mdns)
                announceIpv6(mdns)
                nextAnnouncement = now + ANNOUNCE_INTERVAL_NANOS
            }
            try {
                packet.length = buffer.size
                mdns.receive(packet)
                val source = packet.address
                if (source.isAnyLocalAddress || packet.port == MDNS_PORT &&
                    (source == address || source == ipv6Address)) continue
                val duringHandoff = handoffStarted.get() && !handoffFinished.get()
                val group = if (source is Inet6Address) {
                    val joinedGroup = ipv6Group ?: continue
                    ipv6Packets.incrementAndGet()
                    if (!source.isLinkLocalAddress || source.scopeId != joinedGroup.scopeId) {
                        ipv6ScopeRejects.incrementAndGet()
                        continue
                    }
                    if (duringHandoff) offerIpv6ControlReply(packet, source)
                    joinedGroup
                } else if (source is Inet4Address) {
                    ipv4Packets.incrementAndGet()
                    if (!isOnLinkIpv4Peer(address as Inet4Address, source, ipv4PrefixLength)) {
                        ipv4OffLinkRejects.incrementAndGet()
                        continue
                    }
                    if (duringHandoff) offerIpv4ControlReply(packet, source)
                    MDNS_GROUP
                } else continue
                phoneResume.get()?.offer(packet, elapsedMillis())
                val questions = CarPlayControlDns.readQuestions(
                    packet.data, packet.offset, packet.length, legacy = packet.port != MDNS_PORT,
                ) ?: continue
                if (questions.names.any { it in requestedNames }) {
                    val destination = if (questions.unicastRequested) source else group
                    val destinationPort = if (questions.unicastRequested) packet.port else MDNS_PORT
                    if (source is Inet6Address) {
                        try {
                            sendAnnouncement(mdns, TTL_SECONDS, destination, destinationPort, questions.legacy)
                            ipv6Responses.incrementAndGet()
                        } catch (error: Exception) {
                            reportIpv6Error("ipv6_response_failed", error)
                        }
                    } else {
                        queryCount.incrementAndGet()
                        try {
                            sendAnnouncement(mdns, TTL_SECONDS, destination, destinationPort, questions.legacy)
                            responseCount.incrementAndGet()
                        } catch (error: IOException) {
                            if (!closed.get()) {
                                report("HYBRID_MDNS", JSONObject()
                                    .put("stage", "ipv4_response_failed").put("success", false)
                                    .put("addressFamily", "ipv4")
                                    .put("errorType", error.javaClass.simpleName.take(48)))
                            }
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Also check the announcement timer on an idle network.
            } catch (error: Throwable) {
                if (!closed.get()) {
                    report("HYBRID_MDNS", JSONObject()
                        .put("stage", "listener_error")
                        .put("success", false)
                        .put("errorType", error.javaClass.simpleName.take(48)))
                }
                break
            }
        }
    }

    private fun probeControl(host: InetAddress, port: Int, attempt: Int,
                             resume: TargetedCarPlayResume? = null): Boolean {
        val ipv6 = host is Inet6Address
        val localAddress = if (ipv6) advertisedIpv6Address ?: return false else address
        val family = if (ipv6) "ipv6" else "ipv4"
        val deadline = resume?.deadlineNanos ?: handoffDeadlineNanos
        fun pending() = !closed.get() && (if (resume == null) !handoffFinished.get() else phoneResume.get() === resume)
        if (resume == null && host is Inet4Address && synchronized(peerLock) {
                refreshPeerIdentity()
                ipv4Peers[host]?.endpoints(elapsedMillis())?.none { it.port == port } != false
            }) return false
        val hostLiteral = if (ipv6) "[${host.hostAddress.substringBefore('%')}]" else host.hostAddress
        val probe = Socket()
        val owner = resume?.probe ?: activeProbe
        try {
            if (!owner.compareAndSet(null, probe) || !pending()) return false
            if (ipv6) ipv6Probes.incrementAndGet()
            probe.tcpNoDelay = true
            probe.bind(InetSocketAddress(localAddress, 0))
            probe.connect(InetSocketAddress(host, port), remainingProbeMillis(deadline))
            if (!pending()) return false
            val replyDeadline = minOf(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_TIMEOUT_MILLIS))
            val deviceId = config.deviceId.replace(":", "")
            val request = "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
                "Host: $hostLiteral:$port\r\n" +
                "User-Agent: AirPlay/${config.sourceVersion}\r\n" +
                "AirPlay-Receiver-Device-ID: $deviceId\r\n" +
                "Connection: close\r\n\r\n"
            val output = probe.getOutputStream()
            output.write(request.toByteArray(ASCII))
            output.flush()
            val line = readStatusLine(probe, replyDeadline)
            if (!pending()) return false
            val code = HTTP_STATUS.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val success = code in 200..299
            if (success) {
                probeSuccessCount.incrementAndGet()
                if (ipv6) ipv6ProbeSuccesses.incrementAndGet()
            }
            lastProbe = if (code > 0) "http_$code" else "connected_no_status"
            report("HYBRID_MDNS", JSONObject()
                .put("stage", if (resume == null) "control_service_probe" else "control_resume_probe")
                .put("addressFamily", family)
                .put("success", success)
                .put("resultCode", code)
                .put("attempt", attempt))
            return success
        } catch (error: Exception) {
            if (!pending()) return false
            lastProbe = "failed_${error.javaClass.simpleName}"
            report("HYBRID_MDNS", JSONObject()
                .put("stage", if (resume == null) "control_service_probe" else "control_resume_probe")
                .put("addressFamily", family)
                .put("success", false)
                .put("errorType", error.javaClass.simpleName.take(48))
                .put("attempt", attempt))
            return false
        } finally {
            owner.compareAndSet(probe, null)
            runCatching { probe.close() }
        }
    }

    private fun remainingProbeMillis(deadlineNanos: Long): Int {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0L) throw SocketTimeoutException("CarPlay control deadline reached")
        return TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1L, PROBE_TIMEOUT_MILLIS).toInt()
    }

    private fun readStatusLine(socket: Socket, deadlineNanos: Long): String {
        val output = ByteArrayOutputStream(96)
        val input = socket.getInputStream().buffered()
        while (output.size() < 512) {
            socket.soTimeout = remainingProbeMillis(deadlineNanos)
            val value = input.read()
            if (value < 0 || value == 10) break
            if (value != 13) output.write(value)
        }
        return String(output.toByteArray(), ASCII)
    }

    private fun sendAnnouncement(
        target: MulticastSocket,
        ttl: Int,
        destination: InetAddress = MDNS_GROUP,
        destinationPort: Int = MDNS_PORT,
        legacy: CarPlayControlDns.Companion.LegacyQuestion? = null,
    ) {
        val payload = when {
            legacy != null -> CarPlayControlDns.encodeResponse(legacyRecords, legacy)
            ttl == 0 -> goodbyeResponse
            else -> regularResponse
        }
        target.send(DatagramPacket(payload, payload.size, destination, destinationPort))
    }

    private fun buildRecords(ttl: Int, legacy: Boolean): List<ByteArray> {
        val host = receiverHostName
        val instance = receiverInstanceName
        val records = ArrayList<ByteArray>(6)
        records += resourceRecord(AIRPLAY_SERVICE, TYPE_PTR, CLASS_IN, ttl, dnsName(instance))
        val srv = ByteArrayOutputStream()
        DataOutputStream(srv).apply {
            writeShort(0); writeShort(0); writeShort(config.port); write(dnsName(host))
        }
        val uniqueClass = if (legacy) CLASS_IN else CLASS_IN_FLUSH
        records += resourceRecord(instance, TYPE_SRV, uniqueClass, ttl, srv.toByteArray())
        records += resourceRecord(instance, TYPE_TXT, uniqueClass, ttl, txtRecord())
        // DNS query transport does not constrain which bound addresses are valid on this Wi-Fi link.
        records += resourceRecord(host, TYPE_A, uniqueClass, ttl, address.address)
        advertisedIpv6Address?.let { records += resourceRecord(host, TYPE_AAAA, uniqueClass, ttl, it.address) }
        records += resourceRecord(DNS_SD_META, TYPE_PTR, CLASS_IN, ttl, dnsName(AIRPLAY_SERVICE))
        return records
    }

    private fun txtRecord(): ByteArray {
        val features = AirPlayInfoPlist.features(config)
        val low = "0x${(features and 0xffff_ffffL).toString(16)}"
        val high = features ushr 32
        val featureText = if (high == 0L) low else "$low,0x${high.toString(16)}"
        val values = linkedMapOf(
            "deviceid" to config.deviceId,
            "features" to featureText,
            "flags" to "0x4",
            "model" to config.model,
            "srcvers" to config.sourceVersion,
            "protovers" to "1.1",
            "pi" to identity.pairingId,
            "pk" to identity.publicKeyHex,
        )
        val output = ByteArrayOutputStream(512)
        for ((key, value) in values) {
            val bytes = "$key=$value".toByteArray(UTF8)
            if (bytes.size > 255) throw IOException("AirPlay TXT value exceeds mDNS string limit")
            output.write(bytes.size)
            output.write(bytes)
        }
        return output.toByteArray()
    }

    private fun resourceRecord(name: String, type: Int, recordClass: Int, ttl: Int, rdata: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(512)
        val data = DataOutputStream(output)
        data.write(dnsName(name))
        data.writeShort(type)
        data.writeShort(recordClass)
        data.writeInt(ttl)
        data.writeShort(rdata.size)
        data.write(rdata)
        return output.toByteArray()
    }

    private fun dnsName(value: String): ByteArray {
        val output = ByteArrayOutputStream(128)
        val clean = value.trimEnd('.')
        for (label in clean.split('.')) {
            val bytes = label.toByteArray(UTF8)
            if (bytes.isEmpty() || bytes.size > 63) throw IOException("Invalid mDNS label")
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0)
        return output.toByteArray()
    }

    private fun emit(stage: String, success: Boolean) {
        if (closed.get()) return
        report("HYBRID_MDNS", JSONObject().put("stage", stage).put("success", success))
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelPhoneResume()
        stopPhoneHandoff()
        controlWorker.shutdownNow()
        socket?.let { active ->
            runCatching { sendAnnouncement(active, ttl = 0) }
            multicastInterface?.let { iface ->
                ipv6Group?.let { group ->
                    runCatching { sendAnnouncement(active, ttl = 0, destination = group) }
                    runCatching { active.leaveGroup(InetSocketAddress(group, MDNS_PORT), iface) }
                }
                runCatching { active.leaveGroup(MDNS_MEMBERSHIP, iface) }
            }
            active.close()
        }
        worker?.interrupt()
        try { worker?.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        releaseMulticastLock()
    }

    private companion object {
        const val AIRPLAY_SERVICE = "_airplay._tcp.local."
        const val DNS_SD_META = "_services._dns-sd._udp.local."
        const val MDNS_PORT = 5353
        const val TTL_SECONDS = 120
        const val TYPE_A = 1
        const val TYPE_PTR = 12
        const val TYPE_TXT = 16
        const val TYPE_AAAA = 28
        const val TYPE_SRV = 33
        const val CLASS_IN = 1
        const val CLASS_IN_FLUSH = 0x8001
        const val DNS_HEADER_BYTES = 12
        const val ANNOUNCE_INTERVAL_NANOS = 60_000_000_000L
        const val MAX_CONTROL_SERVICES = 8
        const val MAX_IPV6_PEERS = 4
        const val MAX_PROBE_ATTEMPTS = 4
        const val MAX_TOTAL_PROBES = 7
        const val CONTROL_POLL_MILLIS = 200L
        const val PROBE_TIMEOUT_MILLIS = 1_800L
        const val RESUME_DNS_QUERIES = 3
        const val RESUME_DNS_QUERIES_AFTER_AP = 9
        const val RESUME_QUERY_INTERVAL_MS = 300L
        const val HANDOFF_TIMEOUT_MILLIS = 20_000L
        const val RETRY_DELAY_NANOS = 1_500_000_000L
        const val ACCEPTED_PROBE_RETRY_DELAY_NANOS = 3_000_000_000L
        const val CONTROL_QUERY_INTERVAL_NANOS = 1_500_000_000L
        val MDNS_GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
        val MDNS_MEMBERSHIP = InetSocketAddress(MDNS_GROUP, MDNS_PORT)
        val MDNS_IPV6_BYTES = byteArrayOf(0xff.toByte(), 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xfb.toByte())
        val BLUETOOTH_HEX = Regex("[0-9a-f]{12}")

        fun normalizeBluetoothAddress(address: String?): String? =
            address?.replace(":", "")?.lowercase(Locale.US)
                ?.takeIf { BLUETOOTH_HEX.matches(it) && it != "000000000000" }
        val HTTP_STATUS = Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})(?: |$)")
        val ASCII: Charset = Charset.forName("US-ASCII")
        val UTF8: Charset = Charset.forName("UTF-8")
    }

    private class ControlCandidate(val host: InetAddress, val port: Int) {
        var retryAtNanos = System.nanoTime()
        var probeAttempts = 0
    }
}

private const val MAX_IPV4_PEERS = 4

internal fun learnIpv4ControlPeer(
    peers: LinkedHashMap<Inet4Address, CarPlayControlDns>,
    source: Inet4Address,
    bluetoothId: String,
    packet: DatagramPacket,
    nowMillis: Long,
): Boolean {
    val tracked = peers[source]
    val cache = tracked ?: CarPlayControlDns(source, bluetoothId)
    val matched = cache.offer(packet.data, packet.offset, packet.length, source, nowMillis).isNotEmpty()
    if (tracked != null || !cache.hasControlRecord(nowMillis)) return matched
    if (peers.size >= MAX_IPV4_PEERS) {
        val unresolved = peers.entries.firstOrNull { it.value.endpoints(nowMillis).isEmpty() } ?: return false
        peers.remove(unresolved.key)
    }
    peers[source] = cache
    return matched
}

internal fun isOnLinkIpv4Peer(local: Inet4Address, peer: Inet4Address, prefixLength: Int): Boolean {
    if (prefixLength !in 1..31 || peer == local || peer.isAnyLocalAddress || peer.isLoopbackAddress ||
        peer.isLinkLocalAddress || peer.isMulticastAddress
    ) return false
    fun bits(address: Inet4Address): Int = address.address.fold(0) { value, octet ->
        (value shl 8) or (octet.toInt() and 0xff)
    }
    val mask = -1 shl (32 - prefixLength)
    val peerBits = bits(peer)
    if ((bits(local) and mask) != (peerBits and mask)) return false
    val hostBits = peerBits and mask.inv()
    return prefixLength == 31 || (hostBits != 0 && hostBits != mask.inv())
}
