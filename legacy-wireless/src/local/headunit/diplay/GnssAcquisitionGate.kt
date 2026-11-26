package local.headunit.diplay

/** Gives the phone time to use its own location while the accessory's cold fix settles. */
internal class GnssAcquisitionGate {
    private var previousAtMs = 0L
    private var previousGood = false
    var ready = false
        private set

    fun observe(fixAtMs: Long, accuracyMeters: Double?, nowMs: Long) {
        if (fixAtMs <= 0 || nowMs - fixAtMs !in 0L..MAX_INITIAL_AGE_MS || accuracyMeters == null ||
            !accuracyMeters.isFinite() || accuracyMeters <= 0 || accuracyMeters > KEEP_ACCURACY_METERS) {
            reset()
            return
        }
        // A repeated cache entry cannot stand in for a second measurement.
        if (fixAtMs <= previousAtMs) return
        val continuous = previousAtMs > 0 && fixAtMs - previousAtMs <= MAX_INTERVAL_MS
        val good = accuracyMeters <= ACQUIRE_ACCURACY_METERS
        ready = continuous && (ready || previousGood && good)
        previousAtMs = fixAtMs
        previousGood = good
    }

    fun reset() {
        previousAtMs = 0L
        previousGood = false
        ready = false
    }

    private companion object {
        const val MAX_INITIAL_AGE_MS = 1_800L
        const val MAX_INTERVAL_MS = 2_500L
        const val ACQUIRE_ACCURACY_METERS = 25.0
        const val KEEP_ACCURACY_METERS = 35.0
    }
}
