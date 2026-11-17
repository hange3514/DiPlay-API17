package local.headunit.diplay

import com.shilapi.xcertplay.transport.WiredAvailabilityStartReplyPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredAvailabilityStartReplyPolicyTest {
    @Test fun allowsNormalAvailabilityAndTwoDuplicateReplies() {
        assertTrue(WiredAvailabilityStartReplyPolicy.shouldReply(0))
        assertTrue(WiredAvailabilityStartReplyPolicy.shouldReply(1))
        assertTrue(WiredAvailabilityStartReplyPolicy.shouldReply(2))
    }

    @Test fun suppressesAvailabilityFloodAfterThreeReplies() {
        assertFalse(WiredAvailabilityStartReplyPolicy.shouldReply(3))
        assertFalse(WiredAvailabilityStartReplyPolicy.shouldReply(100))
        assertFalse(WiredAvailabilityStartReplyPolicy.shouldReply(-1))
    }

    @Test fun suppressesStartOnlyWhenPhoneExplicitlyReportsUnavailable() {
        assertTrue(WiredAvailabilityStartReplyPolicy.permitsStart(true))
        assertTrue(WiredAvailabilityStartReplyPolicy.permitsStart(null))
        assertFalse(WiredAvailabilityStartReplyPolicy.permitsStart(false))
    }
}
