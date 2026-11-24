package local.headunit.diplay

internal object MediaDecoderActivity {
    /** A decoder can retain a frame until further input. Bound fast polling to recent activity. */
    fun waiting(inFlight: Int, lastActivityNs: Long?, nowNs: Long, awaitingFirstVideoFrame: Boolean = false): Boolean {
        val graceNs = if (awaitingFirstVideoFrame) 1_000_000_000L else 160_000_000L
        return inFlight > 0 && lastActivityNs != null && nowNs - lastActivityNs < graceNs
    }

    /** With very little PCM left, let already submitted AAC output wake the music worker. */
    fun lowMusicRunwayWaitUs(queuedFrames: Long, trackRate: Int): Long =
        if (trackRate > 0 && queuedFrames in 0..(trackRate / 200L)) 2_000L else 0L
}
