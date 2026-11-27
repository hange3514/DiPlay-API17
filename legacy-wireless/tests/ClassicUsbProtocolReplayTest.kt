package com.shilapi.xcertplay.usbbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ClassicUsbProtocolReplayTest {
    @Test
    fun fragmentedInformationRequestGetsTheBasicFeaturesResponse() {
        val replies = ArrayList<ByteArray>()
        val layer = L2capLayer(7) { handle, packet ->
            assertEquals(7, handle)
            replies.add(packet)
        }
        val request = HciProtocol.encodeL2cap(1, byteArrayOf(0x0A, 6, 2, 0, 2, 0))
        layer.onAclData(HciProtocol.AclPacket(7, 2, request.copyOfRange(0, 5)))
        assertTrue(replies.isEmpty())
        layer.onAclData(HciProtocol.AclPacket(7, 1, request.copyOfRange(5, request.size)))
        assertArrayEquals(
            HciProtocol.encodeL2cap(1, byteArrayOf(0x0B, 6, 8, 0, 2, 0, 0, 0, 0x80.toByte(), 0, 0, 0)),
            replies.single(),
        )
    }

    @Test
    fun sdpContinuationPreservesTokenAndReassemblesTheProtocolDescriptor() {
        val client = SdpClient(
            L2capLayer(7) { _, _ -> error("no radio in SDP codec replay") },
            7,
            uuidBigEndian(UsbBtIdentity.IAP2_ACCESSORY_UUID),
        )
        val server = SdpServer(listOf(SdpCodec.iap2Record(5)))
        val attributes = ByteArrayOutputStream()
        var continuation = byteArrayOf()
        var partCount = 0
        do {
            val tid = ++partCount
            val query = client.buildSearchAttrRequest(tid, continuation)
            assertArrayEquals(continuation, query.copyOfRange(query.size - continuation.size, query.size))
            val reply = server.handle(query, peerMtu = 17)
            val parsed = requireNotNull(SdpClient.parseResponse(reply, tid))
            assertNull(SdpClient.parseResponse(reply.copyOf(reply.size - 1), tid))
            attributes.write(parsed.attributeLists)
            continuation = parsed.continuation
        } while (continuation.isNotEmpty() && partCount < 16)
        assertTrue(partCount > 1)
        assertTrue(continuation.isEmpty())
        assertEquals(5, SdpClient.extractRfcommChannel(SdpClient.ParsedResponse(attributes.toByteArray())))
    }

    @Test
    fun completeEmptySdpResponseIsDistinctFromInvalidAttributes() {
        val client = SdpClient(L2capLayer(7) { _, _ -> error("no radio in SDP codec replay") }, 7)
        val server = SdpServer(emptyList())
        val reply = server.handle(client.buildSearchAttrRequest(1, byteArrayOf()), peerMtu = 128)
        val parsed = requireNotNull(SdpClient.parseResponse(reply, 1))
        assertTrue(parsed.continuation.isEmpty())
        assertTrue(SdpClient.hasNoServiceRecords(parsed.attributeLists))
        assertFalse(SdpClient.hasNoServiceRecords(byteArrayOf(0x35)))
        assertFalse(SdpClient.hasNoServiceRecords(parsed.attributeLists + byteArrayOf(0x00)))
    }

    @Test
    fun rfcommUsesGrantedCreditsAndLocalGrantsRemainSeparate() {
        val flow = RfcommDlcFlow(7)
        flow.negotiate(credits = true, granted = 1, peerFrame = 64)
        assertEquals(64, flow.peerFrameSize)
        assertTrue(flow.takeTxCredit(0))
        assertFalse(flow.takeTxCredit(0))
        flow.creditGrantSent(7)
        repeat(3) { assertEquals(0, flow.consumeRxCredit()) }
        assertEquals(4, flow.consumeRxCredit())
        assertFalse(flow.takeTxCredit(0))
        flow.addTxCredits(2)
        assertTrue(flow.takeTxCredit(0))
        assertTrue(flow.takeTxCredit(0))
        assertFalse(flow.takeTxCredit(0))
    }

    @Test
    fun closingRfcommWakesACreditStarvedWriter() {
        val flow = RfcommDlcFlow(7)
        flow.negotiate(credits = true, granted = 0, peerFrame = 100)
        val started = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val writer = Thread {
            started.countDown()
            result.set(flow.takeTxCredit(5000))
        }.apply { isDaemon = true; start() }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        flow.release()
        writer.join(1000)
        assertFalse(writer.isAlive)
        assertEquals(false, result.get())
    }
}

