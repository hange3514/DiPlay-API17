package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayRecoveryAuthorizationTest {
    @Test
    fun recoverySocketDoesNotAllowSetupCommandsOrPairingBeforeVerification() {
        val handle = AirPlaySession::class.java
            .getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }
        val socket = Socket()
        try {
            val session = AirPlaySession(
                socket,
                AirPlayConfig("test", "00:11:22:33:44:55", "00:11:22:33:44:55", "1",
                    AirPlayDisplayConfig(800, 480)),
                AirPlayIdentity(ByteArray(32), ByteArray(32), "car"),
                PairingStore(),
                null,
                object : AirPlaySessionListener {},
                object : AirPlayMediaHandler {},
                recoveryControllerId = "original-phone",
            )
            for ((method, path) in listOf(
                "POST" to "/pair-setup",
                "SETUP" to "/info",
                "RECORD" to "/",
                "POST" to "/command",
                "TEARDOWN" to "/",
            )) {
                val request = RtspMessage.Request(method, path, "RTSP/1.0", emptyMap(), ByteArray(0))
                val response = handle.invoke(session, request) as RtspMessage.Response
                assertEquals("$method $path", 403, response.status)
            }
        } finally {
            socket.close()
        }
    }
    @Test
    fun rejectedProjectionOwnerDoesNotEnterRecord() {
        val handle = AirPlaySession::class.java
            .getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }
        val socket = Socket()
        try {
            val session = AirPlaySession(
                socket,
                AirPlayConfig("test", "00:11:22:33:44:55", "00:11:22:33:44:55", "1",
                    AirPlayDisplayConfig(800, 480)),
                AirPlayIdentity(ByteArray(32), ByteArray(32), "car"),
                PairingStore(),
                null,
                object : AirPlaySessionListener {
                    override fun claimProjection(session: AirPlaySession): Boolean = false
                },
                object : AirPlayMediaHandler {},
            )
            val request = RtspMessage.Request("RECORD", "/", "RTSP/1.0", emptyMap(), ByteArray(0))
            val response = handle.invoke(session, request) as RtspMessage.Response
            assertEquals(503, response.status)
        } finally {
            socket.close()
        }
    }
}
