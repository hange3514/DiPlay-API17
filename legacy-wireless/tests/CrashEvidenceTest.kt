package local.headunit.diplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashEvidenceTest {
    @Test fun repeatedInterruptedLaunchesKeepRecentIncidents() {
        var pending = JSONArray()
        for (id in 1..10) {
            pending = CrashEvidence.appendIncident(pending, JSONObject().put("captureId", id.toString()))
        }
        assertEquals((3..10).toList(), (0 until pending.length()).map {
            pending.getJSONObject(it).getString("captureId").toInt()
        })

        val afterAcknowledgement = CrashEvidence.withoutIncident(pending, 2)
        assertEquals(listOf(3, 4, 6, 7, 8, 9, 10), (0 until afterAcknowledgement.length()).map {
            afterAcknowledgement.getJSONObject(it).getString("captureId").toInt()
        })
        assertEquals(8, pending.length())
    }

    @Test fun durableCheckpointKeepsOnlyTheSixMostRecentStages() {
        var trail = ""
        for (sequence in 1..8) {
            trail = CrashEvidence.appendPhase(trail, "stage_" + sequence, sequence * 100L)
        }
        assertEquals("stage_3:300,stage_4:400,stage_5:500,stage_6:600,stage_7:700,stage_8:800", trail)
    }
}
