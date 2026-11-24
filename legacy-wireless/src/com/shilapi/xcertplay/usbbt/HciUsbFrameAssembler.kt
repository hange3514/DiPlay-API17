package com.shilapi.xcertplay.usbbt

/**
 * Collects complete HCI packets from endpoint-sized USB reads.
 *
 * A full-size USB packet does not delimit the transfer. A short packet or
 * zero-length packet is the transfer boundary and drops an incomplete frame.
 * Once complete, a frame is delivered immediately without waiting for a ZLP.
 */
internal class HciUsbFrameAssembler(private val isAcl: Boolean) {
    private val headerLength = if (isAcl) 4 else 2
    private val maxPayload = if (isAcl) 8192 else 255
    private val bytes = ByteArray(headerLength + maxPayload)
    private var used = 0
    private var expected = 0

    fun push(packet: ByteArray, length: Int, endOfTransfer: Boolean): List<ByteArray> {
        require(length in 0..packet.size)
        val complete = ArrayList<ByteArray>(1)
        var offset = 0
        while (offset < length) {
            val target = if (used < headerLength) headerLength else expected
            val count = minOf(target - used, length - offset)
            packet.copyInto(bytes, used, offset, offset + count)
            used += count
            offset += count
            if (used == headerLength && expected == 0) {
                val payloadLength = if (isAcl) {
                    (bytes[2].toInt() and 0xff) or ((bytes[3].toInt() and 0xff) shl 8)
                } else {
                    bytes[1].toInt() and 0xff
                }
                if (payloadLength > maxPayload) {
                    reset()
                    break
                }
                expected = headerLength + payloadLength
            }
            if (used == expected && expected != 0) {
                complete.add(bytes.copyOf(expected))
                reset()
            }
        }
        if (endOfTransfer) reset()
        return complete
    }

    private fun reset() {
        used = 0
        expected = 0
    }
}
