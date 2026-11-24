package local.headunit.diplay

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.provider.Settings
import java.lang.reflect.InvocationTargetException

/** A user-selected change to the vehicle's persistent mobile data setting on the MX9A0 ROM. */
internal object VehicleMobileData {
    private const val DISCONNECT_TIMEOUT_MS = 8000L
    private const val CHECK_INTERVAL_MS = 100L

    fun disableBeforeHotspot(context: Context, cancelled: () -> Boolean, onPending: () -> Unit) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: throw unavailable()
        try {
            awaitDisconnected(object : Platform {
                override fun now() = SystemClock.elapsedRealtime()
                override fun sleep(ms: Long) = Thread.sleep(ms)
                override fun settingEnabled() = Settings.Global.getInt(context.contentResolver, "mobile_data", 1) != 0
                override fun hasActiveMobileNetwork() = manager.allNetworkInfo.any { info ->
                    // The MX9A0 tethering overlay can use mobile, HIPRI or the configured DUN APN.
                    info.isConnectedOrConnecting && when (info.type) {
                        ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_MOBILE_HIPRI,
                        ConnectivityManager.TYPE_MOBILE_DUN -> true
                        else -> false
                    }
                }
                override fun requestDisable() {
                    // The OEM API 17 ConnectivityService accepts CHANGE_NETWORK_STATE for this call.
                    ConnectivityManager::class.java.getMethod("setMobileDataEnabled", Boolean::class.javaPrimitiveType)
                        .invoke(manager, false)
                }
            }, cancelled, onPending)
        } catch (failure: InvocationTargetException) {
            throw unavailable(failure.cause ?: failure)
        } catch (failure: NoSuchMethodException) {
            throw unavailable(failure)
        } catch (failure: IllegalAccessException) {
            throw unavailable(failure)
        } catch (failure: RuntimeException) {
            throw unavailable(failure)
        }
    }

    internal interface Platform {
        fun now(): Long
        fun sleep(ms: Long)
        fun settingEnabled(): Boolean
        fun hasActiveMobileNetwork(): Boolean
        fun requestDisable()
    }

    internal fun awaitDisconnected(platform: Platform, cancelled: () -> Boolean, onPending: () -> Unit) {
        fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted) {
                throw InterruptedException("CarPlay connection was cancelled")
            }
        }
        checkCancelled()
        val shouldDisable = platform.settingEnabled()
        if (!shouldDisable && !platform.hasActiveMobileNetwork()) return
        onPending()
        checkCancelled()
        val deadline = platform.now() + DISCONNECT_TIMEOUT_MS
        if (shouldDisable) platform.requestDisable()
        while (true) {
            checkCancelled()
            if (!platform.settingEnabled() && !platform.hasActiveMobileNetwork()) return
            val remaining = deadline - platform.now()
            if (remaining <= 0L) {
                throw LegacyCarHotspotException("mobile_data_disconnect_timeout", "车机移动网络未确认断开。可在系统中手动关闭移动数据，或关闭此连接选项后重试。")
            }
            platform.sleep(minOf(CHECK_INTERVAL_MS, remaining))
        }
    }

    private fun unavailable(cause: Throwable? = null) = LegacyCarHotspotException(
        "mobile_data_switch_unavailable", "车机无法自动关闭移动数据。可在系统中手动关闭，或关闭此连接选项后重试。", cause,
    )
}
