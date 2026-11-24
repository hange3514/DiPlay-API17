package local.headunit.diplay

/** Small playout reserve for interactive speech and navigation on a real-time RTP stream. */
internal class SpeechPlayoutTuning(role: String) {
    val targetMs: Int = if (role == "navigation") 32 else 48
    val maxWaitMs: Long = if (role == "navigation") 40L else 55L

    private var started = false
    private var drySinceNs: Long? = null
    private var lastRebufferNs: Long? = null

    var rebufferCount = 0L
        private set
    var shortDryEvents = 0L
        private set
    var maxShortDryMs = 0L
        private set

    /** Call only after the received queue, decoder and track all have no audio ready. */
    fun markDry(nowNs: Long) {
        if (started && drySinceNs == null) drySinceNs = nowNs
    }

    fun clearDry() {
        drySinceNs = null
    }

    /** Use packet arrival time so a delayed worker cannot turn its own delay into network jitter. */
    fun prefillFor(arrivedNs: Long): Boolean {
        val drySince = drySinceNs
        drySinceNs = null
        if (!started) {
            started = true
            return true
        }
        if (drySince == null) return false
        val dryForNs = arrivedNs - drySince
        if (dryForNs !in MIN_DRY_NS..MAX_DRY_NS) return false

        shortDryEvents++
        maxShortDryMs = maxOf(maxShortDryMs, dryForNs / NS_PER_MS)
        val previous = lastRebufferNs
        if (previous != null && arrivedNs - previous < REBUFFER_COOLDOWN_NS) return false
        lastRebufferNs = arrivedNs
        rebufferCount++
        return true
    }

    fun reset() {
        started = false
        clearDry()
        lastRebufferNs = null
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
        const val MIN_DRY_NS = 12L * NS_PER_MS
        const val MAX_DRY_NS = 160L * NS_PER_MS
        const val REBUFFER_COOLDOWN_NS = 450L * NS_PER_MS
    }
}
