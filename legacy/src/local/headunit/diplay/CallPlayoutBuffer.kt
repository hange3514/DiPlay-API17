package local.headunit.diplay

/** Prefill at startup and after verified depletion, without filling silent intervals. */
internal class CallPlayoutBuffer(private val sampleRate: Int) {
    val targetFrames = sampleRate * 48 / 1000
    val maxWaitMs = 60L

    enum class Arrival { START, CHECK_TRACK, CONTINUE }
    private var previousArrivalNs = Long.MIN_VALUE
    private var previousFrames = 0

    fun packetArrived(arrivedNs: Long, audioFrames: Int): Arrival {
        val previous = previousArrivalNs
        val previousDurationNs = previousFrames * 1_000_000_000L / sampleRate
        previousArrivalNs = arrivedNs
        previousFrames = audioFrames
        if (previous == Long.MIN_VALUE) return Arrival.START
        // Do not query AudioTrack on the fast path of normal packet delivery.
        val inspectAfterNs = maxOf(24_000_000L, previousDurationNs + 8_000_000L)
        return if (arrivedNs - previous >= inspectAfterNs) Arrival.CHECK_TRACK else Arrival.CONTINUE
    }

    fun shouldPrefill(arrival: Arrival, queuedFrames: Long): Boolean =
        arrival == Arrival.START || (arrival == Arrival.CHECK_TRACK && queuedFrames == 0L)
}
