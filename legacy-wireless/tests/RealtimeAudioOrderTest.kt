package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeAudioOrderTest {
    @Test fun continuousPlaybackAndSequenceWrapUseTheDirectPath() {
        val stream = Stream()
        stream.offer(65535, sample = Int.MAX_VALUE - 50)
        stream.offer(0, sample = Int.MIN_VALUE + 50)
        stream.offer(1, sample = Int.MIN_VALUE + 101)
        assertEquals(listOf(65535, 0, 1), List(3) { stream.poll() })
        assertEquals(0, stream.order.bufferedFrames())
        assertTrue(stream.order.summary().contains("rtpResets=0"))
    }

    @Test fun reordersFuturePacketsAcrossSequenceWrapWithoutAddingFrames() {
        val stream = Stream()
        stream.offer(65534, sample = -1920)
        assertEquals(65534, stream.poll())
        stream.offer(0, sample = 0)
        stream.offer(1, sample = 960)
        assertNull(stream.poll())
        assertEquals(1920, stream.order.bufferedFrames())

        stream.millis = 17
        stream.offer(65535, sample = -960)
        assertEquals(listOf(65535, 0, 1), List(3) { stream.poll() })
        assertEquals(0, stream.order.bufferedFrames())
        assertTrue(stream.order.summary().contains("rtpRecoveredGaps=1"))
    }

    @Test fun skipsMissingPacketAtDeadlineAndDropsTheLateArrival() {
        val stream = Stream()
        stream.offer(40)
        assertEquals(40, stream.poll())
        stream.offer(42)
        assertNull(stream.poll())
        stream.millis = 23
        assertNull(stream.poll())
        stream.millis = 25
        assertEquals(42, stream.poll())
        stream.offer(41)
        stream.offer(42)
        stream.offer(43)
        assertEquals(43, stream.poll())
        assertTrue(stream.order.summary().contains("rtpSkippedPackets=1"))
        assertTrue(stream.order.summary().contains("rtpDuplicateOrLate=2"))
    }

    @Test fun finishingAnEarlierGapKeepsTheLaterGapsOwnDeadline() {
        val stream = Stream()
        stream.offer(100)
        assertEquals(100, stream.poll())
        stream.offer(102)
        assertNull(stream.poll())

        stream.millis = 23
        stream.offer(104)
        assertNull(stream.poll())
        stream.millis = 24
        stream.offer(101)
        assertEquals(101, stream.poll())
        assertEquals(102, stream.poll())

        // The old gap expired at 25 ms. Packet 104 only demonstrated the new one at 23 ms.
        stream.millis = 25
        assertNull(stream.poll())
        stream.millis = 28
        stream.offer(103)
        assertEquals(103, stream.poll())
        assertEquals(104, stream.poll())
        assertTrue(stream.order.summary().contains("rtpSkippedPackets=0"))
    }

    @Test fun voiceReordersAShortBurstAndSkipsAtItsOwnDeadline() {
        val stream = Stream(clockRate = 16_000, gapWaitMs = 12)
        stream.offer(10, sample = 3_200)
        assertEquals(10, stream.poll())
        stream.offer(12, sample = 3_840)
        stream.offer(12, sample = 3_840)
        assertNull(stream.poll())
        stream.millis = 11
        stream.offer(11, sample = 3_520)
        assertEquals(listOf(11, 12), List(2) { stream.poll() })

        stream.offer(14, sample = 4_480)
        assertNull(stream.poll())
        stream.millis = 22
        assertNull(stream.poll())
        stream.millis = 23
        assertEquals(14, stream.poll())
        stream.offer(13, sample = 4_160)
        stream.offer(15, sample = 4_800)
        assertEquals(15, stream.poll())
        assertTrue(stream.order.summary().contains("rtpRecoveredGaps=1"))
        assertTrue(stream.order.summary().contains("rtpDuplicateOrLate=2"))
        assertTrue(stream.order.summary().contains("rtpSkippedPackets=1"))
    }

    @Test fun aQuietIntervalBetweenPromptsDoesNotResetAContinuousSequence() {
        val stream = Stream(clockRate = 16_000, gapWaitMs = 12)
        stream.offer(10, sample = 3_200)
        assertEquals(10, stream.poll())
        stream.millis = 10_000
        stream.offer(11, sample = 163_200)
        assertEquals(11, stream.poll())
        assertEquals(0L, stream.order.generation)
    }

    @Test fun searchesAlreadyQueuedPacketsEvenWhenWaitingIsDisabled() {
        val stream = Stream()
        stream.offer(80)
        assertEquals(80, stream.poll())
        stream.offer(82)
        stream.offer(81)
        assertEquals(81, stream.poll(allowGapWait = false))
        assertEquals(82, stream.poll(allowGapWait = false))

        stream.offer(84)
        assertEquals(84, stream.poll(allowGapWait = false))
        assertTrue(stream.order.summary().contains("rtpSkippedPackets=1"))
    }

    @Test fun duplicateInPendingDoesNotCountTowardsBufferedDuration() {
        val stream = Stream()
        stream.offer(30)
        assertEquals(30, stream.poll())
        stream.offer(32)
        stream.offer(32)
        assertNull(stream.poll())
        assertEquals(960, stream.order.bufferedFrames())
        assertTrue(stream.order.summary().contains("rtpDuplicateOrLate=1"))
    }

    @Test fun prefillDoesNotCountDuplicatesOrRetiredSourceAsPlayable() {
        val stream = Stream()
        stream.offer(30, ssrc = 1)
        assertEquals(30, stream.poll())
        stream.offer(600, ssrc = 2)
        val first = checkNotNull(stream.pollPacket())

        repeat(12) {
            stream.offer(31 + it, ssrc = 1)
            stream.offer(600, ssrc = 2)
        }
        assertEquals(960, stream.order.awaitPrefill(first, 22 * 960, 0))

        stream.offer(601, ssrc = 2)
        stream.offer(601, ssrc = 2)
        assertEquals(2 * 960, stream.order.awaitPrefill(first, 22 * 960, 0))
    }

    @Test fun prefillCountsPendingAndFifoOverlapOnlyOnceAcrossRtpWrap() {
        val stream = Stream()
        stream.offer(65534, sample = Int.MAX_VALUE - 1200)
        assertEquals(65534, stream.poll())
        stream.offer(0, sample = Int.MIN_VALUE + 719)
        assertNull(stream.poll())
        stream.offer(65535, sample = Int.MAX_VALUE - 240)
        val first = checkNotNull(stream.pollPacket())
        stream.offer(0, sample = Int.MIN_VALUE + 719)
        stream.offer(1, sample = Int.MIN_VALUE + 1679)
        assertEquals(3 * 960, stream.order.awaitPrefill(first, 3 * 960, 0))
        assertEquals(listOf(0, 1), List(2) { stream.poll() })
    }

    @Test fun prefillReturnsControlWhenAnotherSourceStartsWithoutConsumingItsPacket() {
        val stream = Stream()
        stream.offer(2, ssrc = 1)
        val first = checkNotNull(stream.pollPacket())
        stream.offer(620, ssrc = 2)
        assertEquals(0, stream.order.awaitPrefill(first, 22 * 960, 0))
        assertEquals(0L, stream.order.generation)
        assertEquals(620, stream.poll())
        assertEquals(1L, stream.order.generation)
    }

    @Test fun neverPlaysAnExpiredPendingPacket() {
        val stream = Stream()
        stream.offer(30)
        assertEquals(30, stream.poll())
        stream.offer(32)
        assertNull(stream.poll())
        stream.millis = 951
        assertEquals(0, stream.order.bufferedFrames())
        assertNull(stream.poll(allowGapWait = false))
        assertTrue(stream.order.summary().contains("rtpExpiredPackets=1"))
    }

    @Test fun aNewSsrcStartsImmediatelyAndIgnoresInFlightPacketsFromTheOldSource() {
        val stream = Stream()
        stream.offer(30, ssrc = 1)
        assertEquals(30, stream.poll())
        stream.offer(32, ssrc = 1)
        assertNull(stream.poll())
        stream.offer(600, ssrc = 2)
        assertEquals(600, stream.poll())
        stream.offer(31, ssrc = 1)
        stream.offer(601, ssrc = 2)
        assertEquals(601, stream.poll())
        assertEquals(0, stream.order.bufferedFrames())
        assertTrue(stream.order.summary().contains("rtpResets=1"))
    }

    @Test fun delaysBeyondTheReorderWindowCannotBringBackARetiredSsrc() {
        val stream = Stream()
        stream.offer(30, ssrc = 1)
        assertEquals(30, stream.poll())
        stream.offer(600, ssrc = 2)
        assertEquals(600, stream.poll())
        assertEquals(1L, stream.order.generation)

        stream.millis = 26
        stream.offer(31, ssrc = 1)
        stream.offer(601, ssrc = 2)
        assertEquals(601, stream.poll())

        stream.millis = 950
        stream.offer(32, ssrc = 1)
        stream.offer(602, ssrc = 2)
        assertEquals(602, stream.poll())
        assertEquals(1L, stream.order.generation)
    }

    @Test fun aSequenceRestartDoesNotDiscardTheRestOfTheNewStream() {
        val stream = Stream()
        stream.offer(8295, sample = 1000)
        assertEquals(8295, stream.poll())
        stream.offer(3, sample = 10000)
        stream.offer(4, sample = 10960)
        assertEquals(3, stream.poll())
        assertEquals(4, stream.poll())
        assertTrue(stream.order.summary().contains("rtpResets=1"))
    }

    @Test fun aSmallBackwardSequenceRestartRequiresTwoPacketsOnTheNewTimeline() {
        val stream = Stream(clockRate = 16000)
        for (sequence in 480..500) {
            stream.offer(sequence, sample = sequence * 320)
            assertEquals(sequence, stream.poll())
        }
        stream.offer(492, sample = 200)
        assertNull(stream.poll())
        assertEquals(0L, stream.order.generation)

        stream.millis = 20
        stream.offer(493, sample = 520)
        assertEquals(492, stream.poll())
        assertEquals(1L, stream.order.generation)
        assertEquals(493, stream.poll())
        assertEquals(1L, stream.order.generation)
    }

    @Test fun latePreviouslyPlayedPacketsCannotCorroborateASeek() {
        val stream = Stream()
        for (sequence in 20..25) {
            stream.offer(sequence)
            assertEquals(sequence, stream.poll())
        }
        stream.offer(22)
        stream.offer(23)
        stream.offer(24)
        stream.offer(26)
        assertEquals(26, stream.poll())
        assertEquals(0L, stream.order.generation)
    }

    @Test fun forwardSequenceSkipWithBackwardTimestampNeedsACoherentSuccessor() {
        val stream = Stream()
        stream.offer(100, sample = 480000)
        assertEquals(100, stream.poll())
        stream.offer(104, sample = 5000)
        assertNull(stream.poll())

        // Both packets were received, but in reverse order around the seek.
        stream.offer(103, sample = 4040)
        assertEquals(103, stream.poll())
        assertEquals(1L, stream.order.generation)
        assertEquals(104, stream.poll())
    }

    @Test fun aSeekWithUnchangedSsrcDoesNotDeliverHeldPacketsFromTheOldTimeline() {
        val stream = Stream()
        stream.offer(45, sample = 480000)
        assertEquals(45, stream.poll())
        stream.offer(47, sample = 481920)
        assertNull(stream.poll())
        stream.offer(46, sample = 6000)
        assertEquals(46, stream.poll())
        assertEquals(0, stream.order.bufferedFrames())
        assertNull(stream.poll())
        stream.offer(47, sample = 6960)
        assertEquals(47, stream.poll())
    }

    @Test fun checksRunwayOnlyWhenHeldPacketsWouldActuallyWait() {
        val stream = Stream()
        var runwayChecks = 0
        stream.offer(10)
        stream.offer(11)
        assertEquals(9600, stream.order.poll(0) { runwayChecks++; true }?.sample)
        assertEquals(10560, stream.order.poll(0) { runwayChecks++; true }?.sample)
        assertNull(stream.order.poll(0) { runwayChecks++; true })
        assertEquals(0, runwayChecks)

        stream.offer(13)
        assertNull(stream.order.poll(0) { runwayChecks++; true })
        assertEquals(1, runwayChecks)
    }

    @Test fun manyMissingPacketsDoNotBuildAnUnboundedHoldingQueue() {
        val stream = Stream()
        stream.offer(10)
        assertEquals(10, stream.poll())
        (12..19).forEach { stream.offer(it) }
        assertEquals(12, stream.poll())
        assertEquals(7 * 960, stream.order.bufferedFrames())
        assertEquals((13..19).toList(), List(7) { stream.poll() })
    }

    @Test fun saturationRecoversAlreadyQueuedExpectedPacketWithoutAddingWait() {
        val stream = Stream()
        stream.offer(0)
        assertEquals(0, stream.poll())
        (2..9).forEach { stream.offer(it) }
        stream.offer(1)
        assertEquals((1..9).toList(), List(9) { stream.poll(allowGapWait = false) })
        assertEquals(0, stream.order.bufferedFrames())
        assertTrue(stream.order.summary().contains("rtpSkippedPackets=0"))
    }

    @Test fun saturationDoesNotSearchPastAnotherSource() {
        val stream = Stream()
        stream.offer(0)
        assertEquals(0, stream.poll())
        (2..9).forEach { stream.offer(it) }
        stream.offer(20, ssrc = 2)
        stream.offer(1)
        assertEquals((2..9).toList(), List(8) { stream.poll() })
        assertEquals(20, stream.poll())
        assertNull(stream.poll())
        assertEquals(1L, stream.order.generation)
    }

    private class Stream(clockRate: Int = 48000, gapWaitMs: Long? = null) {
        var millis = 0L
        private val queue = AudioInput(capacity = 32, maxAgeMs = 950) { millis * 1_000_000L }
        val order = if (gapWaitMs == null) RealtimeAudioOrder(queue, clockRate) { millis * 1_000_000L }
            else RealtimeAudioOrder(queue, clockRate, gapWaitMs) { millis * 1_000_000L }

        fun offer(sequence: Int, sample: Int = sequence * 960, ssrc: Int = 1) {
            val bytes = ByteArray(12)
            bytes[2] = (sequence shr 8).toByte()
            bytes[3] = sequence.toByte()
            bytes[11] = ssrc.toByte()
            queue.offer(bytes, sample, 960)
        }

        fun pollPacket(allowGapWait: Boolean = true): AudioInput.Packet? = order.poll(0) { allowGapWait }

        fun poll(allowGapWait: Boolean = true): Int? = pollPacket(allowGapWait)?.let { packet ->
            ((packet.bytes[2].toInt() and 255) shl 8) or (packet.bytes[3].toInt() and 255)
        }
    }
}
