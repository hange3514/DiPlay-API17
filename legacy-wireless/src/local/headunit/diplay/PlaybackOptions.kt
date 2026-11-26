package local.headunit.diplay

import android.content.Context

/** The requested phone-side video frame-rate cap for the next connection. */
object PlaybackOptions {
    const val DEFAULT_FPS = 20
    private const val PREFERENCES = "playback"
    private const val FRAME_RATE_KEY = "fps"
    private val basicRates = intArrayOf(15, DEFAULT_FPS, 30)
    private val highRates = intArrayOf(30, 40, 50, 60)
    private val frameRates = (basicRates.asList() + highRates.asList()).distinct().toIntArray()

    @JvmStatic fun rates(): IntArray = frameRates.copyOf()

    @JvmStatic fun calibrationRates(highRate: Boolean): IntArray =
        (if (highRate) highRates else basicRates).copyOf()

    @JvmStatic fun normalize(value: Int): Int = if (value in frameRates) value else DEFAULT_FPS

    @JvmStatic fun read(context: Context): Int = try {
        normalize(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getInt(FRAME_RATE_KEY, DEFAULT_FPS))
    } catch (_: ClassCastException) {
        DEFAULT_FPS
    }

    @JvmStatic fun save(context: Context, value: Int) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putInt(FRAME_RATE_KEY, normalize(value)).apply()
    }
}
