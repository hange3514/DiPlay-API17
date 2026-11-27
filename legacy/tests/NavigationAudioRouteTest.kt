package local.headunit.diplay

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAudioRouteTest {
    @Test fun auditedBydRouteKeepsIndependentAmplifierMix() {
        val route = candidate(field = "STREAM_MUSIC_SECOND", stream = 10, audited = 10, alias = 3)
        assertEquals(10, route.stream())
        assertTrue(route.usesAuditedStream())
    }

    @Test fun explicitlyNamedStreamMustBeDistinctAndHaveReadableVolume() {
        assertEquals(14, candidate().stream())
        assertEquals(AudioManager.STREAM_MUSIC, candidate(stream = 3).stream())
        assertEquals(AudioManager.STREAM_MUSIC, candidate(reserved = true).stream())
        assertEquals(AudioManager.STREAM_MUSIC, candidate(max = 0).stream())
        assertEquals(AudioManager.STREAM_MUSIC, candidate(volume = 13, max = 12).stream())
    }

    @Test fun knownSharedAliasUsesMedia() {
        assertEquals(AudioManager.STREAM_MUSIC, candidate(alias = 3).stream())
        assertEquals(14, candidate(alias = 14).stream())
    }

    @Test fun ambiguousSecondaryFieldRequiresBydNavigationService() {
        val field = "STREAM_MUSIC_SECOND"
        assertEquals(AudioManager.STREAM_MUSIC, candidate(field = field, stream = 10).stream())
        assertEquals(10, candidate(field = field, stream = 10, alias = 3, bydService = true).stream())
    }

    @Test fun unknownPlatformUsesMedia() {
        assertEquals(AudioManager.STREAM_MUSIC, candidate(field = "none", stream = -1).stream())
    }

    @Test fun rejectedTrackFallsBackForRemainderOfProjection() {
        val route = candidate(field = "STREAM_MUSIC_SECOND", stream = 10, audited = 10)
        assertFalse(route.fallback(8, "initialize", null))
        assertEquals(10, route.stream())
        assertTrue(route.fallback(10, "initialize", IllegalArgumentException()))
        assertEquals(AudioManager.STREAM_MUSIC, route.stream())
        assertFalse(route.usesAuditedStream())
        assertTrue(route.fallback(10, "play", IllegalStateException()))
        assertFalse(route.fallback(AudioManager.STREAM_MUSIC, "play", IllegalStateException()))
        route.trackReady(10)
        route.trackReady(AudioManager.STREAM_MUSIC)
        assertEquals(AudioManager.STREAM_MUSIC, route.stream())
    }

    private fun candidate(field: String = "STREAM_NAVIGATION", stream: Int = 14, audited: Int = -1,
        reserved: Boolean = false, volume: Int = 8, max: Int = 12, alias: Int = -1,
        bydService: Boolean = false) =
        NavigationAudioRoute(null, audited, field, stream, reserved, volume, max, 8, 15, alias, bydService)
}
