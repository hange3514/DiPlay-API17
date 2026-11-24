package local.headunit.diplay

import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * Temporary, read-only reachability check for the reverse hotspot topology.
 * It is bound only to the active Wi-Fi station IPv4 and serves a fixed response;
 * no request data, peer address, or credentials are retained.
 */
internal class WifiReturnPathProbe(
    private val address: InetAddress,
    private val onEvent: (JSONObject) -> Unit,
) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val hits = AtomicInteger()
    private val emitted = AtomicInteger()
    private val startedAt = android.os.SystemClock.elapsedRealtime()
    private val server: ServerSocket
    private val worker: Thread

    init {
        require(address is Inet4Address) { "Return-path probe requires the active Wi-Fi IPv4 address" }
        server = LegacySockets.tcp(address, PORT)
        server.soTimeout = 1000
        emit("listening", success = true, status = 0)
        worker = Thread({ serve() }, "hybrid-wifi-probe").apply {
            isDaemon = true
            start()
        }
    }

    val hitCount: Int get() = hits.get()

    private fun serve() {
        while (!stopped.get()) {
            val client = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Throwable) {
                if (!stopped.get()) emit("accept_failed", success = false, status = 0)
                return
            }
            handle(client)
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 2000
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            var matched = 0
            var total = 0
            val requestPrefix = ByteArray(8)
            while (total < MAX_REQUEST_BYTES && matched < 4) {
                val value = input.read()
                if (value < 0) break
                if (total < requestPrefix.size) requestPrefix[total] = value.toByte()
                total++
                matched = when {
                    matched == 0 && value == 13 -> 1
                    matched == 1 && value == 10 -> 2
                    matched == 2 && value == 13 -> 3
                    matched == 3 && value == 10 -> 4
                    value == 13 -> 1
                    else -> 0
                }
            }
            val method = String(requestPrefix, 0, total.coerceAtMost(requestPrefix.size), Charsets.US_ASCII)
            if (!method.startsWith("GET ") && !method.startsWith("HEAD ")) {
                emit(if (total == 0) "tcp_accepted_no_http" else "tcp_accepted_non_http", success = false, status = 0)
                return
            }
            val body = "DiPlay Wi-Fi return path OK\n".toByteArray(Charsets.UTF_8)
            val headers = ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            val output = socket.getOutputStream()
            output.write(headers)
            output.write(body)
            output.flush()
            hits.incrementAndGet()
            emit("http_200", success = true, status = 200)
        } catch (_: SocketTimeoutException) {
            emit("http_timeout", success = false, status = 0)
        } catch (_: Throwable) {
            emit("http_failed", success = false, status = 0)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun emit(stage: String, success: Boolean, status: Int) {
        // A phone browser may request the page and favicon; cap this probe's own event volume.
        if (emitted.incrementAndGet() > MAX_EVENTS) return
        try {
            val literal = address.hostAddress.substringBefore('%')
            onEvent(JSONObject()
                .put("stage", stage)
                .put("success", success)
                .put("resultCode", status)
                .put("localAddress", literal)
                .put("localPort", PORT)
                .put("addressFamily", "ipv4")
                .put("frameCount", hits.get())
                .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - startedAt))
        } catch (_: Throwable) { }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { server.close() }
        worker.interrupt()
        emit("closed", success = hits.get() > 0, status = hits.get())
    }

    companion object {
        const val PORT = 7001
        private const val MAX_REQUEST_BYTES = 8192
        private const val MAX_EVENTS = 12
    }
}
