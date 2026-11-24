package com.shilapi.xcertplay.airplay

import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AirPlayPeerAcceptTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test
    fun acceptsLoopbackPeerOnDifferentSourcePorts() {
        val listener = ServerSocket(0, 2, loopback)
        listener.soTimeout = 2_000
        val first = Socket(loopback, listener.localPort)
        val second = Socket(loopback, listener.localPort)
        try {
            val acceptedPorts = mutableSetOf<Int>()
            repeat(2) {
                val accepted = listener.acceptFromPeer(loopback)
                try {
                    assertEquals(loopback, accepted.inetAddress)
                    acceptedPorts.add(accepted.port)
                } finally {
                    accepted.close()
                }
            }
            assertEquals(setOf(first.localPort, second.localPort), acceptedPorts)
            assertEquals(2_000, listener.soTimeout)
        } finally {
            first.close()
            second.close()
            listener.close()
        }
    }

    @Test
    fun closesUnexpectedLoopbackPeerAndTimesOut() {
        val listener = ServerSocket(0, 2, loopback)
        val expectedPeer = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))
        listener.soTimeout = 600
        val first = Socket(loopback, listener.localPort)
        val second = Socket(loopback, listener.localPort)
        first.soTimeout = 2_000
        second.soTimeout = 2_000
        val rejected = AtomicInteger()
        val result = FutureTask { listener.acceptFromPeer(expectedPeer) { rejected.incrementAndGet() } }
        thread(isDaemon = true) { result.run() }
        try {
            assertEquals(-1, first.getInputStream().read())
            assertEquals(-1, second.getInputStream().read())
            try {
                result.get(2_000, TimeUnit.MILLISECONDS).close()
                fail("Expected to time out waiting for the control-session address")
            } catch (error: ExecutionException) {
                assertTrue(error.cause is SocketTimeoutException)
            }
            assertEquals(1, rejected.get())
            assertEquals(600, listener.soTimeout)
        } finally {
            first.close()
            second.close()
            listener.close()
        }
    }

    @Test
    fun retainsTheOriginalDeadlineAndAcceptsEquivalentIpv6Address() {
        val address = ByteArray(16).apply {
            this[0] = 0xfe.toByte()
            this[1] = 0x80.toByte()
            this[15] = 1
        }
        val expected = Inet6Address.getByAddress(null, address, 1)
        val scopedPeer = Inet6Address.getByAddress(null, address, 2)
        val wrong = withPeer(loopback)
        val right = withPeer(scopedPeer)
        val timeouts = mutableListOf<Int>()
        val listener = object : ServerSocket() {
            override fun accept(): Socket {
                timeouts.add(soTimeout)
                return if (timeouts.size == 1) wrong else right
            }
        }
        listener.soTimeout = 2_000
        try {
            assertSame(right, listener.acceptFromPeer(expected) { Thread.sleep(50) })
            assertTrue(wrong.isClosed)
            assertTrue(timeouts[1] <= timeouts[0] - 25)
            assertEquals(2_000, listener.soTimeout)
        } finally {
            right.close()
            wrong.close()
            listener.close()
        }
    }

    private fun withPeer(peer: InetAddress): Socket = object : Socket() {
        override fun getInetAddress(): InetAddress = peer
    }
}
