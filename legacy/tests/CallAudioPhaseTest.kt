package local.headunit.diplay

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioPhaseTest {
    private fun update(status: Int) = Iap2BodyBuilder.opaque(CallAudioPhase.UPDATE_MESSAGE)
        .string(0, "fixture caller metadata")
        .u8(2, status)
        .build()

    @Test fun deduplicatesTheCallPhaseAcrossControlChannels() {
        val observer = CallAudioPhase()
        val stages = mutableListOf<String>()
        for (status in listOf(0, 1, 1, 3, 4, 4, 5, 6, 0, 2, 4, 0)) {
            observer.accept(update(status)) { stages.add(it.label) }
        }
        assertEquals(listOf("outgoing_setup", "active", "ended", "ringing", "active", "ended"), stages)
    }

    @Test fun ignoresUnrelatedUnknownAndMalformedUpdates() {
        val observer = CallAudioPhase()
        val stages = mutableListOf<String>()
        val malformed = Iap2Frame(CallAudioPhase.UPDATE_MESSAGE, byteArrayOf(0, 8, 0, 2, 3))
        val wrongSize = Iap2BodyBuilder.opaque(CallAudioPhase.UPDATE_MESSAGE).u16(2, 3).build()
        val unrelated = Iap2BodyBuilder.opaque(0x4154).u8(2, 3).build()
        for (frame in listOf(malformed, wrongSize, unrelated, update(127))) {
            observer.accept(frame) { stages.add(it.label) }
        }
        assertTrue(stages.isEmpty())
    }
}
