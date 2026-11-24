package com.shilapi.xcertplay.usbbt

import android.util.Log
import com.shilapi.xcertplay.usbbt.HciProtocol.L2CAP_CID_SIGNALING
import com.shilapi.xcertplay.usbbt.HciProtocol.L2CAP_CMD_CONFIG_REQ
import com.shilapi.xcertplay.usbbt.HciProtocol.L2CAP_CMD_CONFIG_RSP
import com.shilapi.xcertplay.usbbt.HciProtocol.L2CAP_CMD_CONNECTION_REQ
import com.shilapi.xcertplay.usbbt.HciProtocol.L2CAP_CMD_CONNECTION_RSP
import com.shilapi.xcertplay.usbbt.HciProtocol.L2CAP_CMD_DISCONNECTION_REQ
import com.shilapi.xcertplay.usbbt.HciProtocol.L2CAP_CMD_DISCONNECTION_RSP
import com.shilapi.xcertplay.usbbt.HciProtocol.L2CAP_CONN_PENDING
import com.shilapi.xcertplay.usbbt.HciProtocol.L2CAP_CONN_SUCCESS
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeL2cap
import com.shilapi.xcertplay.usbbt.HciProtocol.parseL2cap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * L2CAP layer over an HCI ACL connection (Phase 3).
 *
 * Bluetooth Core Spec Vol 3, Part A. Handles:
 * - signaling (CID 0x0001): Connection Request/Response, Configuration
 *   Request/Response, Disconnection
 * - dynamic CID allocation (0x0040..0xFFFF)
 * - payload segmentation for the controller's ACL MTU and reassembly of
 *   incoming fragments (we only send/receive unfragmented L2CAP PDUs here;
 *   multi-fragment reassembly is supported on receive)
 *
 * All public methods block the calling thread; use from a background thread.
 * Create one instance per ACL connection handle; call [close] when done.
 *
 * Clean-room: derived from the public Bluetooth Core Specification.
 */
class L2capLayer internal constructor(
    private val aclHandle: Int,
    private val transmitAcl: (Int, ByteArray) -> Boolean,
) {
    constructor(aclHandle: Int, hci: UsbBtHci) : this(aclHandle, { handle, packet -> hci.sendAcl(handle, packet) })

    /** An established L2CAP channel. */
    data class Channel(
        /** Our local CID (source). */
        val localCid: Int,
        /** Peer's CID (destination). */
        val remoteCid: Int,
        val psm: Int,
    ) {
        @Volatile var peerMtu: Int = DEFAULT_MTU
            internal set
    }

    sealed class L2capResult<out T> {
        data class Ok<T>(val value: T) : L2capResult<T>()
        data class Failed(val reason: String) : L2capResult<Nothing>()
    }

    companion object {
        private const val TAG = "L2capLayer"
        private const val DEFAULT_MTU = 672
        private const val MIN_MTU = 48
        private const val COMMAND_REJECT = 0x01
        private const val INFORMATION_REQUEST = 0x0A
        private const val INFORMATION_RESPONSE = 0x0B
        private const val SIGNAL_ID_START = 1
        private const val MIN_DYNAMIC_CID = 0x0040
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val CONFIG_TIMEOUT_MS = 5_000
    }

    private val nextSignalId = AtomicInteger(SIGNAL_ID_START)
    private val nextCid = AtomicInteger(MIN_DYNAMIC_CID)

    /** local CID -> Channel, for established channels. */
    private val channels = ConcurrentHashMap<Int, Channel>()

    /** signal id -> pending connection (for matching responses). */
    private val pendingConnects = ConcurrentHashMap<Int, PendingConnect>()
    private class Configuration {
        @Volatile var localAccepted = false
        @Volatile var peerAccepted = false
        @Volatile var rejected = false
        var continuationRequests = 0
        val done = java.util.concurrent.CountDownLatch(1)
        fun signal() { if (rejected || (localAccepted && peerAccepted)) done.countDown() }
    }
    private val configurations = ConcurrentHashMap<Int, Configuration>()
    private val configRequests = ConcurrentHashMap<Int, Int>()

    /** Incoming data callbacks per local CID. */
    private val dataHandlers = ConcurrentHashMap<Int, (ByteArray) -> Unit>()

    /** PSM -> Inbound connection factory: (Channel) -> ((ByteArray) -> Unit) */
    private val services = ConcurrentHashMap<Int, (Channel) -> ((ByteArray) -> Unit)>()

    fun registerService(psm: Int, serviceFactory: (Channel) -> ((ByteArray) -> Unit)) {
        services[psm] = serviceFactory
    }

    /**
     * Binds an SDP Server to PSM 1 (SDP), responding to incoming Service Search / Attribute queries.
     */
    fun registerSdpServer(records: List<SdpRecord>) {
        registerService(HciProtocol.L2CAP_PSM_SDP) { channel ->
            val server = SdpServer(records)
            val handler: (ByteArray) -> Unit = { req ->
                send(channel, server.handle(req, channel.peerMtu))
            }
            handler
        }
    }

    private data class PendingConnect(
        val psm: Int,
        val localCid: Int,
        @Volatile var remoteCid: Int = -1,
        @Volatile var result: Int = -1,
        val done: java.util.concurrent.CountDownLatch = java.util.concurrent.CountDownLatch(1),
    )

    private val closing = AtomicBoolean(false)
    private val closed: Boolean get() = closing.get()

    private fun nextSignalId(): Int {
        // Signal identifier 0 is reserved; wrap within 1..255.
        return nextWrapped(nextSignalId, SIGNAL_ID_START, 255)
    }

    private fun allocCid(): Int {
        // Dynamic CIDs 0x0040..0xFFFF; wrap around on exhaustion (unlikely).
        return nextWrapped(nextCid, MIN_DYNAMIC_CID, 0xFFFF)
    }

    private fun nextWrapped(counter: AtomicInteger, first: Int, last: Int): Int {
        while (true) {
            val value = counter.get()
            if (counter.compareAndSet(value, if (value >= last) first else value + 1)) return value
        }
    }

    private fun u16le(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())

    private fun readU16(data: ByteArray, at: Int): Int? {
        if (at + 1 >= data.size) return null
        return (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
    }

    /**
     * Connect an L2CAP channel to [psm] on the peer. Performs the signaling
     * handshake (Connection Request -> Response, then Configuration).
     */
    fun connect(psm: Int): L2capResult<Channel> {
        if (closed) return L2capResult.Failed("L2CAP layer closed")
        val localCid = allocCid()
        val signalId = nextSignalId()
        val pending = PendingConnect(psm = psm, localCid = localCid)
        pendingConnects[signalId] = pending
        try {
            if (closed) return L2capResult.Failed("L2CAP layer closed")
            // Connection Request: code(1)=0x02 id(1) len(2)=4 | PSM(2) | SCID(2)
            val req = byteArrayOf(
                L2CAP_CMD_CONNECTION_REQ.toByte(),
                signalId.toByte(),
                0x04, 0x00,
            ) + u16le(psm) + u16le(localCid)
            if (!transmitAcl(aclHandle, encodeL2cap(L2CAP_CID_SIGNALING, req))) {
                return L2capResult.Failed("failed to send L2CAP Connection Request (psm=0x${psm.toString(16)})")
            }
            val got = pending.done.await(CONNECT_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!got) return L2capResult.Failed("L2CAP connect timeout (psm=0x${psm.toString(16)})")
            if (closed) return L2capResult.Failed("L2CAP layer closed")
            if (pending.result != L2CAP_CONN_SUCCESS || pending.remoteCid < MIN_DYNAMIC_CID) {
                return L2capResult.Failed(
                    "L2CAP connect rejected: result=0x${pending.result.toString(16)} (psm=0x${psm.toString(16)})",
                )
            }
            val channel = channels[localCid] ?: return L2capResult.Failed("L2CAP channel closed before configuration")
            // Minimal configuration exchange: send our Config Request, accept theirs.
            // Many stacks work with default parameters; we send an empty config.
            if (!exchangeConfig(channel)) {
                disconnect(channel)
                return L2capResult.Failed("L2CAP configuration failed (psm=0x${psm.toString(16)})")
            }
            Log.i(TAG, "L2CAP channel up: psm=0x${psm.toString(16)} localCid=0x${localCid.toString(16)} remoteCid=0x${pending.remoteCid.toString(16)}")
            return L2capResult.Ok(channel)
        } finally {
            pendingConnects.remove(signalId)
        }
    }

    private fun exchangeConfig(channel: Channel): Boolean {
        val state = configurations.getOrPut(channel.localCid) { Configuration() }
        if (!requestConfig(channel)) return false
        return state.done.await(CONFIG_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) &&
            !closed && !state.rejected && state.localAccepted && state.peerAccepted
    }

    private fun requestConfig(channel: Channel): Boolean {
        if (closed) return false
        val signalId = nextSignalId()
        configRequests[signalId] = channel.localCid
        if (signal(L2CAP_CMD_CONFIG_REQ, signalId, u16le(channel.remoteCid) + u16le(0))) return true
        configRequests.remove(signalId)
        return false
    }

    private fun signal(code: Int, identifier: Int, params: ByteArray): Boolean =
        transmitAcl(aclHandle, encodeL2cap(L2CAP_CID_SIGNALING,
            byteArrayOf(code.toByte(), identifier.toByte()) + u16le(params.size) + params))

    /** Send a single L2CAP PDU; UsbBtHci splits it at the negotiated ACL packet size. */
    fun send(channel: Channel, payload: ByteArray): Boolean {
        if (closed || payload.size > channel.peerMtu || channels[channel.localCid] !== channel) return false
        return transmitAcl(aclHandle, encodeL2cap(channel.remoteCid, payload))
    }

    private var incomingPdu: ByteArray? = null
    private var incomingLength = 0

    /** Reassemble HCI ACL continuation fragments before decoding the L2CAP header. */
    fun onAclData(acl: HciProtocol.AclPacket) {
        if (closed) return
        val data = acl.data
        if ((acl.flags and 3) == 1) {
            val previous = incomingPdu ?: return
            if (data.size > previous.size - incomingLength) {
                incomingPdu = null
                return
            }
            data.copyInto(previous, incomingLength)
            incomingLength += data.size
            if (incomingLength == previous.size) {
                incomingPdu = null
                onAclData(previous)
            }
            return
        }
        incomingPdu = null
        val payloadLength = readU16(data, 0) ?: return
        val expected = payloadLength + 4
        if (data.size == expected) {
            onAclData(data)
        } else if (data.size in 4 until expected) {
            incomingPdu = ByteArray(expected).also { data.copyInto(it) }
            incomingLength = data.size
        }
    }

    /** Register a handler for incoming data on [channel]. */
    fun onData(channel: Channel, handler: (ByteArray) -> Unit) {
        dataHandlers[channel.localCid] = handler
    }

    /**
     * Handle one reassembled L2CAP PDU from the ACL receive loop.
     * Call this for every [HciProtocol.AclPacket] whose handle matches.
     */
    fun onAclData(aclData: ByteArray) {
        val packet = parseL2cap(aclData) ?: run {
            Log.w(TAG, "malformed L2CAP packet (${aclData.size} bytes)")
            return
        }
        if (packet.cid == L2CAP_CID_SIGNALING) {
            onSignaling(packet.payload)
            return
        }
        val handler = dataHandlers[packet.cid]
        if (handler != null) {
            runCatching { handler(packet.payload) }
                .onFailure { Log.w(TAG, "L2CAP data handler failed", it) }
        } else {
            Log.w(TAG, "L2CAP data for unknown CID 0x${packet.cid.toString(16)}")
        }
    }

    private fun onSignaling(payload: ByteArray) {
        var at = 0
        while (at + 4 <= payload.size) {
            val code = payload[at].toInt() and 0xFF
            val id = payload[at + 1].toInt() and 0xFF
            val len = readU16(payload, at + 2) ?: return
            if (at + 4 + len > payload.size) {
                Log.w(TAG, "truncated L2CAP signaling command")
                return
            }
            val data = payload.copyOfRange(at + 4, at + 4 + len)
            when (code) {
                L2CAP_CMD_CONNECTION_REQ -> onConnectionRequest(id, data)
                L2CAP_CMD_CONNECTION_RSP -> onConnectionResponse(id, data)
                L2CAP_CMD_CONFIG_REQ -> onConfigRequest(id, data)
                L2CAP_CMD_CONFIG_RSP -> onConfigResponse(id, data)
                L2CAP_CMD_DISCONNECTION_REQ -> onDisconnectionRequest(id, data)
                L2CAP_CMD_DISCONNECTION_RSP, INFORMATION_RESPONSE, 0x09 -> Unit
                0x08 -> signal(0x09, id, data)
                INFORMATION_REQUEST -> onInformationRequest(id, data)
                COMMAND_REJECT -> onCommandReject(id)
                else -> signal(COMMAND_REJECT, id, u16le(0))
            }
            at += 4 + len
        }
    }

    private fun onConnectionRequest(id: Int, data: ByteArray) {
        if (data.size < 4) return
        val psm = readU16(data, 0) ?: return
        val scid = readU16(data, 2) ?: return
        val factory = services[psm]
        if (factory == null) {
            // Reject: result = 0x0002 (PSM not supported)
            val rsp = byteArrayOf(
                L2CAP_CMD_CONNECTION_RSP.toByte(),
                id.toByte(),
                0x08, 0x00,
            ) + u16le(0) + u16le(scid) + u16le(0x0002) + u16le(0)
            transmitAcl(aclHandle, encodeL2cap(L2CAP_CID_SIGNALING, rsp))
            Log.w(TAG, "L2CAP connection request for unsupported PSM 0x${psm.toString(16)} rejected")
            return
        }

        val localCid = allocCid()
        val channel = Channel(localCid = localCid, remoteCid = scid, psm = psm)
        channels[localCid] = channel
        configurations[localCid] = Configuration()
        val handler = factory(channel)
        dataHandlers[localCid] = handler

        // Respond with Success: result = 0x0000, status = 0x0000
        val rsp = byteArrayOf(
            L2CAP_CMD_CONNECTION_RSP.toByte(),
            id.toByte(),
            0x08, 0x00,
        ) + u16le(localCid) + u16le(scid) + u16le(L2CAP_CONN_SUCCESS) + u16le(0)
        transmitAcl(aclHandle, encodeL2cap(L2CAP_CID_SIGNALING, rsp))

        if (!requestConfig(channel)) {
            removeChannel(localCid)
            return
        }
        Log.i(TAG, "L2CAP inbound channel up: psm=0x${psm.toString(16)} localCid=0x${localCid.toString(16)} remoteCid=0x${scid.toString(16)}")
    }

    private fun onConnectionResponse(id: Int, data: ByteArray) {
        // Response: DCID(2) | SCID(2) | result(2) | status(2)
        if (data.size < 8) return
        val dcid = readU16(data, 0) ?: return
        val scid = readU16(data, 2) ?: return
        val result = readU16(data, 4) ?: return
        val pending = pendingConnects[id] ?: return
        if (pending.localCid != scid) return
        pending.remoteCid = dcid
        pending.result = result
        if (result == L2CAP_CONN_SUCCESS) {
            // Publish before waking connect(): the peer can send its Config
            // Request immediately after this Connection Response.
            channels[scid] = Channel(scid, dcid, pending.psm)
            configurations.putIfAbsent(scid, Configuration())
        }
        if (result == L2CAP_CONN_SUCCESS || result != L2CAP_CONN_PENDING) {
            pending.done.countDown()
        }
        // PENDING means "more to come"; keep waiting for the final response.
    }

    private fun onConfigRequest(id: Int, data: ByteArray) {
        val cid = readU16(data, 0) ?: return
        val flags = readU16(data, 2) ?: return
        val channel = channels[cid] ?: run {
            signal(COMMAND_REJECT, id, u16le(2) + u16le(cid) + u16le(0))
            return
        }
        val state = configurations[cid] ?: return
        var offset = 4
        while (offset < data.size) {
            if (offset + 2 > data.size) return
            val type = data[offset].toInt() and 0xff
            val size = data[offset + 1].toInt() and 0xff
            val end = offset + 2 + size
            if (end > data.size) return
            if (type and 0x7f == 1 && size == 2) {
                val requested = readU16(data, offset + 2) ?: return
                if (requested < MIN_MTU) {
                    val minimum = byteArrayOf(1, 2) + u16le(MIN_MTU)
                    signal(L2CAP_CMD_CONFIG_RSP, id, u16le(channel.remoteCid) + u16le(flags and 1) + u16le(1) + minimum)
                    return
                }
                channel.peerMtu = requested
            } else if (type and 0x7f == 2 && size == 2) {
                // CarPlay's reliable RFCOMM path keeps the default infinite flush timeout.
                if (readU16(data, offset + 2) != 0xffff) {
                    val infiniteTimeout = byteArrayOf(2, 2, 0xff.toByte(), 0xff.toByte())
                    signal(L2CAP_CMD_CONFIG_RSP, id, u16le(channel.remoteCid) + u16le(flags and 1) + u16le(1) + infiniteTimeout)
                    return
                }
            } else if (type and 0x80 == 0) {
                val option = data.copyOfRange(offset, end)
                signal(L2CAP_CMD_CONFIG_RSP, id, u16le(channel.remoteCid) + u16le(flags and 1) + u16le(3) + option)
                return
            }
            offset = end
        }
        if (signal(L2CAP_CMD_CONFIG_RSP, id, u16le(channel.remoteCid) + u16le(flags and 1) + u16le(0)) && flags and 1 == 0) {
            state.peerAccepted = true
            state.signal()
        }
    }

    private fun onConfigResponse(id: Int, data: ByteArray) {
        val cid = configRequests[id] ?: return
        if (readU16(data, 0) != cid) return
        val flags = readU16(data, 2) ?: return
        val result = readU16(data, 4) ?: return
        val state = configurations[cid] ?: return
        if (result == 4) return // A final response with the same identifier follows.
        configRequests.remove(id)
        if (result == 0 && flags and 1 != 0) {
            val channel = channels[cid] ?: return
            if (++state.continuationRequests <= 4 && requestConfig(channel)) return
        } else if (result == 0) {
            state.localAccepted = true
            state.signal()
            return
        }
        state.rejected = true
        state.signal()
    }

    private fun onInformationRequest(id: Int, data: ByteArray) {
        val type = readU16(data, 0) ?: return
        val answer = when (type) {
            2 -> byteArrayOf(0x80.toByte(), 0, 0, 0) // Fixed Channels supported.
            3 -> byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0) // BR/EDR signaling only.
            else -> null
        }
        signal(INFORMATION_RESPONSE, id, u16le(type) + u16le(if (answer == null) 1 else 0) + (answer ?: byteArrayOf()))
    }

    private fun onCommandReject(id: Int) {
        pendingConnects[id]?.done?.countDown()
        val cid = configRequests.remove(id) ?: return
        configurations[cid]?.let { it.rejected = true; it.signal() }
    }

    private fun onDisconnectionRequest(id: Int, data: ByteArray) {
        if (data.size < 4) return
        val dcid = readU16(data, 0) ?: return
        val scid = readU16(data, 2) ?: return
        val channel = channels[dcid]
        if (channel == null || channel.remoteCid != scid) {
            signal(COMMAND_REJECT, id, u16le(2) + u16le(dcid) + u16le(scid))
            return
        }
        val rsp = byteArrayOf(
            L2CAP_CMD_DISCONNECTION_RSP.toByte(),
            id.toByte(),
            0x04, 0x00,
        ) + u16le(dcid) + u16le(scid)
        transmitAcl(aclHandle, encodeL2cap(L2CAP_CID_SIGNALING, rsp))
        removeChannel(dcid)
        Log.i(TAG, "L2CAP channel 0x${dcid.toString(16)} disconnected by peer")
    }

    /** Disconnect [channel]. */
    fun disconnect(channel: Channel) {
        val signalId = nextSignalId()
        val req = byteArrayOf(
            L2CAP_CMD_DISCONNECTION_REQ.toByte(),
            signalId.toByte(),
            0x04, 0x00,
        ) + u16le(channel.remoteCid) + u16le(channel.localCid)
        transmitAcl(aclHandle, encodeL2cap(L2CAP_CID_SIGNALING, req))
        removeChannel(channel.localCid)
    }

    private fun removeChannel(cid: Int) {
        channels.remove(cid)
        configurations.remove(cid)?.done?.countDown()
        dataHandlers.remove(cid)
        for ((id, configured) in configRequests.entries) {
            if (configured == cid) configRequests.remove(id, configured)
        }
    }

    fun close(sendDisconnect: Boolean = true) {
        if (!closing.compareAndSet(false, true)) return
        val openChannels = channels.values.toList()
        channels.clear()
        dataHandlers.clear()
        pendingConnects.values.forEach { it.done.countDown() }
        pendingConnects.clear()
        configurations.values.forEach { it.done.countDown() }
        configurations.clear()
        configRequests.clear()
        if (sendDisconnect) {
            openChannels.forEach { runCatching { disconnect(it) } }
        }
    }
}
