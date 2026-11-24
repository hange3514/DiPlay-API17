package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    fun decode(bytes: ByteArray): Any? = Reader(bytes).decode()

    private class Reader(private val bytes: ByteArray) {
        private val trailer = bytes.size - 32
        private val offsets: LongArray
        private val visiting: BooleanArray
        private val refSize: Int
        private val objectEnd: Int
        private val root: Int
        private var budget = 8 * 1024 * 1024L
        private var visits = 0
        init {
            require(bytes.size >= 40 && bytes.copyOfRange(0, 8).contentEquals(magic)) { "bplist: invalid header" }
            val offsetSize = bytes[trailer + 6].toInt() and 255
            refSize = bytes[trailer + 7].toInt() and 255
            require(offsetSize in 1..8 && refSize in 1..8) { "bplist: invalid integer widths" }
            val count = uint(trailer + 8, 8, bytes.size)
            val top = uint(trailer + 16, 8, bytes.size)
            val table = uint(trailer + 24, 8, bytes.size)
            require(count in 1..65536 && top >= 0 && top < count && table in 8..trailer.toLong()) { "bplist: invalid trailer" }
            require(count <= (trailer - table) / offsetSize) { "bplist: truncated offset table" }
            objectEnd = table.toInt();root = top.toInt()
            charge(count * 9)
            offsets = LongArray(count.toInt()) { index ->
                uint(objectEnd + index * offsetSize, offsetSize, trailer).also {
                    require(it in 8 until table) { "bplist: invalid object offset" }
                }
            }
            visiting = BooleanArray(count.toInt())
        }
        fun decode(): Any? = read(root, 0)
        private fun charge(amount: Long) { require(amount >= 0 && amount <= budget) { "bplist: decoded allocation limit" };budget -= amount }
        private fun uint(position: Int, size: Int, end: Int = objectEnd): Long {
            require(size in 1..8 && position >= 0 && position <= end - size) { "bplist: truncated integer" }
            return readBigEndianLong(bytes, position.toLong(), size)
        }
        private fun read(index: Int, depth: Int): Any? {
            require(depth <= 64 && index in offsets.indices && !visiting[index]) { "bplist: cyclic, deep or invalid reference" }
            require(++visits <= 65536) { "bplist: expanded object limit" };charge(64)
            visiting[index] = true
            try {
                var position = offsets[index].toInt()
                val marker = bytes[position++].toInt() and 255;val type = marker ushr 4;val nibble = marker and 15
                fun count(width: Int): Int {
                    val value = if (nibble != 15) nibble.toLong() else {
                        require(position < objectEnd) { "bplist: missing count" }
                        val m = bytes[position++].toInt() and 255
                        require(m ushr 4 == 1 && m and 15 <= 3) { "bplist: invalid count marker" }
                        val size = 1 shl (m and 15);uint(position, size).also { position += size }
                    }
                    require(value >= 0 && value <= (objectEnd - position).toLong() / width) { "bplist: count exceeds object data" }
                    return value.toInt()
                }
                fun reference(at: Int): Int { val value=uint(at,refSize);require(value>=0&&value<offsets.size){"bplist: invalid reference"};return value.toInt() }
                return when(type) {
                    0 -> when(nibble){0->null;8->false;9->true;else->throw IllegalArgumentException("bplist: unsupported primitive")}
                    1 -> uint(position, 1 shl nibble)
                    2 -> when(1 shl nibble){4->Float.fromBits(uint(position,4).toInt());8->Double.fromBits(uint(position,8));else->throw IllegalArgumentException("bplist: unsupported real")}
                    3 -> {require(nibble==3){"bplist: invalid date"};java.util.Date(Math.round((Double.fromBits(uint(position,8))+APPLE_EPOCH_SECONDS)*1000))}
                    4 -> {val n=count(1);charge(n.toLong());bytes.copyOfRange(position,position+n)}
                    5 -> {val n=count(1);charge(n*2L);String(bytes,position,n,Charsets.US_ASCII)}
                    6 -> {val n=count(2);charge(n*2L);String(bytes,position,n*2,Charsets.UTF_16BE)}
                    8 -> uint(position,nibble+1)
                    10,12 -> {val n=count(refSize);require(n<=65536){"bplist: container limit"};charge(n*8L);ArrayList<Any?>(n).also { list -> for(i in 0 until n)list.add(read(reference(position+i*refSize),depth+1)) }}
                    13 -> {val n=count(refSize*2);require(n<=65536){"bplist: container limit"};charge(n*64L);LinkedHashMap<String,Any?>(n).also { map -> for(i in 0 until n){val key=read(reference(position+i*refSize),depth+1);require(key is String){"bplist: dictionary key must be string"};map[key]=read(reference(position+(n+i)*refSize),depth+1)} }}
                    else -> throw IllegalArgumentException("bplist: unsupported object type")
                }
            } finally { visiting[index] = false }
        }
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(byteArrayOf(0x23) + bigEndian(number.toDouble().toRawBits(), 8))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { add(it.key.toString()) }.toIntArray()
                    val valueRefs = entries.map { add(it.value) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        val raw = number.toByteArray()
        return byteArrayOf((0x10 or log).toByte()) + raw.copyOfRange(raw.size - size, raw.size)
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Long, size: Int): Long {
        var value = 0L
        var base = offset.toInt()
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[base + index].toLong() and 0xffL)
        }
        return value
    }

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()

    private const val APPLE_EPOCH_SECONDS = 978_307_200.0
}
