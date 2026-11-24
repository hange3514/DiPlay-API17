package local.headunit.diplay

internal fun isBluetoothHandoffCommand(type: String): Boolean =
    type.equals("disableBluetooth", ignoreCase = true) || type.equals("disable-bluetooth", ignoreCase = true)

/** Keep the bootstrap link until this same projected phone has a verified Wi-Fi tunnel. */
internal fun shouldReleaseBluetoothAfterHandoff(
    requestedBy: Any?,
    activeSession: Any?,
    bluetoothPeerVerified: Boolean,
    wifiTunnelReady: Boolean,
): Boolean = requestedBy != null && requestedBy === activeSession && bluetoothPeerVerified && wifiTunnelReady
