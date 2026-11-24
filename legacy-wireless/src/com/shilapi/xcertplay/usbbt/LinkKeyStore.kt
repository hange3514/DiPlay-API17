package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.util.Log
import local.headunit.diplay.WirelessPhoneReconnect
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Persistent store for Bluetooth link keys (Phase 4).
 *
 * Keys are indexed by controller and peer BD_ADDR ("XX:XX:XX:XX:XX:XX"). Stored as hex in
 * SharedPreferences; never logged.
 *
 * Clean-room: standard Android persistence, no protocol specifics.
 */
class LinkKeyStore(private val appContext: Context, private val controllerAddress: String) {

    companion object {
        private const val TAG = "LinkKeyStore"
        private const val PREFS = "usbbt_linkkeys"
        private const val KEY_PREFIX = "lk_"
        private const val LEGACY_RESOLVED_PREFIX = "legacy_btstack_resolved_"
    }

    private val keyPrefix = KEY_PREFIX + controllerAddress.uppercase(Locale.US) + "_"
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The legacy journal is shared by adapters, so only recover the confirmed adapter/peer. */
    internal fun restoreLegacy(saved: String?): String {
        val remembered = WirelessPhoneReconnect.choose(saved, controllerAddress, reselect = false).remembered
            ?.uppercase(Locale.US) ?: return "not_applicable"
        val address = HciProtocol.encodeBdAddr(remembered)?.reversedArray() ?: return "not_applicable"
        val name = keyPrefix + remembered
        val resolved = LEGACY_RESOLVED_PREFIX + name
        if (prefs.contains(resolved)) return "resolved"
        if (load(remembered) != null) {
            prefs.edit().putBoolean(resolved, true).apply()
            return "new_key_present"
        }
        val legacy = File(appContext.filesDir, "btstack-linkkeys.tlv")
        if (!legacy.isFile) return "file_missing"
        val key = try {
            legacy.inputStream().buffered().use { LegacyBluetoothLinkKeys.read(it, address) }
        } catch (_: IOException) {
            return "read_error"
        } ?: return "no_valid_record"
        prefs.edit()
            .putString(name, key.joinToString("") { "%02x".format(it) })
            .putBoolean(resolved, true)
            .apply()
        return "migrated"
    }

    /** Store the 16-byte link key for [bdAddr]. */
    fun store(bdAddr: String, linkKey: ByteArray) {
        if (linkKey.size != 16) {
            Log.w(TAG, "refusing to store malformed link key (${linkKey.size} bytes)")
            return
        }
        val hex = linkKey.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(keyPrefix + bdAddr.uppercase(Locale.US), hex).apply()
        Log.i(TAG, "link key stored")
    }

    /** Load the link key for [bdAddr], or null when absent/malformed. */
    fun load(bdAddr: String): ByteArray? {
        val hex = prefs.getString(keyPrefix + bdAddr.uppercase(Locale.US), null) ?: return null
        if (hex.length != 32) return null
        return runCatching {
            ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    /** Forget the link key for [bdAddr]. */
    fun forget(bdAddr: String) {
        prefs.edit().remove(keyPrefix + bdAddr.uppercase(Locale.US)).apply()
    }

    /** All stored peer addresses. */
    fun peers(): Set<String> {
        return prefs.all.keys
            .filter { it.startsWith(keyPrefix) }
            .map { it.removePrefix(keyPrefix) }
            .toSet()
    }
}
