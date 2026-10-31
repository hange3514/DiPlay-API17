package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneReadRecoveryPolicyTest {
    @Test fun emptyReadsAreThrottledAndRestartAfterOneSecondAtMostThreeTimes() {
        val policy = MicrophoneReadRecoveryPolicy()
        assertEquals(MicrophoneReadRecoveryPolicy.Action.WAIT, policy.onReadResult(0, 10L))
        assertEquals(MicrophoneReadRecoveryPolicy.Action.WAIT, policy.onReadResult(0, 999_999_999L))
        assertEquals(MicrophoneReadRecoveryPolicy.Action.RESTART, policy.onReadResult(0, 1_000_000_010L))
        assertEquals(1, policy.restartAttempts)
        assertEquals(MicrophoneReadRecoveryPolicy.Action.WAIT, policy.onReadResult(0, 1_000_000_011L))
        assertEquals(MicrophoneReadRecoveryPolicy.Action.RESTART, policy.onReadResult(0, 2_000_000_010L))
        assertEquals(MicrophoneReadRecoveryPolicy.Action.RESTART, policy.onReadResult(0, 3_000_000_010L))
        assertEquals(MicrophoneReadRecoveryPolicy.Action.RESTARTS_EXHAUSTED, policy.onReadResult(0, 4_000_000_010L))
        assertEquals(MicrophoneReadRecoveryPolicy.Action.WAIT, policy.onReadResult(0, 5_000_000_010L))
        assertEquals(3, policy.restartAttempts)
    }

    @Test fun positiveReadClearsOnlyTheNoDataTimer() {
        val policy = MicrophoneReadRecoveryPolicy()
        assertEquals(MicrophoneReadRecoveryPolicy.Action.WAIT, policy.onReadResult(0, 100L))
        assertEquals(MicrophoneReadRecoveryPolicy.Action.DATA, policy.onReadResult(320, 900_000_000L))
        assertEquals(MicrophoneReadRecoveryPolicy.Action.WAIT, policy.onReadResult(0, 1_000_000_000L))
        assertEquals(MicrophoneReadRecoveryPolicy.Action.WAIT, policy.onReadResult(0, 1_999_999_999L))
        assertEquals(MicrophoneReadRecoveryPolicy.Action.RESTART, policy.onReadResult(0, 2_000_000_000L))
        assertEquals(1, policy.restartAttempts)
    }

    @Test fun negativeReadRestartsImmediatelyThenStopsAfterTheBound() {
        val policy = MicrophoneReadRecoveryPolicy()
        repeat(3) { assertEquals(MicrophoneReadRecoveryPolicy.Action.RESTART, policy.onReadResult(-3, it.toLong())) }
        assertEquals(MicrophoneReadRecoveryPolicy.Action.FATAL, policy.onReadResult(-3, 4L))
        assertEquals(3, policy.restartAttempts)
    }

    @Test fun intentionalRecorderStopDoesNotResetThePerStreamRestartLimit() {
        val policy = MicrophoneReadRecoveryPolicy()
        assertEquals(MicrophoneReadRecoveryPolicy.Action.RESTART, policy.onReadResult(-3, 1L))
        policy.recorderStopped()
        assertEquals(MicrophoneReadRecoveryPolicy.Action.WAIT, policy.onReadResult(0, 2L))
        assertEquals(1, policy.restartAttempts)
    }
}
