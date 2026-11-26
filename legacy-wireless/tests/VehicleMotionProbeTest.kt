package local.headunit.diplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleMotionProbeTest {
    @Test fun yawChannelsRequireAnAngularRateLabel() {
        assertTrue(VehicleMotionProbe.isYawRateLabel("BYD ESP Yaw-Rate"))
        assertTrue(VehicleMotionProbe.isYawRateLabel("车身横摆角速度"))
        assertFalse(VehicleMotionProbe.isYawRateLabel("Yaw angle"))
        assertFalse(VehicleMotionProbe.isYawRateLabel("ESP warning"))
    }

    @Test fun retainsRealVectorsWithoutTurningBadDataIntoZeros() {
        val gyro = VehicleMotionProbe.Channel("android_gyro", "rad_s", 3)
        val values = floatArrayOf(-0.031f, 0.4f, -0.2f)
        gyro.observe(11, 11990000L, 3, floatArrayOf(0.001f, 0f, 0f))
        gyro.observe(73, 73990000L, 3, values)
        values[1] = 9f // SensorEvent.values is reused by Android.
        gyro.observe(190, 190990000L, 3, floatArrayOf(0.002f, 0f, 0f))
        gyro.observe(211, 211990000L, 1, floatArrayOf(Float.NaN, 0f, 0f))
        gyro.observe(217, 217990000L, 1, floatArrayOf(0f))
        gyro.observe(59999, 59999990000L, 2, floatArrayOf(0f, -0.25f, 0f))
        gyro.observe(60000, 60000990000L, 2, floatArrayOf(0f, 1f, 0f))
        val rows = arrayListOf<String>()
        gyro.appendRows(rows, "probe-1", true)
        assertEquals(2, rows.size)
        assertTrue(rows[0].endsWith("values=[73,73990000,3,-0.031,0.4,-0.2;59999,59999990000,2,0.0,-0.25,0.0]"))
        assertTrue(rows[1].contains("state=sampled"))
        assertTrue(rows[1].contains("callbacks=6 valid=4 invalid=2 retained=2"))
    }

    @Test fun reportKeepsSameClockAndRawSamplesForBothSources() {
        val capture = CanBroadcastCapture()
        val gyro = VehicleMotionProbe.Channel("android_gyro", "rad_s", 3)
        val esp = VehicleMotionProbe.Channel("vendor_yaw_candidate", "unknown", 1)
        capture.observe(523, intArrayOf(0x11f, 0x0c, 0))
        gyro.observe(524, 524900000L, 3, floatArrayOf(-0.012f, 0.02f, 0.034f))
        esp.observe(540, 540900000L, 2, floatArrayOf(-1.25f))
        val motion = arrayListOf<String>()
        gyro.appendRows(motion, capture.scanId(), true)
        esp.appendRows(motion, capture.scanId(), true)
        val rows = (capture.sampleRows() + motion).mapIndexed { index, summary ->
            DiagnosticLog.Entry(JSONObject().put("id", "motion-$index")
                .put("time", 1234L + index).put("event", "VEHICLE_CAN_EVIDENCE " + JSONObject()
                    .put("stage", if (index == 0) "can_sample" else "motion_sample")
                    .put("diagnosticSummary", summary).put("readOnly", true)))
        }
        val method = CompatibilityTools::class.java.getDeclaredMethod("filterEvents", List::class.java)
        method.isAccessible = true
        val filtered = method.invoke(null, rows)
        val field = filtered.javaClass.getDeclaredField("events")
        field.isAccessible = true
        val events = field.get(filtered) as JSONArray
        assertEquals(rows.size, events.length())
        for (i in 0 until events.length()) {
            val detail = events.getJSONObject(i).getJSONObject("data")
            assertEquals((capture.sampleRows() + motion)[i], detail.getString("diagnosticSummary"))
        }
    }

    @Test fun evenLongMotionVectorsFitTheServerSummaryLimit() {
        val vendor = VehicleMotionProbe.Channel("vendor_yaw_candidate", "unknown", 1)
        val sample = floatArrayOf(-1.17549435e-38f, -3.4028235e38f, -0.000012345678f)
        for (index in 0 until 300) vendor.observe(index * 200L, Long.MIN_VALUE, Int.MIN_VALUE, sample)
        val rows = arrayListOf<String>()
        vendor.appendRows(rows, "a4d7dcc0-f751-47f5-bbc6-be43c39c035d", true)
        assertEquals(44, rows.size)
        assertTrue(rows.all { it.length <= 768 })
        assertTrue(rows.last().contains("retained=300"))
    }
}
