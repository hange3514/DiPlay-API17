package local.headunit.diplay

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCodecInputTest {
    @Test fun blockedSingleSlotPresentsHeldFrameBeforeRetry() {
        val events = mutableListOf<String>()
        var outputHeld = true
        val input = VideoCodecInput.acquire(
            1, 10_000_000L,
            { timeoutUs ->
                events += "input $timeoutUs"
                if (outputHeld) MediaCodec.INFO_TRY_AGAIN_LATER else 0
            },
            {
                events += "render output"
                outputHeld = false
                true
            },
        )
        assertEquals(0, input)
        assertEquals(listOf("input 0", "render output", "input 5000"), events)
    }

    @Test fun noEarlyRenderWhenSingleInputSlotIsAlreadyFree() {
        val events = mutableListOf<String>()
        val input = VideoCodecInput.acquire(
            1, 10_000_000L, { events += "input ${it}"; 0 },
            { error("No pressure, leave output paced") },
        )
        assertEquals(0, input)
        assertEquals(listOf("input 0"), events)
    }

    @Test fun aRetiredOutputDoesNotStartAnotherBlockingInputWait() {
        val waits = mutableListOf<Long>()
        val input = VideoCodecInput.acquire(
            1, 8_000_000L, { waits += it; MediaCodec.INFO_TRY_AGAIN_LATER }, { false },
        )
        assertEquals(MediaCodec.INFO_TRY_AGAIN_LATER, input)
        assertEquals(listOf(0L), waits)
    }

    @Test fun normalAndMultiSlotWaitsAreUnchanged() {
        val waits = mutableListOf<Long>()
        val dequeue: (Long) -> Int = { waits += it; MediaCodec.INFO_TRY_AGAIN_LATER }
        val unexpectedRelease: () -> Boolean = { error("No early render") }
        VideoCodecInput.acquire(2, 2_500_000L, dequeue, unexpectedRelease)
        VideoCodecInput.acquire(2, 10_000_000L, dequeue, unexpectedRelease)
        VideoCodecInput.acquire(1, null, dequeue, unexpectedRelease)
        VideoCodecInput.acquire(1, -1L, dequeue, unexpectedRelease)
        assertEquals(listOf(2500L, 5000L, 5000L, 0L), waits)
    }

    @Test fun surfaceMayStillRetainTheOnlySlotAfterItsBufferIsSubmitted() {
        val events = mutableListOf<String>()
        val input = VideoCodecInput.acquire(
            1, 10_000_000L, { events += "input ${it}"; MediaCodec.INFO_TRY_AGAIN_LATER },
            { events += "render output"; true },
        )
        assertEquals(MediaCodec.INFO_TRY_AGAIN_LATER, input)
        assertEquals(listOf("input 0", "render output", "input 5000"), events)
    }
}
