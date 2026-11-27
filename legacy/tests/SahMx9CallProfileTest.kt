package local.headunit.diplay

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SahMx9CallProfileTest {
    @Test fun reportedFirmwareAndOemServiceMatch() {
        assertTrue(SahMx9CallProfile.matchesBuild(17, "Freescale", "MX9", "V4.2.2.30.180428"))
        assertTrue(SahMx9CallProfile.matchesOem("SAH", 410))
        assertFalse(SahMx9CallProfile.matchesBuild(17, "Freescale", "MX9A0", "V4.2.2.30.180428"))
        assertFalse(SahMx9CallProfile.matchesBuild(17, "Freescale", "MX9", "V4.2.2.30.170830"))
        assertFalse(SahMx9CallProfile.matchesBuild(19, "Freescale", "MX9", "V4.2.2.30.180428"))
        assertFalse(SahMx9CallProfile.matchesOem("unknown", 410))
        assertFalse(SahMx9CallProfile.matchesOem("SAH", 382))
    }

    @Test fun mediaCallWorksIndependentlyOfNavigationStream() {
        val route = LegacyAudioPolicy.selectCallRoute(secondMusic = -1, sahMx9 = true)
        assertEquals(LegacyAudioPolicy.CallRoute.BYD_SAH_MX9_MEDIA, route)
        assertEquals(AudioManager.STREAM_MUSIC, route.outputStream)
        assertFalse(route.platformPhoneStrategy)
        assertFalse(route.maximizeVolume)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, CallAudioFocusPolicy.gain(!route.platformPhoneStrategy))
        assertEquals(AudioManager.STREAM_MUSIC, LegacyAudioPolicy.stream("navigation", -1))
    }

    @Test fun auditedMx9a0RetainsItsCallPolicy() {
        val audited = LegacyAudioPolicy.selectCallRoute(secondMusic = 10, sahMx9 = false)
        assertEquals(LegacyAudioPolicy.CallRoute.BYD_MX9A0_MEDIA, audited)
        assertEquals(AudioManager.STREAM_MUSIC, audited.outputStream)
        assertFalse(audited.platformPhoneStrategy)
        assertTrue(audited.maximizeVolume)
        assertEquals(10, LegacyAudioPolicy.stream("navigation", 10))
    }

    @Test fun unknownFirmwareUsesMediaAtTheDriverSelectedVolume() {
        val fallback = LegacyAudioPolicy.selectCallRoute(secondMusic = -1, sahMx9 = false)
        assertEquals(LegacyAudioPolicy.CallRoute.DEFAULT_MEDIA, fallback)
        assertEquals(AudioManager.STREAM_MUSIC, fallback.outputStream)
        assertFalse(fallback.platformPhoneStrategy)
        assertFalse(fallback.maximizeVolume)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, CallAudioFocusPolicy.gain(!fallback.platformPhoneStrategy))
    }
}
