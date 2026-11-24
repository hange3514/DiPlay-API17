package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_AUTHENTICATION_COMPLETE
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_IO_CAPABILITY_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_IO_CAPABILITY_RESPONSE
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_LINK_KEY_NOTIFICATION
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_LINK_KEY_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_PIN_CODE_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_SIMPLE_PAIRING_COMPLETE
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_USER_CONFIRMATION_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.IO_CAP_DISPLAY_YES_NO
import com.shilapi.xcertplay.usbbt.HciProtocol.IO_CAP_NO_INPUT_NO_OUTPUT
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_IO_CAPABILITY_REQUEST_REPLY
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_LINK_KEY_REQUEST_NEGATIVE_REPLY
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_LINK_KEY_REQUEST_REPLY
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_PIN_CODE_REQUEST_NEGATIVE_REPLY
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_USER_CONFIRMATION_REQUEST_REPLY
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeLinkKeyNegativeReply
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeLinkKeyReply
import com.shilapi.xcertplay.usbbt.HciProtocol.encodePinCodeNegativeReply
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Secure Simple Pairing agent for the USB Bluetooth dongle (Phase 4).
 *
 * Two modes, selected by [uiCallback]:
 * - null (default): "Just Works" — IO capability NoInputNoOutput, pairing
 *   auto-confirmed. Appropriate for a head unit with no trusted display.
 * - non-null: Numeric Comparison — IO capability DisplayYesNo; the 6-digit
 *   number from User_Confirmation_Request is delivered to [PairingUiCallback]
 *   and the user's accept/reject decision is sent back. On UI timeout the
 *   agent rejects the Numeric Comparison request.
 *
 * Pairing is triggered by the peer (iPhone) connecting; this agent only
 * answers. Start via [start], stop via [stop]. Thread-safe.
 *
 * Clean-room: SSP flow per Bluetooth Core Spec Vol 2, Part E.
 */
class UsbBtPairingAgent(
    private val hci: UsbBtHci,
    private val linkKeys: LinkKeyStore,
    @Volatile var uiCallback: PairingUiCallback? = null,
    private val context: Context? = null,
) {

    companion object {
        private const val TAG = "UsbBtPairingAgent"

        /** How long to wait for the UI decision before rejecting a Numeric Comparison request. */
        private const val UI_DECISION_TIMEOUT_MS = 30_000L

        /**
         * Extract the 6-digit Numeric Comparison value from a
         * User_Confirmation_Request event: params = BD_ADDR(6) |
         * numeric_value(4 LE). Returns null when params are too short.
         * Pure function, unit-tested.
         */
        fun extractSixDigitNumber(params: ByteArray): Int? {
            if (params.size < 10) return null
            val raw = (params[6].toInt() and 0xFF) or
                ((params[7].toInt() and 0xFF) shl 8) or
                ((params[8].toInt() and 0xFF) shl 16) or
                ((params[9].toInt() and 0xFF) shl 24)
            return ((raw.toLong() and 0xFFFFFFFFL) % 1_000_000).toInt()
        }
    }

    /**
     * UI hook for Numeric Comparison pairing. Called on the pairing agent's
     * background thread; implementations must not block it indefinitely —
     * [onNumericComparison] should return promptly after showing the UI and
     * invoke [confirm] from the UI thread when the user decides.
     */
    interface PairingUiCallback {
        fun onNumericComparison(bdAddr: String, sixDigitNumber: Int, confirm: (accepted: Boolean) -> Unit)
    }

    private val running = AtomicBoolean(false)
    private var executor: java.util.concurrent.ExecutorService? = null

    /** Start the pairing agent. Idempotent. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "UsbBtPairing").apply { isDaemon = true }
        }
        UsbBtLog.log(context, TAG, "pairing agent started")
    }

    fun stop() {
        if (!running.compareAndSet(false, true)) return
        executor?.shutdownNow()
        executor = null
        UsbBtLog.log(context, TAG, "pairing agent stopped")
    }

    /**
     * Dispatch an incoming HCI event to the pairing agent.
     * Asynchronously processed on the pairing worker thread to avoid blocking the HCI pump.
     */
    fun onEvent(event: HciProtocol.HciEvent, bondedOnlyPeer: String? = null) {
        if (!running.get()) return
        when (event.code) {
            EVT_IO_CAPABILITY_REQUEST,
            EVT_IO_CAPABILITY_RESPONSE,
            EVT_USER_CONFIRMATION_REQUEST,
            EVT_LINK_KEY_NOTIFICATION,
            EVT_LINK_KEY_REQUEST,
            EVT_PIN_CODE_REQUEST,
            EVT_SIMPLE_PAIRING_COMPLETE,
            EVT_AUTHENTICATION_COMPLETE -> {
                val worker = executor ?: return
                try {
                    val existingKeyOnly = bondedOnlyPeer != null &&
                        (event.code == EVT_IO_CAPABILITY_REQUEST || event.code == EVT_USER_CONFIRMATION_REQUEST ||
                         event.code == EVT_LINK_KEY_NOTIFICATION) &&
                        bdAddrFrom(event).equals(bondedOnlyPeer, ignoreCase = true)
                    worker.execute {
                        runCatching {
                            if (existingKeyOnly) rejectNewBond(event)
                            else handleEvent(event)
                        }.onFailure { UsbBtLog.warn(context, TAG, "pairing event handler failed", it) }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // Closing the adapter may retire the worker just before submission.
                }
            }
        }
    }

    private fun rejectNewBond(event: HciProtocol.HciEvent) {
        val address = event.params.copyOfRange(0, 6)
        when (event.code) {
            EVT_IO_CAPABILITY_REQUEST -> hci.sendCommand(
                HciProtocol.opcode(0x01, 0x0034), address + 0x18.toByte(),
            ) // IO Capability Request Negative Reply: Pairing Not Allowed.
            EVT_USER_CONFIRMATION_REQUEST -> hci.sendCommand(OP_USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY, address)
            EVT_LINK_KEY_NOTIFICATION -> Unit // Keep the previous key until the user initiates pairing.
        }
    }

    private fun handleEvent(event: HciProtocol.HciEvent) {
        when (event.code) {
            EVT_IO_CAPABILITY_REQUEST -> onIoCapabilityRequest(event)
            EVT_IO_CAPABILITY_RESPONSE -> onIoCapabilityResponse(event)
            EVT_USER_CONFIRMATION_REQUEST -> onUserConfirmationRequest(event)
            EVT_LINK_KEY_NOTIFICATION -> onLinkKeyNotification(event)
            EVT_LINK_KEY_REQUEST -> onLinkKeyRequest(event)
            EVT_PIN_CODE_REQUEST -> onPinCodeRequest(event)
            EVT_SIMPLE_PAIRING_COMPLETE -> onSimplePairingComplete(event)
            EVT_AUTHENTICATION_COMPLETE -> onAuthenticationComplete(event)
            else -> { /* not pairing-related; ignore */ }
        }
    }

    private fun bdAddrFrom(event: HciProtocol.HciEvent): String? {
        if (event.params.size < 6) return null
        return (5 downTo 0).joinToString(":") { i ->
            "%02X".format(event.params[i])
        }
    }

    /**
     * IO_Capability_Request: params = BD_ADDR(6). Reply with DisplayYesNo when
     * a UI callback is present (Numeric Comparison), else NoInputNoOutput
     * (Just Works).
     */
    private fun onIoCapabilityRequest(event: HciProtocol.HciEvent) {
        val bdAddr = bdAddrFrom(event) ?: return
        // The vehicle has no pairing-code UI, so preserve the original no-I/O behavior.
        val ioCap = if (uiCallback == null) IO_CAP_NO_INPUT_NO_OUTPUT else IO_CAP_DISPLAY_YES_NO
        val authReq = if (uiCallback == null) 0x04 else 0x05 // General Bonding
        val reply = event.params.copyOfRange(0, 6) +
            byteArrayOf(ioCap.toByte(), 0x00, authReq.toByte())
        when (val r = hci.sendCommand(OP_IO_CAPABILITY_REQUEST_REPLY, reply)) {
            is UsbBtHci.HciResult.Ok ->
                UsbBtLog.log(context, TAG, "IO capability replied ($ioCap, authReq=0x%02X) for $bdAddr".format(authReq))
            is UsbBtHci.HciResult.Failed ->
                UsbBtLog.warn(context, TAG, "IO capability reply failed for $bdAddr: ${r.reason}")
        }
    }

    private fun onIoCapabilityResponse(event: HciProtocol.HciEvent) {
        val bdAddr = bdAddrFrom(event) ?: return
        val ioCap = if (event.params.size >= 7) event.params[6].toInt() and 0xFF else -1
        val authReq = if (event.params.size >= 9) event.params[8].toInt() and 0xFF else -1
        UsbBtLog.log(context, TAG, "Peer IO capability received from $bdAddr: ioCap=$ioCap authReq=$authReq")
    }

    /**
     * User_Confirmation_Request: params = BD_ADDR(6) | numeric_value(4 LE).
     * With a UI callback, deliver the 6-digit number and wait for the user's
     * decision (timeout -> reject). Without, auto-confirm (Just Works).
     */
    private fun onUserConfirmationRequest(event: HciProtocol.HciEvent) {
        val bdAddr = bdAddrFrom(event) ?: return
        val callback = uiCallback
        if (callback == null || event.params.size < 10) {
            autoConfirm(bdAddr, event)
            return
        }
        // Numeric value is a 32-bit LE integer; displayed as 6 decimal digits.
        val sixDigits = extractSixDigitNumber(event.params) ?: run {
            autoConfirm(bdAddr, event)
            return
        }
        val latch = CountDownLatch(1)
        var accepted = false
        var decided = false
        UsbBtLog.log(context, TAG, "Numeric Comparison requested")
        runCatching {
            callback.onNumericComparison(bdAddr, sixDigits) { ok ->
                synchronized(latch) {
                    if (!decided) {
                        decided = true
                        accepted = ok
                        latch.countDown()
                    }
                }
            }
        }.onFailure { UsbBtLog.warn(context, TAG, "pairing UI callback failed", it) }
        latch.await(UI_DECISION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val addrBytes = event.params.copyOfRange(0, 6)
        val opcode = if (accepted) {
            OP_USER_CONFIRMATION_REQUEST_REPLY
        } else {
            OP_USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY
        }
        when (val r = hci.sendCommand(opcode, addrBytes)) {
            is UsbBtHci.HciResult.Ok ->
                UsbBtLog.log(context, TAG, "pairing ${if (accepted) "confirmed" else "rejected"} for $bdAddr")
            is UsbBtHci.HciResult.Failed ->
                UsbBtLog.warn(context, TAG, "confirmation reply failed for $bdAddr: ${r.reason}")
        }
    }

    /** Just Works: confirm without showing the value. */
    private fun autoConfirm(bdAddr: String, event: HciProtocol.HciEvent) {
        val reply = event.params.copyOfRange(0, 6)
        when (val r = hci.sendCommand(OP_USER_CONFIRMATION_REQUEST_REPLY, reply)) {
            is UsbBtHci.HciResult.Ok ->
                UsbBtLog.log(context, TAG, "auto-confirmed pairing for $bdAddr (Just Works)")
            is UsbBtHci.HciResult.Failed ->
                UsbBtLog.warn(context, TAG, "auto-confirm failed for $bdAddr: ${r.reason}")
        }
    }

    /**
     * Link_Key_Notification: params = BD_ADDR(6) | link_key(16) | key_type(1).
     * Persist the key so future connections can skip pairing.
     */
    private fun onLinkKeyNotification(event: HciProtocol.HciEvent) {
        if (event.params.size < 23) {
            UsbBtLog.warn(context, TAG, "malformed Link_Key_Notification (${event.params.size} bytes)")
            return
        }
        val bdAddr = bdAddrFrom(event) ?: return
        val key = event.params.copyOfRange(6, 22)
        linkKeys.store(bdAddr, key)
        UsbBtLog.log(context, TAG, "Link key stored for $bdAddr (size=${key.size})")
    }

    /**
     * Link_Key_Request: params = BD_ADDR(6). If a link key is stored for this peer,
     * reply with OP_LINK_KEY_REQUEST_REPLY. If not, reply with
     * OP_LINK_KEY_REQUEST_NEGATIVE_REPLY so the controller knows to initiate pairing.
     */
    private fun onLinkKeyRequest(event: HciProtocol.HciEvent) {
        if (event.params.size < 6) return
        val bdAddr = bdAddrFrom(event) ?: return
        val rawAddr = event.params.copyOfRange(0, 6)
        val key = linkKeys.load(bdAddr)
        if (key != null && key.size == 16) {
            val reply = encodeLinkKeyReply(rawAddr, key)
            when (val r = hci.sendCommand(OP_LINK_KEY_REQUEST_REPLY, reply)) {
                is UsbBtHci.HciResult.Ok ->
                    UsbBtLog.log(context, TAG, "replied stored link key for $bdAddr")
                is UsbBtHci.HciResult.Failed ->
                    UsbBtLog.warn(context, TAG, "link key reply failed for $bdAddr: ${r.reason}")
            }
        } else {
            val reply = encodeLinkKeyNegativeReply(rawAddr)
            when (val r = hci.sendCommand(OP_LINK_KEY_REQUEST_NEGATIVE_REPLY, reply)) {
                is UsbBtHci.HciResult.Ok ->
                    UsbBtLog.log(context, TAG, "replied negative link key for $bdAddr (initiating pairing)")
                is UsbBtHci.HciResult.Failed ->
                    UsbBtLog.warn(context, TAG, "negative link key reply failed for $bdAddr: ${r.reason}")
            }
        }
    }

    /**
     * PIN_Code_Request: params = BD_ADDR(6). We use SSP exclusively, so reject PIN requests
     * with OP_PIN_CODE_REQUEST_NEGATIVE_REPLY.
     */
    private fun onPinCodeRequest(event: HciProtocol.HciEvent) {
        if (event.params.size < 6) return
        val bdAddr = bdAddrFrom(event) ?: return
        val rawAddr = event.params.copyOfRange(0, 6)
        val reply = encodePinCodeNegativeReply(rawAddr)
        when (val r = hci.sendCommand(OP_PIN_CODE_REQUEST_NEGATIVE_REPLY, reply)) {
            is UsbBtHci.HciResult.Ok ->
                UsbBtLog.log(context, TAG, "replied negative pin code for $bdAddr")
            is UsbBtHci.HciResult.Failed ->
                UsbBtLog.warn(context, TAG, "negative pin code reply failed for $bdAddr: ${r.reason}")
        }
    }

    private fun onSimplePairingComplete(event: HciProtocol.HciEvent) {
        if (event.params.size < 8) return
        val status = event.params[0].toInt() and 0xFF
        val bdAddr = bdAddrFrom(event) ?: "?"
        val msg = if (status == 0) "pairing complete with $bdAddr"
        else "pairing FAILED with $bdAddr status=0x${status.toString(16)}"
        UsbBtLog.log(context, TAG, msg)
    }

    private fun onAuthenticationComplete(event: HciProtocol.HciEvent) {
        if (event.params.size < 3) return
        val status = event.params[0].toInt() and 0xFF
        val handle = (event.params[1].toInt() and 0xFF) or ((event.params[2].toInt() and 0xFF) shl 8)
        val msg = if (status == 0) "authentication complete for handle=0x${handle.toString(16)}"
        else "authentication FAILED for handle=0x${handle.toString(16)} status=0x${status.toString(16)}"
        UsbBtLog.log(context, TAG, msg)
    }
}
