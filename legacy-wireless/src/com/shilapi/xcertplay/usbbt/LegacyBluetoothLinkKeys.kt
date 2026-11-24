package com.shilapi.xcertplay.usbbt

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** Reads the Classic Bluetooth bond journal written by the API17 native USB builds. */
internal object LegacyBluetoothLinkKeys {
    private val magic = "BTstack\u0000".toByteArray(Charsets.US_ASCII)
    private const val MAX_FILE_BYTES = 256 * 1024
    private const val MAX_VALUE_BYTES = 2048
    private const val LINK_KEY_TAG = 0x42544c00
    private const val SLOT_COUNT = 16
    private const val VALUE_BYTES = 32

    /** [peer] is in display order. The returned key retains its original HCI byte order. */
    fun read(input: InputStream, peer: ByteArray): ByteArray? {
        if (peer.size != 6 || peer.all { it == 0.toByte() }) return null
        val data = DataInputStream(input)
        val header = ByteArray(magic.size)
        val skipped = ByteArray(MAX_VALUE_BYTES)
        val slots = arrayOfNulls<ByteArray>(SLOT_COUNT)
        try {
            data.readFully(header)
            if (!header.contentEquals(magic)) return null
            var remaining = MAX_FILE_BYTES - header.size
            while (true) {
                val first = data.read()
                if (first < 0) break
                if (remaining < 8) return null
                val tag = (first shl 24) or (data.readUnsignedByte() shl 16) or data.readUnsignedShort()
                val length = data.readInt()
                remaining -= 8
                if (length !in 0..minOf(remaining, MAX_VALUE_BYTES)) return null
                remaining -= length

                val slot = tag - LINK_KEY_TAG
                if (slot !in slots.indices) {
                    data.readFully(skipped, 0, length)
                    continue
                }
                slots[slot] = when (length) {
                    0 -> null
                    VALUE_BYTES -> ByteArray(VALUE_BYTES).also { data.readFully(it) }
                    else -> return null
                }
            }
        } catch (_: EOFException) {
            return null
        }
        val record = slots.firstOrNull { entry ->
            entry != null && peer.indices.all { entry[4 + it] == peer[it] }
        } ?: return null
        // Cortex-A9 builds wrote a 32-bit enum after two padding bytes. Accept normal SSP keys.
        if (record[28].toInt() !in 4..8 || (29..31).any { record[it] != 0.toByte() }) return null
        return record.copyOfRange(10, 26).takeIf { key -> key.any { it != 0.toByte() } }
    }
}
