package local.headunit.diplay

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackOptionsTest {
    @Test fun allowsTheVideoRatesAndDefaultsUnsupportedPreferencesTo20() {
        assertArrayEquals(intArrayOf(15, 20, 30, 40, 50, 60), PlaybackOptions.rates())
        assertArrayEquals(intArrayOf(15, 20, 30), PlaybackOptions.calibrationRates(false))
        assertArrayEquals(intArrayOf(30, 40, 50, 60), PlaybackOptions.calibrationRates(true))
        assertEquals(20, PlaybackOptions.DEFAULT_FPS)
        for (rate in PlaybackOptions.rates()) assertEquals(rate, PlaybackOptions.normalize(rate))
        for (unsupported in listOf(-1, 0, 25, 35, 45, 55, 65, Int.MAX_VALUE)) {
            assertEquals(20, PlaybackOptions.normalize(unsupported))
        }
    }

    @Test fun everyRateAdvertisesItsExactCapOnTheNativeCanvas() {
        for (rate in PlaybackOptions.rates()) {
            val display = AirPlayDisplayConfig(ProjectionConfig.WIDTH, ProjectionConfig.HEIGHT, fps = rate)
            val config = AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "320.20", display)
            val advertised = (AirPlayInfoPlist.build(config)["displays"] as List<*>).single() as Map<*, *>
            assertEquals(rate, advertised["maxFPS"])
            assertEquals(1024, advertised["widthPixels"])
            assertEquals(600, advertised["heightPixels"])
            val area = (advertised["viewAreas"] as List<*>).single() as Map<*, *>
            assertEquals(1024, area["widthPixels"])
            assertEquals(600, area["heightPixels"])
            assertEquals(0, area["originXPixels"])
            assertEquals(0, area["originYPixels"])
        }
    }
}
