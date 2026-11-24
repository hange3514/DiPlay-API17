package local.headunit.diplay

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Resolves a phone's DNS-SD PTR → SRV → source-matching A or AAAA chain. */
internal class CarPlayControlDns(private val peer: InetAddress, expectedBluetoothId: String? = null) {
    data class Endpoint(val key: String, val host: InetAddress, val port: Int)

    internal data class Name(val labels: List<String>) {
        val key = labels.joinToString("\u0000") { it.lowercase(Locale.US) }
        fun isControlInstance() = labels.size == 4 && labels.drop(1).map { it.lowercase(Locale.US) } == service.labels
        fun isLocalHost() = labels.size >= 2 && labels.last().equals("local", ignoreCase = true)
    }
    internal data class NameAt(val name: Name, val end: Int)
    private data class Record(val owner: Name, val type: Int, val ttl: Long, val start: Int, val end: Int)
    private data class Service(val target: Name, val port: Int)
    private data class Entry<T>(val value: T, val expiresAtMillis: Long)
    private data class Question(val name: Name, val type: Int)

    private val expectedId = expectedBluetoothId?.let(::normalizeBluetoothId)
    private val ipv6 = peer is Inet6Address
    private val addressType = if (ipv6) AAAA else A

    // Only this peer can populate the cache, and all entries have bounded lifetimes.
    private val instances = LinkedHashMap<String, Entry<Name>>()
    private val services = LinkedHashMap<String, Entry<Service>>()
    private val addresses = LinkedHashMap<String, Entry<InetAddress>>()
    private val identities = LinkedHashMap<String, Entry<String>>()

    @Synchronized
    fun query(nowMillis: Long, id: Int = 0): ByteArray {
        expire(nowMillis)
        val questions = linkedSetOf(Question(service, PTR))
        for ((key, instance) in instances) {
            val service = services[key]?.value
            if (service == null) questions += Question(instance.value, SRV)
            else if (!addresses.containsKey(service.target.key)) questions += Question(service.target, addressType)
            if (expectedId != null && !identities.containsKey(key)) questions += Question(instance.value, TXT)
        }
        return encodeQuery(questions, id)
    }

    @Synchronized
    fun offer(bytes: ByteArray, offset: Int, length: Int, source: InetAddress, nowMillis: Long): List<Endpoint> {
        if (!matchesPeer(source) || offset < 0 || length < HEADER || offset > bytes.size - length) return emptyList()
        val end = offset + length
        val records = readResponse(bytes, offset, end) ?: return emptyList()
        expire(nowMillis)
        for (record in records) {
            when (record.type) {
                PTR -> {
                    if (record.owner.key != service.key) continue
                    val value = readName(bytes, offset, end, record.start) ?: continue
                    if (value.end != record.end || !value.name.isControlInstance()) continue
                    update(instances, value.name.key, value.name, record.ttl, nowMillis)
                }
                SRV -> {
                    if (!record.owner.isControlInstance() || record.end - record.start <= 6) continue
                    val port = u16(bytes, record.start + 4)
                    val target = readName(bytes, offset, end, record.start + 6) ?: continue
                    if (target.end != record.end || port == 0 || !target.name.isLocalHost()) continue
                    update(services, record.owner.key, Service(target.name, port), record.ttl, nowMillis)
                }
                TXT -> {
                    if (expectedId == null || !record.owner.isControlInstance()) continue
                    val identity = bluetoothId(bytes, record.start, record.end) ?: continue
                    update(identities, record.owner.key, identity, record.ttl, nowMillis)
                }
                addressType -> {
                    if (!record.owner.isLocalHost() || record.end - record.start != peer.address.size) continue
                    if (!bytes.copyOfRange(record.start, record.end).contentEquals(peer.address)) continue
                    update(addresses, record.owner.key, peer, record.ttl, nowMillis)
                }
            }
        }
        return endpoints(nowMillis)
    }

    @Synchronized
    fun endpoints(nowMillis: Long): List<Endpoint> {
        expire(nowMillis)
        if (ipv6 && expectedId == null) return emptyList()
        return instances.mapNotNull { (key, instance) ->
            if (expectedId != null && identities[key]?.value != expectedId) return@mapNotNull null
            val service = services[key]?.value ?: return@mapNotNull null
            val host = addresses[service.target.key]?.value ?: return@mapNotNull null
            Endpoint(instance.value.labels.first().lowercase(Locale.US), host, service.port)
        }
    }

    @Synchronized
    fun hasControlRecord(nowMillis: Long): Boolean {
        expire(nowMillis)
        return instances.isNotEmpty() || services.isNotEmpty() || identities.values.any { it.value == expectedId }
    }

    private fun matchesPeer(source: InetAddress): Boolean {
        if (source != peer) return false
        if (peer !is Inet6Address) return true
        if (source !is Inet6Address || !peer.isLinkLocalAddress) return false
        return peer.scopeId == 0 || source.scopeId == 0 || peer.scopeId == source.scopeId
    }

    private fun expire(nowMillis: Long) {
        instances.entries.removeAll { it.value.expiresAtMillis <= nowMillis }
        services.entries.removeAll { it.value.expiresAtMillis <= nowMillis }
        addresses.entries.removeAll { it.value.expiresAtMillis <= nowMillis }
        identities.entries.removeAll { it.value.expiresAtMillis <= nowMillis }
    }

    private fun <T> update(map: MutableMap<String, Entry<T>>, key: String, value: T, ttl: Long, nowMillis: Long) {
        if (ttl == 0L) {
            if (map[key]?.value == value) map.remove(key)
        } else if (map.containsKey(key) || map.size < MAX_CACHE_ENTRIES) {
            map[key] = Entry(value, nowMillis + minOf(ttl, 120) * 1_000)
        }
    }

    private fun readResponse(bytes: ByteArray, base: Int, end: Int): List<Record>? {
        val flags = u16(bytes, base + 2)
        // Only complete, successful DNS query responses (including multicast advertisements).
        if (flags and 0xfa0f != 0x8000) return null
        val questionCount = u16(bytes, base + 4)
        val recordCount = u16(bytes, base + 6) + u16(bytes, base + 8) + u16(bytes, base + 10)
        if (questionCount > 32 || recordCount > 128) return null
        var cursor = base + HEADER
        repeat(questionCount) {
            cursor = (readName(bytes, base, end, cursor) ?: return null).end
            if (end - cursor < 4) return null
            cursor += 4
        }
        val out = ArrayList<Record>()
        repeat(recordCount) {
            val owner = readName(bytes, base, end, cursor) ?: return null
            cursor = owner.end
            if (end - cursor < 10) return null
            val type = u16(bytes, cursor)
            val klass = u16(bytes, cursor + 2) and 0x7fff
            val ttl = (u16(bytes, cursor + 4).toLong() shl 16) or u16(bytes, cursor + 6).toLong()
            val dataBytes = u16(bytes, cursor + 8)
            cursor += 10
            if (end - cursor < dataBytes) return null
            if (klass == 1 && type in RECORD_TYPES) {
                out += Record(owner.name, type, ttl, cursor, cursor + dataBytes)
            }
            cursor += dataBytes
        }
        return out
    }

    companion object {
        internal class LegacyQuestion(val id: Int, val count: Int, val questionWire: ByteArray)
        internal class Questions(
            val names: Set<String>,
            val unicastRequested: Boolean,
            val legacy: LegacyQuestion?,
        )

        internal fun browseQuery(): ByteArray = encodeQuery(listOf(Question(service, PTR)), id = 0)

        private fun encodeQuery(questions: Collection<Question>, id: Int): ByteArray {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).apply {
                writeShort(id)
                writeShort(0)
                writeShort(questions.size)
                repeat(3) { writeShort(0) }
                for (question in questions) {
                    for (label in question.name.labels) {
                        val bytes = label.toByteArray(UTF8)
                        writeByte(bytes.size)
                        write(bytes)
                    }
                    writeByte(0)
                    writeShort(question.type)
                    writeShort(1) // IN, requesting a normal multicast response.
                }
            }
            return output.toByteArray()
        }

        internal fun readQuestions(bytes: ByteArray, base: Int, length: Int, legacy: Boolean): Questions? {
            if (base < 0 || length < HEADER || base > bytes.size - length || u16(bytes, base + 2) and 0xf800 != 0) {
                return null
            }
            val end = base + length
            val count = u16(bytes, base + 4)
            if (count !in 1..32) return null
            val names = LinkedHashSet<String>()
            var cursor = base + HEADER
            var unicastRequested = false
            repeat(count) {
                val question = readName(bytes, base, end, cursor) ?: return null
                cursor = question.end
                if (end - cursor < 4) return null
                val recordClass = u16(bytes, cursor + 2)
                if ((recordClass and 0x7fff) == 1 || (recordClass and 0x7fff) == 255) {
                    names += question.name.labels.joinToString(".", postfix = ".").lowercase(Locale.US)
                    if (recordClass and 0x8000 != 0) unicastRequested = true
                }
                cursor += 4
            }
            val legacyQuestion = if (legacy) {
                LegacyQuestion(u16(bytes, base), count, bytes.copyOfRange(base + HEADER, cursor))
            } else null
            return Questions(names, unicastRequested || legacy, legacyQuestion)
        }

        internal fun encodeResponse(records: List<ByteArray>, legacy: LegacyQuestion?): ByteArray {
            val output = ByteArrayOutputStream(1024)
            DataOutputStream(output).apply {
                writeShort(legacy?.id ?: 0)
                writeShort(0x8400) // authoritative response, no truncation
                writeShort(legacy?.count ?: 0)
                writeShort(records.size)
                writeShort(0)
                writeShort(0)
                legacy?.let { write(it.questionWire) }
                for (record in records) write(record)
            }
            return output.toByteArray()
        }

        /** One legacy query can produce separate replies for its PTR, SRV and A questions. */
        internal fun receiveReplies(
            socket: DatagramSocket,
            id: Int,
            timeoutMillis: Long,
            onReply: (DatagramPacket) -> Boolean,
        ) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            val reply = DatagramPacket(ByteArray(4096), 4096)
            while (!socket.isClosed) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return
                socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).toInt()
                reply.length = reply.data.size
                try {
                    socket.receive(reply)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (reply.length >= HEADER && u16(reply.data, reply.offset) == id && onReply(reply)) return
            }
        }

        private fun readName(bytes: ByteArray, base: Int, end: Int, start: Int): NameAt? {
            val labels = ArrayList<String>()
            var cursor = start
            var nextOffset = -1
            var nameLength = 1
            var steps = 0
            while (cursor in base until end && steps++ < 128) {
                val length = bytes[cursor].toInt() and 0xff
                if (length == 0) return NameAt(Name(labels), if (nextOffset < 0) cursor + 1 else nextOffset)
                if (length and 0xc0 == 0xc0) {
                    if (end - cursor < 2) return null
                    val target = base + (u16(bytes, cursor) and 0x3fff)
                    if (target < base + HEADER || target >= cursor) return null
                    if (nextOffset < 0) nextOffset = cursor + 2
                    cursor = target
                } else {
                    if (length > 63 || end - cursor <= length || nameLength + length + 1 > 255) return null
                    val label = String(bytes, cursor + 1, length, UTF8)
                    if (label.indexOf('\u0000') != -1 || label.toByteArray(UTF8).size != length) return null
                    labels += label
                    nameLength += length + 1
                    cursor += 1 + length
                }
            }
            return null
        }

        private fun bluetoothId(bytes: ByteArray, start: Int, end: Int): String? {
            var cursor = start
            var identity: String? = null
            while (cursor < end) {
                val length = bytes[cursor++].toInt() and 0xff
                if (end - cursor < length) return null
                val value = String(bytes, cursor, length, UTF8)
                if (value.startsWith("id=", ignoreCase = true)) {
                    if (identity != null) return null
                    identity = normalizeBluetoothId(value.substring(3)) ?: return null
                }
                cursor += length
            }
            return identity
        }

        private fun normalizeBluetoothId(id: String): String? {
            val compact = id.replace(":", "").lowercase(Locale.US)
            return compact.takeIf { it.length == 12 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
        }

        private const val HEADER = 12
        private const val A = 1
        private const val PTR = 12
        private const val TXT = 16
        private const val AAAA = 28
        private const val SRV = 33
        private const val MAX_CACHE_ENTRIES = 8
        private val RECORD_TYPES = setOf(A, PTR, TXT, AAAA, SRV)
        private val UTF8: Charset = Charset.forName("UTF-8")
        private val service = Name(listOf("_carplay-ctrl", "_tcp", "local"))
        private fun u16(bytes: ByteArray, at: Int) =
            ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
    }
}
