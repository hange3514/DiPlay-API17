package local.headunit.diplay

/** Single-call diagnostic window; stages alter capture only, never the downlink route. */
object CallDuplexSchedule {
    // A: mic off; B: mic on via VOICE_COMMUNICATION (production); C: mic on via plain MIC; D: mic off again.
    @JvmStatic fun stage(elapsedMs: Long): Int = when {
        elapsedMs < 0L -> -1
        elapsedMs < 5_000L -> 0
        elapsedMs < 13_000L -> 1
        elapsedMs < 21_000L -> 2
        elapsedMs < 26_000L -> 3
        else -> 4
    }

    @JvmStatic fun microphoneSuppressed(stage: Int): Boolean = stage == -2 || stage == 0 || stage == 3

    /** AudioSource override per stage, or -1 to keep the production choice. */
    @JvmStatic fun microphoneSource(stage: Int): Int = when (stage) {
        2 -> 1 // MediaRecorder.AudioSource.MIC
        else -> -1
    }
}
