package com.shilapi.xcertplay.transport

/** Sequence gate for the TCP byte stream carried inside USBMUX frames. */
internal object UsbMuxTcpSequencePolicy {
    enum class Mismatch { DUPLICATE_OR_OLD, GAP_OR_OUT_OF_ORDER }

    data class Decision(
        val accepted: Boolean,
        val nextExpected: Int,
        val acceptFin: Boolean,
        val mismatch: Mismatch? = null,
    )

    /** Accept only the exact next byte range; a mismatch is dropped and ACKed at [expected]. */
    fun receive(expected: Int, sequence: Int, payloadBytes: Int, fin: Boolean): Decision {
        require(payloadBytes >= 0)
        val distance = (sequence.toLong() - expected.toLong() + SEQUENCE_SPACE) % SEQUENCE_SPACE
        if (distance != 0L) {
            val kind = if (distance < HALF_SEQUENCE_SPACE) Mismatch.GAP_OR_OUT_OF_ORDER else Mismatch.DUPLICATE_OR_OLD
            return Decision(accepted = false, nextExpected = expected, acceptFin = false, mismatch = kind)
        }
        val afterPayload = expected + payloadBytes
        return Decision(
            accepted = true,
            nextExpected = afterPayload + if (fin) 1 else 0,
            acceptFin = fin,
        )
    }

    private const val SEQUENCE_SPACE = 0x1_0000_0000L
    private const val HALF_SEQUENCE_SPACE = 0x8000_0000L
}
