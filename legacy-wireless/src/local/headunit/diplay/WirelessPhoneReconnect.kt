package local.headunit.diplay

/** Keep phone selection separate from the Bluetooth controller's stored link keys. */
internal object WirelessPhoneReconnect {
    private val bluetoothAddress = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
    private const val PAGE_TIMEOUT = 0x04
    private const val CONNECTION_TIMEOUT = 0x08
    private const val ACL_CONNECTION_ALREADY_EXISTS = 0x0b
    private const val COMMAND_DISALLOWED = 0x0c

    data class Selection(val remembered: String?, val reason: String)

    @JvmStatic
    fun isBluetoothAddress(value: String?): Boolean =
        value != null && value != "00:00:00:00:00:00" && bluetoothAddress.matches(value)

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
