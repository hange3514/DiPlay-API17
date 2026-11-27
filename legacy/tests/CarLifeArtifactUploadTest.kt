package local.headunit.diplay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeArtifactUploadTest {
    private val report = "20261126T093000Z-0123456789ab"
    private val other = "20261126T093001Z-fedcba987654"
    private val digest = "ab".repeat(32)

    @Test fun receiptChecksArtifactIdentityAndSize() {
        val kind = "carlife-apk"
        val bytes = 123
        val response = JSONObject().put("kind", kind).put("reportId", report)
            .put("sha256", digest).put("bytes", bytes).toString()
        assertTrue(CarLifeArtifactUpload.confirmed(response, kind, report, digest, bytes))
        assertFalse(CarLifeArtifactUpload.confirmed(response, kind, other, digest, bytes))
        assertFalse(CarLifeArtifactUpload.confirmed(response, "carlife-odex", report, digest, bytes))
        assertFalse(CarLifeArtifactUpload.confirmed(response, kind, report, "00".repeat(32), bytes))
        assertFalse(CarLifeArtifactUpload.confirmed(response, kind, report, digest, bytes + 1))
        assertFalse(CarLifeArtifactUpload.confirmed("invalid JSON", kind, report, digest, bytes))
    }

    @Test fun optionalSidecarAndRequiredApkHaveDifferentRetryRules() {
        assertTrue(CarLifeArtifactUpload.completed("reused", "uploaded"))
        assertTrue(CarLifeArtifactUpload.completed("uploaded", "missing"))
        assertTrue(CarLifeArtifactUpload.completed("uploaded", "optional_unreadable"))
        assertFalse(CarLifeArtifactUpload.completed("uploaded", "timed_out"))
        assertFalse(CarLifeArtifactUpload.completed("receipt_mismatch", "missing"))
        assertFalse(CarLifeArtifactUpload.completed("not_installed", "not_installed"))
    }

    @Test fun completingAnAttachmentOnlyRemovesItsOriginalReport() {
        val saved = PendingAttachmentReports.append("$report\n$report\ninvalid", other)
        assertEquals(listOf(report, other), PendingAttachmentReports.read(saved))
        assertEquals(listOf(other), PendingAttachmentReports.read(PendingAttachmentReports.complete(saved, report)))
    }
}
