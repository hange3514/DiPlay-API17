package local.headunit.diplay

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.io.IOException
import java.lang.reflect.InvocationTargetException

internal class LegacyCarHotspotException(
    val reason: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

object LegacyCarHotspot {
    internal const val RECOVERY_AP_STOP_TIMEOUT_MS = 9000L
    private const val PREFS = "wireless_headunit_ap"
    private const val AUTO_START = "start_with_carplay"
    private const val MOBILE_DATA_OFF = "disable_mobile_data_on_connect"
    private const val AP_DISABLING = 10
    private const val AP_DISABLED = 11
    private const val AP_ENABLING = 12
    private const val AP_ENABLED = 13
    private const val AP_FAILED = 14
    private const val POLL_MS = 250L
    private const val AP_REQUEST_GRACE_MS = 2000L
    private const val STATION_STOP_TIMEOUT_MS = 5000L
    private const val ROLLBACK_AP_STOP_TIMEOUT_MS = 2000L

    @JvmStatic
    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT == 17 && "freescale".equals(Build.MANUFACTURER, true) &&
            "mx9a0".equals(Build.MODEL, true) &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)

    @JvmStatic
    fun isAutoStartEnabled(context: Context): Boolean = isSupported(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_START, true)

    @JvmStatic
    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        if (isSupported(context)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_START, enabled).apply()
        }
    }

    @JvmStatic
    fun usesWifiDirect(context: Context): Boolean = runCatching {
        val app = context.applicationContext ?: context
        app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
            app.getSystemService(Context.WIFI_SERVICE) is WifiManager &&
            app.getSystemService(Context.WIFI_P2P_SERVICE) is WifiP2pManager
    }.getOrDefault(false)

    @JvmStatic
    fun isMobileDataIsolationEnabled(context: Context): Boolean = isSupported(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MOBILE_DATA_OFF, false)

    @JvmStatic
    fun setMobileDataIsolationEnabled(context: Context, enabled: Boolean) {
        if (isSupported(context)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MOBILE_DATA_OFF, enabled).apply()
        }
    }

    internal fun mobileDataSetting(context: Context): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, "mobile_data", -1).takeIf { it in 0..1 }
    }.getOrNull()

    internal fun shouldOpenSystemSettings(reason: String): Boolean = when (reason) {
        "wifi_service_unavailable", "ap_api_unavailable", "ap_permission_missing", "ap_airplane_mode",
        "ap_station_disable_rejected", "ap_station_not_disabled", "ap_conflict_stop_failed", "ap_start_rejected", "ap_state_unavailable",
        "ap_system_failed", "ap_system_call_failed", "ap_stopped", "ap_start_timeout", "ap_stop_timeout" -> true
        else -> false
    }

    @Synchronized
    internal fun prepare(
        context: Context,
        timeoutMs: Long,
        cancelled: () -> Boolean,
        recovering: Boolean = false,
        onState: (String) -> Unit,
    ): HybridNetworkSnapshot {
        if (!isAutoStartEnabled(context)) {
            throw LegacyCarHotspotException("ap_auto_disabled", "自动开启车载热点未启用。")
        }
        val app = context.applicationContext ?: context
        val manager = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw LegacyCarHotspotException("wifi_service_unavailable", "系统 Wi-Fi 服务尚未就绪。")
        return prepare(SystemPlatform(app, manager), timeoutMs, cancelled, onState, recovering) readReady@{
            val snapshot = try {
                HybridNetwork.requireConnected(app)
            } catch (failure: HybridHotspotException) {
                if (failure.reason == "ap_not_enabled") return@readReady null
                throw configurationFailure(failure)
            } catch (_: IllegalStateException) {
                return@readReady null
            }
            if (!snapshot.credential.available) throw missingPassphrase()
            snapshot
        }
    }

    internal interface Platform {
        fun now(): Long
        fun sleep(ms: Long)
        fun apState(): Int
        fun stationState(): Int
        fun setApEnabled(enabled: Boolean): Boolean
        fun setStationEnabled(enabled: Boolean): Boolean
        fun validateStartup()
    }

    internal fun <T : Any> prepare(
        platform: Platform,
        timeoutMs: Long,
        cancelled: () -> Boolean,
        onState: (String) -> Unit,
        recovering: Boolean = false,
        readReady: () -> T?,
    ): T {
        var deadline = platform.now() + if (recovering) RECOVERY_AP_STOP_TIMEOUT_MS else timeoutMs
        var stationDisabledByApp = false
        var enableRequested = false
        var ready = false

        fun checkRunning(waitingForStop: Boolean = false) {
            if (cancelled() || Thread.currentThread().isInterrupted) {
                throw LegacyCarHotspotException("ap_cancelled", "已停止准备车载热点。")
            }
            if (platform.now() >= deadline) {
                if (waitingForStop) {
                    throw LegacyCarHotspotException("ap_stop_timeout", "系统未能及时关闭旧车载热点。")
                }
                throw LegacyCarHotspotException("ap_start_timeout", "车载热点未能及时获取本地地址，请检查系统热点。")
            }
        }

        fun waitForStateChange(waitingForStop: Boolean = false) {
            checkRunning(waitingForStop)
            val interval = if (recovering) 75L else POLL_MS
            platform.sleep(minOf(interval, deadline - platform.now()).coerceAtLeast(1L))
        }

        fun requireStationDisabled() {
            if (platform.stationState() != WifiManager.WIFI_STATE_DISABLED) throw stationStillOn()
        }

        fun stopStation(): Boolean {
            checkRunning()
            val stationStopDeadline = minOf(deadline, platform.now() + STATION_STOP_TIMEOUT_MS)
            when (platform.stationState()) {
                WifiManager.WIFI_STATE_ENABLED, WifiManager.WIFI_STATE_ENABLING -> {
                    onState("ap_station_disabling")
                    checkRunning()
                    if (!platform.setStationEnabled(false)) {
                        throw LegacyCarHotspotException("ap_station_disable_rejected", "系统未能从 Wi-Fi 客户端切换到车载热点。")
                    }
                    stationDisabledByApp = true
                }
                WifiManager.WIFI_STATE_DISABLING, WifiManager.WIFI_STATE_DISABLED -> Unit
                else -> throw stationStillOn()
            }
            while (platform.stationState() != WifiManager.WIFI_STATE_DISABLED) {
                if (platform.now() >= stationStopDeadline) return false
                waitForStateChange()
            }
            return true
        }

        try {
            checkRunning()
            var apState = platform.apState()
            if (recovering) {
                requireStationDisabled()
                while (apState == AP_DISABLING) {
                    waitForStateChange(waitingForStop = true)
                    requireStationDisabled()
                    apState = platform.apState()
                }
                checkRunning(waitingForStop = true)
                // Shutdown may consume the framework's tether timeout before the new request can run.
                deadline = platform.now() + timeoutMs
            }
            if (apState !in AP_DISABLED..AP_FAILED) throw apUnavailable()
            if (canStartAp(apState) || platform.stationState() != WifiManager.WIFI_STATE_DISABLED) {
                platform.validateStartup()
                checkRunning()
                apState = platform.apState()
                if (apState !in AP_DISABLED..AP_FAILED) throw apUnavailable()
                if (recovering) requireStationDisabled()
                else if (!stopStation()) {
                    if (!isActiveOrStarting(apState) || platform.apState() != AP_ENABLED ||
                        platform.stationState() !in WifiManager.WIFI_STATE_ENABLING..WifiManager.WIFI_STATE_ENABLED
                    ) throw stationStillOn()
                    // Android 4.2 can ignore a station stop while a SoftAP owns the driver.
                    // Rebuild once during preparation, before the phone uses this AP.
                    checkRunning()
                    onState("ap_radio_conflict_restarting")
                    checkRunning()
                    if (!platform.setApEnabled(false)) {
                        throw LegacyCarHotspotException("ap_conflict_stop_failed", "系统拒绝重建冲突的车载热点。")
                    }
                    while (!canStartAp(platform.apState())) waitForStateChange()
                    if (!stopStation()) throw stationStillOn()
                }

                // Some legacy drivers tear down an already-active AP when station mode stops.
                // Give that transition time to complete before sending any AP enable request.
                apState = platform.apState()
                while (apState == AP_DISABLING && stationDisabledByApp) {
                    waitForStateChange()
                    requireStationDisabled()
                    apState = platform.apState()
                }
                if (canStartAp(apState)) {
                    checkRunning()
                    requireStationDisabled()
                    if (!platform.setApEnabled(true)) {
                        throw LegacyCarHotspotException("ap_start_rejected", "系统拒绝自动开启车载热点。")
                    }
                    enableRequested = true
                    onState("ap_enable_requested")
                }
            }
            if (!enableRequested) {
                if (!isActiveOrStarting(apState)) throw apUnavailable()
                onState("ap_existing")
            }

            val requestedAt = platform.now()
            var apBeganEnabling = apState != AP_FAILED
            while (true) {
                checkRunning()
                requireStationDisabled()
                when (platform.apState()) {
                    AP_ENABLED -> {
                        apBeganEnabling = true
                        val snapshot = readReady()
                        if (snapshot != null) {
                            checkRunning()
                            requireStationDisabled()
                            if (platform.apState() == AP_ENABLED) {
                                ready = true
                                onState("ap_enabled_ready")
                                return snapshot
                            }
                        }
                    }
                    AP_ENABLING -> apBeganEnabling = true
                    AP_FAILED -> if (!enableRequested || apBeganEnabling || platform.now() - requestedAt >= AP_REQUEST_GRACE_MS) {
                        throw LegacyCarHotspotException("ap_system_failed", "系统启动热点失败，请检查车机热点设置。")
                    }
                    AP_DISABLING -> throw LegacyCarHotspotException("ap_stopped", "系统已关闭车载热点。")
                    AP_DISABLED -> if (!enableRequested || platform.now() - requestedAt >= AP_REQUEST_GRACE_MS) {
                        throw LegacyCarHotspotException("ap_stopped", "系统已关闭车载热点。")
                    }
                }
                waitForStateChange()
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LegacyCarHotspotException("ap_cancelled", "已停止准备车载热点。", interrupted)
        } finally {
            if (!ready && (enableRequested || stationDisabledByApp)) {
                val restored = rollback(platform, enableRequested, stationDisabledByApp)
                onState(if (restored) "ap_rollback" else "ap_rollback_incomplete")
            }
        }
    }

    private fun canStartAp(state: Int): Boolean = state == AP_DISABLED || state == AP_FAILED

    private fun isActiveOrStarting(state: Int): Boolean = state == AP_ENABLING || state == AP_ENABLED

    private fun apUnavailable() =
        LegacyCarHotspotException("ap_state_unavailable", "车载热点当前无法开启，请检查系统热点设置。")

    private fun stationStillOn() =
        LegacyCarHotspotException("ap_station_not_disabled", "车机 Wi-Fi 客户端尚未确认关闭，请先关闭 Wi-Fi，再开启车载热点。")

    private fun rollback(platform: Platform, ownsApRequest: Boolean, restoreStation: Boolean): Boolean {
        val wasInterrupted = Thread.interrupted()
        try {
            if (ownsApRequest && !platform.setApEnabled(false)) return false
            if (!restoreStation) return true
            if (ownsApRequest) {
                val deadline = platform.now() + ROLLBACK_AP_STOP_TIMEOUT_MS
                while (!canStartAp(platform.apState())) {
                    val remaining = deadline - platform.now()
                    if (remaining <= 0L) return false
                    platform.sleep(minOf(POLL_MS, remaining))
                }
            }
            return canStartAp(platform.apState()) && platform.setStationEnabled(true)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (_: Exception) {
            return false
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private class SystemPlatform(private val context: Context, private val manager: WifiManager) : Platform {
        private var apControl: java.lang.reflect.Method? = null

        override fun now(): Long = SystemClock.elapsedRealtime()
        override fun sleep(ms: Long) = Thread.sleep(ms)
        override fun apState(): Int = HybridNetwork.readApState(context, manager)
        override fun stationState(): Int = call { manager.wifiState }
        override fun setApEnabled(enabled: Boolean): Boolean {
            HotspotEvidence.captureRuntime(context, if (enabled) "diplay_ap_enable_requested" else "diplay_ap_stop_requested", -1, null, null)
            return call { requireNotNull(apControl).invoke(manager, null, enabled) == true }
        }
        override fun setStationEnabled(enabled: Boolean): Boolean {
            HotspotEvidence.captureRuntime(context, if (enabled) "diplay_station_enable_requested" else "diplay_station_disable_requested", -1, null, null)
            return call { manager.setWifiEnabled(enabled) }
        }

        override fun validateStartup() {
            if (context.checkCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
                throw LegacyCarHotspotException("ap_permission_missing", "DiPlay 无权改变车机 Wi-Fi 状态，请在系统设置中开启热点。")
            }
            if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0) {
                throw LegacyCarHotspotException("ap_airplane_mode", "车机处于飞行模式，请关闭飞行模式后重试。")
            }
            // Discover the mutator only when we need to enable AP, before switching off station Wi-Fi.
            apControl = try {
                manager.javaClass.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            } catch (failure: NoSuchMethodException) {
                throw LegacyCarHotspotException("ap_api_unavailable", "系统不支持自动开启车载热点。", failure)
            } catch (failure: SecurityException) {
                throw LegacyCarHotspotException("ap_api_unavailable", "无法访问车机热点控制。", failure)
            }
            val setup = try {
                HybridNetwork.readConfiguredWifiSetup(context)
            } catch (failure: HybridHotspotException) {
                throw configurationFailure(failure)
            }
            if (!setup.credential.available) throw missingPassphrase()
        }

        private inline fun <T> call(operation: () -> T): T = try {
            operation()
        } catch (failure: Exception) {
            val cause = (failure as? InvocationTargetException)?.targetException ?: failure
            throw LegacyCarHotspotException("ap_system_call_failed", "调用车机 Wi-Fi 服务失败，请检查系统热点设置。", cause)
        }
    }

    private fun missingPassphrase() =
        LegacyCarHotspotException("ap_passphrase_missing", "请在 DiPlay 的「车载热点」中填写当前热点口令。")

    private fun configurationFailure(failure: HybridHotspotException) =
        LegacyCarHotspotException(failure.reason, "请在车机系统设置中检查热点名称、加密和口令。", failure)
}
