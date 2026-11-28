package local.headunit.diplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import java.io.Closeable
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Completes the Wi-Fi half after Bluetooth iAP2 bootstrap: advertise AirPlay on the same interface,
 * then move the runtime iAP2 control channel into AirPlay type-130 when the phone opens it.
 */
internal class HybridWirelessHandoff(
    context: Context,
    private val address: InetAddress,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val identification: Iap2IdentificationConfig,
    private val endpoint: Iap2WirelessCarPlayEndpoint,
    private val authenticator: MfiAuthenticator,
    ipv6Address: Inet6Address?,
    private val phoneBluetoothAddress: () -> String?,
    private val onTunnelReady: () -> Unit = {},
    private val onCallState: (Iap2Frame) -> Unit = {},
    private val locationProvider: () -> Iap2LocationProvider? = { null },
    private val report: (String, JSONObject) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val discoveryStarted = AtomicBoolean(false)
    private val discoveryHandoffFinished = AtomicBoolean(false)
    private val discoveryContext = context.applicationContext ?: context
    private val tunnelRequested = AtomicBoolean(false)
    private val tunnelReleaseSignal = Object()
    private val readyTunnelOwner = AtomicReference<AirPlaySession?>(null)
    private val forwardedFrameCount = java.util.concurrent.atomic.AtomicInteger()
    private val lastForwardedMessageId = java.util.concurrent.atomic.AtomicInteger(-1)
    private val activeTunnel = AtomicReference<Iap2Session?>(null)
    private val tunnelWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "hybrid-wifi-iap2").apply { isDaemon = true }
    }
    private val discovery = AtomicReference<Api17AirPlayDiscovery?>(createDiscovery(ipv6Address))

    private fun createDiscovery(ipv6Address: Inet6Address?) = Api17AirPlayDiscovery(
        context = discoveryContext,
        address = address,
        config = airPlayConfig,
        identity = identity,
        report = report,
        ipv6Address = ipv6Address,
        phoneBluetoothAddress = phoneBluetoothAddress,
    )

    val tunnelReady: Boolean get() = !closed.get() && readyTunnelOwner.get()?.isClosed == false

    fun tunnelReadyFor(owner: AirPlaySession): Boolean =
        !closed.get() && !owner.isClosed && readyTunnelOwner.get() === owner

    fun start() {
        if (closed.get() || !discoveryStarted.compareAndSet(false, true)) return
        val initial = discovery.get() ?: return
        // The known IP/port is already listening. Start Bonjour independently for older phones.
        Thread({
            if (closed.get() || discovery.get() !== initial) return@Thread
            try {
                initial.start()
                if (!closed.get() && discovery.get() === initial) report("HYBRID_MDNS_STATE", JSONObject()
                    .put("stage", "startup_snapshot")
                    .put("diagnosticSummary", initial.diagnosticSnapshot()))
            } catch (error: Exception) {
                if (!closed.get() && discovery.get() === initial) report("HYBRID_MDNS", JSONObject()
                    .put("stage", "discovery_start_failed")
                    .put("success", false)
                    .put("errorType", error.javaClass.simpleName.take(48)))
            }
        }, "hybrid-discovery-start").apply { isDaemon = true; start() }
    }

    fun beginPhoneHandoff() { discovery.get()?.beginPhoneHandoff() }

    fun stopPhoneHandoff() {
        discovery.get()?.stopPhoneHandoff()
        discoveryHandoffFinished.set(true)
    }

    /** Refresh interface-bound multicast on the repair worker, leaving the iAP tunnel intact. */
    @Synchronized
    fun reopenDiscovery(ipv6Address: Inet6Address?): Boolean {
        if (closed.get() || !discoveryHandoffFinished.get()) return false
        val previous = discovery.get()
        val replacement = createDiscovery(ipv6Address)
        replacement.stopPhoneHandoff() // Only the verified phone may be invited after recovery.
        if (!discovery.compareAndSet(previous, replacement)) {
            replacement.close()
            return false
        }

        var ready = false
        try {
            // Withdraw stale records before publishing the same Bonjour identity on the new interface.
            previous?.close()
            synchronized(tunnelReleaseSignal) { tunnelReleaseSignal.notifyAll() }
            if (closed.get() || Thread.currentThread().isInterrupted) return false
            replacement.start()
            ready = !closed.get() && discovery.get() === replacement
            return ready
        } catch (error: Exception) {
            if (!closed.get()) report("HYBRID_MDNS", JSONObject()
                .put("stage", "discovery_reopen_failed")
                .put("success", false)
                .put("errorType", error.javaClass.simpleName.take(48)))
            return false
        } finally {
            if (!ready) {
                discovery.compareAndSet(replacement, null)
                runCatching { replacement.close() }
            }
        }
    }

    fun tryResumePhone(
        peer: InetAddress?,
        verifiedBluetoothAddress: String,
        hotspotReopened: Boolean,
        onRequestFinished: (Boolean) -> Unit = {},
    ): Boolean = !closed.get() &&
        discovery.get()?.tryResumePhone(peer, verifiedBluetoothAddress, hotspotReopened, ::awaitTunnelRelease, onRequestFinished) == true

    fun cancelPhoneResume() {
        discovery.get()?.cancelPhoneResume()
        synchronized(tunnelReleaseSignal) { tunnelReleaseSignal.notifyAll() }
    }

    // The replacement's encrypted iAP tunnel must not race teardown of the old owner.
    private fun awaitTunnelRelease(deadlineNanos: Long, pending: () -> Boolean): Boolean {
        synchronized(tunnelReleaseSignal) {
            while (!closed.get() && pending()) {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0L) return false
                if (!tunnelRequested.get()) return true
                TimeUnit.NANOSECONDS.timedWait(tunnelReleaseSignal, remaining)
            }
            return false
        }
    }

    private fun releaseTunnel() {
        synchronized(tunnelReleaseSignal) {
            tunnelRequested.set(false)
            tunnelReleaseSignal.notifyAll()
        }
    }

    /** Called by CarPlayMediaEngine after the phone opens its AirPlay iAP data stream. */
    fun attachTunnel(owner: AirPlaySession, stream: BlockingDuplexByteStream): Boolean {
        if (closed.get() || owner.isClosed || !tunnelRequested.compareAndSet(false, true)) {
            report("HYBRID_IAP_TUNNEL", JSONObject()
                .put("stage", if (closed.get() || owner.isClosed) "rejected_closed" else "rejected_duplicate")
                .put("success", false))
            return false
        }
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "hybrid-wifi-type130",
                traceFrames = false,
                onTrace = { message ->
                    if (message.contains("failed", ignoreCase = true) || message.contains("closed", ignoreCase = true)) {
                        report("HYBRID_IAP_TUNNEL", JSONObject()
                            .put("stage", "iap2_trace")
                            .put("diagnosticSummary", message.take(400)))
                    }
                },
            )
        } catch (error: Throwable) {
            releaseTunnel()
            report("HYBRID_IAP_TUNNEL", JSONObject()
                .put("stage", "open_failed")
                .put("success", false)
                .put("errorType", error.javaClass.simpleName.take(48)))
            return false
        }
        activeTunnel.set(channel)
        if (closed.get()) {
            activeTunnel.compareAndSet(channel, null)
            runCatching { channel.close() }
            releaseTunnel()
            return false
        }
        report("HYBRID_IAP_TUNNEL", JSONObject()
            .put("stage", "type130_opened")
            .put("success", true))
        try {
            tunnelWorker.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(authenticator),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = if (identification.locationInformationEnabled) locationProvider() else null,
                        onReady = {
                            if (!closed.get() && !owner.isClosed && activeTunnel.get() === channel) {
                                readyTunnelOwner.set(owner)
                                report("HYBRID_IAP_TUNNEL", JSONObject()
                                    .put("stage", "control_ready")
                                    .put("success", true)
                                    .put("tunnelOpen", true))
                                if (!closed.get()) runCatching { onTunnelReady() }
                            }
                        },
                        onIncoming = { frame ->
                            forwardedFrameCount.incrementAndGet()
                            lastForwardedMessageId.set(frame.messageId)
                            if (frame.messageId == CallAudioPhase.UPDATE_MESSAGE &&
                                !closed.get() && !owner.isClosed && activeTunnel.get() === channel
                            ) onCallState(frame)
                        },
                        onProgress = { line ->
                            when {
                                line == "iap2 identification accepted" ->
                                    report("HYBRID_IAP_TUNNEL", JSONObject().put("stage", "identified").put("success", true))
                                line == "iap2 authentication accepted" ->
                                    report("HYBRID_IAP_TUNNEL", JSONObject().put("stage", "mfi_authenticated").put("success", true))
                                line == "iap2 subscriptions sent" ->
                                    report("HYBRID_IAP_TUNNEL", JSONObject().put("stage", "subscriptions_sent").put("success", true))
                                line.startsWith("iap2 rx=0xfffa ") || line.startsWith("iap2 rx=0xfffc ") ||
                                    line.startsWith("iap2 tx=0xfffb ") ->
                                    report("VEHICLE_INTEGRATION", JSONObject().put("stage", "location_protocol")
                                        .put("diagnosticSummary", line.take(160)))
                                line.startsWith("iap2 rx=0x4e0d wireless-carplay-update available=") ->
                                    report("HYBRID_IAP_TUNNEL", JSONObject()
                                        .put("stage", "wireless_update")
                                        .put("wirelessAvailable", line.endsWith("true")))
                                line.contains("ignored") || line.contains("failed") ->
                                    report("HYBRID_IAP_TUNNEL", JSONObject()
                                        .put("stage", "control_diagnostic")
                                        .put("diagnosticSummary", line.take(400)))
                            }
                        },
                    )
                    if (!closed.get()) {
                        report("HYBRID_IAP_TUNNEL", JSONObject()
                            .put("stage", "control_ended")
                            .put("success", false)
                            .put("terminal", result.terminal.name.lowercase(java.util.Locale.US))
                            .put("result", result.stage.name.lowercase(java.util.Locale.US))
                            .put("carPlayStartSessionsSent", result.carPlayStartSessionsSent)
                            .put("wifiConfigurationsSent", result.wifiConfigurationsSent)
                            .put("frameCount", forwardedFrameCount.get())
                            .put("resultCode", lastForwardedMessageId.get()))
                    }
                } catch (error: Throwable) {
                    if (!closed.get()) {
                        report("HYBRID_IAP_TUNNEL", JSONObject()
                            .put("stage", "control_failed")
                            .put("success", false)
                            .put("errorType", error.javaClass.simpleName.take(48))
                            .put("frameCount", forwardedFrameCount.get())
                            .put("resultCode", lastForwardedMessageId.get()))
                    }
                } finally {
                    readyTunnelOwner.compareAndSet(owner, null)
                    runCatching { channel.close() }
                    activeTunnel.compareAndSet(channel, null)
                    releaseTunnel()
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            runCatching { channel.close() }
            activeTunnel.compareAndSet(channel, null)
            releaseTunnel()
            report("HYBRID_IAP_TUNNEL", JSONObject().put("stage", "worker_rejected").put("success", false))
            return false
        }
    }

    fun diagnosticSnapshot(): String = (discovery.get()?.diagnosticSnapshot() ?: "discoveryAvailable=false") +
        " wifiIapTunnelRequested=${tunnelRequested.get()} wifiIapTunnelReady=$tunnelReady" +
        " tunnelFrames=${forwardedFrameCount.get()} lastTunnelMessageId=${lastForwardedMessageId.get()}"

    companion object {
        const val PHONE_RESUME_WINDOW_MS = 3_000L
        const val HOTSPOT_RESUME_WINDOW_MS = 4_200L
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val ownedDiscovery = discovery.getAndSet(null)
        ownedDiscovery?.cancelPhoneResume()
        synchronized(tunnelReleaseSignal) { tunnelReleaseSignal.notifyAll() }
        readyTunnelOwner.set(null)
        runCatching { activeTunnel.getAndSet(null)?.close() }
        tunnelWorker.shutdownNow()
        runCatching { ownedDiscovery?.close() }
    }
}
