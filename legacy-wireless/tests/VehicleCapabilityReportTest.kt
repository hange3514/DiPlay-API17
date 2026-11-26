package local.headunit.diplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleCapabilityReportTest {
    @Test fun retainsProbeStagesWithoutUnapprovedFields() {
        val summaries = linkedMapOf(
            "vehicle_signals" to "gpsState=fresh_fix gpsFixes=9 wheelSpeed=unverified tractionSoc=unverified",
            "navigation" to "navigationLink=present routeUpdates=15 maneuverUpdates=3",
            "cluster_link" to "navigationLink=present navAccessibleReceivers=1 factoryCluster=shown",
            "playback" to "freescaleAvc=true hevcCount=0 physicalChannels=unknown",
            "siri" to "endpointSetups=1 mainMicRequested=1 auxInLabelSeen=false",
        )
        val rows = summaries.entries.mapIndexed { i, (stage, summary) ->
            DiagnosticLog.Entry(JSONObject().put("id", "vehicle-$i").put("time", 10_000L + i)
                .put("event", "VEHICLE_CAPABILITY_CHECK " + JSONObject()
                    .put("stage", stage).put("outcome", "timeout_complete").put("durationMs", 60000)
                    .put("readOnly", true).put("diagnosticSummary", summary).put("latitude", 12.0)))
        }
        val events = filterEvents(rows)
        assertEquals(summaries.size, events.length())
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            assertEquals("VEHICLE_CAPABILITY_CHECK", event.getString("type"))
            val data = event.getJSONObject("data")
            assertEquals(summaries.getValue(data.getString("stage")), data.getString("diagnosticSummary"))
            assertEquals(60000, data.getInt("durationMs"))
            assertEquals(true, data.getBoolean("readOnly"))
            assertFalse(data.has("latitude"))
        }
    }

    @Test fun retainsCompleteCanVectorsAndLightTimeThroughReportFiltering() {
        val capture = CanBroadcastCapture()
        val fullFrame = IntArray(64) { Int.MIN_VALUE }
        fullFrame[1] = -1
        capture.observe(1200, fullFrame)
        fullFrame[1] = 255
        capture.mark(1500, "MODE_NIGHT")
        capture.observe(1700, fullFrame)
        capture.observe(1850, intArrayOf(464, -128, 255, 0))
        capture.finishBucket()
        val samples = capture.sampleRows()
        assertEquals(3, samples.size)
        assertTrue(samples[0].contains("tMs=1200 seq=1 id=0x80000000 payloadWords=63 raw=[80000000,ffffffff,"))
        assertTrue(samples[1].contains("tMs=1700 seq=2 id=0x80000000 payloadWords=63 raw=[80000000,ff,"))
        assertTrue(samples[2].endsWith("payloadWords=3 raw=[1d0,ffffff80,ff,0]"))
        assertTrue(capture.contextRows().single().contains("tMs=1500 signal=MODE_NIGHT"))
        val summaries = samples + capture.contextRows()
        val rows = summaries.mapIndexed { index, summary ->
            assertTrue(summary.length <= 768)
            DiagnosticLog.Entry(JSONObject().put("id", "can-$index").put("time", 10000L + index)
                .put("event", "VEHICLE_CAN_EVIDENCE " + JSONObject()
                    .put("stage", if (index < samples.size) "can_sample" else "can_context")
                    .put("diagnosticSummary", summary)))
        }
        val events = filterEvents(rows)
        assertEquals(summaries.size, events.length())
        for (i in summaries.indices) {
            assertEquals(summaries[i], events.getJSONObject(i).getJSONObject("data").getString("diagnosticSummary"))
        }
    }

    @Test fun preservesLateIdsAndRareChangesAlongsideContinuousTraffic() {
        val capture = CanBroadcastCapture()
        repeat(20) { id -> capture.observe(0, intArrayOf(id, 0)) }
        repeat(20) { id -> capture.observe(5, intArrayOf(id, 0)) }
        repeat(8) { id -> capture.observe(100, intArrayOf(id, 1)) }
        repeat(8) { id -> capture.observe(3100, intArrayOf(id, 2)) }
        capture.observe(3200, intArrayOf(19, 3))
        capture.observe(58000, intArrayOf(73, 1, 2))
        capture.observe(59100, intArrayOf(73, 1, 3))
        capture.observe(59101, null)
        capture.finishBucket()
        val samples = capture.sampleRows()
        assertTrue(samples.any { it.contains("tMs=3200 ") && it.endsWith("raw=[13,3]") })
        assertTrue(samples.any { it.contains("tMs=59100 ") && it.endsWith("raw=[49,1,3]") })
        assertTrue(capture.summary().contains("canInvalid=1"))
        assertEquals(59, capture.observed())
        assertEquals(38, capture.saved())
    }

    @Test fun reservesRoomForAFullCanCaptureEvenWithLaterConnectionLogs() {
        val capture = CanBroadcastCapture()
        repeat(96) { id ->
            capture.observe(0, IntArray(64) { if (it == 0) Int.MIN_VALUE + id else Int.MIN_VALUE })
        }
        repeat(20) { bucket -> repeat(8) { id ->
            capture.observe(100L + bucket * 3000, IntArray(64) { if (it == 0) Int.MIN_VALUE + id else -1 - bucket })
        } }
        repeat(32) { capture.mark(it * 1750L, "MODE_NIGHT") }
        capture.finishBucket()
        val samples = capture.sampleRows()
        assertEquals(256, samples.size)
        val evidence = (samples + capture.contextRows() + capture.summary()).mapIndexed { i, summary ->
            DiagnosticLog.Entry(JSONObject().put("id", "evidence-$i").put("time", 100000L + i)
                .put("event", "VEHICLE_CAN_EVIDENCE " + JSONObject().put("stage", "can_sample")
                    .put("durationMs", 60000).put("outcome", "timeout_complete").put("readOnly", true)
                    .put("diagnosticSummary", summary)))
        }
        val later = List(1600) { i -> DiagnosticLog.Entry(JSONObject().put("id", "later-$i").put("time", 101000L + i)
            .put("event", "HYBRID_MDNS " + JSONObject().put("diagnosticSummary", "a".repeat(700)))) }
        val events = filterEvents(evidence + later)
        val retained = (0 until events.length()).map { events.getJSONObject(it) }.filter {
            it.getString("type") == "VEHICLE_CAN_EVIDENCE"
        }.map { it.getJSONObject("data").getString("diagnosticSummary") }
        assertEquals(samples + capture.contextRows() + capture.summary(), retained)
    }

    private fun filterEvents(rows: List<DiagnosticLog.Entry>): JSONArray {
        val filter = CompatibilityTools::class.java.getDeclaredMethod("filterEvents", List::class.java)
        filter.isAccessible = true
        val filtered = filter.invoke(null, rows)
        val field = filtered.javaClass.getDeclaredField("events")
        field.isAccessible = true
        return field.get(filtered) as JSONArray
    }

    @Test fun distinguishesAConfirmedOutputFromNoPromptOrNoTest() {
        assertEquals("shown", VehicleCapabilityCheck.factoryClusterObservation(1))
        assertEquals("no_prompt_during_guidance", VehicleCapabilityCheck.factoryClusterObservation(2))
        assertEquals("not_tested", VehicleCapabilityCheck.factoryClusterObservation(0))
        assertEquals("not_tested", VehicleCapabilityCheck.factoryClusterObservation(-1))
    }
}
