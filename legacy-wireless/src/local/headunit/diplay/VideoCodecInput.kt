package local.headunit.diplay

import android.media.MediaCodec

/** A held display buffer must not stall a single-slot codec that has more input ready. */
internal object VideoCodecInput {
    private const val INPUT_WAIT_US = 5_000L

    inline fun acquire(
        inputBufferCount: Int,
        heldRemainingNs: Long?,
        dequeue: (Long) -> Int,
        releaseHeldOutput: () -> Boolean,
    ): Int {
        if (inputBufferCount == 1 && heldRemainingNs != null && heldRemainingNs > 0L) {
            val index = dequeue(0L)
            if (index != MediaCodec.INFO_TRY_AGAIN_LATER || !releaseHeldOutput()) return index
            return dequeue(INPUT_WAIT_US)
        }
        val waitUs = heldRemainingNs?.let { (it / 1000L).coerceIn(0L, INPUT_WAIT_US) } ?: INPUT_WAIT_US
        return dequeue(waitUs)
    }
}
