package com.shilapi.xcertplay.usbbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class UsbHciTransportReplayTest {
    private fun controllerFrame(size: Int) = HciProtocol.encodeAcl(0x20, ByteArray(size - 4) { it.toByte() })

    @Test
    fun encryptionEventsAcceptBothControllerVersionsAndKeepFailureStatus() {
        val v1 = requireNotNull(HciProtocol.parseEncryptionChange(
            HciProtocol.HciEvent(0x08, byteArrayOf(0, 0x34, 0x02, 1)),
        ))
        assertEquals(0x234, v1.handle)
        assertTrue(v1.enabled)
        assertNull(v1.keySize)
        val v2 = requireNotNull(HciProtocol.parseEncryptionChange(
            HciProtocol.HciEvent(0x59, byteArrayOf(0, 0x34, 0x02, 2, 16)),
        ))
        assertEquals(0x234, v2.handle)
        assertTrue(v2.enabled)
        assertEquals(16, v2.keySize)
        val failure = requireNotNull(HciProtocol.parseEncryptionChange(
            HciProtocol.HciEvent(0x59, byteArrayOf(0x05, 0x34, 0x02, 0, 0)),
        ))
        assertEquals(0x05, failure.status)
        assertFalse(failure.enabled)
        assertNull(HciProtocol.parseEncryptionChange(HciProtocol.HciEvent(0x59, byteArrayOf(0, 0x34, 2, 1))))
        assertNull(HciProtocol.parseEncryptionChange(HciProtocol.HciEvent(0x59, byteArrayOf(0, 0x34, 2, 1, 6))))
    }

    @Test
    fun encryptionReadbackMustConfirmCurrentHandleAndValidKeySize() {
        assertTrue(HciProtocol.hasValidEncryptionKeySize(0x234, byteArrayOf(0, 0x34, 2, 16)))
        assertFalse(HciProtocol.hasValidEncryptionKeySize(0x234, byteArrayOf(0, 0x35, 2, 16)))
        assertFalse(HciProtocol.hasValidEncryptionKeySize(0x234, byteArrayOf(0x2f, 0x34, 2, 16)))
        assertFalse(HciProtocol.hasValidEncryptionKeySize(0x234, byteArrayOf(0, 0x34, 2, 6)))
        assertFalse(HciProtocol.hasValidEncryptionKeySize(0x234, byteArrayOf(0, 0x34, 2, 0xff.toByte())))
        assertFalse(HciProtocol.hasValidEncryptionKeySize(0x234, byteArrayOf(0, 0x34, 2)))
    }

    @Test
    fun encryptionEventCanArriveBeforeItsAsynchronousCommandStatus() {
        val events = LinkedBlockingQueue<HciProtocol.HciEvent>()
        val opcode = HciProtocol.OP_SET_CONNECTION_ENCRYPTION
        HciEventDispatcher(
            readEvent = { timeout -> events.poll(timeout.toLong(), TimeUnit.MILLISECONDS) },
            writeCommand = { packet, _ ->
                events.add(HciProtocol.HciEvent(0x59, byteArrayOf(0, 0x34, 2, 2, 16)))
                events.add(HciProtocol.HciEvent(
                    HciProtocol.EVT_COMMAND_STATUS, byteArrayOf(0, 1, opcode.toByte(), (opcode shr 8).toByte()),
                ))
                packet.size
            },
        ).use { dispatcher ->
            val handle = 0x234
            dispatcher.sendCommand(opcode, HciProtocol.encodeSetConnectionEncryption(handle, true), 500).getOrThrow()
            val event = requireNotNull(dispatcher.pollEvent(500))
            val change = requireNotNull(HciProtocol.parseEncryptionChange(event))
            assertEquals(handle, change.handle)
            assertTrue(change.enabled)
        }
    }

    @Test
    fun rejectedIncomingConnectionDoesNotBlockHciAfterAcceptedCommandStatus() {
        val events = LinkedBlockingQueue<HciProtocol.HciEvent>()
        val opcode = HciProtocol.OP_REJECT_CONNECTION_REQUEST
        HciEventDispatcher(
            readEvent = { timeout -> events.poll(timeout.toLong(), TimeUnit.MILLISECONDS) },
            writeCommand = { packet, _ ->
                assertEquals(opcode, (packet[0].toInt() and 0xff) or ((packet[1].toInt() and 0xff) shl 8))
                // The successful asynchronous command reports Command Status, without Command Complete.
                events.add(HciProtocol.HciEvent(
                    HciProtocol.EVT_COMMAND_STATUS, byteArrayOf(0, 1, opcode.toByte(), (opcode shr 8).toByte()),
                ))
                packet.size
            },
        ).use { dispatcher ->
            val parameters = byteArrayOf(6, 5, 4, 3, 2, 1, 0x0f)
            assertArrayEquals(byteArrayOf(0), dispatcher.sendCommand(opcode, parameters, 500).getOrThrow())
        }
    }

    @Test
    fun completedAclFrameDoesNotWaitForAnotherPacket() {
        val assembler = HciUsbFrameAssembler(isAcl = true)
        val frame = controllerFrame(64)
        assertArrayEquals(frame, assembler.push(frame, 64, endOfTransfer = false).single())
        assertTrue(assembler.push(byteArrayOf(), 0, endOfTransfer = true).isEmpty())
    }

    @Test
    fun aclTransferReassemblesFullAndShortReads() {
        val assembler = HciUsbFrameAssembler(isAcl = true)
        val frame = controllerFrame(136)
        assertTrue(assembler.push(frame.copyOfRange(0, 64), 64, endOfTransfer = false).isEmpty())
        assertTrue(assembler.push(frame.copyOfRange(64, 128), 64, endOfTransfer = false).isEmpty())
        assertArrayEquals(frame, assembler.push(frame.copyOfRange(128, 136), 8, endOfTransfer = true).single())
    }

    @Test
    fun truncatedTransferDoesNotContaminateLaterAclTraffic() {
        val assembler = HciUsbFrameAssembler(isAcl = true)
        val truncated = controllerFrame(136)
        assertTrue(assembler.push(truncated.copyOfRange(0, 64), 64, endOfTransfer = false).isEmpty())
        assertTrue(assembler.push(truncated.copyOfRange(64, 72), 8, endOfTransfer = true).isEmpty())
        val frame = controllerFrame(18)
        assertArrayEquals(frame, assembler.push(frame, frame.size, endOfTransfer = true).single())
    }

    @Test
    fun invalidLengthDoesNotPoisonReceiver() {
        val assembler = HciUsbFrameAssembler(isAcl = true)
        val invalid = byteArrayOf(1, 0x20, 0, 0x40)
        assertTrue(assembler.push(invalid, invalid.size, endOfTransfer = true).isEmpty())
        val frame = controllerFrame(18)
        assertArrayEquals(frame, assembler.push(frame, frame.size, endOfTransfer = true).single())
    }

    @Test
    fun eventChunksPreserveExtendedInquiryNameAndClock() {
        val frame = ByteArray(257)
        frame[0] = HciProtocol.EVT_EXTENDED_INQUIRY_RESULT.toByte()
        frame[1] = 255.toByte()
        frame[2] = 1
        byteArrayOf(6, 5, 4, 3, 2, 1, 2, 0, 4, 2, 0x20, 0x34, 0x12, 0xa0.toByte()).copyInto(frame, 3)
        byteArrayOf(7, 9).copyInto(frame, 17)
        "iPhone".toByteArray().copyInto(frame, 19)
        val assembler = HciUsbFrameAssembler(isAcl = false)
        val packets = ArrayList<ByteArray>()
        for (offset in frame.indices step 16) {
            val chunk = frame.copyOfRange(offset, minOf(offset + 16, frame.size))
            packets.addAll(assembler.push(chunk, chunk.size, endOfTransfer = chunk.size < 16))
        }
        val event = requireNotNull(HciProtocol.parseEvent(packets.single()))
        val inquiry = HciProtocol.parseInquiryResults(event).single()
        assertEquals("01:02:03:04:05:06", inquiry.address)
        assertEquals("iPhone", inquiry.name)
        assertEquals(0x200204, inquiry.classOfDevice)
        assertEquals(2, inquiry.pageScanRepetitionMode)
        assertEquals(0x9234, inquiry.clockOffset)
    }

    @Test
    fun aclContinuationUsesSameHandleAndExactPayload() {
        val original = ByteArray(96) { it.toByte() }
        val first = requireNotNull(HciProtocol.parseAcl(HciProtocol.encodeAcl(0x194, original, 0, 64)))
        val tail = requireNotNull(HciProtocol.parseAcl(
            HciProtocol.encodeAcl(0x194, original, 64, 32, continuation = true),
        ))
        assertEquals(0x194, first.handle)
        assertEquals(first.handle, tail.handle)
        assertEquals(0x02, first.flags)
        assertEquals(0x01, tail.flags)
        assertArrayEquals(original, first.data + tail.data)
    }

    @Test
    fun realtekCm390UsesBothBundledRomPatches() {
        val assets = File("assets")
        val firmware = assets.resolve("rtl8761bu_fw").readBytes()
        val config = assets.resolve("rtl8761bu_config").readBytes()
        val profile = requireNotNull(RealtekFirmware.match(RealtekFirmware.Version(
            hci = 0x0a, revision = 0x0b, manufacturer = 0x5d, subversion = 0x8761,
        )))
        assertEquals("rtl8761bu", profile.name)
        for (rom in 0..1) {
            val image = RealtekFirmware.image(firmware, config, profile, rom)
            val packets = RealtekFirmware.fragments(image).toList()
            val dataBytes = packets.sumOf { it.size - 1 }
            assertEquals(if (rom == 0) 14054 else 30210, dataBytes)
            assertTrue(packets.last()[0].toInt() and 0x80 != 0)
            assertArrayEquals(config, image.copyOfRange(image.size - config.size, image.size))
        }
    }

    @Test
    fun exactCm591NeverTriggersARealtekUpload() {
        val loader = UsbBtFirmwareLoader(
            command = { _, _, _ -> error("unexpected firmware command") },
            readAsset = { error("unexpected asset read") },
        )
        val realtekLookingReply = byteArrayOf(0, 0x0a, 0x0b, 0, 0x0a, 0x5d, 0, 0x61, 0x87.toByte())
        assertTrue(loader.loadForDevice(0x10d7, 0xb012, realtekLookingReply) is
            UsbBtFirmwareLoader.LoadResult.NoFirmwareNeeded)
    }

    @Test
    fun disconnectedHandleCannotSpendCreditsOfItsReplacement() {
        val window = HciAclWindow()
        window.initialize(1)
        window.connected(0x42)
        val original = requireNotNull(window.find(0x42))
        fun deadline() = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        assertTrue(window.acquire(original, deadline()))
        window.disconnected(0x42)
        window.connected(0x42)
        val replacement = requireNotNull(window.find(0x42))
        assertFalse(window.isCurrent(original))
        assertFalse(window.acquire(original, deadline()))
        assertTrue(window.acquire(replacement, deadline()))
        window.returnUnsent(original)
        // Failed work from the old connection must not release the new packet.
        assertFalse(window.acquire(replacement, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10)))
        window.completed(0x42, 1)
        assertTrue(window.acquire(replacement, deadline()))
    }
}
