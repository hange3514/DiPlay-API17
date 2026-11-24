package local.headunit.diplay

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

/** Wireless credential with a redacted diagnostic representation. */
class HybridWifiCredential(
    val passphrase: String?,
    val security: Iap2WirelessSecurity,
    val source: String,
    val configuredNetworkMatched: Boolean,
) {
    val available: Boolean get() = security == Iap2WirelessSecurity.NONE || passphrase != null
    override fun toString(): String = "HybridWifiCredential(available=$available, security=$security, source=$source, configuredNetworkMatched=$configuredNetworkMatched)"
}

class HybridWifiSetup internal constructor(
    val ssid: String,
    val credential: HybridWifiCredential,
    val hasSavedPassphrase: Boolean,
)

class HybridHotspotException(val reason: String, message: String) : IllegalStateException(message)

/** One verified binding on the head unit's local Wi-Fi access point. */
class HybridNetworkSnapshot internal constructor(
    val ssid: String,
    val localAddress: InetAddress,
    val wifiLinkLocalIpv6: Inet6Address?,
    val macAddress: String,
    val apBssid: ByteArray?,
    val interfaceName: String,
    val networkMask: String,
    val frequencyMhz: Int,
    val capturedAtElapsedMs: Long,
    val credential: HybridWifiCredential,
    val frequencySource: String,
    val bindingSource: String,
) {
    /** Compatibility with diagnostics shared by the connection flow. A SoftAP has no station ID or RSSI. */
    val networkId: Int get() = -1
    val linkSpeedMbps: Int get() = -1
    val rssiDbm: Int get() = -1
    val scanResultAgeMs: Long get() = -1L
    val addressMatchesWifiInfo: Boolean get() = false
    val gatewayAddress: String get() = localAddress.hostAddress.orEmpty()
    val channelNumber: Int get() = HybridNetwork.channelForFrequency(frequencyMhz)

    fun safeSummary(): String = "ssidConfigured=true interfaceName=$interfaceName " +
        "localAddress=$gatewayAddress gatewayAddress=$gatewayAddress networkMask=$networkMask " +
        "frequencyMhz=$frequencyMhz frequencySource=$frequencySource channel=$channelNumber " +
        "credentialAvailable=${credential.available} credentialSource=${credential.source} " +
        "configuredNetworkMatched=${credential.configuredNetworkMatched} security=${credential.security} " +
        "apBssidPresent=${apBssid != null} bindingSource=$bindingSource"
}

object HybridNetwork {
    private const val CREDENTIAL_PREFS = "wireless_headunit_ap"
    private const val AP_STATE_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val TETHER_STATE_ACTION = "android.net.conn.TETHER_STATE_CHANGED"
    private const val AP_ENABLED = 13
    private const val AUTH_WPA2_PSK = 4 // @hide on Android 4.2.2.
    private const val MX9A0_AP_GATEWAY = "192.168.43.1"
    private val wifiInterfaceName = Regex("(?:wlan|wifi|ap|softap|swlan)[0-9][A-Za-z0-9_.-]{0,10}")
    private val nonWifiInterfaceName = Regex("(?:p2p|rndis|usb|eth|bt|rmnet|tun|ppp|lo|ncm|veth|dummy)[0-9].*", RegexOption.IGNORE_CASE)
    private val safeInterfaceName = Regex("[A-Za-z][A-Za-z0-9_.-]{0,15}")
    private val rawPsk = Regex("[0-9a-fA-F]{64}")

    internal data class ApCandidate(val interfaceName: String, val ipv4: Inet4Address, val prefixLength: Int)
    internal data class ApBinding(val candidate: ApCandidate, val source: String)

    @JvmStatic
    fun isApEnabled(context: Context): Boolean = wifiManager(context)?.let { apEnabled(context, it) } ?: false

    @JvmStatic
    fun isHotspotPassphrase(value: String): Boolean =
        value.length in 8..63 && value.all { it in ' '..'~' }

    @JvmStatic
    fun currentWifiSetup(context: Context): HybridWifiSetup? = runCatching { requireWifiSetup(context) }.getOrNull()

    @JvmStatic
    fun requireWifiSetup(context: Context): HybridWifiSetup = readWifiSetup(context, requireApConfiguration(context))

    @JvmStatic
    fun readConfiguredWifiSetup(context: Context): HybridWifiSetup {
        val manager = wifiManager(context)
            ?: throw HybridHotspotException("wifi_service_unavailable", "Wi-Fi service unavailable")
        val config = apConfiguration(manager)
            ?: throw HybridHotspotException("ap_config_unavailable", "Cannot read the head unit hotspot configuration")
        return readWifiSetup(context, config)
    }

    @JvmStatic
    fun isCurrentWifiSetup(context: Context, setup: HybridWifiSetup): Boolean {
        val current = runCatching { readConfiguredWifiSetup(context) }.getOrNull() ?: return false
        return sameCredentials(current, setup)
    }

    @JvmStatic
    fun saveHotspotPassphrase(context: Context, setup: HybridWifiSetup, passphrase: String): Boolean {
        if (!isHotspotPassphrase(passphrase) || setup.credential.security != Iap2WirelessSecurity.WPA_WPA2 ||
            !isCurrentWifiSetup(context, setup)) return false
        context.getSharedPreferences(CREDENTIAL_PREFS, Context.MODE_PRIVATE)
            .edit().putString(apCredentialKey(setup.ssid, setup.credential.security), passphrase).apply()
        return true
    }

    @JvmStatic
    fun clearHotspotPassphrase(context: Context, setup: HybridWifiSetup): Boolean {
        if (!isCurrentWifiSetup(context, setup)) return false
        context.getSharedPreferences(CREDENTIAL_PREFS, Context.MODE_PRIVATE)
            .edit().remove(apCredentialKey(setup.ssid, setup.credential.security)).apply()
        return true
    }

    @Throws(IOException::class)
    fun requireCurrentConnection(
        context: Context,
        before: HybridNetworkSnapshot,
        verifyIpv6: Boolean = true,
    ): HybridNetworkSnapshot {
        val now = try {
            requireConnected(context)
        } catch (unavailable: IllegalStateException) {
            throw IOException("Head unit hotspot became unavailable", unavailable)
        }
        if (before.localAddress != now.localAddress || before.interfaceName != now.interfaceName ||
            before.networkMask != now.networkMask || before.ssid != now.ssid ||
            before.macAddress != now.macAddress ||
            (before.frequencyMhz > 0 && now.frequencyMhz > 0 && before.frequencyMhz != now.frequencyMhz) ||
            before.credential.security != now.credential.security ||
            before.credential.passphrase != now.credential.passphrase ||
            (verifyIpv6 && before.wifiLinkLocalIpv6 != null &&
                (before.wifiLinkLocalIpv6 != now.wifiLinkLocalIpv6 ||
                 before.wifiLinkLocalIpv6.scopeId != now.wifiLinkLocalIpv6?.scopeId))) {
            throw IOException("Head unit hotspot changed after binding the AirPlay listener")
        }
        return now
    }

    fun requireConnected(context: Context): HybridNetworkSnapshot {
        val config = requireApConfiguration(context)
        val setup = readWifiSetup(context, config)
        val candidates = readApCandidates()
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = (network?.let { tetherNames(it, "getTetheredIfaces") } ?: stickyTetherNames(context, "activeArray")).toSet()
        val patterns = network?.let { tetherNames(it, "getTetherableWifiRegexs") }.orEmpty()
            .filter { it.length in 1..96 }
            .mapNotNull { runCatching { Regex(it) }.getOrNull() }
        val expectedGateway = if (Build.VERSION.SDK_INT == 17 && "freescale".equals(Build.MANUFACTURER, true) &&
            "mx9a0".equals(Build.MODEL, true)) MX9A0_AP_GATEWAY else null
        val chosen = selectApBinding(candidates, active, patterns, expectedGateway)
            ?: throw IllegalStateException("Head unit hotspot has no verified Wi-Fi interface and IPv4 gateway")
        val local = chosen.candidate
        val iface = runCatching { NetworkInterface.getByName(local.interfaceName) }.getOrNull()
            ?: throw IllegalStateException("Head unit hotspot interface disappeared")
        if (!runCatching { iface.isUp && iface.interfaceAddresses.any {
                it.address == local.ipv4 && it.networkPrefixLength.toInt() == local.prefixLength
            } }.getOrDefault(false) || !isApEnabled(context)) {
            throw IllegalStateException("Head unit hotspot changed while preparing the AirPlay address")
        }
        val linkLocal = runCatching {
            iface.inetAddresses?.let(Collections::list).orEmpty().filterIsInstance<Inet6Address>()
                .firstOrNull { it.isLinkLocalAddress }
                ?.let { Inet6Address.getByAddress(null, it.address, iface) }
                ?.takeIf { it.scopeId != 0 }
        }.getOrNull()
        val interfaceMac = runCatching { iface.hardwareAddress?.takeIf(::validAccessPointBssid) }.getOrNull()
        val mac = interfaceMac?.let(::formatMac) ?: localAirPlayIdentity(context)
        val channel = runCatching { config.javaClass.getField("apChannel").getInt(config) }.getOrDefault(0)
        val frequency = frequencyForChannel(channel)
        return HybridNetworkSnapshot(
            // The API 17 interface MAC identifies this receiver. The beacon BSSID is separate.
            setup.ssid, local.ipv4, linkLocal, mac, null, local.interfaceName,
            prefixMask(local.prefixLength), frequency, SystemClock.elapsedRealtime(),
            setup.credential, if (frequency > 0) "ap_configuration" else "unavailable", chosen.source,
        )
    }

    private fun wifiManager(context: Context): WifiManager? =
        (context.applicationContext ?: context).getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private fun apEnabled(context: Context, manager: WifiManager): Boolean =
        readApState(context, manager) == AP_ENABLED

    internal fun readApState(context: Context, manager: WifiManager): Int {
        val state = runCatching { (manager.javaClass.getMethod("getWifiApState").invoke(manager) as? Int) ?: -1 }.getOrDefault(-1)
        if (state in 10..14) return state
        val enabled = runCatching { manager.javaClass.getMethod("isWifiApEnabled").invoke(manager) as? Boolean }
            .getOrNull()
        if (enabled == true) return AP_ENABLED
        val broadcastState = sticky(context, AP_STATE_ACTION)?.getIntExtra("wifi_state", -1) ?: -1
        if (broadcastState in 10..14 && (enabled != false || broadcastState != AP_ENABLED)) return broadcastState
        return if (enabled == false) 11 else -1
    }

    private fun apConfiguration(manager: WifiManager): WifiConfiguration? =
        runCatching { manager.javaClass.getMethod("getWifiApConfiguration").invoke(manager) as? WifiConfiguration }
            .getOrNull()

    private fun requireApConfiguration(context: Context): WifiConfiguration {
        val manager = wifiManager(context) ?: throw HybridHotspotException("wifi_service_unavailable", "Wi-Fi service unavailable")
        if (!apEnabled(context, manager)) {
            throw HybridHotspotException("ap_not_enabled", "Enable the head unit hotspot in system settings")
        }
        return apConfiguration(manager)
            ?: throw HybridHotspotException("ap_config_unavailable", "Cannot read the head unit hotspot configuration")
    }

    private fun sameCredentials(first: HybridWifiSetup, second: HybridWifiSetup): Boolean =
        first.ssid == second.ssid && first.credential.security == second.credential.security &&
            first.credential.passphrase == second.credential.passphrase

    private fun apCredentialKey(ssid: String, security: Iap2WirelessSecurity): String =
        security.wireValue.toString() + ":" + Base64.encodeToString(ssid.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun readWifiSetup(context: Context, config: WifiConfiguration): HybridWifiSetup {
        val ssid = validSsid(config.SSID)
            ?: throw HybridHotspotException("ap_ssid_invalid", "Head unit hotspot SSID is missing or invalid")
        val configured = config.allowedKeyManagement
        val security = apSecurity(
            configured?.get(WifiConfiguration.KeyMgmt.NONE) == true,
            configured?.get(WifiConfiguration.KeyMgmt.WPA_PSK) == true,
            configured?.get(AUTH_WPA2_PSK) == true,
            config.wepKeys?.any { !it.isNullOrEmpty() } == true,
            configured?.isEmpty != false,
            !config.preSharedKey.isNullOrEmpty(),
        ) ?: throw HybridHotspotException("ap_security_unsupported", "Use WPA2-PSK or an open head unit hotspot")
        val stored = context.getSharedPreferences(CREDENTIAL_PREFS, Context.MODE_PRIVATE)
            .getString(apCredentialKey(ssid, security), null)
        val saved = stored?.takeIf(::isHotspotPassphrase)
        return HybridWifiSetup(ssid, selectCredential(security, config.preSharedKey, saved), saved != null)
    }

    internal fun validSsid(raw: String?): String? = raw?.takeIf {
        it.isNotBlank() && !it.equals("<unknown ssid>", true) && '\u0000' !in it &&
            it.toByteArray(Charsets.UTF_8).size <= 32
    }

    internal fun apSecurity(
        open: Boolean,
        wpa: Boolean,
        wpa2: Boolean,
        hasWep: Boolean,
        noAuthFlags: Boolean,
        hasKey: Boolean,
    ): Iap2WirelessSecurity? = when {
        hasWep -> null
        wpa || wpa2 || (noAuthFlags && hasKey) -> Iap2WirelessSecurity.WPA_WPA2
        open -> Iap2WirelessSecurity.NONE
        else -> null
    }

    internal fun selectCredential(
        security: Iap2WirelessSecurity,
        androidKey: String?,
        savedApPassphrase: String?,
    ): HybridWifiCredential {
        if (security == Iap2WirelessSecurity.NONE) {
            return HybridWifiCredential("", security, "android_softap_configuration_open", true)
        }
        val systemPassphrase = androidKey?.takeIf(::isHotspotPassphrase)
        val fallback = savedApPassphrase?.takeIf(::isHotspotPassphrase)
        val source = when {
            systemPassphrase != null -> "android_softap_configuration"
            fallback != null -> "app_softap_credential"
            androidKey == "*" -> "android_softap_configuration_masked"
            androidKey != null && rawPsk.matches(androidKey) -> "android_softap_configuration_raw_psk"
            else -> "android_softap_configuration_no_passphrase"
        }
        return HybridWifiCredential(systemPassphrase ?: fallback, security, source, true)
    }

    private fun sticky(context: Context, action: String): Intent? =
        runCatching { context.registerReceiver(null, IntentFilter(action)) }.getOrNull()

    private fun stickyTetherNames(context: Context, key: String): List<String> {
        val intent = sticky(context, TETHER_STATE_ACTION) ?: return emptyList()
        return runCatching {
            intent.getStringArrayListExtra(key)?.toList() ?: intent.getStringArrayExtra(key)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun tetherNames(manager: ConnectivityManager, method: String): List<String>? =
        runCatching { (manager.javaClass.getMethod(method).invoke(manager) as? Array<*>)?.filterIsInstance<String>() }.getOrNull()

    private fun readApCandidates(): List<ApCandidate> =
        runCatching { NetworkInterface.getNetworkInterfaces()?.let(Collections::list).orEmpty() }
            .getOrDefault(emptyList())
            .flatMap { network ->
                if (!safeInterfaceName.matches(network.name.orEmpty())) return@flatMap emptyList<ApCandidate>()
                runCatching {
                    if (!network.isUp || network.isLoopback) return@runCatching emptyList<ApCandidate>()
                    network.interfaceAddresses.mapNotNull { address ->
                        (address.address as? Inet4Address)?.let {
                            ApCandidate(network.name, it, address.networkPrefixLength.toInt())
                        }
                    }
                }.getOrDefault(emptyList())
            }

    internal fun selectApBinding(
        candidates: List<ApCandidate>,
        activeTethers: Set<String>,
        wifiPatterns: List<Regex>,
        knownGateway: String?,
    ): ApBinding? {
        val wifi = candidates.distinct().filter {
            val name = it.interfaceName
            safeInterfaceName.matches(name) && !nonWifiInterfaceName.matches(name) &&
                (wifiInterfaceName.matches(name.lowercase(Locale.US)) || wifiPatterns.any { p -> p.matches(name) }) &&
                it.prefixLength in 1..30 && usableIpv4(it.ipv4)
        }
        val active = wifi.filter { it.interfaceName in activeTethers }
        val expected = wifi.filter { it.ipv4.hostAddress == knownGateway }
        return when {
            active.size == 1 -> ApBinding(active.single(), "active_wifi_tether")
            active.size > 1 -> active.singleOrNull { it.ipv4.hostAddress == knownGateway }
                ?.let { ApBinding(it, "active_wifi_tether_gateway") }
            else -> expected.singleOrNull()?.let { ApBinding(it, "mx9a0_gateway") }
        }
    }

    private fun usableIpv4(address: Inet4Address): Boolean =
        !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
            !address.isMulticastAddress && (address.address[0].toInt() and 0xff) in 1..223

    internal fun prefixMask(prefix: Int): String = InetAddress.getByAddress(
        ByteArray(4) { index ->
            val bits = (prefix - index * 8).coerceIn(0, 8)
            ((0xff shl (8 - bits)) and 0xff).toByte()
        }
    ).hostAddress.orEmpty()

    private fun frequencyForChannel(channel: Int): Int = when (channel) {
        in 1..13 -> 2407 + 5 * channel
        14 -> 2484
        in 32..177 -> 5000 + 5 * channel
        else -> -1
    }

    internal fun channelForFrequency(frequencyMhz: Int): Int = when {
        frequencyMhz in 2412..2472 && (frequencyMhz - 2407) % 5 == 0 -> (frequencyMhz - 2407) / 5
        frequencyMhz == 2484 -> 14
        frequencyMhz in 5160..5885 && (frequencyMhz - 5000) % 5 == 0 -> (frequencyMhz - 5000) / 5
        else -> 0
    }

    internal fun validAccessPointBssid(bytes: ByteArray): Boolean =
        bytes.size == 6 && bytes.any { it.toInt() != 0 } && bytes.any { (it.toInt() and 0xff) != 0xff } &&
            (bytes[0].toInt() and 1) == 0 &&
            !(bytes[0].toInt() == 2 && (1 until bytes.size).all { bytes[it].toInt() == 0 })

    private fun localAirPlayIdentity(context: Context): String {
        val id = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull().orEmpty()
        return stableBluetoothIdentity("headunit-ap:" + context.packageName + ":" + id)
    }

    private fun formatMac(bytes: ByteArray): String = bytes.joinToString(":") { "%02x".format(it.toInt() and 0xff) }

    fun stableBluetoothIdentity(stableId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(stableId.toByteArray(Charsets.UTF_8))
        val bytes = digest.copyOfRange(0, 6)
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return formatMac(bytes)
    }
}
