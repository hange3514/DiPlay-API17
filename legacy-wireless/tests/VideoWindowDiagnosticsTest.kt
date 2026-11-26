package local.headunit.diplay

import com.shilapi.xcertplay.airplay.StreamReceiveStats
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoWindowDiagnosticsTest {
    @Test fun separatesBodyReadsAndMeasuresOnlyRelativeSenderTiming() {
        var now = 1_000_000L
        val lines = mutableListOf<String>()
        val stats = StreamReceiveStats("video", lines::add, video = true) { now }
        stats.reading()
        now = 21_000_000L
        stats.videoHeaderReceived()
        now = 31_000_000L
        stats.received(120)
        stats.videoFrame(5_000_000_000L, now)
        stats.processed()

        now = 90_000_000L
        stats.reading()
        now = 145_000_000L
        stats.videoHeaderReceived()
        now = 161_000_000L
        stats.received(180)
        stats.videoFrame(5_066_000_000L, now)
        stats.processed()

        now = 5_001_000_000L
        stats.flush()
        val window = lines.single()
        assertTrue(window.contains("receivedFrames=2 receivedFpsMilli=400 senderGapSamples=1"))
        assertTrue(window.contains("senderGapMaxMs=66 arrivalGapMaxMs=130 deliveryJitterMaxMs=64"))
        assertTrue(window.contains("headerReadMaxMs=55 bodyReadMaxMs=16"))
    }

    @Test fun retainsCappedVideoWindowsAndDropsUnapprovedPayloadFields() {
        val logs = mutableListOf<DiagnosticLog.Entry>()
        fun add(type: String, detail: String) {
            val index = logs.size
            logs.add(DiagnosticLog.Entry(JSONObject().put("id", "video-$index").put("time", 10_000L + index)
                .put("event", "$type " + JSONObject().put("elapsedMs", index).put("detail", detail))))
        }
        repeat(21) {
            add("AIRPLAY", "Receive: video eventType=video_rx_window streamType=110 requestedFps=20 " +
                "windowMs=5000 receivedFrames=90 receivedFpsMilli=18000 senderGapMaxMs=51 ended=false unknown=900")
            add("MEDIA", "video_progress eventType=video_render_window streamType=110 requestedFps=20 " +
                "windowMs=5000 submittedFrames=89 queuePeak=3 decodeMaxMs=7 receiveToSurfaceMaxMs=31 unknown=900")
        }
        add("VIDEO_READY", "ready")
        val filter = CompatibilityTools::class.java.getDeclaredMethod("filterEvents", List::class.java)
        filter.isAccessible = true
        val result = filter.invoke(null, logs)
        val field = result.javaClass.getDeclaredField("events")
        field.isAccessible = true
        val events = field.get(result) as JSONArray
        val data = (0 until events.length()).map { events.getJSONObject(it).getJSONObject("data") }
        val received = data.filter { it.optString("eventType") == "video_rx_window" }
        val rendered = data.filter { it.optString("eventType") == "video_render_window" }

        assertEquals(18, received.size)
        assertEquals(18, rendered.size)
        assertEquals(37, events.length())
        assertEquals(6, received.first().getInt("elapsedMs"))
        assertEquals(20, received.first().getInt("requestedFps"))
        assertTrue(received.first().getString("diagnosticSummary").contains("requestedFps=20"))
        assertTrue(received.first().getString("diagnosticSummary").contains("senderGapMaxMs=51"))
        assertFalse(received.first().getString("diagnosticSummary").contains("unknown"))
        assertEquals(17800, rendered.first().getInt("submittedFpsMilli"))
        assertEquals(31, rendered.first().getInt("receiveToSurfaceMaxMs"))
    }
}
