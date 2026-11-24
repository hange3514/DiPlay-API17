package local.headunit.diplay

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BootContinuityTest {
    private val oldBoot = "a3757234-0651-4cfe-a381-b6d5472b78ec"
    private val newBoot = "9a9bc48c-2187-4880-a6df-0aeed9a901d7"

    @Test fun kernelRebootTakesPrecedenceOverAClockThatAlreadyCaughtUp() {
        val previous = BootContinuity(oldBoot, "71:19", "60:18")
        val recovered = BootContinuity(newBoot, "71:19", "60:18")
        assertEquals("kernel_boot_changed", recovered.classify(previous, 3_000, 10_000))
    }

    @Test fun daemonAndZygoteLifetimeChangesAreSeparateFromAppRestart() {
        val previous = BootContinuity(oldBoot, "71:19", "60:18")
        assertEquals("system_server_changed", BootContinuity(oldBoot, "71:57", "60:18").classify(previous, 20, 30))
        assertEquals("zygote_changed", BootContinuity(oldBoot, "", "95:89").classify(previous, 20, 30))
        assertEquals("same_kernel_and_system_server", previous.classify(previous, 20, 30))
    }

    @Test fun exposesIndependentProcessChangesOnlyWhenBothSamplesExist() {
        assertEquals(true, BootContinuity.identityChanged("71:19", "71:57"))
        assertEquals(false, BootContinuity.identityChanged("60:18", "60:18"))
        assertNull(BootContinuity.identityChanged("60:18", ""))
        assertNull(BootContinuity.identityChanged("", "60:18"))
    }

    @Test fun legacyAndRestrictedProcfsMarkersDoNotClaimMoreThanTheirEvidence() {
        assertEquals("boot_elapsed_reset", BootContinuity.UNKNOWN.classify(BootContinuity.UNKNOWN, 60_000, 5_000))
        assertEquals("unknown", BootContinuity(oldBoot, "71:19", "60:18").classify(BootContinuity.UNKNOWN, 4_000, 5_000))
        assertEquals("same_kernel_boot", BootContinuity(oldBoot, "", "").classify(BootContinuity(oldBoot, "", ""), 4_000, 5_000))
    }

    @Test fun procSamplerReadsStartTicksAndHandlesSpacesAndParenthesesInComm() {
        val proc = Files.createTempDirectory("diplay-boot-evidence").toFile()
        try {
            write(proc, "sys/kernel/random/boot_id", "$oldBoot\n")
            write(proc, "self/stat", stat(126, "diplay (ui thread)", 60, 89))
            write(proc, "60/stat", stat(60, "zygote", 1, 18))
            write(proc, "71/stat", stat(71, "system_server", 60, 19))
            write(proc, "80/stat", "80 (system_server) truncated")
            val sample = BootContinuity.sample(proc)
            assertEquals(oldBoot, sample.bootId)
            assertEquals("60:18", sample.zygote)
            assertEquals("71:19", sample.systemServer)
        } finally {
            proc.deleteRecursively()
        }
    }

    private fun stat(pid: Int, name: String, parent: Int, startTicks: Long): String {
        val fields = MutableList(50) { "0" }
        fields[0] = "S"
        fields[1] = parent.toString()
        fields[19] = startTicks.toString()
        return "$pid ($name) ${fields.joinToString(" ")}\n"
    }

    private fun write(proc: File, relative: String, value: String) {
        File(proc, relative).apply {
            parentFile.mkdirs()
            writeText(value)
        }
    }
}
