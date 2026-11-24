package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingAttachmentReportsTest {
    private val older = "20261123T000000Z-000000000001"
    private val newer = "20261123T000100Z-000000000002"
    private val third = "20261123T000200Z-000000000003"

    @Test
    fun appendsToTheLegacyPointerAndDoesNotDuplicateAnAcknowledgedBaseReport() {
        assertEquals(listOf(older), PendingAttachmentReports.read(older))
        val queued = PendingAttachmentReports.append(older, newer)
        assertEquals(listOf(older, newer), PendingAttachmentReports.read(queued))
        assertEquals(queued, PendingAttachmentReports.append(queued, newer))
    }

    @Test
    fun onlyTheCompletedReportIsRemovedWhenNewerUploadsFinishFirst() {
        val queued = PendingAttachmentReports.append("$older\n$newer", third)
        val remaining = PendingAttachmentReports.complete(queued, newer)
        assertEquals(listOf(older, third), PendingAttachmentReports.read(remaining))
        assertEquals(remaining, PendingAttachmentReports.complete(remaining, newer))
        assertEquals("", PendingAttachmentReports.complete(third, third))
    }

    @Test
    fun malformedAndRepeatedPreferenceEntriesDoNotHideOtherReports() {
        assertEquals(listOf(older, newer), PendingAttachmentReports.read("$older\n\nnot an ID\n$older\n$newer"))
    }
}
