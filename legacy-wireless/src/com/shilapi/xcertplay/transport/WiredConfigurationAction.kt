package com.shilapi.xcertplay.transport

/**
 * Selects the iPhone USB configuration policy before iAP2 is opened.
 * iOS can expose USBMUX+NCM while not in CarPlay mode; that configuration
 * can complete iAP2 but never open the AirPlay connection.
 */
internal enum class WiredConfigurationAction(val diagnostic: String) {
    USE("reuse-descriptors"),
    USE_WITHOUT_APPLE_ETHERNET("reuse-without-apple-ethernet"),
    REQUEST_TRANSITION("request-transition"),
    REJECT("reject-missing-configuration");

    companion object {
        fun choose(
            found: Boolean,
            carPlayMode: Boolean,
            transitionsTried: Int,
            maximumTransitions: Int,
            sessionAlreadyStarted: Boolean = false,
        ): WiredConfigurationAction = when {
            found && carPlayMode && sessionAlreadyStarted && transitionsTried == 0 -> REQUEST_TRANSITION
            found && carPlayMode -> USE
            found && transitionsTried > 0 -> USE_WITHOUT_APPLE_ETHERNET
            transitionsTried < maximumTransitions -> REQUEST_TRANSITION
            else -> REJECT
        }

        fun fallbackAfterRejection(
            found: Boolean,
            carPlayMode: Boolean,
            sessionAlreadyStarted: Boolean = false,
        ): Boolean = found && (!carPlayMode || sessionAlreadyStarted)
    }
}

/** Remembers the last enumeration that received a CarPlay StartSession request. */
internal object WiredSessionMemory {
    @Volatile private var lastStartedDevice: String? = null

    fun started(deviceName: String) {
        lastStartedDevice = deviceName
    }

    fun startedOn(deviceName: String): Boolean = lastStartedDevice == deviceName
}

/** One narrow recovery for stale wired sessions, gated by measured NCM and TCP state. */
internal object WiredStartAgain {
    const val QUIET_MILLIS = 2_000L
    private const val NANOS_PER_MILLI = 1_000_000L

    /** A SYN only proves the current request progressed once both its request and network readiness evidence were observed. */
    fun connectionAttemptAfterStart(requestStartedAtNanos: Long, lastAttemptAtNanos: Long?): Boolean =
        lastAttemptAtNanos != null && lastAttemptAtNanos - requestStartedAtNanos >= 0L

    fun connectionAttemptAfterReady(
        startFlushedAtNanos: Long?,
        ncmNetworkReadyAtNanos: Long?,
        lastAttemptAtNanos: Long?,
    ): Boolean {
        val attempt = lastAttemptAtNanos ?: return false
        val flushed = startFlushedAtNanos ?: return false
        val networkReady = ncmNetworkReadyAtNanos ?: return false
        return attempt > maxOf(flushed, networkReady)
    }

    /** Either IP family shows the phone tried TCP 7000 on this NCM link. */
    fun anyAddressFamilyAttemptAfterReady(
        startFlushedAtNanos: Long?,
        ncmNetworkReadyAtNanos: Long?,
        lastIpv6AttemptAtNanos: Long?,
        lastIpv4AttemptAtNanos: Long?,
    ): Boolean = connectionAttemptAfterReady(
        startFlushedAtNanos,
        ncmNetworkReadyAtNanos,
        lastIpv6AttemptAtNanos,
    ) || connectionAttemptAfterReady(
        startFlushedAtNanos,
        ncmNetworkReadyAtNanos,
        lastIpv4AttemptAtNanos,
    )

    fun decide(
        nowNanos: Long,
        startFlushedAtNanos: Long,
        ncmNetworkReadyAtNanos: Long?,
        sessionActive: Boolean,
        connectionAttemptAfterReady: Boolean,
    ): Iap2WiredControlClient.StartAgain = when {
        sessionActive || connectionAttemptAfterReady -> Iap2WiredControlClient.StartAgain.DONE
        ncmNetworkReadyAtNanos == null -> Iap2WiredControlClient.StartAgain.WAIT
        nowNanos - maxOf(startFlushedAtNanos, ncmNetworkReadyAtNanos) < QUIET_MILLIS * NANOS_PER_MILLI -> Iap2WiredControlClient.StartAgain.WAIT
        else -> Iap2WiredControlClient.StartAgain.SEND
    }
}
