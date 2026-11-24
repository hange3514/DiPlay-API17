package local.headunit.diplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridReportPayloadTest {
    @Test fun offsetsSaturateAcrossClockChangesAndOverflow() {
        assertEquals(0L, HybridReportPayload.boundedOffset(1000, 1001))
        assertEquals(31L, HybridReportPayload.boundedOffset(1000, 969))
        assertEquals(86_400_000L, HybridReportPayload.boundedOffset(86_400_001L, 1))
        assertEquals(86_400_000L, HybridReportPayload.boundedOffset(86_400_002L, 1))
        assertEquals(86_400_000L, HybridReportPayload.boundedOffset(Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(0L, HybridReportPayload.boundedOffset(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test fun repairsSavedOffsetsWithoutChangingIdentityOrOtherEventData() {
        val id = "20261123T000000Z-001122aabbcc"
        val data = JSONObject().put("stage", "music_starved")
        val events = JSONArray()
            .put(JSONObject().put("type", "MEDIA").put("offsetMs", 86_400_001).put("data", data))
            .put(JSONObject().put("type", "BOOT_AUTOSTART").put("offsetMs", Long.MIN_VALUE))
            .put(JSONObject().put("type", "USB_BT_STACK").put("offsetMs", 5_000))
        val body = JSONObject().put("clientReportId", id).put("events", events)
        val saved = JSONObject(body.toString())

        assertTrue(HybridReportPayload.repairOffsets(saved))
        assertEquals(id, saved.getString("clientReportId"))
        val repaired = saved.getJSONArray("events")
        assertEquals(3, repaired.length())
        assertEquals("MEDIA", repaired.getJSONObject(0).getString("type"))
        assertEquals("music_starved", repaired.getJSONObject(0).getJSONObject("data").getString("stage"))
        assertEquals(86_400_000L, repaired.getJSONObject(0).getLong("offsetMs"))
        assertEquals(0L, repaired.getJSONObject(1).getLong("offsetMs"))
        assertEquals(5_000L, repaired.getJSONObject(2).getLong("offsetMs"))
        assertFalse(HybridReportPayload.repairOffsets(saved))
    }

    @Test fun doesNotRewriteNonIntegerOffsets() {
        val events = JSONArray()
            .put(JSONObject().put("offsetMs", "86400001"))
            .put(JSONObject().put("offsetMs", 86_400_001.5))
            .put(JSONObject().put("offsetMs", true))
            .put(JSONObject())
        val body = JSONObject().put("events", events)
        val original = body.toString()
        assertFalse(HybridReportPayload.repairOffsets(body))
        assertEquals(original, body.toString())
    }

    @Test fun stripsPythonWhitespaceAndRetainsInternalCharacters() {
        val whitespace = "\t\n\u000b\u000c\r\u001c\u001d\u001e\u001f \u0085\u00a0\u1680" +
            "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a" +
            "\u2028\u2029\u202f\u205f\u3000"
        val issue = "音乐\u00a0卡顿\n车机正常重连"
        assertEquals("", HybridReportPayload.issueText(null))
        assertEquals("", HybridReportPayload.issueText(whitespace))
        assertEquals(issue, HybridReportPayload.issueText(whitespace + issue + whitespace))
        assertEquals("\u200b", HybridReportPayload.issueText(" \u200b "))
    }
}
