package local.headunit.diplay

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

/** Captures a single explicitly armed sample from an outbound microphone stream. */
object CallMicCapture {
    const val MAX_SECONDS = 10
    private const val ARM_TIMEOUT_MS = 5 * 60 * 1000L
    private const val READY_TIMEOUT_MS = 24 * 60 * 60 * 1000L
    private const val WAV_HEADER_BYTES = 44

    private data class Armed(val id: String, val expiresAtMs: Long)
    private class Recording(val arming: Armed, val owner: Recorder, val sampleRate: Int, val audioType: String) {
        val before = ByteArray(sampleRate * 2 * MAX_SECONDS)
        val after = ByteArray(before.size)
        var bytes = 0
        var pendingBytes = 0
        var firstFrameAtMs = -1L
    }

    private data class Clip(
        val id: String,
        val audioType: String,
        val sampleRate: Int,
        val bytes: Int,
        val before: ByteArray,
        val after: ByteArray,
        val expiresAtMs: Long,
    )

    class Recorder internal constructor() {
        fun before(pcm: ByteArray) = beforeFilter(this, pcm)
        fun after(pcm: ByteArray) = afterFilter(this, pcm)
        fun finish() = finishRecording(this)
    }

    data class ReadySample(val id: String, val audioType: String)

    data class Snapshot(
        val id: String,
        val audioType: String,
        val sampleRate: Int,
        val pcmBytes: Int,
        val beforeWav: ByteArray,
        val afterWav: ByteArray,
    )

    private val lock = Any()
    private var armed: Armed? = null
    private var active: Recording? = null
    private var ready: Clip? = null

    @JvmStatic fun status(): String = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        expire(now)
        when {
            ready != null -> "ready"
            active != null -> "recording"
            armed != null -> "armed"
            else -> "idle"
        }
    }

    @JvmStatic fun arm(): Boolean = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        expire(now)
        if (armed != null || active != null || ready != null) return@synchronized false
        armed = Armed(UUID.randomUUID().toString().replace("-", ""), now + ARM_TIMEOUT_MS)
        true
    }

    @JvmStatic fun discard() = synchronized(lock) {
        armed = null
        active = null
        ready = null
    }

    @JvmStatic fun readySample(): ReadySample? = synchronized(lock) {
        expire(SystemClock.elapsedRealtime())
        ready?.let { ReadySample(it.id, it.audioType) }
    }

    @JvmStatic fun readyId(): String? = readySample()?.id

    /** Creates the two WAV files in memory, to be encrypted on the report worker. */
    @JvmStatic fun attachment(confirmedId: String): Snapshot? {
        val clip = synchronized(lock) {
            expire(SystemClock.elapsedRealtime())
            ready?.takeIf { it.id == confirmedId }
        } ?: return null
        return Snapshot(clip.id, clip.audioType, clip.sampleRate, clip.bytes,
            wav(clip.before, clip.bytes, clip.sampleRate),
            wav(clip.after, clip.bytes, clip.sampleRate))
    }

    @JvmStatic fun acknowledge(id: String): Unit = synchronized(lock) {
        if (ready?.id == id) ready = null
    }

    /** Claims the next microphone stream. Call before backend capture starts. */
    internal fun begin(sampleRate: Int, audioType: String): Recorder? = synchronized(lock) {
        expire(SystemClock.elapsedRealtime())
        val arming = armed ?: return@synchronized null
        require(sampleRate in 8000..48000)
        val owner = Recorder()
        val role = when (val normalized = audioType.lowercase(Locale.US)) {
            "speechrecognition", "telephony" -> normalized
            else -> "voice_input"
        }
        active = Recording(arming, owner, sampleRate, role)
        armed = null
        owner
    }

    private fun beforeFilter(owner: Recorder, pcm: ByteArray) = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        expire(now)
        val recording = active?.takeIf { it.owner === owner } ?: return@synchronized
        val bytes = minOf(pcm.size, recording.before.size - recording.bytes) and -2
        if (bytes == 0) return@synchronized
        if (recording.firstFrameAtMs < 0) recording.firstFrameAtMs = now
        pcm.copyInto(recording.before, recording.bytes, 0, bytes)
        recording.pendingBytes = bytes
    }

    private fun afterFilter(owner: Recorder, pcm: ByteArray): Unit = synchronized(lock) {
        val recording = active?.takeIf { it.owner === owner } ?: return@synchronized
        val bytes = recording.pendingBytes
        if (bytes == 0) return@synchronized
        pcm.copyInto(recording.after, recording.bytes, 0, bytes)
        recording.pendingBytes = 0
        recording.bytes += bytes
        if (recording.bytes == recording.after.size) complete(recording, SystemClock.elapsedRealtime())
    }

    private fun finishRecording(owner: Recorder) = synchronized(lock) {
        val recording = active?.takeIf { it.owner === owner } ?: return@synchronized
        complete(recording, SystemClock.elapsedRealtime())
    }

    private fun expire(now: Long) {
        if (armed?.let { now >= it.expiresAtMs } == true) armed = null
        if (ready?.let { now >= it.expiresAtMs } == true) ready = null
        val recording = active ?: return
        if (recording.firstFrameAtMs < 0) {
            if (now >= recording.arming.expiresAtMs) active = null
        } else if (now - recording.firstFrameAtMs >= MAX_SECONDS * 1000L) {
            complete(recording, now)
        }
    }

    private fun complete(recording: Recording, now: Long) {
        active = null
        if (recording.bytes == 0) {
            if (now < recording.arming.expiresAtMs) armed = recording.arming
            return
        }
        ready = Clip(recording.arming.id, recording.audioType, recording.sampleRate, recording.bytes,
            recording.before, recording.after, now + READY_TIMEOUT_MS)
    }

    private fun wav(pcm: ByteArray, bytes: Int, rate: Int): ByteArray {
        val wav = ByteBuffer.allocate(WAV_HEADER_BYTES + bytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(bytes + 36)
        wav.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        wav.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        wav.put("data".toByteArray(Charsets.US_ASCII)).putInt(bytes)
        wav.put(pcm, 0, bytes)
        return wav.array()
    }
}
