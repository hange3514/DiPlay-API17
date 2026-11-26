package local.headunit.diplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCompatibilityEvidenceTest {
    @Test fun identificationUsesSafeLogEnvelopeAndExplicitSource() {
        val events = JSONArray()
        val identity = JSONObject().put("vehicleCode", "SAH").put("vehicleFamily", "byd_song_dm")
            .put("vehicleSource", "oem_property").put("firmwareDisplay", "BYD Example 4.2.2")
            .put("oemMultimediaVersion", 382).put("vin", "should_not_be_copied")
        CompatibilityEvidence.appendIdentity(events, identity)
        val row = events.getJSONObject(0)
        assertEquals("VEHICLE_IDENTITY", row.getString("type"))
        assertEquals(0, row.getInt("offsetMs"))
        val summary = row.getJSONObject("data").getString("diagnosticSummary")
        assertTrue(summary.contains("vehicleSource=oem_property"))
        assertTrue(summary.contains("firmwareDisplay=BYD_Example_4.2.2"))
        assertTrue(summary.contains("oemMultimediaVersion=382"))
        assertFalse(summary.contains("should_not_be_copied"))
    }

    @Test fun alsaInventoryKeepsPermissionsAndSplitsTablesToServerLimit() {
        val events = JSONArray().put(JSONObject().put("offsetMs", 654))
        val capture = JSONObject().put("path", "/dev/snd/pcmC2D0c").put("source", "proc_pcm")
            .put("exists", true).put("read", true).put("write", false)
        val env = JSONObject().put("nativeProbeLoaded", true).put("pcm", "32 Capture\n".repeat(300))
            .put("captureCandidates", JSONArray().put(capture))
        CompatibilityEvidence.appendAlsaInventory(events, env)
        assertTrue(events.length() > 4)
        for (i in 1 until events.length()) {
            val row = events.getJSONObject(i)
            assertEquals("ALSA_ENVIRONMENT", row.getString("type"))
            assertEquals(654, row.getInt("offsetMs"))
            val value = row.getJSONObject("data").getString("diagnosticSummary")
            assertTrue(value.length <= 768)
            assertTrue(Regex("[A-Za-z0-9_.,:/;=x\\[\\]()+# -]+").matches(value))
        }
        val tail = events.getJSONObject(events.length() - 1).getJSONObject("data")
        assertEquals("alsa_candidate", tail.getString("stage"))
        assertTrue(tail.getString("diagnosticSummary").contains("path=/dev/snd/pcmC2D0c"))
        assertTrue(tail.getString("diagnosticSummary").contains("write=false"))
    }

    @Test fun wheelRegistrationAndCallRoutePreserveObservedFields() {
        val wheel = JSONObject().put("receiverRegistered", true).put("oemServiceVersion", 11)
            .put("oemBroadcastAction", "APP_REV_DATA").put("keyScanCode", 248)
        val audio = JSONObject().put("outputStream", 3).put("activeVolume", 8).put("maxVolume", 15)
            .put("platformPhoneStrategy", false).put("detail", "android_media_route_preserving_current_mcu_source")
        val out = JSONObject()
        CompatibilityEvidence.addRuntimeContext("WHEEL_CONTROL", wheel, out)
        assertTrue(out.getString("diagnosticSummary").contains("receiverRegistered=true"))
        assertTrue(out.getString("diagnosticSummary").contains("keyScanCode=248"))
        CompatibilityEvidence.addRuntimeContext("CALL_AUDIO_ROUTE", audio, out)
        assertTrue(out.getString("diagnosticSummary").contains("outputStream=3"))
        assertTrue(out.getString("diagnosticSummary").contains("activeVolume=8"))
    }
}
