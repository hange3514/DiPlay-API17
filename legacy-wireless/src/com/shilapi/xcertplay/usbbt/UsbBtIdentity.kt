package com.shilapi.xcertplay.usbbt

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Bluetooth identity and Extended Inquiry Response (EIR) generator for wireless CarPlay.
 *
 * CarPlay discovery requires:
 * 1. EIR Tag 6 (Incomplete 128-bit Service UUIDs) or Tag 7 (Complete 128-bit UUIDs) containing:
 *    - IAP2_ACCESSORY_UUID: 00000000-deca-fade-deca-deafdecacaff (big-endian + little-endian)
 *    - CARPLAY_UUID: ec884348-cd41-40a2-9727-575d50bf1fd3 (little-endian)
 * 2. EIR Tag 9 (Complete Local Name) or Tag 8 (Shortened Local Name)
 * 3. EIR Tag 10 (Tx Power Level, optional)
 * 4. EIR Tag 16 (Device ID, optional)
 * 5. Class of Device: 0x200408 (Handsfree audio / car audio)
 */
data class UsbBtIdentity(
    val name: String,
    val classOfDevice: Int = CLASS_OF_DEVICE_HANDS_FREE,
    val iap2RfcommChannel: Int = IAP2_RFCOMM_CHANNEL,
    val ioCapability: Int = 1, // DisplayYesNo
    val deviceId: DeviceId? = null,
) {
    data class DeviceId(
        val vendorIdSource: Int,
        val vendorId: Int,
        val productId: Int,
        val version: Int,
    )

    companion object {
        const val CLASS_OF_DEVICE_HANDS_FREE = 0x200408 // 2098184
        const val IAP2_RFCOMM_CHANNEL = 1

        val IAP2_ACCESSORY_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")
        val IAP2_IPHONE_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        val CARPLAY_UUID: UUID = UUID.fromString("ec884348-cd41-40a2-9727-575d50bf1fd3")

        const val EIR_INCOMPLETE_16_BIT_UUIDS = 2
        const val EIR_COMPLETE_16_BIT_UUIDS = 3
        const val EIR_INCOMPLETE_128_BIT_UUIDS = 6
        const val EIR_COMPLETE_128_BIT_UUIDS = 7
        const val EIR_SHORTENED_LOCAL_NAME = 8
        const val EIR_COMPLETE_LOCAL_NAME = 9
        const val EIR_TX_POWER_LEVEL = 10
        const val EIR_DEVICE_ID = 16
    }

    /**
     * Builds the Extended Inquiry Response (EIR) payload (up to 240 bytes).
     */
    fun extendedInquiryResponse(txPowerLevel: Int? = null): ByteArray {
        val nonNamePayload = ByteArrayOutputStream()

        // 0. Tag 2: Incomplete 16-bit Service Class UUIDs
        // iOS Settings > Bluetooth scans for Handsfree (0x111E) and Audio services.
        // Without 16-bit audio UUIDs in EIR, iOS Settings > Bluetooth filters out devices with CoD 0x200408.
        writeEirField(nonNamePayload, EIR_INCOMPLETE_16_BIT_UUIDS, byteArrayOf(0x1E.toByte(), 0x11.toByte(), 0x03.toByte(), 0x12.toByte()))

        // 1. Tag 6: Incomplete 128-bit UUIDs (iAP2 accessory UUID in both big-endian and little-endian)
        val iap2Be = uuidBigEndian(IAP2_ACCESSORY_UUID)
        val iap2Le = uuidLittleEndian(IAP2_ACCESSORY_UUID)
        writeEirField(nonNamePayload, EIR_INCOMPLETE_128_BIT_UUIDS, iap2Be + iap2Le)

        // 2. Tag 7: Complete 128-bit UUIDs (CarPlay UUID in little-endian)
        val carplayLe = uuidLittleEndian(CARPLAY_UUID)
        writeEirField(nonNamePayload, EIR_COMPLETE_128_BIT_UUIDS, carplayLe)

        // 3. Tag 10: Tx Power Level
        if (txPowerLevel != null) {
            writeEirField(nonNamePayload, EIR_TX_POWER_LEVEL, byteArrayOf(txPowerLevel.toByte()))
        }

        // 4. Tag 16: Device ID (if present)
        deviceId?.let { dev ->
            val devBytes = ByteArrayOutputStream()
            writeLe16(devBytes, dev.vendorIdSource)
            writeLe16(devBytes, dev.vendorId)
            writeLe16(devBytes, dev.productId)
            writeLe16(devBytes, dev.version)
            writeEirField(nonNamePayload, EIR_DEVICE_ID, devBytes.toByteArray())
        }

        val nonNameBytes = nonNamePayload.toByteArray()
        val availableForName = 238 - nonNameBytes.size // 240 minus 2 bytes of name field header (len + type)

        val out = ByteArrayOutputStream()
        val nameRaw = name.toByteArray(Charsets.UTF_8)
        if (nameRaw.size <= availableForName) {
            writeEirField(out, EIR_COMPLETE_LOCAL_NAME, nameRaw)
        } else {
            val truncated = truncateUtf8(nameRaw, availableForName.coerceAtLeast(0))
            writeEirField(out, EIR_SHORTENED_LOCAL_NAME, truncated)
        }
        out.write(nonNameBytes)
        return out.toByteArray()
    }

    private fun writeEirField(out: ByteArrayOutputStream, tag: Int, data: ByteArray) {
        out.write((data.size + 1) and 0xFF)
        out.write(tag and 0xFF)
        out.write(data)
    }

    private fun writeLe16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun truncateUtf8(raw: ByteArray, maxLen: Int): ByteArray {
        if (raw.size <= maxLen) return raw
        var end = maxLen
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) {
            end-- // backtrack continuation bytes
        }
        return raw.copyOfRange(0, end)
    }
}

/** Convert UUID to 16 bytes Big-Endian. */
fun uuidBigEndian(uuid: UUID): ByteArray {
    val msb = uuid.mostSignificantBits
    val lsb = uuid.leastSignificantBits
    val out = ByteArray(16)
    for (i in 0..7) {
        out[i] = ((msb ushr ((7 - i) * 8)) and 0xFF).toByte()
    }
    for (i in 0..7) {
        out[8 + i] = ((lsb ushr ((7 - i) * 8)) and 0xFF).toByte()
    }
    return out
}

/** Convert UUID to 16 bytes Little-Endian. */
fun uuidLittleEndian(uuid: UUID): ByteArray {
    val be = uuidBigEndian(uuid)
    return ByteArray(16) { i -> be[15 - i] }
}
