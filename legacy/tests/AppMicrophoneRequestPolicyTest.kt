package local.headunit.diplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMicrophoneRequestPolicyTest {
    private fun wants(audioType: String, vararg fields: Pair<String, Any?>) =
        AppMicrophoneRequestPolicy.wantsMicrophone(audioType, mapOf(*fields))

    @Test fun callsAndSiriAlwaysRequestMicrophone() {
        assertTrue(wants("telephony"))
        assertTrue(wants("speechrecognition"))
    }

    @Test fun appAudioUsesExplicitInputOrUplinkPort() {
        assertTrue(wants("default", "dataPort" to 50_000))
        assertTrue(wants("compatibility", "dataPort" to 50_000))
        assertTrue(wants("default", "input" to true))
        assertTrue(wants("default", "input" to 1L, "dataPort" to 50_000))
    }

    @Test fun guidanceAndNonCaptureStreamsDoNotOpenMicrophone() {
        assertFalse(wants("default"))
        assertFalse(wants("default", "dataPort" to 0))
        assertFalse(wants("default", "input" to false, "dataPort" to 50_000))
        assertFalse(wants("default", "input" to 0, "dataPort" to 50_000))
        assertFalse(wants("media", "dataPort" to 50_000))
        assertFalse(wants("alert", "input" to true, "dataPort" to 50_000))
    }
}
