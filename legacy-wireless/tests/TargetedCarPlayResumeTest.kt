package local.headunit.diplay

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetedCarPlayResumeTest {
    private val phone = InetAddress.getByName("192.168.43.20")
    private val other = InetAddress.getByName("192.168.43.21")
    private val bluetoothId = "001122334455"
    private val service = "_carplay-ctrl._tcp.local"
    private val instance = "phone.$service"
    private val hostname = "phone.local"
    private val port = 50911

    @Test
    fun acceptsOnlyCurrentSourceAndSavedPhoneIdentity() {
        TargetedCarPlayResume(phone, bluetoothId).use { attempt ->
            offer(attempt, response(bluetoothId), other)
            offer(attempt, response("556677889900"), phone)
            offer(attempt, response(bluetoothId), phone, fromPort = 443)
            assertNull(attempt.awaitEndpoint(15))

            offer(attempt, response(bluetoothId), phone)
            val accepted = checkNotNull(attempt.awaitEndpoint(50))
            assertEquals(phone, accepted.host)
            assertEquals(port, accepted.port)
        }
    }

    @Test
    fun cancelledAttemptWakesWaiterAndClosesItsRequestSocket() {
        val attempt = TargetedCarPlayResume(phone, bluetoothId)
        val probe = Socket()
        attempt.probe.set(probe)
        val result = FutureTask { attempt.awaitEndpoint(1_500L) }
        val worker = Thread(result).apply { isDaemon = true; start() }
        attempt.close()
        assertNull(result.get(500, TimeUnit.MILLISECONDS))
        assertTrue(probe.isClosed)
        assertNull(attempt.probe.get())
        offer(attempt, response(bluetoothId), phone)
        assertNull(attempt.awaitEndpoint(50))
        worker.join(500)
    }

    private fun offer(attempt: TargetedCarPlayResume, response: ByteArray, source: InetAddress,
                      fromPort: Int = 5353) {
        val packet = DatagramPacket(response, response.size, source, fromPort)
        attempt.offer(packet, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()))
    }

    private fun response(id: String): ByteArray {
        val txt = "id=$id".toByteArray(Charsets.US_ASCII)
        return CarPlayControlDns.encodeResponse(listOf(
            record(service, 12, wireName(instance)),
            record(instance, 33, wire { writeShort(0); writeShort(0); writeShort(port); write(wireName(hostname)) }),
            record(instance, 16, byteArrayOf(txt.size.toByte()) + txt),
            record(hostname, 1, phone.address),
        ), null)
    }

    private fun record(name: String, type: Int, body: ByteArray): ByteArray = wire {
        write(wireName(name))
        writeShort(type)
        writeShort(1)
        writeInt(120)
        writeShort(body.size)
        write(body)
    }

    private fun wireName(name: String): ByteArray = wire {
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            writeByte(bytes.size)
            write(bytes)
        }
        writeByte(0)
    }

    private fun wire(build: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { it.build() }
            bytes.toByteArray()
        }
}
