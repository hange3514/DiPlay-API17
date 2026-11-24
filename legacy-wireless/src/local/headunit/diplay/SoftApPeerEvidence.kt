package local.headunit.diplay

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

/** Passive AP-side ARP evidence. Incomplete or empty tables cannot rule out a Wi-Fi association. */
internal class SoftApPeerEvidence(
    private val apInterface: String,
    private val apAddress: Inet4Address?,
    networkMask: String,
    private val openTable: () -> BufferedReader = { File("/proc/net/arp").bufferedReader(Charsets.US_ASCII) },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    constructor(wifi: HybridNetworkSnapshot) : this(wifi.interfaceName, wifi.localAddress as? Inet4Address, wifi.networkMask)

    private val prefix = parseIpv4(networkMask)?.let { bytes ->
        val mask = bytes.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 255) }
        Integer.bitCount(mask).takeIf { it in 1..31 && mask == (-1 shl (32 - it)) }
    }
    private data class Neighbor(val ip: Inet4Address, val mac: String)
    private data class Read(val status: String, val neighbors: Set<Neighbor>? = null)

    private var original: Set<Neighbor>? = null
    private var peakNewPeers = -1

    @Synchronized
    fun baseline(): String {
        val result = read()
        original = result.neighbors
        peakNewPeers = if (original == null) -1 else 0
        return summary(result)
    }

    @Synchronized
    fun sample(): String = summary(read())

    private fun summary(result: Read): String {
        val initial = original
        val current = result.neighbors
        val newPeers = if (current != null && initial != null) current.count { it !in initial } else -1
        peakNewPeers = maxOf(peakNewPeers, newPeers)
        return "apArpStatus=${result.status} apArpComplete=${current?.size ?: -1} " +
            "apArpBaseline=${initial?.size ?: -1} apArpNew=$newPeers apArpNewPeak=$peakNewPeers"
    }

    private fun read(): Read {
        val local = apAddress ?: return Read("unsupported_binding")
        val subnet = prefix ?: return Read("unsupported_binding")
        if (apInterface.isEmpty()) return Read("unsupported_binding")
        val startedAt = nanoTime()
        return try {
            openTable().use { reader ->
                if (reader.readLine()?.startsWith("IP address") != true) return Read("unavailable")
                val neighbors = LinkedHashSet<Neighbor>()
                repeat(MAX_ARP_LINES) {
                    if (nanoTime() - startedAt >= READ_BUDGET_NS) return Read("scan_limit")
                    val line = reader.readLine() ?: return Read("available", neighbors)
                    val columns = line.trim().split(whitespace, limit = 6)
                    if (columns.size != 6 || columns[5] != apInterface) return@repeat
                    val flags = columns[2].removePrefix("0x").toIntOrNull(16) ?: return@repeat
                    if ((flags and ARP_COMPLETE) == 0 || (flags and ARP_PERMANENT) != 0) return@repeat
                    val mac = columns[3].lowercase(Locale.US)
                    if (!hardwareAddress.matches(mac) || mac == "00:00:00:00:00:00" || (mac.substring(0, 2).toInt(16) and 1) != 0) return@repeat
                    val bytes = parseIpv4(columns[0]) ?: return@repeat
                    val ip = InetAddress.getByAddress(bytes) as Inet4Address
                    if (isOnLinkIpv4Peer(local, ip, subnet)) neighbors.add(Neighbor(ip, mac))
                }
                if (reader.readLine() == null) Read("available", neighbors) else Read("scan_limit")
            }
        } catch (_: IOException) {
            Read("unavailable")
        } catch (_: SecurityException) {
            Read("unavailable")
        }
    }

    companion object {
        private const val MAX_ARP_LINES = 256
        private const val READ_BUDGET_NS = 40_000_000L
        private const val ARP_COMPLETE = 0x02
        private const val ARP_PERMANENT = 0x04
        private val whitespace = Regex("\\s+")
        private val hardwareAddress = Regex("[0-9a-f]{2}(?::[0-9a-f]{2}){5}")

        private fun parseIpv4(literal: String): ByteArray? {
            val columns = literal.split('.', limit = 5)
            if (columns.size != 4) return null
            return ByteArray(4) { index ->
                val octet = columns[index]
                if (octet.length !in 1..3 || !octet.all { it in '0'..'9' }) return null
                val value = octet.toInt()
                if (value > 255) return null
                value.toByte()
            }
        }
    }
}
