package local.headunit.diplay

internal object MediaDecoderActivity {
    /** A decoder can retain a frame until further input. Bound fast polling to recent activity. */
    fun waiting(inFlight: Int, lastActivityNs: Long?, nowNs: Long, awaitingFirstVideoFrame: Boolean = false): Boolean {
        val graceNs = if (awaitingFirstVideoFrame) 1_000_000_000L else 160_000_000L
        return inFlight > 0 && lastActivityNs != null && nowNs - lastActivityNs < graceNs
    }
}
