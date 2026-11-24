package com.shilapi.xcertplay.usbbt

/** USB ROM identities and epatch wire formats, checked against Linux btrtl's public protocol.
 * See FIRMWARE.md for sources, formats deliberately excluded, and hardware validation limits.
 */
internal object RealtekFirmware {
    // Bluetooth SIG Company Identifier (distinct from USB VID 0x0bda).
    const val COMPANY_ID = 0x005d
    const val READ_ROM_VERSION = 0xfc6d
    const val FRAGMENT_SIZE = 252

    data class Version(val hci: Int, val revision: Int, val manufacturer: Int, val subversion: Int) {
        companion object {
            fun parse(reply: ByteArray): Version? = if (reply.size == 9 && reply[0] == 0.toByte()) {
                Version(u8(reply[1]), u16(reply, 2), u16(reply, 5), u16(reply, 7))
            } else null
        }
    }

    data class Profile(
        val name: String, val subversion: Int, val revision: Int, val hci: Int,
        val project: Int?, val configRequired: Boolean = false,
    ) {
        val firmwareAsset get() = "usbbt_fw/rtl_bt/${name}_fw${if (name == "rtl8852cu") "_v2" else ""}.bin"
        val configAsset get() = "usbbt_fw/rtl_bt/${name}_config.bin"
    }

    // USB identities only. UART B/BS/CS/DS firmware must never substitute for USB BU/CU.
    val profiles = listOf(
        Profile("rtl8723a", 0x1200, 0x000b, 0x06, null),
        Profile("rtl8723b", 0x8723, 0x000b, 0x06, 1),
        Profile("rtl8723d", 0x8723, 0x000d, 0x08, 9, true),
        Profile("rtl8761a", 0x8761, 0x000a, 0x06, 3),
        Profile("rtl8761bu", 0x8761, 0x000b, 0x0a, 14),
        Profile("rtl8761cu", 0x8761, 0x000e, 0, 51),
        Profile("rtl8821a", 0x8821, 0x000a, 0x06, 2),
        Profile("rtl8821c", 0x8821, 0x000c, 0x08, 10),
        Profile("rtl8822b", 0x8822, 0x000b, 0x07, 8, true),
        Profile("rtl8822cu", 0x8822, 0x000c, 0x0a, 13),
        Profile("rtl8852au", 0x8852, 0x000a, 0x0b, 18),
        Profile("rtl8852bu", 0x8852, 0x000b, 0x0b, 20),
        Profile("rtl8852cu", 0x8852, 0x000c, 0x0c, 25),
        Profile("rtl8851bu", 0x8851, 0x000b, 0x0c, 36),
        Profile("rtl8922au", 0x8922, 0x000a, 0x0c, 44),
        Profile("rtl8852btu", 0x8852, 0x0087, 0x0c, 47),
    )

    fun match(version: Version): Profile? = profiles.firstOrNull {
        version.manufacturer == COMPANY_ID && version.subversion == it.subversion &&
            version.revision == it.revision && (it.hci == 0 || version.hci == it.hci)
    }

    /** Select exactly one ROM ECO patch, replacing its version trailer, then append config. */
    fun image(firmware: ByteArray, config: ByteArray?, profile: Profile, romVersion: Int, keyId: Int = 0): ByteArray {
        require(firmware.size in 8..1_048_576) { "invalid firmware size" }
        require(romVersion in 0..255 && keyId in 0..255) { "invalid ROM or security key ID" }
        require(!profile.configRequired || config != null) { "missing mandatory config for ${profile.name}" }
        config?.let { validateConfig(it) }
        val patch = if (profile.project == null) {
            require(!hasSignature(firmware, "Realtech") && !hasSignature(firmware, "RTBTCore")) {
                "unexpected container for raw RTL8723A firmware"
            }
            firmware.copyOf()
        } else {
            val v2 = isV2(firmware)
            require(v2 || hasSignature(firmware, "Realtech")) { "unsupported epatch format" }
            val headerSize = if (v2) 20 else 14
            require(firmware.size >= headerSize + 7) { "truncated epatch header" }
            val tail = byteArrayOf(0x51, 0x04, 0xfd.toByte(), 0x77)
            require(firmware.takeLast(4).toByteArray().contentEquals(tail)) { "bad epatch extension signature" }
            val count = if (v2) 0 else u16(firmware, 12)
            val tableEnd = if (v2) headerSize else 14 + count * 8
            require((v2 || count in 1..256) && tableEnd <= firmware.size - 7) { "invalid epatch table" }
            var cursor = firmware.size - 4
            var project = -1
            var extensionStart = cursor
            while (cursor > tableEnd) {
                val opcode = u8(firmware[--cursor])
                if (opcode == 0xff) break
                require(cursor > tableEnd) { "truncated extension record" }
                val length = u8(firmware[--cursor])
                require(length > 0 && length <= cursor - tableEnd) { "invalid extension length" }
                cursor -= length
                if (opcode == 0 && length == 1) {
                    project = u8(firmware[cursor])
                    extensionStart = cursor
                    break
                }
            }
            require(project == profile.project) { "firmware project $project does not match ${profile.name}" }
            if (v2) {
                v2Patch(firmware, extensionStart, romVersion, keyId)
            } else {
                val matches = (0 until count).filter { u16(firmware, 14 + it * 2) == romVersion + 1 }
                require(matches.size == 1) { "no unique patch for ROM ECO $romVersion" }
                val index = matches.single()
                val length = u16(firmware, 14 + count * 2 + index * 2)
                val offset = u32(firmware, 14 + count * 4 + index * 4)
                require(length >= 4 && offset >= tableEnd && offset <= extensionStart.toLong() - length) {
                    "patch outside firmware data"
                }
                firmware.copyOfRange(offset.toInt(), offset.toInt() + length).also {
                    firmware.copyInto(it, length - 4, 8, 12)
                }
            }
        }
        return if (config == null) patch else patch + config
    }

    fun isV2(data: ByteArray) = data.size >= 8 && hasSignature(data, "RTBTCore")

    /** V2 snippets/dummy/security sections are filtered by ECO/key and sent in priority order. */
    private fun v2Patch(data: ByteArray, end: Int, rom: Int, key: Int): ByteArray {
        data class Part(val priority: Int, val order: Int, val offset: Int, val length: Int)
        val sections = u32(data, 16)
        require(sections in 1L..1024L) { "invalid v2 section count" }
        val parts = mutableListOf<Part>()
        var cursor = 20
        repeat(sections.toInt()) {
            require(end - cursor >= 8) { "truncated v2 section" }
            val opcode = u32(data, cursor)
            val length = u32(data, cursor + 4)
            cursor += 8
            require(length <= end - cursor) { "v2 section exceeds firmware" }
            val sectionEnd = cursor + length.toInt()
            if (opcode in 1L..3L) {
                require(sectionEnd - cursor >= 4) { "truncated v2 subsection table" }
                val count = u16(data, cursor)
                cursor += 4
                repeat(count) {
                    require(sectionEnd - cursor >= 8) { "truncated v2 subsection" }
                    val eco = u8(data[cursor])
                    val priority = u8(data[cursor + 1])
                    val sectionKey = u8(data[cursor + 2])
                    val size = u32(data, cursor + 4)
                    cursor += 8
                    require(size <= sectionEnd - cursor) { "v2 subsection exceeds section" }
                    if (eco == rom + 1 && (opcode != 3L || key != 0 && sectionKey == key)) {
                        parts.add(Part(priority, parts.size, cursor, size.toInt()))
                    }
                    cursor += size.toInt()
                }
                require(cursor == sectionEnd) { "invalid v2 section length" }
            }
            cursor = sectionEnd
        }
        require(parts.isNotEmpty() && parts.sumOf { it.length } > 0) { "no v2 patch for ROM ECO $rom key $key" }
        val output = java.io.ByteArrayOutputStream()
        parts.sortedWith(compareBy<Part> { it.priority }.thenByDescending { it.order }).forEach {
            output.write(data, it.offset, it.length)
        }
        return output.toByteArray()
    }

    fun fragments(image: ByteArray): Sequence<ByteArray> {
        require(image.isNotEmpty() && image.size <= 1_048_576) { "invalid download image size" }
        return sequence {
            val last = image.size / FRAGMENT_SIZE
            var wireIndex = 0
            for (part in 0..last) {
                val offset = part * FRAGMENT_SIZE
                val length = minOf(FRAGMENT_SIZE, image.size - offset)
                val index = wireIndex or (if (part == last) 0x80 else 0)
                yield(byteArrayOf(index.toByte()) + image.copyOfRange(offset, offset + length))
                wireIndex = if (wireIndex == 0x7f) 1 else wireIndex + 1
            }
        }
    }

    private fun validateConfig(config: ByteArray) {
        require(config.size in 6..65_541 && u32(config, 0) == 0x8723ab55L &&
            u16(config, 4) == config.size - 6) { "invalid Realtek config header" }
        var cursor = 6
        while (cursor < config.size) {
            require(config.size - cursor >= 3) { "truncated config entry" }
            val length = u8(config[cursor + 2])
            cursor += 3
            require(length <= config.size - cursor) { "truncated config payload" }
            cursor += length
        }
    }

    private fun hasSignature(data: ByteArray, signature: String) =
        data.copyOfRange(0, 8).contentEquals(signature.toByteArray(Charsets.US_ASCII))
    private fun u8(value: Byte) = value.toInt() and 0xff
    private fun u16(data: ByteArray, offset: Int) = u8(data[offset]) or (u8(data[offset + 1]) shl 8)
    private fun u32(data: ByteArray, offset: Int) =
        (0..3).fold(0L) { value, index -> value or (u8(data[offset + index]).toLong() shl (index * 8)) }
}
