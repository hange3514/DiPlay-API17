package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallUsbRecoveryPlanningTest {
    @Test fun confirmedUsbStillAllowsFirstUnmuteThenStopsRepeatedWrites() {
        assertFalse(BydCallUsbAssert.canSkipTimedAssertion(10, false))
        assertTrue(BydCallUsbAssert.canSkipTimedAssertion(10, true))
        assertFalse(BydCallUsbAssert.canSkipTimedAssertion(-1, true))
        assertFalse(BydCallUsbAssert.canSkipTimedAssertion(13, true))
    }

    @Test fun actualSourceChangesGetADeferredRetryAndUseTheSameRateLimit() {
        assertEquals(0L, BydCallUsbAssert.reassertDelayMs(600, -1))
        assertEquals(900L, BydCallUsbAssert.reassertDelayMs(1200, 600))
        assertTrue(BydCallUsbAssert.shouldReassert(true, 13, 2100, 600, 0))
        assertFalse(BydCallUsbAssert.shouldReassert(true, 10, 2100, 600, 0))
    }
}
