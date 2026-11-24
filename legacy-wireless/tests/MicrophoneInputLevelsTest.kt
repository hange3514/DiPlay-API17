package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicrophoneInputLevelsTest {
    @Test
    fun reportsClippingSeparatelyEvenWhenDownmixWouldHideTheRails() {
        val levels = MicrophoneInputLevels(channels = 2, maxFrames = 4)
        levels.observe(pcm(-32768, 0, 32767, 5, -32701, 27, 32700, 4), capturedFrames = 4)

        assertEquals(
            "micInputFrames=4 micInputChannels=2 micInputPeak=32768/27 " +
                "micInputRails=2/0 micInputNearRails=4/0 micInputDc=0/9",
            levels.summary(),
        )
    }

    @Test
    fun respectsCaptureCountAndClosesTheMeasurementAtTheFrameLimit() {
        val levels = MicrophoneInputLevels(channels = 1, maxFrames = 3)
        levels.observe(pcm(-600, -300, 32767), capturedFrames = 2)
        levels.observe(pcm(-300, 32767), capturedFrames = 2)
        levels.observe(pcm(32767), capturedFrames = 1)

        assertEquals(
            "micInputFrames=3 micInputChannels=1 micInputPeak=600 " +
                "micInputRails=0 micInputNearRails=0 micInputDc=-400",
            levels.summary(),
        )
    }

    @Test
    fun ignoresUnfilledBuffersAndPartialFrames() {
        val levels = MicrophoneInputLevels(channels = 2, maxFrames = 20)
        levels.observe(pcm(32767), capturedFrames = 5)
        levels.observe(pcm(32767, 32767), capturedFrames = -1)
        assertNull(levels.summary())
    }

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2) { index ->
        (samples[index / 2] shr ((index % 2) * 8)).toByte()
    }
}
