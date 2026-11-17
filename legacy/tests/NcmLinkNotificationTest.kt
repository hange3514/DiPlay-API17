package local.headunit.diplay

import org.junit.Assert.*
import org.junit.Test

class NcmLinkNotificationTest {
    @Test fun standardCarrierNotificationRequiresControlInterface() {
        val decoder = NcmStatusNotificationDecoder()
        val packet = notification(code = 0, value = 1, index = 3, payload = byteArrayOf())
        val decoded = decoder.feed(packet, packet.size).single()

        assertEquals(true, NcmLinkNotification.connected(decoded, controlInterface = 3))
        assertNull(NcmLinkNotification.connected(decoded, controlInterface = 4))
        assertEquals(true, NcmLinkNotification.connected(packet, packet.size))
        val disconnected = notification(code = 0, value = 0, index = 3, payload = byteArrayOf())
        assertEquals(false, decoder.feed(disconnected, disconnected.size).single().let {
            NcmLinkNotification.connected(it, controlInterface = 3)
        })
    }

    @Test fun truncatedAndUnrelatedNotificationsCannotDeclareCarrier() {
        val packet = notification(code = 0, value = 1, index = 3, payload = byteArrayOf())
        assertNull(NcmLinkNotification.connected(packet, 7))
        assertNull(NcmLinkNotification.connected(packet, packet.size + 1))

        val decoder = NcmStatusNotificationDecoder()
        val other = notification(code = 0x2a, value = 1, index = 3, payload = byteArrayOf())
        assertNull(NcmLinkNotification.connected(decoder.feed(other, other.size).single(), 3))
        val payload = notification(code = 0, value = 1, index = 3, payload = byteArrayOf(1))
        assertNull(NcmLinkNotification.connected(decoder.feed(payload, payload.size).single(), 3))
    }

    @Test fun applePrivateNotificationIsReassembledAcrossSixteenPlusTwoByteReads() {
        val packet = notification(
            code = 0x2b,
            value = 0,
            index = 4,
            payload = byteArrayOf(0, 1, -1, -1, -1, -1, 0, 0, 0, 0),
        )
        val decoder = NcmStatusNotificationDecoder()

        assertTrue(decoder.feed(packet, 16).isEmpty())
        val notification = decoder.feed(packet.copyOfRange(16, 18), 2).single()
        assertEquals(0xa1, notification.requestType)
        assertEquals(0x2b, notification.code)
        assertEquals(4, notification.index)
        assertEquals(10, notification.payload.size)
        assertEquals(0xffff_ffffL, notification.observedAppleLinkWord())
        assertNull(NcmLinkNotification.connected(notification, controlInterface = 4))
    }

    @Test fun decoderHandlesSplitHeadersConcatenatedNotificationsAndLeadingNoise() {
        val first = notification(code = 0, value = 1, index = 3, payload = byteArrayOf())
        val second = notification(code = 0, value = 0, index = 3, payload = byteArrayOf())
        val decoder = NcmStatusNotificationDecoder()
        assertTrue(decoder.feed(byteArrayOf(0x55, 0x66) + first.copyOfRange(0, 5), 7).isEmpty())

        val remainder = first.copyOfRange(5, first.size) + second
        val decoded = decoder.feed(remainder, remainder.size)
        assertEquals(2, decoded.size)
        assertEquals(true, NcmLinkNotification.connected(decoded[0], 3))
        assertEquals(false, NcmLinkNotification.connected(decoded[1], 3))
    }

    private fun notification(code: Int, value: Int, index: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            0xa1.toByte(), code.toByte(),
            value.toByte(), (value ushr 8).toByte(),
            index.toByte(), (index ushr 8).toByte(),
            payload.size.toByte(), (payload.size ushr 8).toByte(),
        ) + payload
}
