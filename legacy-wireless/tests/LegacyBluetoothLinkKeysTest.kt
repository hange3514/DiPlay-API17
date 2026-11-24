package com.shilapi.xcertplay.usbbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class LegacyBluetoothLinkKeysTest {
    private val peer = byteArrayOf(2, 17, 34, 51, 68, 85)
    private val other = byteArrayOf(2, 91, 92, 93, 94, 95)
    private val oldKey = ByteArray(16) { (it + 17).toByte() }
    private val newKey = ByteArray(16) { (it + 120).toByte() }

    @Test fun acceptsTheArmRecordAndKeepsHciByteOrder() {
        val file = journal(slot(0) to bond(other, newKey), slot(15) to bond(peer, oldKey))
        assertArrayEquals(oldKey, LegacyBluetoothLinkKeys.read(file.inputStream(), peer))
        assertArrayEquals(newKey, LegacyBluetoothLinkKeys.read(file.inputStream(), other))
        assertNull(LegacyBluetoothLinkKeys.read(file.inputStream(), peer.reversedArray()))
    }

    @Test fun slotReplacementAndDeletionCannotResurrectAnEarlierKey() {
        val removed = journal(slot(2) to bond(peer, oldKey), slot(2) to byteArrayOf())
        val overwritten = journal(slot(2) to bond(peer, oldKey), slot(2) to bond(other, newKey))
        val rewritten = journal(
            slot(2) to bond(peer, oldKey), slot(2) to byteArrayOf(), slot(2) to bond(peer, newKey)
        )
        assertNull(LegacyBluetoothLinkKeys.read(removed.inputStream(), peer))
        assertNull(LegacyBluetoothLinkKeys.read(overwritten.inputStream(), peer))
        assertArrayEquals(newKey, LegacyBluetoothLinkKeys.read(rewritten.inputStream(), peer))
    }

    @Test fun rejectsTruncatedJournalsEvenAfterValidRecords() {
        val first = slot(3) to bond(peer, oldKey)
        val known = journal(first, slot(4) to bond(other, newKey))
        val unknown = journal(first, 0x4c453030 to ByteArray(64))
        assertNull(LegacyBluetoothLinkKeys.read(known.copyOf(known.size - 1).inputStream(), peer))
        assertNull(LegacyBluetoothLinkKeys.read(unknown.copyOf(unknown.size - 60).inputStream(), peer))
        assertNull(LegacyBluetoothLinkKeys.read((journal(first) + 1.toByte()).inputStream(), peer))
        assertArrayEquals(oldKey, LegacyBluetoothLinkKeys.read(unknown.inputStream(), peer))
    }

    @Test fun rejectsAFileOrRecordOutsideTheLegacyLimits() {
        val valid = journal(slot(0) to bond(peer, oldKey))
        assertNull(LegacyBluetoothLinkKeys.read(valid.copyOf().also { it[7] = 3 }.inputStream(), peer))
        val tooLarge = journal(
            slot(0) to bond(peer, oldKey),
            *Array(128) { 0x4c453030 to ByteArray(2048) }
        )
        assertNull(LegacyBluetoothLinkKeys.read(tooLarge.inputStream(), peer))
        assertNull(LegacyBluetoothLinkKeys.read(journal(slot(0) to ByteArray(31)).inputStream(), peer))
    }

    @Test fun rejectsAnAllZeroKeyAndDebugOrInvalidKeyTypes() {
        for ((key, type) in listOf(ByteArray(16) to 8, oldKey to 3, oldKey to 0xffff)) {
            val invalid = journal(slot(0) to bond(peer, key, type))
            assertNull(LegacyBluetoothLinkKeys.read(invalid.inputStream(), peer))
        }
    }

    private fun slot(index: Int) = 0x42544c00 + index

    private fun bond(address: ByteArray, key: ByteArray, type: Int = 8): ByteArray =
        ByteArray(32).also {
            address.copyInto(it, 4)
            key.copyInto(it, 10)
            it[28] = type.toByte()
            it[29] = (type ushr 8).toByte()
        }

    private fun journal(vararg records: Pair<Int, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeBytes("BTstack\u0000")
            for ((tag, value) in records) {
                output.writeInt(tag)
                output.writeInt(value.size)
                output.write(value)
            }
        }
        return bytes.toByteArray()
    }
}
