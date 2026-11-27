package com.shilapi.xcertplay.usbbt

import android.util.Log
import java.io.ByteArrayOutputStream
import com.shilapi.xcertplay.usbbt.HciProtocol.IAP2_UUID_BYTES
import com.shilapi.xcertplay.usbbt.HciProtocol.L2CAP_PSM_SDP
import com.shilapi.xcertplay.usbbt.HciProtocol.SDP_PDU_SERVICE_SEARCH_ATTR_REQ
import com.shilapi.xcertplay.usbbt.HciProtocol.SDP_PDU_SERVICE_SEARCH_ATTR_RSP
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SDP client over L2CAP (Phase 3).
 *
 * Bluetooth Core Spec Vol 3, Part B. Queries the peer for the iAP2 service
 * and extracts its RFCOMM server channel from the Protocol Descriptor List
 * (attribute 0x0004).
 *
 * All public methods block the calling thread; use from a background thread.
 *
 * Clean-room: derived from the public Bluetooth Core Specification.
 */
class SdpClient(
    private val l2cap: L2capLayer,
    private val aclHandle: Int,
    private val targetUuidBytes: ByteArray = IAP2_UUID_BYTES,
) {

    sealed class SdpResult<out T> {
        data class Ok<T>(val value: T) : SdpResult<T>()
        data class Failed(val reason: String) : SdpResult<Nothing>()
        object ServiceNotFound : SdpResult<Nothing>()
    }

    data class ParsedResponse(val attributeLists: ByteArray, val continuation: ByteArray = byteArrayOf())

    companion object {
        private const val TAG = "SdpClient"
        private const val QUERY_TIMEOUT_MS = 8_000L
        private const val MAX_CONTINUATION_SIZE = 16
        private const val MAX_RESPONSE_SIZE = 65_535
        private const val MAX_RESPONSE_PARTS = 32

        /** SDP attribute: Protocol Descriptor List. */
        private const val ATTR_PROTOCOL_DESCRIPTOR_LIST = 0x0004

        /** Well-known UUIDs (16-bit, big-endian on the wire). */
        private const val UUID_L2CAP = 0x0100
        private const val UUID_RFCOMM = 0x0003
        private val UUID_RFCOMM_128 = java.util.UUID.fromString("00000003-0000-1000-8000-00805F9B34FB")

        fun parseResponse(pdu: ByteArray, wantTid: Int): ParsedResponse? {
            if (pdu.size < 5) return null
            if (pdu[0].toInt() and 0xFF != SDP_PDU_SERVICE_SEARCH_ATTR_RSP) return null
            val tid = ((pdu[1].toInt() and 0xFF) shl 8) or (pdu[2].toInt() and 0xFF)
            if (tid != wantTid) return null
            val paramLen = ((pdu[3].toInt() and 0xFF) shl 8) or (pdu[4].toInt() and 0xFF)
            if (5 + paramLen != pdu.size || paramLen < 3) return null
            // Params: AttributeListsByteCount(2) | lists | ContinuationState
            val listLen = ((pdu[5].toInt() and 0xFF) shl 8) or (pdu[6].toInt() and 0xFF)
            val continuationStart = 7 + listLen
            if (continuationStart >= pdu.size) return null
            val continuationLength = pdu[continuationStart].toInt() and 0xff
            if (continuationLength > MAX_CONTINUATION_SIZE || continuationStart + 1 + continuationLength != pdu.size) return null
            return ParsedResponse(pdu.copyOfRange(7, continuationStart), pdu.copyOfRange(continuationStart + 1, pdu.size))
        }

        /**
         * Walk the attribute list DES, find attribute 0x0004, then walk its
         * Protocol Descriptor List to find the RFCOMM entry's channel byte.
         */
        fun extractRfcommChannel(parsed: ParsedResponse): Int? {
            // 1. Fast byte-level signature scan for RFCOMM channel in ProtocolDescriptorList:
            // UUID16 (0x09 0x00 0x03) followed by UINT8 channel (0x08 <channel>)
            extractRfcommScanner(parsed.attributeLists)?.let { return it }

            // 2. Structured parsing via SdpCodec
            val element = runCatching { SdpCodec.decode(parsed.attributeLists).first }.getOrNull()
            if (element is DataElement.Sequence) {
                extractFromElement(element)?.let { return it }
            }

            // 3. Fallback to manual DES walker
            val lists = parsed.attributeLists
            val outer = readDes(lists, 0) ?: return null
            var at = outer.contentStart
            val outerEnd = outer.contentStart + outer.contentLength
            while (at < outerEnd) {
                val attrList = readDes(lists, at) ?: return null
                var inner = attrList.contentStart
                val innerEnd = attrList.contentStart + attrList.contentLength
                while (inner < innerEnd) {
                    if (lists[inner].toInt() and 0xFF != 0x09 || inner + 3 > innerEnd) break
                    val attrId = ((lists[inner + 1].toInt() and 0xFF) shl 8) or
                        (lists[inner + 2].toInt() and 0xFF)
                    inner += 3
                    val valueDes = readDes(lists, inner) ?: return null
                    if (attrId == ATTR_PROTOCOL_DESCRIPTOR_LIST) {
                        findRfcommInPdl(lists, valueDes)?.let { return it }
                    }
                    inner = valueDes.contentStart + valueDes.contentLength
                }
                at = attrList.contentStart + attrList.contentLength
            }
            return null
        }

        /** A complete empty attribute sequence reports no matching service records. */
        internal fun hasNoServiceRecords(attributes: ByteArray): Boolean {
            val (value, consumed) = runCatching { SdpCodec.decode(attributes) }.getOrNull() ?: return false
            return value is DataElement.Sequence && value.items.isEmpty() && consumed == attributes.size
        }

        private fun extractRfcommScanner(data: ByteArray): Int? {
            for (i in 0 until data.size - 4) {
                if (data[i] == 0x09.toByte() &&
                    data[i + 1] == 0x00.toByte() &&
                    data[i + 2] == 0x03.toByte() &&
                    data[i + 3] == 0x08.toByte()
                ) {
                    val channel = data[i + 4].toInt() and 0xFF
                    if (channel in 1..30) return channel
                }
            }
            return null
        }

        private fun extractFromElement(element: DataElement): Int? {
            if (element !is DataElement.Sequence) return null
            val items = element.items
            for (i in 0 until items.size - 1) {
                val idElem = items[i]
                val valElem = items[i + 1]
                if (idElem is DataElement.UInt && idElem.value == ATTR_PROTOCOL_DESCRIPTOR_LIST.toLong()) {
                    findChannelInPdlElement(valElem)?.let { return it }
                }
            }
            for (child in items) {
                if (child is DataElement.Sequence) {
                    extractFromElement(child)?.let { return it }
                }
            }
            return null
        }

        private fun findChannelInPdlElement(pdl: DataElement): Int? {
            if (pdl !is DataElement.Sequence) return null
            for (proto in pdl.items) {
                if (proto is DataElement.Sequence) {
                    val hasRfcomm = proto.items.any {
                        it is DataElement.Uuid && (
                            (it.bytes == 2 && ((it.uuid.mostSignificantBits ushr 32) and 0xFFFF) == UUID_RFCOMM.toLong()) ||
                            it.uuid == UUID_RFCOMM_128
                        )
                    }
                    if (hasRfcomm) {
                        val channelElem = proto.items.filterIsInstance<DataElement.UInt>().firstOrNull()
                        if (channelElem != null) {
                            return channelElem.value.toInt()
                        }
                    }
                }
            }
            return null
        }

        /** Inside the Protocol Descriptor List, find [UUID_RFCOMM, channel]. */
        private fun findRfcommInPdl(data: ByteArray, pdl: Des): Int? {
            var at = pdl.contentStart
            val end = pdl.contentStart + pdl.contentLength
            while (at < end) {
                val entry = readDes(data, at) ?: return null
                var inner = entry.contentStart
                val entryEnd = entry.contentStart + entry.contentLength
                if (inner + 3 <= entryEnd && data[inner].toInt() and 0xFF == 0x09) {
                    val uuid = ((data[inner + 1].toInt() and 0xFF) shl 8) or
                        (data[inner + 2].toInt() and 0xFF)
                    if (uuid == UUID_RFCOMM) {
                        inner += 3
                        if (inner + 2 <= entryEnd && data[inner].toInt() and 0xFF == 0x08) {
                            return data[inner + 1].toInt() and 0xFF
                        }
                        return null
                    }
                }
                at = entry.contentStart + entry.contentLength
            }
            return null
        }

        /** A parsed Data Element Sequence header. */
        private data class Des(val contentStart: Int, val contentLength: Int)

        /**
         * Parse a DES header (Type 6, tag 0x35/0x36/0x37) at [at]. Returns null when malformed.
         */
        private fun readDes(data: ByteArray, at: Int): Des? {
            if (at >= data.size) return null
            val tag = data[at].toInt() and 0xFF
            val type = tag ushr 3
            val sizeIndex = tag and 0x07
            if (type != 6) return null // Type 6 = Data Element Sequence
            val (length, headerSize) = when (sizeIndex) {
                5 -> {
                    if (at + 2 > data.size) return null
                    (data[at + 1].toInt() and 0xFF) to 2
                }
                6 -> {
                    if (at + 3 > data.size) return null
                    (((data[at + 1].toInt() and 0xFF) shl 8) or (data[at + 2].toInt() and 0xFF)) to 3
                }
                7 -> {
                    if (at + 5 > data.size) return null
                    val len = (((data[at + 1].toLong() and 0xFF) shl 24) or
                        ((data[at + 2].toLong() and 0xFF) shl 16) or
                        ((data[at + 3].toLong() and 0xFF) shl 8) or
                        (data[at + 4].toLong() and 0xFF)).toInt()
                    len to 5
                }
                else -> return null
            }
            val start = at + headerSize
            if (start + length > data.size) return null
            return Des(contentStart = start, contentLength = length)
        }
    }

    private val nextTransactionId = AtomicInteger(1)
    private val responses = LinkedBlockingQueue<ByteArray>(8)
    @Volatile private var channel: L2capLayer.Channel? = null
    @Volatile private var closed = false

    /**
     * Query the peer's iAP2 service and return its RFCOMM server channel,
     * or a failure. Opens (and keeps) the SDP L2CAP channel for reuse.
     */
    fun findIap2RfcommChannel(): SdpResult<Int> {
        val ch = ensureChannel() ?: return SdpResult.Failed("SDP L2CAP channel failed")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(QUERY_TIMEOUT_MS)
        val attributes = ByteArrayOutputStream()
        val tokens = HashSet<List<Byte>>()
        var continuation = byteArrayOf()
        for (part in 1..MAX_RESPONSE_PARTS) {
            if (closed || System.nanoTime() >= deadline) return SdpResult.Failed("SDP query timeout")
            val tid = nextTransactionId.getAndIncrement() and 0xffff
            if (!l2cap.send(ch, buildSearchAttrRequest(tid, continuation))) {
                return SdpResult.Failed("failed to send SDP request")
            }
            val response = try {
                waitResponse(tid, deadline)
            } catch (failure: IllegalStateException) {
                return SdpResult.Failed(failure.message ?: "SDP error")
            } ?: return SdpResult.Failed("SDP query timeout")
            if (response.attributeLists.size > MAX_RESPONSE_SIZE - attributes.size()) {
                return SdpResult.Failed("SDP reply exceeds the attribute size limit")
            }
            attributes.write(response.attributeLists)
            continuation = response.continuation
            if (continuation.isEmpty()) {
                val complete = attributes.toByteArray()
                if (hasNoServiceRecords(complete)) return SdpResult.ServiceNotFound
                val channel = extractRfcommChannel(ParsedResponse(complete))
                    ?.takeIf { it in 1..30 }
                    ?: return SdpResult.Failed("iAP2 service reply lacks a valid RFCOMM channel")
                Log.i(TAG, "iAP2 RFCOMM channel=$channel")
                return SdpResult.Ok(channel)
            }
            if (!tokens.add(continuation.toList())) return SdpResult.Failed("repeated SDP continuation state")
        }
        return SdpResult.Failed("SDP reply has too many continuation parts")
    }

    private fun waitResponse(tid: Int, deadline: Long): ParsedResponse? {
        while (!closed) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            val pdu = responses.poll(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(400)), TimeUnit.NANOSECONDS)
                ?: continue
            if (pdu.size == 7 && pdu[0] == 0x01.toByte() &&
                ((pdu[1].toInt() and 0xff) shl 8 or (pdu[2].toInt() and 0xff)) == tid) {
                val error = ((pdu[5].toInt() and 0xff) shl 8) or (pdu[6].toInt() and 0xff)
                throw IllegalStateException("SDP error=0x${error.toString(16)}")
            }
            val parsed = parseResponse(pdu, tid)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun ensureChannel(): L2capLayer.Channel? {
        channel?.let { return it }
        if (closed) return null
        return when (val r = l2cap.connect(L2CAP_PSM_SDP)) {
            is L2capLayer.L2capResult.Ok -> {
                l2cap.onData(r.value) { payload -> onSdpPdu(payload) }
                channel = r.value
                r.value
            }
            is L2capLayer.L2capResult.Failed -> {
                Log.w(TAG, "SDP L2CAP connect failed: ${r.reason}")
                null
            }
        }
    }

    private fun onSdpPdu(payload: ByteArray) {
        if (payload.isNotEmpty()) {
            responses.offer(payload)
        }
    }

    // ------------------------------------------------------------------
    // Request building.
    // ------------------------------------------------------------------

    /**
     * Service Search Attribute Request for the iAP2 128-bit UUID, asking only
     * for the Protocol Descriptor List attribute.
     */
    internal fun buildSearchAttrRequest(transactionId: Int, continuation: ByteArray = byteArrayOf()): ByteArray {
        require(continuation.size <= MAX_CONTINUATION_SIZE)
        // ServiceSearchPattern: DES (0x35) len | UUID128 (0x1C) + 16 bytes
        val searchPattern = byteArrayOf(0x35, 0x11, 0x1C) + targetUuidBytes
        // AttributeIDList: DES (0x35) len=3 | UINT16 (0x09) 0x0004
        val attrList = byteArrayOf(0x35, 0x03, 0x09, 0x00, 0x04)
        val params = searchPattern +
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + // max attribute bytes = 65535
            attrList +
            byteArrayOf(continuation.size.toByte()) + continuation
        return byteArrayOf(SDP_PDU_SERVICE_SEARCH_ATTR_REQ.toByte()) +
            byteArrayOf(((transactionId ushr 8) and 0xFF).toByte(), (transactionId and 0xFF).toByte()) +
            byteArrayOf(((params.size ushr 8) and 0xFF).toByte(), (params.size and 0xFF).toByte()) +
            params
    }

    fun close() {
        closed = true
        channel?.let { runCatching { l2cap.disconnect(it) } }
        channel = null
        responses.clear()
    }
}
