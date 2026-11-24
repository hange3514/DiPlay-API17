package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2LinkHandoffDiagnosticsTest {
    @Test
    fun unlimitedDriveStillHasABoundedIdentificationAndAuthenticationWindow() {
        var now = 10_000_000L
        val unlimited = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS
        val authentication = WirelessControlDeadline.forAuthentication(unlimited) { now }
        val drive = WirelessControlDeadline(unlimited) { now }
        assertEquals(60_000L, authentication.requireRemaining())
        now += 59_999_000_000L
        assertEquals(1L, authentication.requireRemaining())
        now += 1_000_000L
        assertEquals(0L, authentication.remainingMillis())
        assertTrue(drive.requireRemaining() > 0L)
    }

    @Test
    fun bluetoothAuthenticationRetainsItsOwnBudgetAndFrameAccountingMatchesWireLength() {
        var now = 10_000_000L
        val authentication = WirelessControlDeadline.forAuthentication(45_000L) { now }
        now += 40_000_000_000L
        assertEquals(5_000L, authentication.requireRemaining())
        for (size in listOf(0, 16, 65_529)) {
            val frame = Iap2Frame(0x4301, ByteArray(size))
            assertEquals(frame.encodedFrame().size, frame.encodedSize)
        }
    }

    @Test
    fun handoffPacketsHaveDistinctPeerAcknowledgements() {
        val stages = mutableListOf<Pair<String, Int>>()
        val accessory = Iap2LinkEngine(onLinkDiagnostic = { stage, sequence, _, _ ->
            stages += stage to sequence
        })
        val phone = Iap2LinkEngine()
        accessory.start(wiredInitiator = true, nowMillis = 0L)
        phone.start(wiredInitiator = false, nowMillis = 0L)
        repeat(6) {
            phone.feed(accessory.takeOutput(), 1L)
            accessory.feed(phone.takeOutput(), 1L)
        }
        assertTrue(accessory.writable())
        assertTrue(phone.writable())

        accessory.sendControl(Iap2Frame(0x5703, byteArrayOf(1, 2)).encodedFrame(), 2L)
        accessory.sendControl(Iap2Frame(0x4301, byteArrayOf(3, 4)).encodedFrame(), 2L)
        assertFalse(stages.any { it.first.endsWith("link_ack") })
        phone.feed(accessory.takeOutput(), 3L)
        phone.advanceTime(1000L)
        accessory.feed(phone.takeOutput(), 1001L)

        for (name in listOf("wifi_config", "carplay_start")) {
            val sent = stages.single { it.first == "${name}_link_tx" }
            val acknowledged = stages.single { it.first == "${name}_link_ack" }
            assertEquals(sent.second, acknowledged.second)
        }
    }

    @Test
    fun fragmentsAndOtherSessionsDoNotProduceHandoffLabels() {
        val session = Iap2LinkEngine.CONTROL_SESSION_ID
        val complete = Iap2Frame(0x5703, ByteArray(16)).encodedFrame()
        assertEquals("wifi_config", wirelessHandoffFrameLabel(session, complete))
        assertNull(wirelessHandoffFrameLabel(session, complete.copyOf(12)))
        assertNull(wirelessHandoffFrameLabel(session, complete + byteArrayOf(0)))
        assertNull(wirelessHandoffFrameLabel(session + 1, complete))
        assertNull(wirelessHandoffFrameLabel(session, Iap2Frame(0x5702, ByteArray(0)).encodedFrame()))
    }
}
