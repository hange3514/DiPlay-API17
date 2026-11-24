package com.shilapi.xcertplay.usbbt

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * SDP Server handling incoming PDU requests from the connected iPhone:
 * - ServiceSearchRequest (0x02) -> ServiceSearchResponse (0x03)
 * - ServiceAttributeRequest (0x04) -> ServiceAttributeResponse (0x05)
 * - ServiceSearchAttributeRequest (0x06) -> ServiceSearchAttributeResponse (0x07)
 */
class SdpServer(
    private val records: List<SdpRecord>,
) {
    private var pending: Pending? = null

    private class ContinuationError : Exception()

    private class Pending(
        val requestKey: String,
        val full: ByteArray,
    )

    fun handle(request: ByteArray, peerMtu: Int): ByteArray {
        if (request.size < 5) return error(0, SdpCodec.ERR_INVALID_PDU_SIZE)
        val pduId = request[0].toInt() and 0xFF
        val tid = ((request[1].toInt() and 0xFF) shl 8) or (request[2].toInt() and 0xFF)
        val paramLen = ((request[3].toInt() and 0xFF) shl 8) or (request[4].toInt() and 0xFF)
        if (request.size < 5 + paramLen) return error(tid, SdpCodec.ERR_INVALID_PDU_SIZE)
        val params = request.copyOfRange(5, 5 + paramLen)

        return try {
            when (pduId) {
                SdpCodec.SERVICE_SEARCH_REQUEST -> serviceSearch(tid, params)
                SdpCodec.SERVICE_ATTRIBUTE_REQUEST -> serviceAttribute(tid, params, peerMtu)
                SdpCodec.SERVICE_SEARCH_ATTRIBUTE_REQUEST -> serviceSearchAttribute(tid, params, peerMtu)
                else -> error(tid, SdpCodec.ERR_INVALID_SYNTAX)
            }
        } catch (_: SdpCodec.Malformed) {
            error(tid, SdpCodec.ERR_INVALID_SYNTAX)
        } catch (_: ContinuationError) {
            error(tid, SdpCodec.ERR_INVALID_CONTINUATION)
        }
    }

    private fun matching(uuids: List<UUID>): List<SdpRecord> {
        return records.filter { record ->
            uuids.all { record.contains(it) }
        }
    }

    private fun serviceSearch(tid: Int, params: ByteArray): ByteArray {
        val (pattern, offset) = searchPattern(params, 0)
        if (params.size < offset + 3) throw SdpCodec.Malformed("short")
        val maxRecords = u16(params, offset)
        val matched = matching(pattern).take(maxRecords)
        val out = ByteArrayOutputStream()
        writeU16(out, matched.size) // TotalServiceRecordCount
        writeU16(out, matched.size) // CurrentServiceRecordCount
        for (rec in matched) {
            for (i in 3 downTo 0) {
                out.write(((rec.handle ushr (i * 8)) and 0xFF).toInt())
            }
        }
        out.write(0) // No continuation state
        return pdu(SdpCodec.SERVICE_SEARCH_RESPONSE, tid, out.toByteArray())
    }

    private fun serviceAttribute(tid: Int, params: ByteArray, peerMtu: Int): ByteArray {
        if (params.size < 6) throw SdpCodec.Malformed("short")
        val handle = ((u16(params, 0).toLong()) shl 16) or (u16(params, 2).toLong())
        val maxBytes = u16(params, 4)
        val (ranges, contOffset) = attributeRanges(params, 6)
        val record = records.firstOrNull { it.handle == handle }
            ?: return error(tid, SdpCodec.ERR_INVALID_RECORD_HANDLE)
        val attrList = record.attributeList(ranges).encode()
        return chunked(
            pduId = SdpCodec.SERVICE_ATTRIBUTE_RESPONSE,
            tid = tid,
            params = params,
            contOffset = contOffset,
            full = attrList,
            maxBytes = maxBytes,
            peerMtu = peerMtu,
        )
    }

    private fun serviceSearchAttribute(tid: Int, params: ByteArray, peerMtu: Int): ByteArray {
        val (pattern, offset) = searchPattern(params, 0)
        if (params.size < offset + 2) throw SdpCodec.Malformed("short")
        val maxBytes = u16(params, offset)
        val (ranges, contOffset) = attributeRanges(params, offset + 2)
        val matched = matching(pattern)
        val seqItems = matched.map { it.attributeList(ranges) }
        val fullBytes = DataElement.Sequence(seqItems).encode()
        return chunked(
            pduId = SdpCodec.SERVICE_SEARCH_ATTRIBUTE_RESPONSE,
            tid = tid,
            params = params,
            contOffset = contOffset,
            full = fullBytes,
            maxBytes = maxBytes,
            peerMtu = peerMtu,
        )
    }

    private fun chunked(
        pduId: Int,
        tid: Int,
        params: ByteArray,
        contOffset: Int,
        full: ByteArray,
        maxBytes: Int,
        peerMtu: Int,
    ): ByteArray {
        if (params.size <= contOffset) throw SdpCodec.Malformed("no continuation state")
        val contLen = params[contOffset].toInt() and 0xFF
        val contDataOffset = contOffset + 1
        if (params.size < contDataOffset + contLen) throw SdpCodec.Malformed("continuation truncated")

        val key = params.take(contOffset).joinToString("") { "%02x".format(it) } + "/$pduId"
        val startOffset = if (contLen == 0) {
            pending = Pending(key, full)
            0
        } else if (contLen == 2) {
            val p = pending ?: throw ContinuationError()
            if (p.requestKey != key) throw ContinuationError()
            val offset = u16(params, contDataOffset)
            if (offset >= p.full.size) throw ContinuationError()
            offset
        } else {
            throw ContinuationError()
        }

        val p = pending ?: throw ContinuationError()
        val sliceLimit = (minOf(maxBytes, peerMtu - 10)).coerceAtLeast(1)
        val endOffset = minOf(p.full.size, startOffset + sliceLimit)
        val chunkLen = endOffset - startOffset

        val out = ByteArrayOutputStream()
        writeU16(out, chunkLen)
        out.write(p.full, startOffset, chunkLen)
        if (endOffset < p.full.size) {
            out.write(2) // Continuation state length
            writeU16(out, endOffset)
        } else {
            out.write(0) // End of response
            pending = null
        }
        return pdu(pduId, tid, out.toByteArray())
    }

    private fun searchPattern(params: ByteArray, offset: Int): Pair<List<UUID>, Int> {
        val (elem, nextPos) = SdpCodec.decode(params, offset)
        val seq = elem as? DataElement.Sequence ?: throw SdpCodec.Malformed("pattern not sequence")
        val uuids = seq.items.map {
            (it as? DataElement.Uuid)?.uuid ?: throw SdpCodec.Malformed("pattern not uuid")
        }
        if (uuids.isEmpty() || uuids.size > 12) throw SdpCodec.Malformed("pattern size")
        return Pair(uuids, nextPos)
    }

    private fun attributeRanges(params: ByteArray, offset: Int): Pair<List<IntRange>, Int> {
        val (elem, nextPos) = SdpCodec.decode(params, offset)
        val seq = elem as? DataElement.Sequence ?: throw SdpCodec.Malformed("ranges not sequence")
        val ranges = seq.items.map { item ->
            val u = item as? DataElement.UInt ?: throw SdpCodec.Malformed("range not uint")
            when (u.bytes) {
                2 -> IntRange(u.value.toInt(), u.value.toInt())
                4 -> IntRange((u.value ushr 16).toInt(), (u.value and 0xFFFF).toInt())
                else -> throw SdpCodec.Malformed("range uint size")
            }
        }
        if (ranges.isEmpty()) throw SdpCodec.Malformed("empty ranges")
        return Pair(ranges, nextPos)
    }

    private fun error(tid: Int, code: Int): ByteArray =
        pdu(SdpCodec.ERROR_RESPONSE, tid, byteArrayOf((code ushr 8).toByte(), code.toByte()))

    private fun pdu(pduId: Int, tid: Int, params: ByteArray): ByteArray {
        val out = ByteArray(5 + params.size)
        out[0] = pduId.toByte()
        out[1] = ((tid ushr 8) and 0xFF).toByte()
        out[2] = (tid and 0xFF).toByte()
        out[3] = ((params.size ushr 8) and 0xFF).toByte()
        out[4] = (params.size and 0xFF).toByte()
        params.copyInto(out, 5)
        return out
    }

    private fun u16(data: ByteArray, at: Int): Int {
        if (at + 2 > data.size) throw SdpCodec.Malformed("short")
        return ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}
