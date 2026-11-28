package local.headunit.diplay

import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one autonomous API 17 P2P group used as a local Wi-Fi access point. */
internal class WifiP2pHotspot(context: Context) : Closeable {
    internal class StartException(
        val reason: String,
        message: String,
        cause: Throwable? = null,
        val safeToUseSoftAp: Boolean = false,
    ) : IOException(message, cause)

    private data class GroupIdentity(val ssid: String, val interfaceName: String, val owner: String?)
    private class ClientSample(
        val manager: WifiP2pManager,
        val channel: WifiP2pManager.Channel,
        val group: GroupIdentity,
        val ownerAddress: Inet4Address,
    )

    private val app = context.applicationContext ?: context
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val wifi get() = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var manager: WifiP2pManager? = null
    private var callbacks: HandlerThread? = null
    private var channel: WifiP2pManager.Channel? = null
    private val channelLost = AtomicBoolean()
    private var enabledWifi = false
    private var createRequest: Reply<Int>? = null
    private var ownedGroup: GroupIdentity? = null
    @Volatile private var clientSample: ClientSample? = null

    /** Run away from the UI thread. The returned IP is ready before iPhone associates. */
    fun start(cancelled: () -> Boolean, onStage: (String) -> Unit): HybridNetworkSnapshot =
        synchronized(lock) {
            check(Looper.myLooper() != Looper.getMainLooper()) { "P2P startup must run on a worker" }
            check(callbacks == null) { "P2P hotspot instances are single-use" }
            val stopped = { closed.get() || cancelled() }
            try {
                startGroup(stopped, onStage)
            } catch (failure: Exception) {
                closed.set(true)
                val released = runCatching { release() }.getOrDefault(false)
                val error = when (failure) {
                    is InterruptedException -> throw failure
                    is StartException -> failure
                    is SecurityException -> StartException(
                        "p2p_permission_denied", "系统拒绝开启 Wi‑Fi Direct。请检查应用权限。", failure,
                    )
                    else -> StartException(
                        "p2p_system_error", "开启 Wi‑Fi Direct 时系统出现异常。请提交无线版日志。", failure,
                    )
                }
                if (allowsSoftApFallback(error.reason, released)) {
                    throw StartException(error.reason, error.message.orEmpty(), error.cause, safeToUseSoftAp = true)
                }
                throw error
            }
        }

    private fun startGroup(
        stopped: () -> Boolean,
        onStage: (String) -> Unit,
    ): HybridNetworkSnapshot {
        checkCancelled(stopped)
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            throw StartException("p2p_unsupported", "车机系统未声明支持 Wi‑Fi Direct。")
        }
        val wifiManager = wifi ?: throw StartException("wifi_missing", "车机 Wi‑Fi 服务不可用。")
        requireNoTethering(wifiManager)
        val p2p = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: throw StartException("p2p_service_missing", "车机 Wi‑Fi Direct 服务不可用。")
        manager = p2p

        onStage("p2p_wait_wifi")
        if (wifiManager.wifiState == WifiManager.WIFI_STATE_DISABLED) {
            if (!wifiManager.setWifiEnabled(true)) {
                throw StartException("p2p_wifi_enable_rejected", "系统拒绝开启 Wi‑Fi。请在车机设置里启用 Wi‑Fi。")
            }
            enabledWifi = true
        }
        val wifiDeadline = SystemClock.elapsedRealtime() + WIFI_START_TIMEOUT_MS
        while (!wifiManager.isWifiEnabled) {
            checkCancelled(stopped)
            if (SystemClock.elapsedRealtime() >= wifiDeadline) {
                throw StartException("p2p_wifi_start_timeout", "等待车机 Wi‑Fi 开启超时。")
            }
            Thread.sleep(POLL_MS)
        }
        requireNoTethering(wifiManager)
        checkCancelled(stopped)

        val thread = HandlerThread("legacy-wifi-p2p")
        thread.start()
        callbacks = thread
        val p2pChannel = p2p.initialize(app, thread.looper) { channelLost.set(true) }
            ?: throw StartException("p2p_channel_missing", "无法连接车机的 Wi‑Fi Direct 服务。")
        channel = p2pChannel
        onStage("p2p_create_group")
        val groupDeadline = SystemClock.elapsedRealtime() + GROUP_START_TIMEOUT_MS
        while (true) {
            requireNoTethering(wifiManager)
            if (requestGroup(p2p, p2pChannel).require(REQUEST_TIMEOUT_MS, stopped) != null) {
                throw StartException("p2p_group_in_use", "车机已有 Wi‑Fi Direct 连接，请结束后再重试。")
            }
            val request = Reply<Int>()
            createRequest = request
            p2p.createGroup(p2pChannel, actionListener(request))
            val result = request.require(REQUEST_TIMEOUT_MS, stopped)
            if (result == ACTION_SUCCESS) break
            createRequest = null
            if (result != WifiP2pManager.BUSY || SystemClock.elapsedRealtime() >= groupDeadline) {
                throw StartException("p2p_create_failed_$result", "车机无法建立 Wi‑Fi Direct 热点（系统状态 $result）。")
            }
            Thread.sleep(POLL_MS)
        }
        onStage("p2p_wait_group")
        while (SystemClock.elapsedRealtime() < groupDeadline) {
            requireNoTethering(wifiManager)
            val group = requestGroup(p2p, p2pChannel).require(REQUEST_TIMEOUT_MS, stopped)
            if (group != null) {
                val identity = groupIdentity(group)
                    ?: throw StartException("p2p_local_group_missing", "系统未返回完整的本机 Wi‑Fi Direct 热点信息。")
                own(identity)
                val connection = requestConnection(p2p, p2pChannel).require(REQUEST_TIMEOUT_MS, stopped)
                val ownerAddress = usableOwnerAddress(connection?.groupOwnerAddress)
                if (connection?.groupFormed == true && connection.isGroupOwner && ownerAddress != null) {
                    val snapshot = readBinding(group, identity, ownerAddress)
                    if (snapshot != null) {
                        checkCancelled(stopped)
                        clientSample = ClientSample(p2p, p2pChannel, identity, ownerAddress)
                        onStage("p2p_ip_ready")
                        return snapshot
                    }
                }
            }
            Thread.sleep(POLL_MS)
        }
        throw StartException("p2p_ip_timeout", "Wi‑Fi Direct 热点未取得可用的本地地址。请提交无线版日志。")
    }

    /** Verifies current group ownership and the actual interface on a background thread. */
    fun current(): HybridNetworkSnapshot = synchronized(lock) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "P2P verification must run on a worker" }
        checkCancelled { closed.get() }
        val p2p = manager ?: throw stoppedError()
        val p2pChannel = channel ?: throw stoppedError()
        val active = clientSample ?: throw stoppedError()
        val group = requestGroup(p2p, p2pChannel).require(REQUEST_TIMEOUT_MS) { closed.get() }
            ?: throw stoppedError()
        if (groupIdentity(group) != active.group) throw stoppedError()
        readBinding(group, active.group, active.ownerAddress) ?: throw stoppedError()
    }

    /** Counts stations reported by the owned group. This is independent of DHCP or AirPlay. */
    fun sampleClientCount(onCount: (Int) -> Unit) {
        val sample = clientSample ?: return
        if (closed.get()) return
        runCatching {
            sample.manager.requestGroupInfo(sample.channel) { group ->
                if (group != null && !closed.get() && clientSample === sample &&
                    groupIdentity(group) == sample.group
                ) {
                    runCatching { onCount(group.clientList.size) }
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun close() {
        closed.set(true)
        synchronized(lock) { release() }
    }

    private fun release(): Boolean {
        val interrupted = Thread.interrupted()
        try {
            val p2p = manager
            val p2pChannel = channel
            val wifiManager = wifi
            if (wifiManager == null) return !enabledWifi && createRequest == null && ownedGroup == null
            if (!canReleaseGroup(wifiManager)) {
                if (ownedGroup != null) {
                    Log.w(TAG, "P2P teardown skipped because another system tether could be affected")
                }
                return false
            }
            val groupReleased = if (p2p != null && p2pChannel != null) {
                releaseGroup(p2p, p2pChannel, wifiManager)
            } else createRequest == null && ownedGroup == null
            if (!groupReleased) return false
            return !enabledWifi || (canReleaseGroup(wifiManager) && restoreWifi(wifiManager))
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Wi-Fi Direct cleanup was interrupted", failure)
        } finally {
            enabledWifi = false
            clientSample = null
            ownedGroup = null
            createRequest = null
            channel = null
            manager = null
            // Channel.close() is not present on API 17.
            callbacks?.quit()
            callbacks = null
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun restoreWifi(manager: WifiManager): Boolean {
        val accepted = manager.setWifiEnabled(false)
        if (!accepted) Log.w(TAG, "Wi-Fi state restoration was rejected")
        return accepted
    }

    private fun releaseGroup(
        p2p: WifiP2pManager,
        p2pChannel: WifiP2pManager.Channel,
        wifiManager: WifiManager,
    ): Boolean {
        val accepted = createRequest?.let { request ->
            if (!request.await(REQUEST_TIMEOUT_MS)) return false
            request.value == ACTION_SUCCESS
        } ?: false
        val reply = requestGroup(p2p, p2pChannel)
        if (!reply.await(REQUEST_TIMEOUT_MS)) return false
        val group = reply.value
        if (!canReleaseGroup(wifiManager)) return false
        if (group == null) {
            if (!accepted || ownedGroup != null) return true
            // The create reply can arrive before the group-start event.
            val cancelRequest = Reply<Int>()
            p2p.cancelConnect(p2pChannel, actionListener(cancelRequest))
            if (!cancelRequest.await(REQUEST_TIMEOUT_MS)) return false
            val refreshed = requestGroup(p2p, p2pChannel)
            if (!refreshed.await(REQUEST_TIMEOUT_MS)) return false
            val formed = refreshed.value
            if (formed == null) return cancelRequest.value == ACTION_SUCCESS
            return canReleaseGroup(wifiManager) && removeVerifiedGroup(p2p, p2pChannel, formed, accepted)
        }
        return removeVerifiedGroup(p2p, p2pChannel, group, accepted)
    }

    private fun removeVerifiedGroup(
        p2p: WifiP2pManager,
        p2pChannel: WifiP2pManager.Channel,
        group: WifiP2pGroup,
        accepted: Boolean,
    ): Boolean {
        val identity = groupIdentity(group) ?: return false
        // A preexisting group never gets a create request. If a formed group was observed,
        // only release that specific group, including during a failed startup.
        if (!accepted || ownedGroup?.let { it != identity } == true) return false
        val request = Reply<Int>()
        p2p.removeGroup(p2pChannel, actionListener(request))
        if (!request.await(REQUEST_TIMEOUT_MS) || request.value != ACTION_SUCCESS) return false
        val deadline = SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS
        do {
            val current = requestGroup(p2p, p2pChannel)
            if (!current.await(REQUEST_TIMEOUT_MS)) return false
            if (current.value == null) return true
            if (current.value?.let(::groupIdentity) != identity) return false
            Thread.sleep(POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun own(identity: GroupIdentity) {
        val previous = ownedGroup
        if (previous != null && previous != identity) throw stoppedError()
        ownedGroup = identity
    }

    private fun groupIdentity(group: WifiP2pGroup): GroupIdentity? {
        if (!group.isGroupOwner) return null
        val ssid = group.networkName?.takeIf { it.startsWith("DIRECT-") && it.toByteArray(Charsets.UTF_8).size <= 32 }
            ?: return null
        val interfaceName = group.`interface`?.takeIf { SAFE_INTERFACE.matches(it) } ?: return null
        return GroupIdentity(ssid, interfaceName, group.owner?.deviceAddress)
    }

    private fun readBinding(
        group: WifiP2pGroup,
        identity: GroupIdentity,
        ownerAddress: Inet4Address,
    ): HybridNetworkSnapshot? {
        val passphrase = group.passphrase?.takeIf(HybridNetwork::isHotspotPassphrase) ?: return null
        val iface = NetworkInterface.getByName(identity.interfaceName)?.takeIf { it.isUp } ?: return null
        val prefix = iface.interfaceAddresses.firstNotNullOfOrNull { address ->
            address.networkPrefixLength.toInt().takeIf { address.address == ownerAddress && it in 1..30 }
        } ?: return null
        val linkLocal = runCatching {
            Collections.list(iface.inetAddresses)
                .filterIsInstance<Inet6Address>()
                .firstOrNull { it.isLinkLocalAddress }
                ?.let { Inet6Address.getByAddress(null, it.address, iface) }
                ?.takeIf { it.scopeId != 0 }
        }.getOrNull()
        val wlanMac = runCatching { NetworkInterface.getByName("wlan0")?.hardwareAddress }.getOrNull()
        return HybridNetworkSnapshot(
            identity.ssid, ownerAddress, linkLocal, HybridNetwork.receiverMac(app, wlanMac), null,
            iface.name, HybridNetwork.prefixMask(prefix), 0, SystemClock.elapsedRealtime(),
            HybridWifiCredential(passphrase, Iap2WirelessSecurity.WPA_WPA2, "p2p_generated", true),
            "unavailable", "wifi_p2p_group",
        )
    }

    private fun requireNoTethering(manager: WifiManager) {
        if (!systemHotspotOff(manager)) {
            throw StartException("system_hotspot_active", "请先在车机设置里关闭系统热点，再连接 Wi‑Fi Direct。")
        }
        if (!otherTetheringOff()) {
            throw StartException(
                "system_tethering_in_use", "系统网络共享未确认关闭。请先关闭 USB 或蓝牙网络共享，再连接 Wi‑Fi Direct。",
            )
        }
    }

    private fun canReleaseGroup(manager: WifiManager): Boolean = systemHotspotOff(manager) && otherTetheringOff()

    private fun systemHotspotOff(manager: WifiManager): Boolean {
        val state = HybridNetwork.readApState(app, manager)
        return state == AP_DISABLED || state == AP_FAILED
    }

    private fun otherTetheringOff(): Boolean {
        // P2P calls netd's shared DHCP server directly. Do not stop any OS-managed tether.
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val tethered = runCatching {
            connectivity?.javaClass?.getMethod("getTetheredIfaces")?.invoke(connectivity) as? Array<*>
        }.getOrNull()
        if (tethered != null) return tethered.isEmpty()
        val state = app.registerReceiver(null, IntentFilter(TETHER_STATE_CHANGED))
        return state?.getStringArrayListExtra("activeArray")?.isEmpty() == true
    }

    private fun stoppedError() = StartException("p2p_group_gone", "Wi‑Fi Direct 热点连接已中断。")

    private fun checkCancelled(stopped: () -> Boolean) {
        if (stopped() || Thread.currentThread().isInterrupted) throw InterruptedException("Wi-Fi Direct stopped")
        if (channelLost.get()) {
            throw StartException("p2p_channel_lost", "车机 Wi‑Fi Direct 服务已断开。")
        }
    }

    private fun <T> Reply<T>.require(timeoutMs: Long, stopped: () -> Boolean): T? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            checkCancelled(stopped)
            if (await(POLL_MS)) return value
        } while (SystemClock.elapsedRealtime() < deadline)
        throw StartException("p2p_callback_timeout", "车机 Wi‑Fi Direct 服务未返回。请提交无线版日志。")
    }

    private fun requestGroup(p2p: WifiP2pManager, channel: WifiP2pManager.Channel): Reply<WifiP2pGroup> =
        Reply<WifiP2pGroup>().also { reply -> p2p.requestGroupInfo(channel) { reply.complete(it) } }

    private fun requestConnection(p2p: WifiP2pManager, channel: WifiP2pManager.Channel): Reply<WifiP2pInfo> =
        Reply<WifiP2pInfo>().also { reply -> p2p.requestConnectionInfo(channel) { reply.complete(it) } }

    private fun actionListener(reply: Reply<Int>) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = reply.complete(ACTION_SUCCESS)
        override fun onFailure(reason: Int) = reply.complete(reason)
    }

    private class Reply<T> {
        private val ready = CountDownLatch(1)
        var value: T? = null
            private set

        fun complete(result: T?) {
            value = result
            ready.countDown()
        }

        fun await(timeoutMs: Long): Boolean = ready.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        internal fun allowsSoftApFallback(reason: String, groupReleased: Boolean): Boolean =
            groupReleased && reason != "system_hotspot_active" &&
                reason != "system_tethering_in_use" && reason != "p2p_group_in_use"

        internal fun usableOwnerAddress(address: InetAddress?): Inet4Address? =
            (address as? Inet4Address)?.takeUnless {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress ||
                    it.isMulticastAddress || it.hostAddress == "255.255.255.255"
            }

        private val SAFE_INTERFACE = Regex("[A-Za-z][A-Za-z0-9_.-]{0,15}")
        private const val TAG = "DiPlayWifiP2p"
        private const val TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
        private const val AP_DISABLED = 11
        private const val AP_FAILED = 14
        private const val ACTION_SUCCESS = -1
        private const val POLL_MS = 120L
        private const val REQUEST_TIMEOUT_MS = 800L
        private const val WIFI_START_TIMEOUT_MS = 6000L
        private const val GROUP_START_TIMEOUT_MS = 10000L
    }
}
