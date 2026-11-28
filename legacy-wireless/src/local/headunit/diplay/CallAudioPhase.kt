package local.headunit.diplay

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Records only observed call phase changes, never caller metadata. */
internal class CallAudioPhase {
    enum class Stage(val label: String) {
        OUTGOING_SETUP("outgoing_setup"),
        RINGING("ringing"),
        ACTIVE("active"),
        ENDED("ended"),
    }

    @Volatile private var last = Stage.ENDED

    val outgoingSetup: Boolean get() = last == Stage.OUTGOING_SETUP

    @Synchronized
    fun accept(frame: Iap2Frame, record: (Stage) -> Unit) {
        if (frame.messageId != UPDATE_MESSAGE) return
        val status = runCatching { Iap2BodyReader.of(frame).optionalU8(STATUS_PARAMETER) }.getOrNull()
        val stage = when (status) {
            1, 3 -> Stage.OUTGOING_SETUP
            2 -> Stage.RINGING
            4, 5 -> Stage.ACTIVE
            0, 6 -> Stage.ENDED
            else -> return
        }
        if (stage == last) return
        last = stage
        record(stage)
    }

    companion object {
        const val UPDATE_MESSAGE = 0x4155
        private const val STATUS_PARAMETER = 2
    }
}
