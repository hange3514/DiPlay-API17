package local.headunit.diplay

import java.time.Instant
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverySystemEvidenceTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun closesAfterManualStopDoNotEraseTheOriginalDrop() {
        val first = JSONObject().put("runtimePhase", "first_frame").put("eventElapsedMs", 100)
        val closing = JSONObject().put("source", "ap_broadcast").put("runtimePhase", "projection_ap_disabling")
            .put("eventElapsedMs", 302_000).put("tetherLastError", 8)
        val failure = JSONObject().put("runtimePhase", "failure_wireless_ip_reconnect").put("eventElapsedMs", 306_000)
        val replacement = JSONObject().put("runtimePhase", "first_frame").put("eventElapsedMs", 316_000)
        val stop = JSONObject().put("runtimePhase", "ui_stop_requested").put("eventElapsedMs", 321_000)
        val laterClose = JSONObject().put("source", "ap_broadcast").put("apState", 11).put("eventElapsedMs", 338_000)
        val noisy = List(30) { JSONObject().put("source", "hotspot_followup").put("eventElapsedMs", 340_000 + it) }
        val input = (listOf(first, closing, failure, replacement, stop, laterClose) + noisy).toMutableList()

        val retained = JSONArray(HotspotEvidence.trimEvidence(input, 8, 1600))
        val offsets = List(retained.length()) { retained.getJSONObject(it).getInt("eventElapsedMs") }
        assertEquals(listOf(100, 302_000, 306_000, 316_000, 321_000, 338_000, 340_028, 340_029), offsets)
        assertEquals(8, retained.getJSONObject(1).getInt("tetherLastError"))
    }

    @Test fun matchesOnlyKnownSystemMarkersNearRecovery() {
        val wall = Instant.parse("2026-11-23T18:16:32Z").toEpochMilli()
        val text = """
            11-23 18:12:33.000  400  411 W Watchdog: *** WATCHDOG KILLING SYSTEM PROCESS: old.Monitor
            11-23 18:16:19.000  500  511 E AndroidRuntime: FATAL EXCEPTION: main
            11-23 18:16:20.000  500  511 W Watchdog: Debugger connected: Watchdog is *not* killing the system process
            11-23 18:16:29.300  500  511 W Watchdog: *** WATCHDOG KILLING SYSTEM PROCESS: com.android.server.WifiMonitor
            11-23 18:16:29.400  200  200 I Zygote  : Exit zygote because system server (500) has terminated
            11-23 18:16:30.000    1    1 I init    : service 'zygote' killed by signal 9
            11-23 18:16:44.000  700  711 I DEBUG   : pid: 700, tid: 711, name: crash  >>> system_server <<<
        """.trimIndent()

        val summary = RecoverySystemEvidence.summarizeLog(text, wall, utc)
        assertEquals(
            "systemLog_watchdog_ms=-2700 systemLog_zygote_exit_ms=-2600 " +
                "systemLog_init_zygote_exit_ms=-2000 watchdogMonitor=com.android.server.WifiMonitor",
            summary,
        )
    }

    @Test fun capturesExceptionTypeOnlyFromTheSameProcessAndThread() {
        val wall = Instant.parse("2027-01-01T00:00:00Z").toEpochMilli()
        val text = """
            12-31 23:59:58.911  500  511 E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: main
            12-31 23:59:58.912  501  512 E AndroidRuntime: java.io.IOException: wrong process
            12-31 23:59:58.913  500  511 E AndroidRuntime: java.lang.IllegalStateException: arbitrary private text
            01-01 00:00:00.001  700  711 I DEBUG: pid: 700, tid: 711, name: Binder  >>> system_server <<<
        """.trimIndent()

        val summary = RecoverySystemEvidence.summarizeLog(text, wall, utc)
        assertTrue(summary.contains("systemLog_java_fatal_ms=-1089"))
        assertTrue(summary.contains("systemLog_debuggerd_system_server_ms=1"))
        assertTrue(summary.contains("systemLogJavaFatalType=java.lang.IllegalStateException"))
        assertFalse(summary.contains("IOException"))
        assertFalse(summary.contains("private text"))
    }
}
