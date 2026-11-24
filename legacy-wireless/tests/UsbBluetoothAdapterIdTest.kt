package local.headunit.diplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBluetoothAdapterIdTest {
    @Test fun realtekCm390RevisionsAreEligibleForFirmware() {
        for (vendor in listOf(0x2b89, 0x0bda)) {
            for (product in listOf(0x8771, 0x6275, 0x8761)) {
                assertTrue(UsbBluetoothProbe.isKnownRealtek(vendor, product))
                assertTrue(UsbBluetoothProbe.usesBundledRtl8761BuFirmware(vendor, product))
                assertFalse(UsbBluetoothProbe.isKnownAts2851(vendor, product))
            }
        }
    }

    @Test fun supportedAdaptersCanWaitForTheSystemGrantAtBoot() {
        assertTrue(UsbBluetoothProbe.hasUsbAttachHandler(0x2b89, 0x8771))
        assertTrue(UsbBluetoothProbe.hasUsbAttachHandler(0x2b89, 0x6275))
        assertTrue(UsbBluetoothProbe.hasUsbAttachHandler(0x0bda, 0x8771))
        assertTrue(UsbBluetoothProbe.hasUsbAttachHandler(0x10d7, 0xb012))
        assertFalse(UsbBluetoothProbe.hasUsbAttachHandler(0x10d7, 0xb013))
    }

    @Test fun cm591RemainsOnTheActionsPath() {
        assertTrue(UsbBluetoothProbe.isKnownAts2851(0x10d7, 0xb012))
        assertFalse(UsbBluetoothProbe.isKnownRealtek(0x10d7, 0xb012))
        assertFalse(UsbBluetoothProbe.usesBundledRtl8761BuFirmware(0x10d7, 0xb012))
    }
}
