package local.headunit.diplay

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Passive ALSA inventory and per-head-unit path preference. It never changes mixer controls. */
object AlsaCapturePathSelector {
    private const val KNOWN_MX9A0_PATH = "/dev/snd/pcmC1D0c"
    private const val PREFS = "alsa_capture_profile"
    private val procPcmRow = Regex("^\\s*(\\d+)-(\\d+):\\s*(.*?)\\s*:\\s*(.*)$")
    private val captureCount = Regex("(?:^|\\s)capture\\s+(\\d+)(?:\\s|$)", RegexOption.IGNORE_CASE)
    private val nodeName = Regex("pcmC(\\d+)D(\\d+)c")
    private val cardRow = Regex("^\\s*(\\d+)\\s+\\[([^]]+)]\\s*:\\s*(.*)$")

    data class Candidate(
        val path: String,
        val source: String,
        val card: Int,
        val device: Int,
        val description: String,
        val exists: Boolean,
        val readable: Boolean,
        val writable: Boolean
    )

    data class Inventory(
        val profileKey: String,
        val cardsText: String,
        val pcmText: String,
        val deviceNodes: List<JSONObject>,
        val candidates: List<Candidate>,
        val totalCandidates: Int,
        val truncated: Boolean,
        val preferredPath: String?
    )

    @JvmStatic
    fun discover(context: Context?): Inventory {
        val cardsText = readBounded("/proc/asound/cards", 4096)
        val pcmText = readBounded("/proc/asound/pcm", 8192)
        val cardNames = LinkedHashMap<Int, String>()
        for (line in cardsText.lines()) {
            val m = cardRow.find(line) ?: continue
            cardNames[m.groupValues[1].toIntOrNull() ?: continue] = (m.groupValues[2] + " " + m.groupValues[3]).trim().take(96)
        }

        val byPath = LinkedHashMap<String, Candidate>()
        for (line in pcmText.lines()) {
            val m = procPcmRow.find(line) ?: continue
            val card = m.groupValues[1].toIntOrNull() ?: continue
            val device = m.groupValues[2].toIntOrNull() ?: continue
            val streams = m.groupValues[4]
            val count = captureCount.find(streams)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (count <= 0) continue
            val path = "/dev/snd/pcmC${card}D${device}c"
            byPath[path] = candidate(path, "proc_pcm", card, device, (cardNames[card].orEmpty() + " " + m.groupValues[3]).trim().take(120))
        }

        val dir = File("/dev/snd")
        val files = try { dir.listFiles() } catch (_: Throwable) { null }
        val nodeRows = ArrayList<JSONObject>()
        if (files != null) {
            for (file in files.sortedBy { it.name }.take(96)) {
                nodeRows.add(JSONObject().put("name", file.name).put("read", file.canRead()).put("write", file.canWrite()))
                val m = nodeName.matchEntire(file.name) ?: continue
                val path = file.absolutePath
                val card = m.groupValues[1].toIntOrNull() ?: continue
                val device = m.groupValues[2].toIntOrNull() ?: continue
                if (!byPath.containsKey(path)) byPath[path] = candidate(path, "device_node", card, device, cardNames[card].orEmpty())
            }
        }

        // Preserve the one field-verified MX9A0 path, but always probe alternatives too.
        if (!byPath.containsKey(KNOWN_MX9A0_PATH)) {
            byPath[KNOWN_MX9A0_PATH] = candidate(KNOWN_MX9A0_PATH, "known_mx9a0", 1, 0, cardNames[1].orEmpty())
        }
        val profileKey = profileKey(cardsText, pcmText)
        val prefs = try { context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) } catch (_: Throwable) { null }
        val remembered = try { prefs?.getString("path_$profileKey", null) } catch (_: Throwable) { null }
        val all = byPath.values.toList()
        val ordered = ArrayList<Candidate>()
        if (remembered != null) all.firstOrNull { it.path == remembered }?.let { ordered.add(it.copy(source = "last_signal")) }
        all.firstOrNull { it.path == KNOWN_MX9A0_PATH }?.let { if (ordered.none { c -> c.path == it.path }) ordered.add(it) }
        for (candidate in all.sortedWith(compareBy<Candidate>({ it.card }, { it.device }, { it.path }))) {
            if (ordered.none { it.path == candidate.path }) ordered.add(candidate)
        }
        val limit = ordered.take(12)
        return Inventory(profileKey, cardsText, pcmText, nodeRows, limit, ordered.size, ordered.size > limit.size, remembered)
    }

    @JvmStatic
    fun rememberSignal(context: Context?, inventory: Inventory, path: String, rate: Int, channels: Int, peak: Int, meanAbs: Long) {
        if (context == null || peak < 128 || meanAbs < 16L) return
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("path_${inventory.profileKey}", path)
                .putInt("rate_${inventory.profileKey}", rate)
                .putInt("channels_${inventory.profileKey}", channels)
                .putLong("savedAt_${inventory.profileKey}", System.currentTimeMillis())
                .commit()
        } catch (_: Throwable) { }
    }

    @JvmStatic
    fun environment(context: Context): JSONObject {
        val inventory = discover(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rememberedPath = inventory.preferredPath ?: ""
        val candidates = JSONArray()
        for (candidate in inventory.candidates) {
            candidates.put(JSONObject()
                .put("path", candidate.path).put("source", candidate.source)
                .put("card", candidate.card).put("device", candidate.device)
                .put("description", candidate.description)
                .put("exists", candidate.exists).put("read", candidate.readable).put("write", candidate.writable))
        }
        val nodes = JSONArray()
        for (node in inventory.deviceNodes) nodes.put(node)
        return JSONObject()
            .put("method", "passive_proc_asound_and_dev_snd_enumeration")
            .put("nativeProbeLoaded", AlsaProbe.available())
            .put("profileKey", inventory.profileKey)
            .put("rememberedPath", rememberedPath)
            .put("rememberedRate", if (rememberedPath.isEmpty()) 0 else prefs.getInt("rate_${inventory.profileKey}", 0))
            .put("rememberedChannels", if (rememberedPath.isEmpty()) 0 else prefs.getInt("channels_${inventory.profileKey}", 0))
            .put("rememberedSignalAt", if (rememberedPath.isEmpty()) 0L else prefs.getLong("savedAt_${inventory.profileKey}", 0L))
            .put("cards", inventory.cardsText.take(2048))
            .put("pcm", inventory.pcmText.take(4096))
            .put("deviceNodes", nodes)
            .put("captureCandidates", candidates)
            .put("candidateCount", inventory.totalCandidates)
            .put("candidateListOmittedCount", (inventory.totalCandidates - inventory.candidates.size).coerceAtLeast(0))
            .put("candidateListTruncated", inventory.truncated)
            .put("knownMx9a0Path", KNOWN_MX9A0_PATH)
            .put("mixerControlsChanged", false)
            .put("rawAudioCollected", false)
    }

    private fun candidate(path: String, source: String, card: Int, device: Int, description: String): Candidate {
        val file = File(path)
        return Candidate(path, source, card, device, description.take(120), file.exists(), file.canRead(), file.canWrite())
    }

    private fun readBounded(path: String, maxBytes: Int): String {
        return try {
            val input = FileInputStream(path)
            try {
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(512)
                var total = 0
                while (total < maxBytes) {
                    val n = input.read(buffer, 0, Math.min(buffer.size, maxBytes - total))
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    total += n
                }
                String(out.toByteArray(), Charsets.UTF_8).filter { it == '\n' || it == '\r' || it == '\t' || it >= ' ' }
            } finally { input.close() }
        } catch (_: Throwable) { "unavailable" }
    }

    private fun profileKey(cards: String, pcm: String): String {
        val value = listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.BOARD, Build.HARDWARE,
            Build.VERSION.SDK_INT.toString(), cards, pcm).joinToString("|")
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            digest.take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
        } catch (_: Throwable) { Integer.toHexString(value.hashCode()) }
    }
}
