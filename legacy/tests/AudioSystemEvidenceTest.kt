package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSystemEvidenceTest {
    @Test fun decodesResolvedOutputDeviceMask() {
        assertEquals("speaker,bluetooth_sco,default", AudioSystemEvidence.deviceNames(0x40000012))
        assertEquals("none_or_unknown", AudioSystemEvidence.deviceNames(0))
    }

    @Test fun unknownDeviceMaskIsNotMistakenForNoRoute() {
        assertEquals("unknown_0x08000000", AudioSystemEvidence.deviceNames(0x08000000))
        assertTrue(AudioSystemEvidence.deviceNames(0x08000002).contains("unknown_0x08000000"))
    }
}
