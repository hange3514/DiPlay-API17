package local.headunit.diplay

import com.shilapi.xcertplay.transport.LockdownPairingRejected
import com.shilapi.xcertplay.transport.WiredSessionMemory
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.TlsFatalAlertReceived
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WiredPairingPolicyTest {
    @Test fun usbModeTransitionCanDetectAnotherPhoneWithoutRejectingMissingSerials() {
        val original = WiredPairingPolicy.usbKey("00008130-ab1234567890cdef")
        val sameAfterModeChange = WiredPairingPolicy.usbKey("00008130-AB1234567890CDEF")
        val replacement = WiredPairingPolicy.usbKey("00008130-991234567890cdef")
        assertTrue(WiredPairingPolicy.isDifferentUsbPeer(original, replacement))
        assertFalse(WiredPairingPolicy.isDifferentUsbPeer(original, sameAfterModeChange))
        assertFalse(WiredPairingPolicy.isDifferentUsbPeer(original, null))
        assertFalse(WiredPairingPolicy.isDifferentUsbPeer(null, replacement))
    }

    @Test fun usbCacheScopeFollowsPhoneAcrossApi17Enumerations() {
        val first = WiredPairingPolicy.usbKey("00008130-ab1234567890cdef")
        val second = WiredPairingPolicy.usbKey("00008130-991234567890cdef")
        assertNotNull(first)
        assertEquals(first, WiredPairingPolicy.usbKey(" 00008130-AB1234567890CDEF "))
        assertNotEquals(first, second)
        assertFalse(first!!.contains("AB1234"))
        assertNull(WiredPairingPolicy.usbKey("00000000-0000000000000000"))
        assertNull(WiredPairingPolicy.usbKey(null))
    }

    @Test fun legacyMigrationRequiresMatchingDevicePublicKey() {
        val oneDevice = "-----BEGIN RSA PUBLIC KEY-----\nabcDEFxyz\n-----END RSA PUBLIC KEY-----".toByteArray()
        val wrappedDifferently = "-----BEGIN RSA PUBLIC KEY-----\r\nabcDEF\r\nxyz\n-----END RSA PUBLIC KEY-----".toByteArray()
        val anotherDevice = "-----BEGIN RSA PUBLIC KEY-----\nabcDEFFyz\n-----END RSA PUBLIC KEY-----".toByteArray()
        assertTrue(WiredPairingPolicy.matchesPublicKey(oneDevice, wrappedDifferently))
        assertFalse(WiredPairingPolicy.matchesPublicKey(oneDevice, anotherDevice))
        assertEquals(WiredPairingPolicy.publicKey(oneDevice), WiredPairingPolicy.publicKey(wrappedDifferently))
        assertNotEquals(WiredPairingPolicy.publicKey(oneDevice), WiredPairingPolicy.publicKey(anotherDevice))
    }

    @Test fun onlyCredentialRejectionTriggersPairRepair() {
        assertTrue(WiredPairingPolicy.needsRepair(LockdownPairingRejected("InvalidHostID")))
        assertTrue(WiredPairingPolicy.needsRepair(IOException(TlsFatalAlertReceived(AlertDescription.unknown_ca))))
        assertFalse(WiredPairingPolicy.needsRepair(IOException("StartService timed out")))
        assertFalse(WiredPairingPolicy.needsRepair(TlsFatalAlertReceived(AlertDescription.protocol_version)))
    }

    @Test fun unrelatedDetachCannotClearTheActiveEnumeration() {
        WiredSessionMemory.started("test-newer-enumeration")
        WiredSessionMemory.detached("test-older-enumeration")
        assertTrue(WiredSessionMemory.startedOn("test-newer-enumeration"))
        WiredSessionMemory.detached("test-newer-enumeration")
        assertFalse(WiredSessionMemory.startedOn("test-newer-enumeration"))
    }
}
