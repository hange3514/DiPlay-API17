package local.headunit.diplay

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverySystemEvidenceTest {
    private val utc = TimeZone.getTimeZone("UTC")

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
