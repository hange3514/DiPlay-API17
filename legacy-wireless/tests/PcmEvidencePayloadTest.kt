package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmEvidencePayloadTest {
    @Test
    fun waveformEvidenceReadsOnlyTheRtpPayload() {
        val pcm = byteArrayOf(0, 0, 1, -1, 2, 3)
        val header = ByteArray(12) { i -> (127 - i).toByte() }
        val separate = PcmEvidence().apply { observeByteOrderCandidates(pcm) }
        val inPacket = PcmEvidence().apply { observeByteOrderCandidates(header + pcm, header.size) }
        assertEquals(separate.byteOrderCandidateSummary(), inPacket.byteOrderCandidateSummary())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAPartialSample() {
        PcmEvidence().observeByteOrderCandidates(ByteArray(13), 12)
    }
}
