package com.shilapi.xcertplay.usbbt

import android.content.res.AssetManager
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_BCM_DOWNLOAD_MINIDRIVER
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_READ_LOCAL_VERSION
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_RESET
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_RTL_DOWNLOAD_COMMAND
import java.io.IOException
import java.util.Locale

/** Background-thread firmware setup. Assets, protocol references and support limits: FIRMWARE.md. */
class UsbBtFirmwareLoader internal constructor(
    private val command: (Int, ByteArray, Int) -> UsbBtHci.HciResult<ByteArray>,
    private val readAsset: (String) -> ByteArray?,
) {
    constructor(hci: UsbBtHci, assets: AssetManager) : this(
        { opcode, params, timeout -> hci.sendCommand(opcode, params, timeout) },
        { name ->
            try {
                val bundledName = when (name) {
                    "usbbt_fw/rtl_bt/rtl8761bu_fw.bin" -> "rtl8761bu_fw"
                    "usbbt_fw/rtl_bt/rtl8761bu_config.bin" -> "rtl8761bu_config"
                    else -> name
                }
                assets.open(bundledName).use { stream ->
                    // Bound both corrupt local overrides and future asset additions.
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 1_048_576) { "firmware asset exceeds 1 MiB: $name" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } catch (_: java.io.FileNotFoundException) { null }
        },
    )

    companion object {
        const val FW_ASSET_DIR = "usbbt_fw"
        const val RTL_FRAGMENT_SIZE = RealtekFirmware.FRAGMENT_SIZE
        const val VID_CSR = 0x0a12
        const val VID_BROADCOM = 0x0a5c
        const val VID_REALTEK = 0x0bda
        const val VID_UGREEN_HAYLOU = 0x2b89
        const val VID_TPLINK = 0x2357
        const val VID_ASUS = 0x0b05
        const val VID_EDIMAX = 0x7392
        const val VID_DLINK = 0x2001
        const val VID_BELKIN = 0x050d
        const val VID_AZUREWAVE = 0x13d3
        const val VID_LENOVO = 0x17ef
        const val VID_DELL = 0x413c

        enum class ChipFamily { REALTEK, BROADCOM, CSR_NO_FW, OTHER }

        /** VID/PID hints are only used when the controller cannot return its real identity. */
        fun detectChipFamily(vendorId: Int, productId: Int): ChipFamily = when {
            vendorId == VID_REALTEK ||
                vendorId == VID_UGREEN_HAYLOU && productId in listOf(0x6275, 0x8771) ||
                vendorId == VID_TPLINK && productId in listOf(0x0604, 0x0605) ||
                vendorId == VID_ASUS && productId == 0x190e ||
                vendorId == VID_EDIMAX && productId == 0xc611 ||
                vendorId == VID_DLINK && productId == 0x6511 ||
                vendorId == VID_AZUREWAVE && productId in listOf(0x3563, 0x3564, 0x3567, 0x3578, 0x3584) ||
                vendorId == VID_LENOVO && productId == 0xa318 -> ChipFamily.REALTEK
            vendorId == VID_BROADCOM ||
                vendorId == VID_ASUS && productId == 0x17cb ||
                vendorId == VID_BELKIN && productId in listOf(0x065a, 0x0018) ||
                vendorId == VID_DELL && productId == 0x8143 ||
                vendorId == VID_LENOVO && productId == 0x7205 -> ChipFamily.BROADCOM
            vendorId == VID_CSR || vendorId == VID_TPLINK && productId == 0x0600 -> ChipFamily.CSR_NO_FW
            else -> ChipFamily.OTHER
        }

        /** Local overrides are exact device matches; chip firmware is selected from HCI version. */
        fun getCandidateFirmwareAssets(vendorId: Int, productId: Int): List<String> = listOf(
            assetNameFor(vendorId, productId,
                if (detectChipFamily(vendorId, productId) == ChipFamily.BROADCOM) ".hcd" else ".bin"),
        )

        fun assetNameFor(vendorId: Int, productId: Int, ext: String): String =
            "$FW_ASSET_DIR/${String.format(Locale.ROOT, "%04x_%04x", vendorId, productId)}$ext"
    }

    sealed class LoadResult {
        data class Uploaded(val bytes: Int, val detail: String) : LoadResult()
        data class NoFirmware(val assetName: String) : LoadResult()
        data class NoFirmwareNeeded(val detail: String) : LoadResult()
        data class Skipped(val detail: String) : LoadResult()
        data class Failed(val reason: String) : LoadResult()
    }

    data class HcdRecord(val opcode: Int, val payload: ByteArray) {
        override fun equals(other: Any?) = other is HcdRecord && opcode == other.opcode && payload.contentEquals(other.payload)
        override fun hashCode() = 31 * opcode + payload.contentHashCode()
    }
    object HcdParser {
        fun parse(bytes: ByteArray): List<HcdRecord>? {
            val records = ArrayList<HcdRecord>()
            var offset = 0
            while (offset < bytes.size) {
                if (bytes.size - offset < 3) return null
                val opcode = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
                val length = bytes[offset + 2].toInt() and 255
                offset += 3
                if (length > bytes.size - offset) return null
                records.add(HcdRecord(opcode, bytes.copyOfRange(offset, offset + length)))
                offset += length
            }
            return records
        }
    }

    /** Called after Reset even when Reset succeeded; a responding ROM may still need its patch. */
    fun loadForDevice(vendorId: Int, productId: Int, localVersion: ByteArray? = null): LoadResult {
        if (vendorId == 0x10d7 && productId == 0xb012) {
            return LoadResult.NoFirmwareNeeded("Actions ATS2851 ROM controller; no patch uploaded")
        }
        return try {
            val family = detectChipFamily(vendorId, productId)
            var version = localVersion?.let { RealtekFirmware.Version.parse(it) }
            if (version == null && family == ChipFamily.REALTEK) {
                version = RealtekFirmware.Version.parse(send(OP_READ_LOCAL_VERSION))
                    ?: throw IOException("malformed Realtek Read_Local_Version reply")
            }
            when {
                version?.manufacturer == RealtekFirmware.COMPANY_ID -> loadRealtek(vendorId, productId, version)
                family == ChipFamily.REALTEK -> LoadResult.Skipped("controller manufacturer is not Realtek; no patch sent")
                family == ChipFamily.BROADCOM -> loadBroadcom(vendorId, productId)
                family == ChipFamily.CSR_NO_FW -> LoadResult.NoFirmwareNeeded("CSR ROM controller; no patch uploaded")
                else -> LoadResult.Skipped("no supported firmware protocol for this controller")
            }
        } catch (error: IOException) {
            LoadResult.Failed(error.message ?: "firmware I/O failed")
        } catch (error: IllegalArgumentException) {
            LoadResult.Failed(error.message ?: "invalid firmware")
        }
    }

    private fun send(opcode: Int, params: ByteArray = byteArrayOf(), timeout: Int = 3_000): ByteArray =
        when (val result = command(opcode, params, timeout)) {
            is UsbBtHci.HciResult.Ok -> result.value.also {
                if (it.isEmpty() || it[0] != 0.toByte()) throw IOException("opcode 0x${opcode.toString(16)} rejected")
            }
            is UsbBtHci.HciResult.Failed -> throw IOException("opcode 0x${opcode.toString(16)}: ${result.reason}")
        }

    private fun loadRealtek(vendorId: Int, productId: Int, version: RealtekFirmware.Version): LoadResult {
        val profile = RealtekFirmware.match(version) ?: return LoadResult.Skipped(
            "unsupported or already patched Realtek identity: $version; no patch sent",
        )
        val exact = assetNameFor(vendorId, productId, ".bin")
        val override = readAsset(exact)
        val asset = if (override != null) exact else profile.firmwareAsset
        val firmware = override ?: readAsset(asset) ?: throw IOException("missing required firmware: $asset")
        // A device override may have its own config. Otherwise use the matching USB profile config.
        val key = if (RealtekFirmware.isV2(firmware)) {
            val reply = send(0xfc61, byteArrayOf(0x10, 0xa4.toByte(), 0xad.toByte(), 0, 0xb0.toByte()))
            require(reply.size == 3) { "malformed Realtek security key reply" }
            reply[1].toInt() and 255
        } else 0
        val config = if (key == 0) {
            readAsset(assetNameFor(vendorId, productId, "_config.bin")) ?: readAsset(profile.configAsset)
        } else null
        val rom = if (profile.project == null) 0 else {
            val reply = send(RealtekFirmware.READ_ROM_VERSION)
            require(reply.size == 2) { "malformed Realtek ROM version reply" }
            reply[1].toInt() and 255
        }
        val image = RealtekFirmware.image(firmware, config, profile, rom, key)
        var fragments = 0
        for (fragment in RealtekFirmware.fragments(image)) {
            val reply = send(OP_RTL_DOWNLOAD_COMMAND, fragment)
            require(reply.size == 2 && (reply[1].toInt() and 0x7f) == (fragment[0].toInt() and 0x7f)) {
                "Realtek fragment $fragments acknowledgement mismatch"
            }
            fragments++
        }
        // The final fragment activates the patch. Verify a complete version reply before reporting success.
        val running = RealtekFirmware.Version.parse(send(OP_READ_LOCAL_VERSION))
            ?: throw IOException("malformed post-upload version reply")
        require(running.manufacturer == RealtekFirmware.COMPANY_ID) { "post-upload controller identity mismatch" }
        require(running != version) { "controller still reports the unpatched ROM identity after download" }
        return LoadResult.Uploaded(image.size,
            "${profile.name} ROM=$rom key=$key asset=$asset config=${config?.size ?: 0} bytes fragments=$fragments running=$running")
    }

    private fun loadBroadcom(vendorId: Int, productId: Int): LoadResult {
        val asset = assetNameFor(vendorId, productId, ".hcd")
        val bytes = readAsset(asset) ?: return LoadResult.NoFirmware(asset)
        val records = HcdParser.parse(bytes) ?: throw IOException("malformed .hcd file: $asset")
        require(records.isNotEmpty()) { "empty .hcd file: $asset" }
        send(OP_BCM_DOWNLOAD_MINIDRIVER)
        records.forEach { send(it.opcode, it.payload) }
        send(OP_RESET, timeout = 5_000)
        return LoadResult.Uploaded(bytes.size, "loaded $asset (${records.size} hcd records)")
    }
}
