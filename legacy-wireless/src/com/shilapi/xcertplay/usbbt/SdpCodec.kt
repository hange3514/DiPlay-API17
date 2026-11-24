package com.shilapi.xcertplay.usbbt

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * SDP encoding and decoding utilities, plus default CarPlay / iAP2 service records.
 */
object SdpCodec {
    const val PSM = 1

    const val ERROR_RESPONSE = 1
    const val SERVICE_SEARCH_REQUEST = 2
    const val SERVICE_SEARCH_RESPONSE = 3
    const val SERVICE_ATTRIBUTE_REQUEST = 4
    const val SERVICE_ATTRIBUTE_RESPONSE = 5
    const val SERVICE_SEARCH_ATTRIBUTE_REQUEST = 6
    const val SERVICE_SEARCH_ATTRIBUTE_RESPONSE = 7

    const val ERR_INVALID_RECORD_HANDLE = 2
    const val ERR_INVALID_SYNTAX = 3
    const val ERR_INVALID_PDU_SIZE = 4
    const val ERR_INVALID_CONTINUATION = 5

    const val ATTR_SERVICE_RECORD_HANDLE = 0
    const val ATTR_SERVICE_CLASS_ID_LIST = 1
    const val ATTR_PROTOCOL_DESCRIPTOR_LIST = 4
    const val ATTR_BROWSE_GROUP_LIST = 5
    const val ATTR_PROFILE_DESCRIPTOR_LIST = 9
    const val ATTR_SERVICE_NAME = 256

    const val UUID_SDP = 0x0001
    const val UUID_RFCOMM = 0x0003
    const val UUID_L2CAP = 0x0100
    const val UUID_PUBLIC_BROWSE_ROOT = 0x1002
    const val UUID_SERIAL_PORT = 0x1101
    const val UUID_HANDSFREE = 0x111E
    const val UUID_GENERIC_AUDIO = 0x1203
    const val UUID_PNP_INFORMATION = 0x1200

    const val HANDLE_IAP2 = 0x00010000L // 65536
    const val HANDLE_DEVICE_ID = 0x00010001L // 65537
    const val HANDLE_HANDSFREE = 0x00010002L // 65538

    private const val BASE_UUID_LSB = -9223371485494954757L // 0x8000_0080_5F9B_34FBL

    fun baseUuid(shortUuid: Long): UUID {
        return UUID((shortUuid shl 32) or 0x00001000L, BASE_UUID_LSB)
    }

    /**
     * Creates an SDP record for Wireless iAPv2:
     * - ServiceClassIDList: IAP2_ACCESSORY_UUID (128-bit)
     * - ProtocolDescriptorList: L2CAP + RFCOMM (channel)
     * - BrowseGroupList: PublicBrowseRoot
     * - ProfileDescriptorList: SerialPort Profile (v1.0)
     * - ServiceName: "Wireless iAPv2"
     */
    fun iap2Record(
        rfcommChannel: Int = UsbBtIdentity.IAP2_RFCOMM_CHANNEL,
        serviceName: String = "Wireless iAPv2",
        serviceUuid: UUID = UsbBtIdentity.IAP2_ACCESSORY_UUID,
    ): SdpRecord {
        return SdpRecord(
            handle = HANDLE_IAP2,
            attributes = mapOf(
                ATTR_SERVICE_CLASS_ID_LIST to DataElement.Sequence(
                    DataElement.Uuid.of128(serviceUuid)
                ),
                ATTR_PROTOCOL_DESCRIPTOR_LIST to DataElement.Sequence(
                    DataElement.Sequence(DataElement.Uuid.of16(UUID_L2CAP)),
                    DataElement.Sequence(
                        DataElement.Uuid.of16(UUID_RFCOMM),
                        DataElement.UInt(1, rfcommChannel.toLong())
                    )
                ),
                ATTR_BROWSE_GROUP_LIST to DataElement.Sequence(
                    DataElement.Uuid.of16(UUID_PUBLIC_BROWSE_ROOT)
                ),
                ATTR_PROFILE_DESCRIPTOR_LIST to DataElement.Sequence(
                    DataElement.Sequence(
                        DataElement.Uuid.of16(UUID_SERIAL_PORT),
                        DataElement.UInt(2, 0x0100L)
                    )
                ),
                ATTR_SERVICE_NAME to DataElement.Text(serviceName)
            )
        )
    }

    /**
     * Creates an SDP record for Handsfree Audio (HFP):
     * - ServiceClassIDList: Handsfree (0x111E), GenericAudio (0x1203)
     * - ProtocolDescriptorList: L2CAP + RFCOMM (channel 2)
     * - BrowseGroupList: PublicBrowseRoot
     * - ProfileDescriptorList: Handsfree Profile (v1.7)
     * - ServiceName: "Handsfree Audio"
     */
    fun handsfreeRecord(
        rfcommChannel: Int = 2,
        serviceName: String = "Handsfree Audio",
    ): SdpRecord {
        return SdpRecord(
            handle = HANDLE_HANDSFREE,
            attributes = mapOf(
                ATTR_SERVICE_CLASS_ID_LIST to DataElement.Sequence(
                    DataElement.Uuid.of16(UUID_HANDSFREE),
                    DataElement.Uuid.of16(UUID_GENERIC_AUDIO),
                ),
                ATTR_PROTOCOL_DESCRIPTOR_LIST to DataElement.Sequence(
                    DataElement.Sequence(DataElement.Uuid.of16(UUID_L2CAP)),
                    DataElement.Sequence(
                        DataElement.Uuid.of16(UUID_RFCOMM),
                        DataElement.UInt(1, rfcommChannel.toLong()),
                    )
                ),
                ATTR_BROWSE_GROUP_LIST to DataElement.Sequence(
                    DataElement.Uuid.of16(UUID_PUBLIC_BROWSE_ROOT),
                ),
                ATTR_PROFILE_DESCRIPTOR_LIST to DataElement.Sequence(
                    DataElement.Sequence(
                        DataElement.Uuid.of16(UUID_HANDSFREE),
                        DataElement.UInt(2, 0x0107L),
                    )
                ),
                ATTR_SERVICE_NAME to DataElement.Text(serviceName),
                0x0311 to DataElement.UInt(2, 0x003FL), // Supported features
            )
        )
    }

    /**
     * Builds standard records list for a given [UsbBtIdentity].
     */
    fun defaultRecords(identity: UsbBtIdentity): List<SdpRecord> {
        val list = mutableListOf<SdpRecord>()
        list.add(iap2Record(identity.iap2RfcommChannel))
        identity.deviceId?.let { dev ->
            list.add(
                SdpRecord(
                    handle = HANDLE_DEVICE_ID,
                    attributes = mapOf(
                        ATTR_SERVICE_CLASS_ID_LIST to DataElement.Sequence(DataElement.Uuid.of16(UUID_PNP_INFORMATION)),
                        ATTR_PROTOCOL_DESCRIPTOR_LIST to DataElement.Sequence(
                            DataElement.Sequence(DataElement.Uuid.of16(UUID_L2CAP), DataElement.UInt(2, 1L)),
                            DataElement.Sequence(DataElement.Uuid.of16(UUID_SDP))
                        ),
                        ATTR_BROWSE_GROUP_LIST to DataElement.Sequence(DataElement.Uuid.of16(UUID_PUBLIC_BROWSE_ROOT)),
                        ATTR_PROFILE_DESCRIPTOR_LIST to DataElement.Sequence(
                            DataElement.Sequence(DataElement.Uuid.of16(UUID_PNP_INFORMATION), DataElement.UInt(2, 0x0103L))
                        ),
                        512 to DataElement.UInt(2, 0x0103L), // Specification ID
                        513 to DataElement.UInt(2, dev.vendorId.toLong()),
                        514 to DataElement.UInt(2, dev.productId.toLong()),
                        515 to DataElement.UInt(2, dev.version.toLong()),
                        516 to DataElement.Bool(true),
                        517 to DataElement.UInt(2, dev.vendorIdSource.toLong())
                    )
                )
            )
        }
        return list
    }

    class Malformed(message: String) : Exception(message)

    fun encodeTo(e: DataElement, out: ByteArrayOutputStream) {
        when (e) {
            is DataElement.Nil -> out.write(0)
            is DataElement.UInt -> {
                val sizeIndex = when (e.bytes) {
                    1 -> 0
                    2 -> 1
                    4 -> 2
                    8 -> 3
                    else -> error("uint size ${e.bytes}")
                }
                out.write((1 shl 3) or sizeIndex)
                for (b in (e.bytes - 1) downTo 0) {
                    out.write(((e.value ushr (b * 8)) and 0xFF).toInt())
                }
            }
            is DataElement.Uuid -> {
                when (e.bytes) {
                    2 -> {
                        out.write((3 shl 3) or 1)
                        writeBe(out, (e.uuid.mostSignificantBits ushr 32) and 0xFFFF, 2)
                    }
                    4 -> {
                        out.write((3 shl 3) or 2)
                        writeBe(out, (e.uuid.mostSignificantBits ushr 32) and 0xFFFFFFFFL, 4)
                    }
                    else -> {
                        out.write((3 shl 3) or 4)
                        out.write(uuidBigEndian(e.uuid))
                    }
                }
            }
            is DataElement.Text -> {
                header(out, 4, e.value.size)
                out.write(e.value)
            }
            is DataElement.Bool -> {
                out.write(5 shl 3)
                out.write(if (e.value) 1 else 0)
            }
            is DataElement.Sequence -> container(out, 6, e.items)
            is DataElement.Alternative -> container(out, 7, e.items)
        }
    }

    private fun container(out: ByteArrayOutputStream, type: Int, items: List<DataElement>) {
        val payload = ByteArrayOutputStream()
        for (item in items) {
            encodeTo(item, payload)
        }
        val bytes = payload.toByteArray()
        header(out, type, bytes.size)
        out.write(bytes)
    }

    private fun header(out: ByteArrayOutputStream, type: Int, length: Int) {
        if (length <= 255) {
            out.write((type shl 3) or 5)
            out.write(length and 0xFF)
        } else if (length <= 65535) {
            out.write((type shl 3) or 6)
            writeBe(out, length.toLong(), 2)
        } else {
            out.write((type shl 3) or 7)
            writeBe(out, length.toLong(), 4)
        }
    }

    private fun writeBe(out: ByteArrayOutputStream, v: Long, bytes: Int) {
        for (b in (bytes - 1) downTo 0) {
            out.write(((v ushr (b * 8)) and 0xFF).toInt())
        }
    }

    fun decode(data: ByteArray, offset: Int = 0): Pair<DataElement, Int> =
        decode(data, offset, data.size, 0)

    private fun decode(data: ByteArray, offset: Int, limit: Int, depth: Int): Pair<DataElement, Int> {
        if (depth > 24) throw Malformed("SDP container nesting exceeds limit")
        if (offset < 0 || offset >= limit) throw Malformed("empty")
        val b = data[offset].toInt() and 0xFF
        val type = b ushr 3
        val sizeIndex = b and 7
        var pos = offset + 1

        val elem: DataElement = when (type) {
            0 -> DataElement.Nil
            1, 2 -> {
                val len = when (sizeIndex) {
                    0 -> 1
                    1 -> 2
                    2 -> 4
                    3 -> 8
                    else -> throw Malformed("int size index $sizeIndex")
                }
                if (len > limit - pos) throw Malformed("int truncated")
                val v = decodeBe(data, pos, len)
                pos += len
                DataElement.UInt(len, v)
            }
            3 -> {
                when (sizeIndex) {
                    1 -> {
                        if (2 > limit - pos) throw Malformed("uuid truncated")
                        val v = decodeBe(data, pos, 2)
                        pos += 2
                        DataElement.Uuid.of16(v.toInt())
                    }
                    2 -> {
                        if (4 > limit - pos) throw Malformed("uuid truncated")
                        val v = decodeBe(data, pos, 4)
                        pos += 4
                        DataElement.Uuid.of32(v)
                    }
                    4 -> {
                        if (16 > limit - pos) throw Malformed("uuid truncated")
                        val msb = decodeBe(data, pos, 8)
                        val lsb = decodeBe(data, pos + 8, 8)
                        pos += 16
                        DataElement.Uuid.of128(UUID(msb, lsb))
                    }
                    else -> throw Malformed("uuid size index $sizeIndex")
                }
            }
            4, 8 -> {
                val len = decodeVarLength(sizeIndex, data, pos)
                pos += sizeLengthBytes(sizeIndex)
                if (len > limit - pos) throw Malformed("text truncated")
                val txt = data.copyOfRange(pos, pos + len)
                pos += len
                DataElement.Text(txt)
            }
            5 -> {
                if (pos >= limit) throw Malformed("bool truncated")
                val v = data[pos] != 0.toByte()
                pos++
                DataElement.Bool(v)
            }
            6, 7 -> {
                val len = decodeVarLength(sizeIndex, data, pos)
                pos += sizeLengthBytes(sizeIndex)
                if (len > limit - pos) throw Malformed("container truncated")
                val end = pos + len
                val items = mutableListOf<DataElement>()
                while (pos < end) {
                    val (child, nextPos) = decode(data, pos, end, depth + 1)
                    items.add(child)
                    pos = nextPos
                }
                if (type == 6) DataElement.Sequence(items) else DataElement.Alternative(items)
            }
            else -> throw Malformed("unknown type $type")
        }
        return Pair(elem, pos)
    }

    private fun decodeVarLength(sizeIndex: Int, data: ByteArray, pos: Int): Int {
        return when (sizeIndex) {
            5 -> {
                if (pos >= data.size) throw Malformed("length truncated")
                data[pos].toInt() and 0xFF
            }
            6 -> {
                if (pos + 2 > data.size) throw Malformed("length truncated")
                decodeBe(data, pos, 2).toInt()
            }
            7 -> {
                if (pos + 4 > data.size) throw Malformed("length truncated")
                val length = decodeBe(data, pos, 4)
                if (length > Int.MAX_VALUE) throw Malformed("SDP length overflow")
                length.toInt()
            }
            else -> throw Malformed("invalid var length index $sizeIndex")
        }
    }

    private fun sizeLengthBytes(sizeIndex: Int): Int = when (sizeIndex) {
        5 -> 1
        6 -> 2
        7 -> 4
        else -> 0
    }

    private fun decodeBe(data: ByteArray, offset: Int, length: Int): Long {
        if (offset + length > data.size) throw Malformed("bytes truncated")
        var res = 0L
        for (i in 0 until length) {
            res = (res shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return res
    }
}
