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
    private const val MAX_CANDIDATES = 128
    private val procPcmRow = Regex("^\\s*(\\d+)-(\\d+):\\s*(.*?)\\s*:\\s*(.*)$")
    private val captureCount = Regex("(?:^|\\s)capture\\s+(\\d+)(?:\\s|$)", RegexOption.IGNORE_CASE)
    private val nodeName = Regex("pcmC(\\d{1,3})D(\\d{1,3})c")
    private val reportableNodeName = Regex("[A-Za-z0-9_.-]{1,64}")
    private val cardRow = Regex("^\\s*(\\d+)\\s+\\[([^]]+)]\\s*:\\s*(.*)$")
    private val loopbackName = Regex("loop[-_ ]?back|(?:^|\\W)aloop(?:\\W|$)", RegexOption.IGNORE_CASE)
    private val microphoneName = Regex("microphone|(?:^|\\W)(?:mic|dmic|pdm|adc)(?:\\W|$)", RegexOption.IGNORE_CASE)

    data class Candidate(
        val path: String,
        val source: String,
        val card: Int,
        val device: Int,
        val description: String,
        val exists: Boolean,
        val readable: Boolean,
        val writable: Boolean
    ) {
        fun isLoopback(): Boolean = loopbackName.containsMatchIn(description)
    }

    data class Inventory(
        val profileKey: String,
        val cardsText: String,
        val pcmText: String,
        val deviceNodes: List<JSONObject>,
        val candidates: List<Candidate>,
        val totalCandidates: Int,
        val truncated: Boolean,
        val preferredPath: String?,
        val preferredRate: Int,
        val preferredChannels: Int,
        val knownMx9a0: Boolean,
        val preferSahPcm1754: Boolean = false
    ) {
        fun hasProvenSignal(candidate: Candidate): Boolean =
            candidate.source == "last_signal" ||
                ((knownMx9a0 || preferSahPcm1754) && candidate.isPcm1754())

        fun captureFormats(candidate: Candidate, targetRate: Int): List<Pair<Int, Int>> {
            val defaultFormats = listOf(
                48000 to 2, 44100 to 2, targetRate to 2, 48000 to 1,
                44100 to 1, targetRate to 1, 16000 to 1, 8000 to 1
            )
            if (candidate.source != "last_signal" || preferredRate !in 8000..96000 ||
                preferredChannels !in 1..8 || (knownMx9a0 && candidate.path == KNOWN_MX9A0_PATH)) {
                return defaultFormats.distinct()
            }
            return (listOf(preferredRate to preferredChannels) + defaultFormats).distinct()
        }
    }

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
            val card = pcmIndex(m.groupValues[1]) ?: continue
            val device = pcmIndex(m.groupValues[2]) ?: continue
            val streams = m.groupValues[4]
            val count = captureCount.find(streams)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (count <= 0) continue
            val path = "/dev/snd/pcmC${card}D${device}c"
            if (byPath.size >= MAX_CANDIDATES) break
            byPath[path] = candidate(path, "proc_pcm", card, device, (cardNames[card].orEmpty() + " " + m.groupValues[3]).trim())
        }

        val dir = File("/dev/snd")
        val nodeRows = ArrayList<JSONObject>()
        val deviceNames = directoryNames(dir)
        for (name in deviceNames.asSequence().filter(reportableNodeName::matches).take(96)) {
            val file = File(dir, name)
            nodeRows.add(JSONObject().put("name", name).put("read", file.canRead()).put("write", file.canWrite()))
        }
        // Some Android builds expose sound devices in sysfs but deny listing /dev/snd.
        val captureNames = deviceNames.asSequence() + directoryNames(File("/sys/class/sound")).asSequence()
        for (name in captureNames) {
            if (byPath.size >= MAX_CANDIDATES) break
            val m = nodeName.matchEntire(name) ?: continue
            val card = pcmIndex(m.groupValues[1]) ?: continue
            val device = pcmIndex(m.groupValues[2]) ?: continue
            val path = File(dir, name).absolutePath
            if (!byPath.containsKey(path)) {
                byPath[path] = candidate(path, "device_node", card, device, cardNames[card].orEmpty())
            }
        }

        val knownMx9a0 = Build.MODEL.equals("mx9a0", true) && Build.MANUFACTURER.equals("freescale", true)
        // SAH .180428 reports a working PCM1754 input at C1D0. Only prioritize it
        // when that capture node is visible and readable before adding fallback guesses.
        val preferSahPcm1754 = byPath[KNOWN_MX9A0_PATH]?.let { it.exists && it.readable && it.isPcm1754() } == true &&
            context != null && SahMx9CallProfile.matches(context)
        val profileKey = profileKey(cardsText, pcmText)
        val prefs = runCatching { context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
        val remembered = runCatching { prefs?.getString("path_$profileKey", null) }.getOrNull()
        // Preserve the field-verified node on its own model. Elsewhere, prefer nodes
        // that were actually enumerated. Probe the two common indices as a last resort.
        val inventoryUnavailable = byPath.isEmpty()
        for ((card, source) in listOf(0 to "device_node", 1 to if (knownMx9a0) "known_mx9a0" else "device_node")) {
            val path = "/dev/snd/pcmC${card}D0c"
            val fallback = candidate(path, source, card, 0, cardNames[card].orEmpty())
            if (!byPath.containsKey(path) && (fallback.exists || inventoryUnavailable ||
                    remembered == path || (knownMx9a0 && path == KNOWN_MX9A0_PATH))) {
                if (byPath.size == MAX_CANDIDATES && knownMx9a0 && path == KNOWN_MX9A0_PATH) {
                    byPath.remove(byPath.keys.last())
                }
                if (byPath.size < MAX_CANDIDATES) byPath[path] = fallback
            }
        }
        val ordered = orderCandidates(byPath.values, remembered, knownMx9a0, preferSahPcm1754)
        val limit = ordered.take(12)
        val rememberedRate = runCatching { prefs?.getInt("rate_$profileKey", 0) ?: 0 }.getOrDefault(0)
        val rememberedChannels = runCatching { prefs?.getInt("channels_$profileKey", 0) ?: 0 }.getOrDefault(0)
        return Inventory(profileKey, cardsText, pcmText, nodeRows, limit, ordered.size, ordered.size > limit.size,
            remembered, rememberedRate, rememberedChannels, knownMx9a0, preferSahPcm1754)
    }

    internal fun orderCandidates(
        candidates: Collection<Candidate>,
        remembered: String?,
        knownMx9a0: Boolean,
        preferSahPcm1754: Boolean = false
    ): List<Candidate> {
        fun isUsableRemembered(candidate: Candidate): Boolean =
            candidate.path == remembered && candidate.exists && (!preferSahPcm1754 || candidate.readable)

        return candidates.sortedWith(compareBy<Candidate>(
            { it.isLoopback() },
            { !isUsableRemembered(it) },
            { !(knownMx9a0 && it.path == KNOWN_MX9A0_PATH ||
                preferSahPcm1754 && it.exists && it.readable && it.isPcm1754()) },
            { !it.exists },
            { !it.readable },
            { !microphoneName.containsMatchIn(it.description) },
            { it.source != "proc_pcm" },
            { it.card }, { it.device }
        )).map { candidate ->
            if (isUsableRemembered(candidate) && !candidate.isLoopback()) {
                candidate.copy(source = "last_signal")
            } else candidate
        }
    }

    private fun Candidate.isPcm1754(): Boolean =
        path == KNOWN_MX9A0_PATH && description.contains("pcm1754", ignoreCase = true)

    /** Extend the legacy sequence when a driver's advertised hardware limits need another format. */
    internal fun extraFormats(rateMin: Int, rateMax: Int, channelMin: Int, channelMax: Int): List<Pair<Int, Int>> {
        if (rateMin <= 0 || rateMax < rateMin || channelMin <= 0 || channelMax < channelMin) return emptyList()
        val rates = listOf(48000, 44100, 32000, 24000, 22050, 16000, rateMin, rateMax).distinct()
            .filter { it in 8000..96000 && it in rateMin..rateMax }
        val channels = listOf(1, 2, channelMin, channelMax, 4, 6, 8).distinct()
            .filter { it in 1..8 && it in channelMin..channelMax }
        return rates.flatMap { rate -> channels.map { rate to it } }
    }

    private fun pcmIndex(value: String): Int? = value.toIntOrNull()?.takeIf { it in 0..255 }

    private fun directoryNames(dir: File): List<String> =
        runCatching { dir.list()?.sorted().orEmpty() }.getOrDefault(emptyList())

    @JvmStatic
    fun rememberSignal(context: Context?, inventory: Inventory, path: String, rate: Int, channels: Int, peak: Int, meanAbs: Long) {
        if (context == null || peak < 128 || meanAbs < 16L) return
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("path_${inventory.profileKey}", path)
                .putInt("rate_${inventory.profileKey}", rate)
                .putInt("channels_${inventory.profileKey}", channels)
                .putLong("savedAt_${inventory.profileKey}", System.currentTimeMillis())
                .apply()
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
            .put("rememberedRate", if (rememberedPath.isEmpty()) 0 else inventory.preferredRate.coerceIn(0, 96000))
            .put("rememberedChannels", if (rememberedPath.isEmpty()) 0 else inventory.preferredChannels.coerceIn(0, 8))
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
        return Candidate(path, source, card, device, description.filter { it >= ' ' }.take(120),
            file.exists(), file.canRead(), file.canWrite())
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
                String(out.toByteArray(), Charsets.UTF_8).filter { it == '\n' || it == '\t' || it >= ' ' }
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
