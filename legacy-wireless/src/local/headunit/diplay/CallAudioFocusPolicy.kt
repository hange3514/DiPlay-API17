package local.headunit.diplay

import android.media.AudioManager

/** Use transient music focus for CarPlay call PCM on BYD's Android media output. */
object CallAudioFocusPolicy {
    @JvmStatic fun gain(bydMediaOutput: Boolean): Int =
        if (bydMediaOutput) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        else AudioManager.AUDIOFOCUS_GAIN
}
