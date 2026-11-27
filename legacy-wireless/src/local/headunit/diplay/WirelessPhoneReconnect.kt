package local.headunit.diplay

import java.util.Locale

/** Keep phone selection separate from the Bluetooth controller's stored link keys. */
internal object WirelessPhoneReconnect {
    private val bluetoothAddress = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
    private const val PAGE_TIMEOUT = 0x04
    private const val PIN_OR_KEY_MISSING = 0x06
    private const val CONNECTION_TIMEOUT = 0x08
    private const val ACL_CONNECTION_ALREADY_EXISTS = 0x0b
    private const val COMMAND_DISALLOWED = 0x0c

    data class Selection(val remembered: String?, val reason: String)

    @JvmStatic
    fun isBluetoothAddress(value: String?): Boolean =
        value != null && value != "00:00:00:00:00:00" && bluetoothAddress.matches(value)

    @JvmStatic
    fun canContinueSelection(startedAt: Long, now: Long): Boolean =
        startedAt in 1..now && now - startedAt < 180_000L

    fun choose(saved: String?, adapter: String, reselect: Boolean): Selection {
        if (reselect) return Selection(null, "user_requested")
        if (saved.isNullOrEmpty()) return Selection(null, "no_saved_phone")
        val entry = saved.split('|', limit = 2)
        if (entry.size != 2 || !isBluetoothAddress(entry[0]) || !isBluetoothAddress(entry[1])) {
            return Selection(null, "invalid_saved_phone")
        }
        if (!entry[0].equals(adapter, ignoreCase = true)) return Selection(null, "different_adapter")
        return Selection(entry[1], "remembered")
    }

    fun chooseForAdapter(
        projected: String?, saved: String?, legacy: String?, adapter: String, reselect: Boolean,
    ): Selection {
        if (reselect) return choose(null, adapter, true)
        val lastProjected = choose(projected, adapter, false)
        if (lastProjected.remembered != null) return lastProjected
        val lastSaved = choose(saved, adapter, false)
        if (lastSaved.remembered != null) return lastSaved
        val migrated = choose(legacy, adapter, false)
        return if (migrated.remembered != null || saved == null) migrated else lastSaved
    }

    /** Availability is established by paging an existing bond, not by discoverability in Settings. */
    fun bondedCandidates(preferred: String?, bonded: Collection<String>, unavailablePhones: Collection<String> = emptySet()): List<String> {
        val unavailable = unavailablePhones.map { it.uppercase(Locale.US) }.toSet()
        val peers = bonded.filter(::isBluetoothAddress).map { it.uppercase(Locale.US) }
            .distinct().filterNot { it in unavailable }.sorted()
        val first = preferred?.uppercase(Locale.US)
        return if (first != null && first in peers) listOf(first) + peers.filterNot { it == first } else peers
    }

    fun matchesAirPlayController(controller: String?, expected: String?, otherControllers: Set<String>): Boolean =
        !controller.isNullOrBlank() && (expected == null || controller == expected) && controller !in otherControllers

    fun otherKnownControllers(values: Map<String, *>, prefix: String, selectedPhone: String): Set<String> {
        val selectedControllers = HashSet<String>()
        val otherControllers = HashSet<String>()
        for ((key, value) in values) {
            if (value !is String || value.isBlank() || !key.startsWith("$prefix.") || !key.endsWith(".controller")) continue
            val ownerKey = key.removeSuffix(".controller")
            val detail = ownerKey.removePrefix("$prefix.").split(".verified", limit = 2)
            if (detail.size != 2 || !isBluetoothAddress(detail[0])) continue
            val phone = when {
                detail[1].startsWith(".peer.") -> detail[1].removePrefix(".peer.")
                detail[1].isEmpty() -> choose(values[ownerKey] as? String, detail[0], false).remembered
                else -> null
            }
            if (!isBluetoothAddress(phone)) continue
            if (selectedPhone.equals(phone, ignoreCase = true)) selectedControllers.add(value)
            else otherControllers.add(value)
        }
        return otherControllers - selectedControllers
    }

    fun shouldDiscoverAfterPageFailure(stage: String, status: Int): Boolean =
        stage == "outbound_acl_failed" && (status == PAGE_TIMEOUT || status == CONNECTION_TIMEOUT)

    fun canTryNextBondedPhone(stage: String, status: Int): Boolean =
        stage == "outbound_acl_wait_timeout" || shouldDiscoverAfterPageFailure(stage, status) ||
            stage == "outbound_sdp_no_iap2_service" ||
            (stage == "outbound_auth_failed" && status == PIN_OR_KEY_MISSING)

    fun retryDelayMs(
        stage: String, status: Int, remembered: Boolean, retries: Int, attemptElapsedMs: Long
    ): Long {
        if (stage != "outbound_acl_failed") return 0L
        // A connection can race a controller still completing inquiry or an incoming page.
        if (status == COMMAND_DISALLOWED) return if (retries == 0) 650L else 0L
        if (!remembered || status != ACL_CONNECTION_ALREADY_EXISTS) return 0L
        val retryIntervalMs = when (retries) {
            0 -> 3000L
            1 -> 7000L
            else -> return 0L
        }
        // Count the time already spent on the outgoing page. Keep a quiet period after
        // a terminal collision so calls do not loop quickly against the same radio state.
        return (retryIntervalMs - attemptElapsedMs.coerceAtLeast(0L)).coerceAtLeast(1500L)
    }

    fun isTransientAclFailure(stage: String, status: Int): Boolean {
        if (stage == "outbound_acl_wait_timeout") return true
        // This stage carries a terminal HCI controller status, separate from SDP and RFCOMM.
        if (stage != "outbound_acl_failed") return false
        return when (status) {
            PAGE_TIMEOUT, CONNECTION_TIMEOUT, ACL_CONNECTION_ALREADY_EXISTS -> true
            else -> false
        }
    }

    fun confirmedRecord(adapter: String, selected: String?, connected: String?): String? =
        selected?.takeIf {
            isBluetoothAddress(adapter) && isBluetoothAddress(it) && it.equals(connected, ignoreCase = true)
        }?.let { "$adapter|$it" }
}
