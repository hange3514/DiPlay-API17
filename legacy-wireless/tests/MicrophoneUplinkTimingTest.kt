package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicrophoneUplinkTimingTest {
    @Test
    fun distinguishesDelayedCaptureFromFastEncoderAndUdp() {
        val timing = MicrophoneUplinkTiming(20)
        timing.onRead(ms(1), ms(20), 960)
        timing.onFrame(ms(21), ms(1))
        timing.onSend(ms(22), ms(1), 100_000, 50_000)
        timing.onRead(ms(22), ms(74), 960)
        timing.onFrame(ms(75), ms(1))
        timing.onSend(ms(76), ms(1), 110_000, 90_000)
        timing.onRead(ms(76), ms(76), 960)
        timing.onFrame(ms(77), ms(1))
        timing.onSend(ms(78), ms(1), 100_000, 50_000)

        val fields = values(timing.finish(ms(80)))
        assertEquals(54L, fields["micReadGapMs"])
        assertEquals(54L, fields["micCaptureGapMs"])
        assertEquals(54L, fields["maxSendGapMs"])
        assertEquals(52_000L, fields["micReadMaxUs"])
        assertEquals(1_000L, fields["micEncodeMaxUs"])
        assertEquals(110L, fields["micSealMaxUs"])
        assertEquals(90L, fields["micSendMaxUs"])
        assertEquals(1L, fields["micBurstPackets"])
        assertEquals(1L, fields["micLatePackets"])
        assertEquals(2_880L, fields["micReadFrames"])
    }

    @Test
    fun collectsReadAndSendFailuresInAShortWindow() {
        val timing = MicrophoneUplinkTiming(20)
        assertNull(timing.finish(ms(0)))
        timing.onRead(ms(1), ms(10), 0)
        timing.onRead(ms(10), ms(11), -32)
        timing.onEncodeFailure(ms(15), ms(2))
        timing.onQueue(depth = 3, droppedFrames = 2)
        timing.onQueueUnderrun(ms(17))

        assertNull(timing.reportIfDue(ms(18)))
        val fields = values(timing.finish(ms(18)))
        assertEquals(1L, fields["micPartialWindow"])
        assertEquals(0L, fields["packets"])
        assertEquals(2L, fields["micReadCalls"])
        assertEquals(1L, fields["micReadEmpty"])
        assertEquals(1L, fields["micReadErrors"])
        assertEquals(1L, fields["micEncodeErrors"])
        assertEquals(3L, fields["micQueueMaxDepth"])
        assertEquals(2L, fields["micQueueDropped"])
        assertEquals(1L, fields["micQueueUnderruns"])
        assertNull(timing.finish(ms(19)))
    }

    @Test
    fun intervalCrossingAReportBoundaryStillCounts() {
        val timing = MicrophoneUplinkTiming(20, windowNs = ms(100))
        timing.onRead(ms(0), ms(10), 960)
        timing.onSend(ms(99), 0, 0, 0)
        assertNull(timing.reportIfDue(ms(99)))
        val first = values(timing.reportIfDue(ms(100)))
        assertEquals(1L, first["packets"])
        assertEquals(0L, first["micPartialWindow"])
        timing.onSend(ms(147), 0, 0, 0)

        val second = values(timing.finish(ms(150)))
        assertEquals(1L, second["packets"])
        assertEquals(48L, second["maxSendGapMs"])
        assertEquals(0L, second["micReadCalls"])
    }

    @Test
    fun associatesCpuTimeWithTheSlowestWallClockFrame() {
        val timing = MicrophoneUplinkTiming(20)
        timing.onSend(ms(50), ms(45), 0, 0, ms(42))
        timing.onSend(ms(150), ms(100), 0, 0, ms(12))

        val report = values(timing.finish(ms(160)))
        assertEquals(100_000L, report["micEncodeMaxUs"])
        assertEquals(12_000L, report["micEncodeCpuAtMaxUs"])
        assertEquals(42_000L, report["micEncodeCpuMaxUs"])

        timing.onEncodeFailure(ms(180), ms(9))
        val next = values(timing.finish(ms(181)))
        assertEquals(-1L, next["micEncodeCpuAtMaxUs"])
        assertEquals(-1L, next["micEncodeCpuMaxUs"])
    }

    private fun values(report: String?): Map<String, Long> =
        checkNotNull(report).split(' ').associate {
            it.substringBefore('=') to it.substringAfter('=').toLong()
        }

    private fun ms(value: Long): Long = value * 1_000_000
}
