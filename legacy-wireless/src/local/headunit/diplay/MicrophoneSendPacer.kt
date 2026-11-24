package local.headunit.diplay

/** Paces one microphone sender without extending bursts after a late encode. */
internal class MicrophoneSendPacer(private val frameNanos: Long) {
    init {
        require(frameNanos > 0)
    }

    private val minimumGapNanos = frameNanos - frameNanos / 4
    private var hasDeadline = false
    private var nextSendNanos = 0L

    fun waitNanos(nowNanos: Long): Long =
        if (hasDeadline) (nextSendNanos - nowNanos).coerceAtLeast(0L) else 0L

    fun isLate(nowNanos: Long): Boolean =
        hasDeadline && nowNanos - nextSendNanos >= frameNanos / 2

    fun onPacketSent(sendStartedNanos: Long, queuedFrames: Int) {
        val latenessNanos = if (hasDeadline) sendStartedNanos - nextSendNanos else frameNanos
        val gapNanos = when {
            // Leave room for the next captured frame when two or more are already waiting.
            queuedFrames >= 2 -> minimumGapNanos
            latenessNanos >= frameNanos -> frameNanos
            else -> maxOf(frameNanos - latenessNanos, minimumGapNanos)
        }
        nextSendNanos = sendStartedNanos + gapNanos
        hasDeadline = true
    }
}
