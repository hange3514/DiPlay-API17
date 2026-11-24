package local.headunit.diplay

import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetworkDeadlineTest {
    private class PendingConnection : HttpURLConnection(URL("http://127.0.0.1")) {
        val firstDisconnect = CountDownLatch(1)
        val repeatedDisconnect = CountDownLatch(2)

        override fun connect() = Unit
        override fun usingProxy() = false

        override fun disconnect() {
            firstDisconnect.countDown()
            repeatedDisconnect.countDown()
        }
    }

    @Test
    fun abortsBlockedOperationsAfterTheElapsedDeadline() {
        val connection = PendingConnection()
        val deadline = NetworkDeadline(connection, 25)
        try {
            assertTrue(connection.firstDisconnect.await(3, TimeUnit.SECONDS))
            try {
                deadline.check()
                fail("Expected overall request timeout")
            } catch (expected: SocketTimeoutException) {
                // The transfer timeout classification is preserved.
            }
        } finally {
            deadline.close()
        }
    }

    @Test
    fun retriesCancellationUntilTheOwnerClosesTheDeadline() {
        val connection = PendingConnection()
        val deadline = NetworkDeadline(connection, 30_000)
        try {
            deadline.check()
            deadline.cancel()
            assertTrue(connection.repeatedDisconnect.await(3, TimeUnit.SECONDS))
            try {
                deadline.check()
                fail("Expected explicit cancellation")
            } catch (expected: InterruptedIOException) {
                assertFalse(expected is SocketTimeoutException)
            }
        } finally {
            deadline.close()
        }
    }
}
