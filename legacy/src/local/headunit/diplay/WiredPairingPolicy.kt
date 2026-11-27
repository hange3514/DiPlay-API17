package local.headunit.diplay

import com.shilapi.xcertplay.transport.LockdownPairingRejected
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.TlsFatalAlertReceived
import java.security.MessageDigest
import java.util.Locale

internal object WiredPairingPolicy {
    fun usbKey(serial: String?): String? {
        val value = serial?.trim()?.uppercase(Locale.US) ?: return null
        if (!value.matches(Regex("[A-Z0-9-]{8,80}")) || value.all { it == '0' || it == '-' }) return null
        return "lockdown_usb_" + digest(value.toByteArray(Charsets.US_ASCII))
    }

    fun isDifferentUsbPeer(expected: String?, candidate: String?): Boolean =
        expected != null && candidate != null && expected != candidate

    fun publicKey(publicKeyPem: ByteArray): String =
        "lockdown_key_" + digest(normalizePublicKey(publicKeyPem))

    fun matchesPublicKey(left: ByteArray, right: ByteArray): Boolean =
        normalizePublicKey(left).contentEquals(normalizePublicKey(right))

    fun needsRepair(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        it is LockdownPairingRejected || it is TlsFatalAlertReceived && when (it.alertDescription) {
            AlertDescription.bad_certificate, AlertDescription.unsupported_certificate,
            AlertDescription.certificate_revoked, AlertDescription.certificate_expired,
            AlertDescription.certificate_unknown, AlertDescription.unknown_ca -> true
            else -> false
        }
    }

    private fun normalizePublicKey(value: ByteArray): ByteArray =
        value.filterNot { it == 9.toByte() || it == 10.toByte() || it == 13.toByte() || it == 32.toByte() }
            .toByteArray()

    private fun digest(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
}
