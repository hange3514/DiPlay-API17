package com.shilapi.xcertplay.network

import java.io.FileDescriptor
import java.lang.reflect.Method
import java.net.Socket

internal object TcpLiveness {
    // Linux TCP socket options. These predate the MX9A0 kernel and take seconds,
    // except TCP_USER_TIMEOUT which takes milliseconds.
    private const val IPPROTO_TCP = 6
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6
    private const val TCP_USER_TIMEOUT = 18
    private const val IDLE_SECONDS = 12
    private const val INTERVAL_SECONDS = 3
    private const val PROBE_COUNT = 3
    private const val USER_TIMEOUT_MILLIS = 22_000

    private class LibcoreOptions(val fd: Method, val setsockopt: Method, val os: Any) {
        fun set(descriptor: FileDescriptor, option: Int, value: Int): Boolean =
            optional {
                setsockopt.invoke(os, descriptor, IPPROTO_TCP, option, value)
                true
            } ?: false
    }

    private val libcoreOptions by lazy {
        optional {
            val osType = Class.forName("libcore.io.Os")
            val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
            val fd = Socket::class.java.getDeclaredMethod("getFileDescriptor\$").apply { isAccessible = true }
            val set = osType.getMethod(
                "setsockoptInt", FileDescriptor::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            if (os != null) LibcoreOptions(fd, set, os) else null
        }
    }

    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        socket.keepAlive = true
        // A wrapper may expose a logical connection such as the user-space IP backend.
        // Only the platform's accepted TCP socket owns a descriptor we may configure.
        if (socket.javaClass != Socket::class.java || !socket.isConnected || socket.isClosed) return
        val options = libcoreOptions
        val descriptor = options?.let { optional { it.fd.invoke(socket) as? FileDescriptor } }
        if (options == null || descriptor == null || !descriptor.valid()) {
            diagnostic("airplay tcp liveness keepAlive=platform_default")
            return
        }
        // Keep the platform idle delay if the interval or probe count was rejected.
        val tuned = options.set(descriptor, TCP_KEEPINTVL, INTERVAL_SECONDS) &&
            options.set(descriptor, TCP_KEEPCNT, PROBE_COUNT) &&
            options.set(descriptor, TCP_KEEPIDLE, IDLE_SECONDS)
        val userTimeout = tuned && options.set(descriptor, TCP_USER_TIMEOUT, USER_TIMEOUT_MILLIS)
        diagnostic(
            "airplay tcp liveness keepAlive=" + (if (tuned) "tuned" else "platform_default") +
                " userTimeoutMs=" + (if (userTimeout) USER_TIMEOUT_MILLIS else 0),
        )
    }

    private inline fun <T> optional(operation: () -> T): T? = try {
        operation()
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }
}
