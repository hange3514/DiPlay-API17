package local.headunit.diplay

import com.shilapi.xcertplay.transport.Iap2WiredControlClient.StartAgain
import com.shilapi.xcertplay.transport.WiredConfigurationAction
import com.shilapi.xcertplay.transport.WiredStartAgain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredConnectionRecoveryTest {
    @Test fun nativePacketAgeUsesOneConservativeLowerBoundAcrossFamiliesAndMdns() {
        val queryStartedAt = 10_000_000_000L
        assertEquals(queryStartedAt - 1_000_000L, UserlandNcmNetwork.conservativeEventAtNanos(queryStartedAt, 0L))
        assertEquals(queryStartedAt - 6_000_000L, UserlandNcmNetwork.conservativeEventAtNanos(queryStartedAt, 5L))
        assertEquals(null, UserlandNcmNetwork.conservativeEventAtNanos(queryStartedAt, -1L))
        assertEquals(null, UserlandNcmNetwork.conservativeEventAtNanos(queryStartedAt, null))
    }

    @Test fun earlyNonReplayableNcmWritesUseTheShortTimeoutOnlyBeforeLinkAcceptance() {
        assertEquals(20, StartupUsbWrite.timeoutMillis(replayable = false, firstBlockAccepted = false, requestedMillis = 1_000))
        assertEquals(20, StartupUsbWrite.timeoutMillis(replayable = false, firstBlockAccepted = false, requestedMillis = 20))
        assertEquals(1_000, StartupUsbWrite.timeoutMillis(replayable = true, firstBlockAccepted = false, requestedMillis = 1_000))
        assertEquals(1_000, StartupUsbWrite.timeoutMillis(replayable = false, firstBlockAccepted = true, requestedMillis = 1_000))
    }

    @Test fun configurationTransitionIsBoundedAndPreservesSafeFallbacks() {
        assertEquals(WiredConfigurationAction.USE, WiredConfigurationAction.choose(true, true, 0, 2))
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, WiredConfigurationAction.choose(true, true, 0, 2, true))
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, WiredConfigurationAction.choose(true, false, 0, 2))
        assertEquals(WiredConfigurationAction.USE_WITHOUT_APPLE_ETHERNET, WiredConfigurationAction.choose(true, false, 1, 2))
        assertEquals(WiredConfigurationAction.REJECT, WiredConfigurationAction.choose(false, false, 2, 2))
        assertTrue(WiredConfigurationAction.fallbackAfterRejection(true, false))
        assertTrue(!WiredConfigurationAction.fallbackAfterRejection(true, true))
        assertTrue(WiredConfigurationAction.fallbackAfterRejection(true, true, true))
    }

    @Test fun startRequestIsRetriedAfterAQuietPeriodFromBothFlushedStartAndNcmReadiness() {
        val second = 1_000_000_000L
        fun decide(now: Long, opened: Long?, active: Boolean = false, syn: Boolean = false) =
            WiredStartAgain.decide(now, 10 * second, opened, active, syn)

        assertEquals(StartAgain.WAIT, decide(40 * second, null))
        assertEquals(StartAgain.SEND, decide(40 * second, 9 * second))
        assertEquals(StartAgain.WAIT, decide(11_999_999_999L, 9 * second))
        assertEquals(StartAgain.SEND, decide(12 * second, 9 * second))
        assertEquals(StartAgain.WAIT, decide(11_999_999_999L, 10 * second))
        assertEquals(StartAgain.SEND, decide(12 * second, 10 * second))
        assertEquals(StartAgain.DONE, decide(12 * second, 10 * second, syn = true))
        assertEquals(StartAgain.DONE, decide(12 * second, 10 * second, active = true))
    }

    @Test fun aSynBeforeTheCurrentStartDoesNotSuppressTheRecovery() {
        val second = 1_000_000_000L
        val started = 10 * second
        val opened = 8 * second
        val lastSynBeforeStart = 9 * second
        val triedAfterReady = WiredStartAgain.connectionAttemptAfterReady(started, opened, lastSynBeforeStart)
        assertEquals(false, triedAfterReady)
        assertEquals(StartAgain.SEND, WiredStartAgain.decide(
            nowNanos = 13 * second,
            startFlushedAtNanos = started,
            ncmNetworkReadyAtNanos = opened,
            sessionActive = false,
            connectionAttemptAfterReady = triedAfterReady,
        ))
    }

    @Test fun aSynAfterRequestPreparationButBeforeTheStartFlushDoesNotSuppressRetry() {
        val second = 1_000_000_000L
        val requestPrepared = 10 * second
        val startFlushed = 20 * second
        val opened = 8 * second
        val synDuringSlowFlush = 14 * second
        val triedAfterReady = WiredStartAgain.connectionAttemptAfterReady(startFlushed, opened, synDuringSlowFlush)
        assertEquals(true, WiredStartAgain.connectionAttemptAfterStart(requestPrepared, synDuringSlowFlush))
        assertEquals(false, triedAfterReady)
        assertEquals(StartAgain.WAIT, WiredStartAgain.decide(
            nowNanos = 21_999_999_999L,
            startFlushedAtNanos = startFlushed,
            ncmNetworkReadyAtNanos = opened,
            sessionActive = false,
            connectionAttemptAfterReady = triedAfterReady,
        ))
        assertEquals(StartAgain.SEND, WiredStartAgain.decide(
            nowNanos = 22 * second,
            startFlushedAtNanos = startFlushed,
            ncmNetworkReadyAtNanos = opened,
            sessionActive = false,
            connectionAttemptAfterReady = triedAfterReady,
        ))
    }

    @Test fun aSynAfterStartFlushButBeforeNcmOutReadinessDoesNotSuppressRetry() {
        val second = 1_000_000_000L
        val startFlushed = 10 * second
        val opened = 20 * second
        val synBeforeOpen = 14 * second
        assertEquals(false, WiredStartAgain.connectionAttemptAfterReady(startFlushed, opened, synBeforeOpen))
    }

    @Test fun aSynAfterStartAndNcmOutReadinessSuppressesTheDuplicateRequest() {
        val second = 1_000_000_000L
        val started = 10 * second
        val opened = 20 * second
        val lastSynAfterReady = 21 * second
        val triedAfterReady = WiredStartAgain.connectionAttemptAfterReady(started, opened, lastSynAfterReady)
        assertEquals(true, triedAfterReady)
        assertEquals(StartAgain.DONE, WiredStartAgain.decide(
            nowNanos = 22 * second,
            startFlushedAtNanos = started,
            ncmNetworkReadyAtNanos = opened,
            sessionActive = false,
            connectionAttemptAfterReady = triedAfterReady,
        ))
    }

    @Test fun ipv4OrIpv6AttemptAfterReadinessSuppressesTheDuplicateRequest() {
        val second = 1_000_000_000L
        val startFlushed = 10 * second
        val ncmOutAccepted = 20 * second
        val now = 22 * second

        val ipv4Attempt = WiredStartAgain.anyAddressFamilyAttemptAfterReady(
            startFlushedAtNanos = startFlushed,
            ncmNetworkReadyAtNanos = ncmOutAccepted,
            lastIpv6AttemptAtNanos = null,
            lastIpv4AttemptAtNanos = 21 * second,
        )
        assertEquals(true, ipv4Attempt)
        assertEquals(StartAgain.DONE, WiredStartAgain.decide(
            nowNanos = now,
            startFlushedAtNanos = startFlushed,
            ncmNetworkReadyAtNanos = ncmOutAccepted,
            sessionActive = false,
            connectionAttemptAfterReady = ipv4Attempt,
        ))

        val earlyIpv4Attempt = WiredStartAgain.anyAddressFamilyAttemptAfterReady(
            startFlushedAtNanos = startFlushed,
            ncmNetworkReadyAtNanos = ncmOutAccepted,
            lastIpv6AttemptAtNanos = null,
            lastIpv4AttemptAtNanos = 19 * second,
        )
        assertEquals(false, earlyIpv4Attempt)
        assertEquals(StartAgain.SEND, WiredStartAgain.decide(
            nowNanos = now,
            startFlushedAtNanos = startFlushed,
            ncmNetworkReadyAtNanos = ncmOutAccepted,
            sessionActive = false,
            connectionAttemptAfterReady = earlyIpv4Attempt,
        ))
    }

    @Test fun aSynDuringASlowStartSessionFlushSuppressesTheDuplicateRequest() {
        val second = 1_000_000_000L
        val requestPrepared = 10 * second
        val usbMuxFlushCompleted = 18 * second
        val synDuringFlush = 14 * second
        // A SYN before the request actually reaches the phone is not evidence
        // that this StartSession opened a usable CarPlay connection.
        assertTrue(WiredStartAgain.connectionAttemptAfterStart(requestPrepared, synDuringFlush))
        assertEquals(false, WiredStartAgain.connectionAttemptAfterReady(usbMuxFlushCompleted, 8 * second, synDuringFlush))
        assertEquals(StartAgain.SEND, WiredStartAgain.decide(
            nowNanos = 20 * second,
            startFlushedAtNanos = usbMuxFlushCompleted,
            ncmNetworkReadyAtNanos = 8 * second,
            sessionActive = false,
            connectionAttemptAfterReady = false,
        ))
    }

    @Test fun startWatchdogGivesOneBoundedGraceForTheJustifiedRetry() {
        var now = 0L
        var timeoutAt: Long? = null
        val deadlines = mutableListOf<Pair<Long, () -> Unit>>()
        val watch = WiredStartWatchdog(
            schedule = { delay, action ->
                deadlines += (now + delay) to action
                { Unit }
            },
            onTimeout = { timeoutAt = now },
            secondRequestCanStillGoOut = { true },
        )
        watch.startSessionSent()
        now = WiredStartWatchdog.TIMEOUT_MILLIS
        deadlines.single().second()
        assertEquals(WiredStartWatchdog.HELD_TIMEOUT_MILLIS, deadlines.last().first)
        assertEquals(null, timeoutAt)
        now = WiredStartWatchdog.HELD_TIMEOUT_MILLIS
        deadlines.last().second()
        assertEquals(now, timeoutAt)
    }

    @Test fun activeSessionCancelsStaleTimeoutsAndRetryRefreshesOnlyOnce() {
        var now = 0L
        var timeouts = 0
        val deadlines = mutableListOf<Pair<Long, () -> Unit>>()
        val watch = WiredStartWatchdog(
            schedule = { delay, action -> deadlines += (now + delay) to action; { Unit } },
            onTimeout = { timeouts++ },
        )
        watch.startSessionSent()
        val first = deadlines.last()
        now = 10_000L
        watch.startSessionSentAgain()
        val refreshed = deadlines.last()
        assertEquals(now + WiredStartWatchdog.TIMEOUT_MILLIS, refreshed.first)
        now = refreshed.first
        first.second()
        assertEquals(0, timeouts)
        refreshed.second()
        assertEquals(1, timeouts)

        val active = WiredStartWatchdog(
            schedule = { delay, action -> deadlines += (now + delay) to action; { Unit } },
            onTimeout = { timeouts++ },
        )
        active.startSessionSent()
        val stale = deadlines.last().second
        active.sessionActive()
        stale()
        assertEquals(1, timeouts)
    }
}
