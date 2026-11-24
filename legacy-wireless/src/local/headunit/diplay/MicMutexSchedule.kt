package local.headunit.diplay

/** Panel mic-mutex self-test v4 timeline; pure timing, unit-tested. No phone call needed. */
object MicMutexSchedule {
    // S0 tone only; S1 VOICE_RECOGNITION retest; S2 DEFAULT source;
    // S3 ALSA mixer+capture card0 (long: mux sweep); S4 ALSA mixer+capture card1;
    // S5 mic released.
    @JvmStatic fun stage(elapsedMs: Long): Int = when {
        elapsedMs < 0L -> -1
        elapsedMs < 4_000L -> 0
        elapsedMs < 8_000L -> 1
        elapsedMs < 12_000L -> 2
        elapsedMs < 22_000L -> 3
        elapsedMs < 28_000L -> 4
        elapsedMs < 32_000L -> 5
        else -> 6
    }

    /** AudioRecord source per stage, or -1 for "no AudioRecord". */
    @JvmStatic fun recorderSource(stage: Int): Int = when (stage) {
        1 -> 6 // MediaRecorder.AudioSource.VOICE_RECOGNITION
        2 -> 0 // MediaRecorder.AudioSource.DEFAULT
        else -> -1
    }

    /** ALSA card index for direct-capture stages, or -1. */
    @JvmStatic fun alsaCard(stage: Int): Int = when (stage) {
        3 -> 0
        4 -> 1
        else -> -1
    }

    /** Milliseconds of ALSA capture to attempt within the stage window. */
    @JvmStatic fun alsaCaptureMillis(stage: Int): Int = when (stage) {
        3 -> 2_500
        4 -> 2_000
        else -> 0
    }

    @JvmStatic fun finished(stage: Int): Boolean = stage >= 6
}
