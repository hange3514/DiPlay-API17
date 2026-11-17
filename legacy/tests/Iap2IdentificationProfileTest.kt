package local.headunit.diplay

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2UsbHostTransportFallbackPolicy
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2LinkConfig
import com.shilapi.xcertplay.transport.Iap2LinkEngine
import com.shilapi.xcertplay.iap2.session.Iap2Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class Iap2IdentificationProfileTest {
    private fun config(
        name: String = Iap2IdentificationClient.DEFAULT_USB_HOST_TRANSPORT_NAME,
        fallback: String? = Iap2IdentificationClient.LEGACY_USB_HOST_TRANSPORT_NAME,
    ) = Iap2IdentificationConfig(
        name = "DiPlay",
        modelIdentifier = "TestHeadUnit",
        manufacturer = "Independent",
        serialNumber = "test-serial",
        firmwareVersion = "1",
        hardwareVersion = "1",
        carPlayUsbInterfaceNumber = 3,
        usbHostTransportName = name,
        usbHostTransportFallbackName = fallback,
    )

    @Test fun defaultProfileUsesCurrentReferenceUsbHostNameAndKeepsDetectedInterface() {
        val frame = Iap2IdentificationClient.identificationInformation(config())
        val identify = Iap2BodyReader.of(frame)
        val usbHost = identify.group(16)
        assertEquals("USBHostTransport", usbHost.string(1))
        assertEquals(3, usbHost.u8(3))
        assertEquals(true, usbHost.optionalVoid(2))
        assertEquals(true, usbHost.optionalVoid(4))
    }

    @Test fun fallbackIsOnlySelectedForRejectedUsbHostParameterAndOnlyOnce() {
        val cfg = config()
        assertEquals(
            "IAP2-USBHost",
            Iap2UsbHostTransportFallbackPolicy.nextName(cfg, setOf(16), alreadyRetried = false),
        )
        assertNull(Iap2UsbHostTransportFallbackPolicy.nextName(cfg, setOf(1), alreadyRetried = false))
        assertNull(Iap2UsbHostTransportFallbackPolicy.nextName(cfg, setOf(16), alreadyRetried = true))
    }

    @Test fun fallbackCanBeDisabledAndExplicitProfileIsEncodedVerbatim() {
        val frame = Iap2IdentificationClient.identificationInformation(config(name = "USBHostTransport", fallback = null))
        assertEquals("USBHostTransport", Iap2BodyReader.of(frame).group(16).string(1))
        assertNull(Iap2UsbHostTransportFallbackPolicy.nextName(config(fallback = null), setOf(16), alreadyRetried = false))
    }

    @Test fun rejectedTransportNameRetriesOnceWithLegacyNameThenCompletes() {
        val stream = IdentificationLoopbackStream()
        val session = Iap2Session.openTunnel(stream)
        try {
            assertTrue("loopback iAP2 did not become ready", session.awaitReady(1_000))
            val failure = arrayOfNulls<Throwable>(1)
            val worker = Thread {
                try {
                    Iap2IdentificationClient(session).identify(config(), 3_000)
                } catch (error: Throwable) {
                    failure[0] = error
                }
            }
            worker.start()

            stream.sendPeerFrame(Iap2CsmFramer.encodeFrame(Iap2IdentificationClient.START_IDENTIFICATION, byteArrayOf()))
            val initial = Iap2CsmFramer().offer(stream.takeAccessoryControl()).single()
            assertEquals("USBHostTransport", Iap2BodyReader.of(initial).group(16).string(1))

            val rejected = com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
                .opaque(Iap2IdentificationClient.IDENTIFICATION_REJECTED)
                .void(16)
                .build()
            stream.sendPeerFrame(Iap2CsmFramer.encodeFrame(rejected.messageId, rejected.payload))
            val fallback = Iap2CsmFramer().offer(stream.takeAccessoryControl()).single()
            assertEquals("IAP2-USBHost", Iap2BodyReader.of(fallback).group(16).string(1))

            stream.sendPeerFrame(Iap2CsmFramer.encodeFrame(Iap2IdentificationClient.IDENTIFICATION_ACCEPTED, byteArrayOf()))
            worker.join(2_000)
            assertTrue("identification worker remained blocked", !worker.isAlive)
            assertNull(failure[0])
        } finally {
            session.close()
        }
    }

    private class IdentificationLoopbackStream : BlockingDuplexByteStream {
        private val peer = Iap2LinkEngine(
            Iap2LinkConfig(maxOutgoing = 4, controlSessionVersion = 2, zeroAcknowledgements = true),
        )
        private val incoming = LinkedBlockingQueue<ByteArray>()
        private val accessoryControl = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false

        init {
            peer.start(wiredInitiator = false, nowMillis = now())
            peer.takeOutput().takeIf { it.isNotEmpty() }?.let(incoming::offer)
        }

        override fun send(data: ByteArray) {
            if (closed) throw IOException("loopback stream closed")
            val at = now()
            peer.feed(data, at)
            peer.advanceTime(at)
            while (true) {
                val event = peer.pollEvent() ?: break
                if (event is Iap2LinkEngine.Event.Control) accessoryControl.offer(event.bytes)
            }
            peer.takeOutput().takeIf { it.isNotEmpty() }?.let(incoming::offer)
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            if (closed) return ByteArray(0)
            val bytes = incoming.poll(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: return null
            if (bytes.size <= maxBytes) return bytes
            incoming.offer(bytes.copyOfRange(maxBytes, bytes.size))
            return bytes.copyOfRange(0, maxBytes)
        }

        fun sendPeerFrame(csmBytes: ByteArray) {
            val at = now()
            peer.sendSession(Iap2LinkEngine.CONTROL_SESSION_ID, csmBytes, at)
            peer.takeOutput().takeIf { it.isNotEmpty() }?.let(incoming::offer)
        }

        fun takeAccessoryControl(): ByteArray =
            accessoryControl.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("accessory sent no CSM control frame")

        override fun close() {
            closed = true
            incoming.offer(ByteArray(0))
        }

        private fun now(): Long = System.nanoTime() / 1_000_000L
    }
}
