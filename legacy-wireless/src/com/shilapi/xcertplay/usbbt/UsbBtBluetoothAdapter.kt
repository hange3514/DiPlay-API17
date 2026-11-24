package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.WakeableBlockingDuplexByteStream
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB Classic host adapted from DiPlay Legacy Nissan (GPL-3.0).
 * The caller supplies the exact device and connection approved by Android.
 */
class UsbBtBluetoothAdapter(
    private val context: Context,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val bluetoothName: String,
    private val eirProfile: Int,
    private val outboundOnly: Boolean,
    private val listener: Listener,
) : Closeable {
    interface Listener {
        fun onIncomingStream(address: String, stream: BlockingDuplexByteStream): Boolean
        fun onFailure(message: String)
        fun onSecurityResult(stage: String, status: Int, eventCode: Int, outcome: String)
    }

    data class Peer(val address: String, val name: String, val classOfDevice: Int)

    class ConnectionFailure(val stage: String, val status: Int, message: String, cause: Throwable? = null) :
        IOException(message, cause)

    private class Inquiry {
        val complete = CountDownLatch(1)
        val peers = LinkedHashMap<String, HciProtocol.InquiryPeer>()
        @Volatile var status = -1
    }

    private class NameRequest(val address: String) {
        val complete = CountDownLatch(1)
        @Volatile var name: String? = null
    }

    private class AclAttempt(val address: String) {
        val complete = CountDownLatch(1)
        @Volatile var handle = -1
        @Volatile var status = -1
    }

    private class Link(val address: String, val handle: Int, val l2cap: L2capLayer) {
        val authenticated = CountDownLatch(1)
        val encryptionChanged = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        @Volatile var authenticationStatus = -1
        @Volatile var encryptionStatus = -1
        @Volatile var disconnectionReason = -1
        @Volatile var encrypted = false
        @Volatile var closed = false
        @Volatile var rfcomm: RfcommLayer? = null
        @Volatile var stream: UsbBtRfcommStream? = null
    }

    private val lifecycle = Any()
    private val linkReady = java.lang.Object()
    private val discovery = Any()
    private val outgoing = Any()
    private val closed = AtomicBoolean(false)
    private val links = ConcurrentHashMap<Int, Link>()
    @Volatile private var hci: UsbBtHci? = null
    @Volatile private var pairing: UsbBtPairingAgent? = null
    @Volatile private var linkKeys: LinkKeyStore? = null
    var legacyLinkKeyStatus: String = "not_applicable"
        private set
    @Volatile private var phoneConnectionsAllowed = false
    @Volatile private var deferredPhoneConnections = false
    @Volatile private var bondedOnlyPeer: String? = null
    @Volatile private var retiringPreferred: Link? = null
    @Volatile private var preferredCleanupFailed = false
    private val acceptedIncoming = HashSet<String>() // event pump only
    @Volatile private var info: UsbBtHci.InitResult? = null
    @Volatile private var currentInquiry: Inquiry? = null
    @Volatile private var nameRequest: NameRequest? = null
    @Volatile private var aclAttempt: AclAttempt? = null
    private var eventPump: Thread? = null
    private var aclPump: Thread? = null

    val localAddress: String? get() = info?.bdAddr
    val scanEnable: Int get() = info?.scanEnable ?: -1
    val scanEnableStatus: Int get() = info?.scanEnableReadStatus ?: -1
    val scanEnableRequested: Int get() = info?.scanEnableRequested ?: -1
    val scanEnableWriteStatus: Int get() = info?.scanEnableWriteStatus ?: -1
    val scanEnableWriteCount: Int get() = info?.scanEnableWriteCount ?: 0
    val scanEnableUsbSubmitResult: Int get() = info?.scanEnableUsbSubmitResult ?: -1
    val scanEnableAccepted: Boolean get() = scanEnableWriteStatus == 0
    val controllerManufacturer: Int get() = info?.controllerManufacturer ?: -1
    val controllerHciVersion: Int get() = info?.controllerHciVersion ?: -1
    val realtekSetupSelected: Boolean get() = info?.realtekFirmwareSelected == true

    @Throws(IOException::class)
    @JvmOverloads
    fun start(deferPhoneConnections: Boolean = false, legacyPhoneRecord: String? = null) {
        val transport = synchronized(lifecycle) {
            checkOpen()
            if (hci != null) throw IOException("USB Bluetooth already started")
            val opened = UsbBtHci.open(device, connection)
                ?: throw IOException("Unable to claim the authorized Bluetooth USB interface")
            hci = opened
            deferredPhoneConnections = deferPhoneConnections
            phoneConnectionsAllowed = !deferPhoneConnections
            opened
        }
        try {
            val result = transport.initialize(
                firmwareLoader = UsbBtFirmwareLoader(transport, context.assets),
                bluetoothName = bluetoothName,
                eirProfile = eirProfile,
                outboundOnly = outboundOnly,
                deferPhoneConnections = deferPhoneConnections,
            )
            val initialized = when (result) {
                is UsbBtHci.HciResult.Ok -> result.value
                is UsbBtHci.HciResult.Failed -> throw IOException(result.reason)
            }
            synchronized(lifecycle) {
                checkOpen()
                info = initialized
                val keys = LinkKeyStore(context, initialized.bdAddr)
                legacyLinkKeyStatus = keys.restoreLegacy(legacyPhoneRecord)
                linkKeys = keys
                pairing = UsbBtPairingAgent(transport, keys, null, context).also { it.start() }
                eventPump = pump("UsbBtControllerEvents") { processEvent(transport, transport.pollEvent(100)) }
                aclPump = pump("UsbBtControllerAcl") {
                    for (packet in transport.pollAclPackets(100)) {
                        linkForAclPacket(packet.handle)?.l2cap?.onAclData(packet)
                    }
                }
            }
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    @Throws(IOException::class)
    fun allowPhoneConnections() = synchronized(outgoing) {
        val transport = readyTransport()
        if (phoneConnectionsAllowed) return@synchronized
        if (preferredCleanupFailed) throw IOException("Previous Bluetooth ACL release was not confirmed")
        val initialized = info ?: throw IOException("USB Bluetooth is not ready")
        val requested = if (outboundOnly) HciProtocol.SCAN_PAGE else HciProtocol.SCAN_INQUIRY_AND_PAGE
        // A controller may report its first connection request just before the write returns.
        phoneConnectionsAllowed = true
        when (val result = transport.configureScan(initialized, requested)) {
            is UsbBtHci.HciResult.Ok -> info = result.value
            is UsbBtHci.HciResult.Failed -> {
                phoneConnectionsAllowed = false
                throw IOException(result.reason)
            }
        }
    }

    /** Stop classic discovery before releasing the app-owned controller at Wi-Fi handoff. */
    fun stopDiscoveryForHandoff(): Boolean = synchronized(outgoing) {
        if (closed.get()) return@synchronized false
        val transport = hci ?: return@synchronized false
        val initialized = info ?: return@synchronized false
        phoneConnectionsAllowed = false
        if (initialized.scanEnableRequested == HciProtocol.SCAN_DISABLED &&
            initialized.scanEnableWriteStatus == 0
        ) return@synchronized true
        when (val result = transport.configureScan(initialized, HciProtocol.SCAN_DISABLED)) {
            is UsbBtHci.HciResult.Ok -> {
                info = result.value
                true
            }
            is UsbBtHci.HciResult.Failed -> false
        }
    }

    /**
     * A previously authenticated CM390 peer can page out before scan starts. Incoming pages
     * stay disabled until the worker returns or is fully retired by its caller.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun openRememberedRfcommStream(address: String, uuid: String): BlockingDuplexByteStream? = synchronized(outgoing) {
        val peer = address.uppercase(Locale.US)
        if (outboundOnly || !deferredPhoneConnections || phoneConnectionsAllowed ||
            links.isNotEmpty() || linkKeys?.load(peer) == null) return@synchronized null
        bondedOnlyPeer = peer
        try {
            openRfcommStream(peer, uuid)
        } finally {
            bondedOnlyPeer = null
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun discoverPhones(timeoutMillis: Long): List<Peer> = synchronized(discovery) {
        require(timeoutMillis >= 1_280)
        val transport = readyTransport()
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val inquiry = Inquiry()
        currentInquiry = inquiry
        try {
            val length = ((timeoutMillis - 4_000L) / 1_280L).coerceIn(1L, 12L).toInt()
            val result = transport.sendCommand(HciProtocol.OP_INQUIRY, HciProtocol.encodeInquiry(length))
            if (result is UsbBtHci.HciResult.Failed) throw IOException("Bluetooth inquiry failed: ${result.reason}")
            val inquiryBudget = minOf(remaining(deadline), length * 1_280L + 1_200L)
            if (!inquiry.complete.await(inquiryBudget, TimeUnit.MILLISECONDS)) {
                transport.sendCommand(HciProtocol.OP_INQUIRY_CANCEL, timeoutMs = 750)
            }
            checkOpen()
            val discovered = synchronized(inquiry) { inquiry.peers.values.toList() }
            if (inquiry.status > 0 && discovered.isEmpty()) {
                throw IOException("Bluetooth inquiry reported status 0x${inquiry.status.toString(16)}")
            }
            discovered.map { peer ->
                val name = peer.name ?: lookupName(transport, peer, deadline)
                Peer(peer.address, name.orEmpty(), peer.classOfDevice)
            }
        } finally {
            currentInquiry = null
        }
    }

    fun cancelInquiry() {
        val inquiry = currentInquiry ?: return
        currentInquiry = null
        inquiry.complete.countDown()
        nameRequest?.complete?.countDown()
        hci?.sendCommand(HciProtocol.OP_INQUIRY_CANCEL, timeoutMs = 750)
    }

    private fun lookupName(transport: UsbBtHci, peer: HciProtocol.InquiryPeer, deadline: Long): String? {
        val address = HciProtocol.encodeBdAddr(peer.address) ?: return null
        if (remaining(deadline) < 500 || currentInquiry == null) return null
        val request = NameRequest(peer.address)
        nameRequest = request
        try {
            val result = transport.sendCommand(
                HciProtocol.OP_REMOTE_NAME_REQUEST,
                HciProtocol.encodeRemoteNameRequest(address, peer.pageScanRepetitionMode, peer.clockOffset),
                minOf(750L, remaining(deadline).coerceAtLeast(1)).toInt(),
            )
            if (result is UsbBtHci.HciResult.Failed) return null
            request.complete.await(minOf(900L, remaining(deadline)), TimeUnit.MILLISECONDS)
            if (request.name == null && !closed.get()) {
                transport.sendCommand(HciProtocol.opcode(1, 0x1A), address, timeoutMs = 500)
            }
            return request.name
        } finally {
            nameRequest = null
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun openRfcommStream(address: String, uuid: String): BlockingDuplexByteStream = synchronized(outgoing) {
        val transport = readyTransport()
        val peerAddress = address.uppercase(Locale.US)
        val rawAddress = HciProtocol.encodeBdAddr(peerAddress) ?: throw IOException("Invalid Bluetooth address")
        val service = try { UUID.fromString(uuid) } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid service UUID", failure)
        }
        cancelInquiry()
        val remembered = bondedOnlyPeer == peerAddress
        val link = openAcl(transport, peerAddress, rawAddress, remembered)
        try {
            val client = SdpClient(link.l2cap, link.handle, uuidBigEndian(service))
            val channel = try {
                when (val result = client.findIap2RfcommChannel()) {
                    is SdpClient.SdpResult.Ok -> result.value
                    is SdpClient.SdpResult.Failed -> throw ConnectionFailure("outbound_sdp_failed", -1, result.reason)
                }
            } finally {
                client.close()
            }
            ensureEncrypted(transport, link)
            val bearer = when (val result = link.l2cap.connect(HciProtocol.L2CAP_PSM_RFCOMM)) {
                is L2capLayer.L2capResult.Ok -> result.value
                is L2capLayer.L2capResult.Failed ->
                    throw ConnectionFailure("outbound_rfcomm_failed", -1, result.reason)
            }
            val rfcomm = RfcommLayer(link.l2cap, bearer)
            link.rfcomm = rfcomm
            rfcomm.onDlcClosed = { link.stream?.remoteClosed() }
            val result = rfcomm.openDlc(channel) { dlc ->
                link.stream = UsbBtRfcommStream(rfcomm, dlc) { finishOutbound(transport, link, remembered) }
            }
            if (result is RfcommLayer.RfcommResult.Failed) {
                throw ConnectionFailure("outbound_rfcomm_failed", -1, result.reason)
            }
            checkOpen()
            if (link.closed) throw IOException("Bluetooth ACL disconnected")
            if (!link.encrypted) throw ConnectionFailure("outbound_encryption_lost", link.encryptionStatus,
                "Bluetooth link encryption was lost while opening RFCOMM")
            link.stream ?: throw IOException("Bluetooth RFCOMM receiver was not initialized")
        } catch (failure: Exception) {
            finishOutbound(transport, link, remembered)
            throw failure
        }
    }

    private fun openAcl(transport: UsbBtHci, address: String, rawAddress: ByteArray, remembered: Boolean): Link {
        links.values.firstOrNull { it.address == address && !it.closed }?.let { return it }
        val attempt = AclAttempt(address)
        synchronized(linkReady) { aclAttempt = attempt }
        var pageMayBePending = true
        var usableLink = false
        try {
            when (val result = transport.sendCommand(HciProtocol.OP_CREATE_CONNECTION, HciProtocol.encodeCreateConnection(rawAddress))) {
                is UsbBtHci.HciResult.Failed -> {
                    if (result.status >= 0) pageMayBePending = false
                    // When the phone pages us concurrently, the controller can reject the
                    // outbound command while accepting that same peer's incoming ACL.
                    if (result.status != HciProtocol.STATUS_COMMAND_DISALLOWED ||
                        !attempt.complete.await(350, TimeUnit.MILLISECONDS)
                    ) throw ConnectionFailure("outbound_acl_failed", result.status, result.reason)
                }
                is UsbBtHci.HciResult.Ok -> Unit
            }
            if (!attempt.complete.await(10_000, TimeUnit.MILLISECONDS)) {
                throw ConnectionFailure("outbound_acl_failed", -1, "Bluetooth ACL connection timed out")
            }
            checkOpen()
            if (attempt.status != 0) {
                throw ConnectionFailure("outbound_acl_failed", attempt.status, "Bluetooth ACL connection failed")
            }
            val link = links[attempt.handle] ?: throw IOException("Bluetooth ACL closed while connecting")
            usableLink = true
            return link
        } finally {
            val unused = synchronized(linkReady) {
                aclAttempt = null
                if (!usableLink && attempt.status == 0) links[attempt.handle] else null
            }
            if (!closed.get() && !usableLink && pageMayBePending) {
                if (unused != null) finishOutbound(transport, unused, remembered)
                else if (attempt.status < 0 || remembered) {
                    finishBluetoothCleanup {
                        // Unlock the HCI gate first so a racing late completion never acquires a stream.
                        transport.sendCommand(HciProtocol.opcode(1, 0x08), rawAddress, timeoutMs = 750)
                    }
                }
            }
        }
    }

    private inline fun finishBluetoothCleanup(cleanup: () -> Unit) {
        val interrupted = Thread.interrupted()
        try {
            cleanup()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun ensureEncrypted(transport: UsbBtHci, link: Link) {
        if (link.encrypted) return
        val auth = transport.sendCommand(
            HciProtocol.OP_AUTHENTICATION_REQUESTED,
            HciProtocol.encodeAuthenticationRequested(link.handle),
        )
        if (link.encrypted && !link.closed) return
        if (auth is UsbBtHci.HciResult.Failed && auth.status != HciProtocol.STATUS_COMMAND_DISALLOWED) {
            throw ConnectionFailure("outbound_auth_command_failed", auth.status, auth.reason)
        }
        val authenticationReported = link.authenticated.await(35_000, TimeUnit.MILLISECONDS)
        if (link.closed) throw ConnectionFailure("outbound_auth_disconnected", link.disconnectionReason,
            "Bluetooth ACL disconnected during authentication")
        if (link.encrypted) return
        if (!authenticationReported) throw ConnectionFailure("outbound_auth_timeout", link.authenticationStatus,
            "Bluetooth controller did not confirm authentication")
        if (link.authenticationStatus != 0) throw ConnectionFailure("outbound_auth_failed", link.authenticationStatus,
            "Bluetooth authentication failed")

        checkOpen()
        val encryption = transport.sendCommand(
            HciProtocol.OP_SET_CONNECTION_ENCRYPTION,
            HciProtocol.encodeSetConnectionEncryption(link.handle, true),
        )
        if (link.encrypted && !link.closed) return
        if (encryption is UsbBtHci.HciResult.Failed && encryption.status != HciProtocol.STATUS_COMMAND_DISALLOWED) {
            throw ConnectionFailure("outbound_encryption_command_failed", encryption.status, encryption.reason)
        }

        val encryptionReported = link.encryptionChanged.await(5_000, TimeUnit.MILLISECONDS)
        if (link.closed) throw ConnectionFailure("outbound_encryption_disconnected", link.disconnectionReason,
            "Bluetooth ACL disconnected during encryption")
        if (link.encrypted) return
        if (encryptionReported) throw ConnectionFailure("outbound_encryption_failed", link.encryptionStatus,
            "Bluetooth controller did not enable link encryption")

        // A missing Encryption Change event is not evidence of encryption. Require a
        // successful, same-handle key-size read from the controller before opening RFCOMM.
        val keySize = transport.sendCommand(
            HciProtocol.OP_READ_ENCRYPTION_KEY_SIZE,
            HciProtocol.encodeAuthenticationRequested(link.handle),
            timeoutMs = 750,
        )
        if (link.closed) throw ConnectionFailure("outbound_encryption_disconnected", link.disconnectionReason,
            "Bluetooth ACL disconnected before encryption could be verified")
        if (link.encrypted) return
        if (keySize is UsbBtHci.HciResult.Ok &&
            HciProtocol.hasValidEncryptionKeySize(link.handle, keySize.value) &&
            (link.encryptionStatus <= 0 || link.encryptionStatus == HciProtocol.STATUS_COMMAND_DISALLOWED)
        ) {
            link.encrypted = true
            listener.onSecurityResult("encryption_key_size_confirmed", 0, -1, "enabled")
            deliverIncoming(link)
            return
        }
        val status = (keySize as? UsbBtHci.HciResult.Failed)?.status?.takeIf { it >= 0 }
            ?: link.encryptionStatus
        throw ConnectionFailure("outbound_encryption_timeout", status,
            "Bluetooth encryption event was not received and key-size verification failed")
    }

    private fun processEvent(transport: UsbBtHci, event: HciProtocol.HciEvent?) {
        if (event == null) return
        pairing?.onEvent(event, bondedOnlyPeer)
        when (event.code) {
            HciProtocol.EVT_CONNECTION_REQUEST -> {
                val request = HciProtocol.parseConnectionRequest(event) ?: return
                if (request.linkType != 1) return
                val address = request.bdAddr.uppercase(Locale.US)
                val accept = if (outboundOnly) address == aclAttempt?.address else phoneConnectionsAllowed
                if (accept) {
                    val result = transport.sendCommand(
                        HciProtocol.OP_ACCEPT_CONNECTION_REQUEST, HciProtocol.encodeAcceptConnection(request.rawAddr),
                    )
                    if (!outboundOnly && deferredPhoneConnections && result is UsbBtHci.HciResult.Ok) {
                        acceptedIncoming.add(address)
                    }
                } else {
                    transport.sendCommand(HciProtocol.OP_REJECT_CONNECTION_REQUEST, request.rawAddr + 0x0F.toByte())
                }
            }
            HciProtocol.EVT_CONNECTION_COMPLETE -> {
                val connected = HciProtocol.parseConnectionComplete(event) ?: return
                val address = connected.bdAddr?.uppercase(Locale.US) ?: return
                val inbound = !outboundOnly && phoneConnectionsAllowed &&
                    (!deferredPhoneConnections || acceptedIncoming.remove(address))
                var orphaned = false
                var incomingLink: Link? = null
                synchronized(linkReady) {
                    if (closed.get()) return
                    val pending = aclAttempt?.takeIf { it.address == address }
                    if (connected.status == 0) {
                        if (!inbound && pending == null) {
                            orphaned = true
                        } else {
                            val link = Link(address, connected.handle, L2capLayer(connected.handle, transport))
                            link.encrypted = connected.encryptionEnabled
                            if (inbound) registerIncoming(transport, link)
                            links[link.handle] = link
                            linkReady.notifyAll()
                            if (inbound && pending == null && !link.encrypted) incomingLink = link
                        }
                    }
                    pending?.let {
                        it.status = connected.status
                        it.handle = connected.handle
                        it.complete.countDown()
                    }
                }
                if (orphaned) {
                    transport.sendCommand(HciProtocol.OP_DISCONNECT, HciProtocol.encodeDisconnect(connected.handle), timeoutMs = 750)
                }
                incomingLink?.let { link ->
                    pump("UsbBtIncomingAuthentication", once = true) {
                        try {
                            ensureEncrypted(transport, link)
                        } catch (failure: ConnectionFailure) {
                            Log.w(TAG, "Incoming Bluetooth link could not be encrypted (status=${failure.status})")
                            disconnect(transport, link)
                        }
                    }
                }
            }
            HciProtocol.EVT_AUTHENTICATION_COMPLETE -> {
                val status = event.params.firstOrNull()?.toInt()?.and(0xff) ?: return
                val link = eventLink(event) ?: return
                if (status != HciProtocol.STATUS_COMMAND_DISALLOWED || link.authenticationStatus < 0) {
                    link.authenticationStatus = status
                }
                if (status != HciProtocol.STATUS_COMMAND_DISALLOWED) link.authenticated.countDown()
                listener.onSecurityResult("authentication_complete", status, event.code,
                    if (status == 0) "success" else "rejected")
            }
            HciProtocol.EVT_ENCRYPTION_CHANGE, HciProtocol.EVT_ENCRYPTION_CHANGE_V2 -> {
                val eventResult = HciProtocol.parseEncryptionChange(event) ?: return
                val link = links[eventResult.handle] ?: return
                val status = eventResult.status
                link.encryptionStatus = status
                if (status == 0) {
                    link.encrypted = eventResult.enabled
                    if (link.encrypted) {
                        link.encryptionChanged.countDown()
                        // An encrypted BR/EDR ACL also confirms peer-initiated authentication.
                        link.authenticated.countDown()
                        deliverIncoming(link)
                    } else if (link.stream != null) {
                        disconnect(transport, link)
                    }
                } else if (status != HciProtocol.STATUS_COMMAND_DISALLOWED && !link.encrypted) {
                    link.encryptionChanged.countDown()
                }
                listener.onSecurityResult("encryption_change", status, event.code,
                    if (status != 0) "rejected" else if (eventResult.enabled) "enabled" else "disabled")
            }
            HciProtocol.EVT_DISCONNECTION_COMPLETE -> {
                val disconnected = HciProtocol.parseDisconnectionComplete(event) ?: return
                if (disconnected.status == 0) {
                    val removed = synchronized(linkReady) {
                        retiringPreferred?.takeIf { it.handle == disconnected.handle }?.disconnected?.countDown()
                        links.remove(disconnected.handle)
                    }
                    removed?.let {
                        it.disconnectionReason = disconnected.reason
                        releaseLink(it, false)
                    }
                }
            }
            HciProtocol.EVT_INQUIRY_COMPLETE -> currentInquiry?.let {
                it.status = event.params.firstOrNull()?.toInt()?.and(0xff) ?: -1
                it.complete.countDown()
            }
            HciProtocol.EVT_INQUIRY_RESULT, HciProtocol.EVT_INQUIRY_RESULT_WITH_RSSI,
            HciProtocol.EVT_EXTENDED_INQUIRY_RESULT -> currentInquiry?.let { inquiry ->
                synchronized(inquiry) {
                    for (peer in HciProtocol.parseInquiryResults(event)) {
                        val saved = inquiry.peers[peer.address]
                        if (saved != null || inquiry.peers.size < 64) {
                            if (saved?.name == null || peer.name != null) inquiry.peers[peer.address] = peer
                        }
                    }
                }
            }
            HciProtocol.EVT_REMOTE_NAME_REQUEST_COMPLETE -> nameRequest?.let { request ->
                val name = HciProtocol.parseRemoteName(event)
                if (name != null && name.first.equals(request.address, ignoreCase = true)) {
                    request.name = name.second
                    request.complete.countDown()
                }
            }
            0x10 -> throw IOException("Bluetooth controller reported a hardware error")
        }
    }

    private fun linkForAclPacket(handle: Int): Link? {
        links[handle]?.let { return it }
        // The interrupt and bulk pipes are read independently. The first ACL
        // signal can be observed before the corresponding Connection Complete.
        val deadline = SystemClock.elapsedRealtime() + 150
        synchronized(linkReady) {
            while (!closed.get()) {
                links[handle]?.let { return it }
                val wait = deadline - SystemClock.elapsedRealtime()
                if (wait <= 0) break
                linkReady.wait(wait)
            }
        }
        return null
    }

    private fun eventLink(event: HciProtocol.HciEvent): Link? {
        if (event.params.size < 3) return null
        val handle = (event.params[1].toInt() and 0xff) or ((event.params[2].toInt() and 0x0f) shl 8)
        return links[handle]
    }

    private fun registerIncoming(transport: UsbBtHci, link: Link) {
        val identity = UsbBtIdentity(bluetoothName)
        val service = if (device.vendorId == 0x10d7 && device.productId == 0xb012 && eirProfile == 1) {
            UsbBtIdentity.IAP2_ACCESSORY_UUID
        } else {
            UsbBtIdentity.IAP2_IPHONE_UUID
        }
        link.l2cap.registerSdpServer(listOf(SdpCodec.iap2Record(identity.iap2RfcommChannel, "iAP2 Wireless", service)))
        link.l2cap.registerService(HciProtocol.L2CAP_PSM_RFCOMM) { channel ->
            val rfcomm = RfcommLayer(link.l2cap, channel, initiator = false, allowedServerChannel = identity.iap2RfcommChannel)
            link.rfcomm = rfcomm
            rfcomm.onDlcOpened = { dlc ->
                if (dlc.serverChannel == identity.iap2RfcommChannel && !link.closed && link.stream == null) {
                    link.stream = UsbBtRfcommStream(rfcomm, dlc) { disconnect(transport, link) }
                    deliverIncoming(link)
                }
            }
            rfcomm.onDlcClosed = { link.stream?.remoteClosed() }
            rfcomm::onRfcommFrame
        }
    }

    private fun deliverIncoming(link: Link) {
        val stream = link.stream ?: return
        if (!outboundOnly && link.encrypted && !link.closed && stream.markDelivered()) {
            if (!listener.onIncomingStream(link.address, stream)) {
                pump("UsbBtRejectedIncoming", once = true) { stream.close() }
            }
        }
    }

    private fun finishOutbound(transport: UsbBtHci, link: Link, remembered: Boolean) {
        finishBluetoothCleanup {
            if (!remembered) {
                disconnect(transport, link)
                return@finishBluetoothCleanup
            }
            // On a failed preferred attempt, stop the ACL directly instead of spending
            // the fallback budget on graceful closes of its provisional protocol channels.
            synchronized(linkReady) {
                if (!links.remove(link.handle, link)) return@finishBluetoothCleanup
                retiringPreferred = link
            }
            try {
                releaseLink(link, false)
                if (closed.get()) return@finishBluetoothCleanup
                val result = transport.sendCommand(
                    HciProtocol.OP_DISCONNECT, HciProtocol.encodeDisconnect(link.handle), timeoutMs = 750,
                )
                if (result is UsbBtHci.HciResult.Failed && result.status == 0x02) {
                    return@finishBluetoothCleanup // Unknown Connection Identifier: ACL is already gone.
                }
                val confirmed = try {
                    link.disconnected.await(1_000L, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
                if (!confirmed && !closed.get()) preferredCleanupFailed = true
            } finally {
                retiringPreferred = null
            }
        }
    }

    private fun disconnect(transport: UsbBtHci, link: Link) {
        if (!links.remove(link.handle, link)) return
        releaseLink(link, true)
        if (!closed.get()) {
            transport.sendCommand(HciProtocol.OP_DISCONNECT, HciProtocol.encodeDisconnect(link.handle), timeoutMs = 750)
        }
    }

    private fun releaseLink(link: Link, sendDisc: Boolean) {
        link.closed = true
        link.authenticated.countDown()
        link.encryptionChanged.countDown()
        link.stream?.remoteClosed()
        link.rfcomm?.close(sendDisc)
        link.l2cap.close(sendDisc)
    }

    private fun pump(name: String, once: Boolean = false, work: () -> Unit): Thread = Thread({
        try {
            do {
                if (closed.get()) break
                work()
            } while (!once)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (!closed.get()) {
                listener.onFailure(error.message ?: error.javaClass.simpleName)
                close()
            }
        }
    }, name).apply {
        isDaemon = true
        start()
    }

    private fun remaining(deadline: Long) = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    private fun checkOpen() { if (closed.get()) throw IOException("USB Bluetooth was closed") }
    private fun readyTransport(): UsbBtHci {
        checkOpen()
        if (info == null) throw IOException("USB Bluetooth is not ready")
        return hci ?: throw IOException("USB Bluetooth is not ready")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val transport = synchronized(lifecycle) { hci.also { hci = null } }
        currentInquiry?.complete?.countDown()
        nameRequest?.complete?.countDown()
        aclAttempt?.complete?.countDown()
        retiringPreferred?.disconnected?.countDown()
        pairing?.stop()
        eventPump?.interrupt()
        aclPump?.interrupt()
        val closingLinks = synchronized(linkReady) {
            val snapshot = links.values.toList()
            links.clear()
            linkReady.notifyAll()
            snapshot
        }
        closingLinks.forEach { releaseLink(it, false) }
        transport?.close()
    }

    companion object {
        private const val TAG = "UsbBtBluetoothAdapter"
        const val PHONE_IAP2_UUID = "02030302-1d19-415f-86f2-22a2106a0a77"
    }
}

/** An ordered RFCOMM view. EOF or overflow wakes the receiver without losing queued bytes. */
private class UsbBtRfcommStream(
    private val rfcomm: RfcommLayer,
    dlc: RfcommLayer.Dlc,
    private val onClose: () -> Unit,
) : WakeableBlockingDuplexByteStream {
    private val monitor = java.lang.Object()
    private val chunks = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var headOffset = 0
    private var ended = false
    private var overflow = false
    private var wakePending = false
    private val delivered = AtomicBoolean(false)
    private val locallyClosed = AtomicBoolean(false)
    private val sendLock = Any()
    private val outgoing = dlc

    init {
        rfcomm.onData(dlc) { data ->
            if (data.isNotEmpty()) synchronized(monitor) {
                if (!ended) {
                    if (queuedBytes + data.size > MAX_QUEUED_BYTES) { overflow = true; ended = true }
                    else { chunks.addLast(data); queuedBytes += data.size }
                    monitor.notifyAll()
                }
            }
        }
    }

    fun markDelivered(): Boolean = delivered.compareAndSet(false, true)

    override fun send(data: ByteArray) {
        synchronized(sendLock) {
            synchronized(monitor) { if (ended) throw IOException("Bluetooth RFCOMM closed") }
            if (data.isNotEmpty() && !rfcomm.send(outgoing, data)) throw IOException("Bluetooth RFCOMM send failed")
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(monitor) {
        require(maxBytes > 0 && timeoutMillis >= 0)
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (queuedBytes == 0 && !ended && !wakePending) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            monitor.wait(remaining)
        }
        if (queuedBytes == 0) {
            if (!ended) { wakePending = false; return null }
            if (overflow) throw IOException("Bluetooth RFCOMM receive buffer exhausted")
            return ByteArray(0)
        }
        wakePending = false
        val head = chunks.peekFirst()
        val size = minOf(maxBytes, head.size - headOffset)
        val result = head.copyOfRange(headOffset, headOffset + size)
        queuedBytes -= size
        headOffset += size
        if (headOffset == head.size) { chunks.removeFirst(); headOffset = 0 }
        result
    }

    override fun wakeReceiver() {
        synchronized(monitor) { wakePending = true; monitor.notifyAll() }
    }

    fun remoteClosed() {
        synchronized(monitor) { ended = true; monitor.notifyAll() }
    }

    override fun close() {
        if (!locallyClosed.compareAndSet(false, true)) return
        remoteClosed()
        onClose()
    }

    companion object { private const val MAX_QUEUED_BYTES = 256 * 1024 }
}

