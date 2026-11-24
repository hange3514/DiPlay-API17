package local.headunit.diplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothHandoffPolicyTest {
    @Test fun releaseRequiresTheActivePhonesRequestAndAnAuthenticatedWifiTunnel() {
        val activePhone = Any()
        val previousPhone = Any()
        assertFalse(shouldReleaseBluetoothAfterHandoff(null, activePhone, true, true))
        assertFalse(shouldReleaseBluetoothAfterHandoff(previousPhone, activePhone, true, true))
        assertFalse(shouldReleaseBluetoothAfterHandoff(activePhone, activePhone, false, true))
        assertFalse(shouldReleaseBluetoothAfterHandoff(activePhone, activePhone, true, false))
        assertTrue(shouldReleaseBluetoothAfterHandoff(activePhone, activePhone, true, true))
    }

    @Test fun acceptsTheTwoBluetoothHandoffCommandsUsedUpstream() {
        assertTrue(isBluetoothHandoffCommand("disableBluetooth"))
        assertTrue(isBluetoothHandoffCommand("disable-bluetooth"))
        assertFalse(isBluetoothHandoffCommand("enableBluetooth"))
        assertFalse(isBluetoothHandoffCommand("modesChanged"))
    }
}
