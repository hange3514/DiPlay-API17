package com.shilapi.xcertplay.transport

import java.util.ArrayDeque

/** Reassembles NTB16 blocks across USB completions and recovers from bounded stream damage. */
internal class Ntb16StreamDecoder(
    private val usbPacketSize: Int,
    private val onDiagnostic: (String) -> Unit = {},
) {
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private var optionalShortPacketPad = false
    private var ignoredControlTransferCount = 0
    private var receivedNtb = false
    private var emptyNtbDiagnosticBudget = 4
    private val resyncTimes = ArrayDeque<Long>()
    private var resyncCount = 0
    private var resyncReason: String? = null
    private var resyncSkipped = 0

    init {
        require(usbPacketSize > 0) { "usbPacketSize must be positive" }
    }

    /** Adds one native USB completion and returns every complete Ethernet frame it contained. */
    fun append(source: ByteArray, length: Int): List<ByteArray> {
        require(length in 0..source.size) { "USB completion length is outside the source buffer" }
        if (length == 0) return emptyList()

        // Linux ipheth ignores complete four-byte 00 01 transfers on Apple's bulk-IN endpoint.
        // They are normally sent once before the first NTB, but can recur. Only skip at a clean
        // NTB boundary so a matching byte sequence inside a fragmented block remains data.
        if (bufferedSize == 0 && length == 4 &&
            source[0].toInt() == 0 && source[1].toInt() == 1
        ) {
            ignoredControlTransferCount++
            if (ignoredControlTransferCount <= 4 || ignoredControlTransferCount % 100 == 0) {
                onDiagnostic("ncm ignored iPhone bulk-in control transfer count=$ignoredControlTransferCount")
            }
            return emptyList()
        }

        val required = bufferedSize + length
        if (required > buffered.size) {
            val capacity = maxOf(required, maxOf(READ_CHUNK_BYTES, buffered.size * 2))
            val grown = ByteArray(capacity)
            buffered.copyInto(grown, 0, 0, bufferedSize)
            buffered = grown
        }
        source.copyInto(buffered, bufferedSize, 0, length)
        bufferedSize += length
        return drainFrames()
    }

    private fun drainFrames(): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        while (true) {
            // A packet-aligned NTB can end either with a ZLP or one explicit zero byte. If the
            // next completion starts with another NTH16 signature, there was no explicit pad.
            if (optionalShortPacketPad && bufferedSize > 0) {
                optionalShortPacketPad = false
                when (buffered[0].toInt() and 0xff) {
                    0 -> discard(1)
                    Ntb16Codec.NTH16_SIG and 0xff -> Unit
                    else -> if (!resynchronize("Invalid NTB16 short-packet pad")) return frames
                }
            }
            if (bufferedSize < NTH16_LENGTH) return frames

            val blockLength = readU16(buffered, 8)
            if (readU32(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                if (!resynchronize("NCM read buffer does not begin with an NTB16 header")) return frames
                continue
            }
            if (blockLength < MIN_NTB16_LENGTH) {
                if (!resynchronize("Invalid NTB16 block length $blockLength")) return frames
                continue
            }
            if (bufferedSize < blockLength) return frames

            val parsedFrames = Ntb16Codec.parse(buffered, 0, blockLength)
            if (parsedFrames.isEmpty() && emptyNtbDiagnosticBudget > 0) {
                emptyNtbDiagnosticBudget--
                val ndpIndex = readU16(buffered, 10)
                val ndpSignature = if (ndpIndex >= NTH16_LENGTH && ndpIndex <= blockLength - 4) {
                    Integer.toHexString(readU32(buffered, ndpIndex))
                } else "invalid"
                val ndpLength = if (ndpIndex >= NTH16_LENGTH && ndpIndex <= blockLength - 6) {
                    readU16(buffered, ndpIndex + 4).toString()
                } else "invalid"
                onDiagnostic(
                    "ncm NTB contained no datagrams sequence=${readU16(buffered, 6)} " +
                        "headerLength=${readU16(buffered, 4)} blockLength=$blockLength " +
                        "ndpIndex=$ndpIndex ndpSignature=$ndpSignature ndpLength=$ndpLength",
                )
            }
            frames.addAll(parsedFrames)
            receivedNtb = true
            discard(blockLength)
            optionalShortPacketPad = blockLength % usbPacketSize == 0
            finishResync()
        }
    }

    /** Discard bytes up to the next plausible header, retaining enough suffix for a split header. */
    private fun resynchronize(reason: String): Boolean {
        if (resyncReason == null) {
            val now = System.nanoTime()
            while (resyncTimes.isNotEmpty() && now - resyncTimes.peekFirst() > RESYNC_WINDOW_NANOS) {
                resyncTimes.removeFirst()
            }
            if (resyncTimes.size >= MAX_RESYNCS_PER_WINDOW) throw IphoneUsbException.DeviceUnavailable("$reason (repeated)")
            resyncTimes.addLast(now)
            resyncCount++
            resyncReason = reason
            resyncSkipped = 0
        }
        val next = (1..bufferedSize - NTH16_LENGTH).firstOrNull(::plausibleHeaderAt)
        val skipped = next ?: (bufferedSize - (NTH16_LENGTH - 1)).coerceAtLeast(0)
        discard(skipped)
        resyncSkipped += skipped
        return next != null
    }

    private fun finishResync() {
        val reason = resyncReason ?: return
        resyncReason = null
        if (resyncCount <= LOGGED_RESYNCS || resyncCount % 50 == 0) {
            runCatching { onDiagnostic("NCM resynchronized after $reason; skipped=$resyncSkipped resyncs=$resyncCount") }
        }
    }

    private fun plausibleHeaderAt(offset: Int): Boolean {
        if (offset < 0 || offset + NTH16_LENGTH > bufferedSize ||
            readU32(buffered, offset) != Ntb16Codec.NTH16_SIG
        ) return false
        val headerLength = readU16(buffered, offset + 4)
        val blockLength = readU16(buffered, offset + 8)
        val ndpIndex = readU16(buffered, offset + 10)
        return headerLength == NTH16_LENGTH && blockLength >= MIN_NTB16_LENGTH &&
            ndpIndex >= NTH16_LENGTH && ndpIndex % 4 == 0 && ndpIndex + 8 <= blockLength
    }

    private fun discard(count: Int) {
        if (count <= 0) return
        buffered.copyInto(buffered, 0, count, bufferedSize)
        bufferedSize -= count
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val READ_CHUNK_BYTES = 16 * 1024
        private const val NTH16_LENGTH = 12
        private const val MIN_NTB16_LENGTH = 28
        private const val MAX_RESYNCS_PER_WINDOW = 16
        private const val RESYNC_WINDOW_NANOS = 10_000_000_000L
        private const val LOGGED_RESYNCS = 5
    }
}
