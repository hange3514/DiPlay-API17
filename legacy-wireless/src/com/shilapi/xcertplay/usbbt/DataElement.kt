package com.shilapi.xcertplay.usbbt

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * SDP Data Element representation and encoding/decoding (Bluetooth Core Spec Vol 3, Part B, Sec 3).
 */
abstract class DataElement {

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        SdpCodec.encodeTo(this, out)
        return out.toByteArray()
    }

    open fun uuids(): List<UUID> = emptyList()

    data class UInt(val bytes: Int, val value: Long) : DataElement()

    class Uuid private constructor(val uuid: UUID, val bytes: Int) : DataElement() {
        override fun uuids(): List<UUID> = listOf(uuid)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Uuid) return false
            return uuid == other.uuid
        }

        override fun hashCode(): Int = uuid.hashCode()

        override fun toString(): String = "Uuid($uuid, bytes=$bytes)"

        companion object {
            fun of16(value: Int): Uuid =
                Uuid(SdpCodec.baseUuid((value and 0xFFFF).toLong()), 2)

            fun of32(value: Long): Uuid =
                Uuid(SdpCodec.baseUuid(value and 0xFFFFFFFFL), 4)

            fun of128(uuid: UUID): Uuid =
                Uuid(uuid, 16)
        }
    }

    class Text(val value: ByteArray) : DataElement() {
        constructor(text: String) : this(text.toByteArray(Charsets.UTF_8))

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Text) return false
            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Text(${String(value, Charsets.UTF_8)})"
    }

    data class Bool(val value: Boolean) : DataElement()

    data class Sequence(val items: List<DataElement>) : DataElement() {
        constructor(vararg items: DataElement) : this(items.toList())

        override fun uuids(): List<UUID> = items.flatMap { it.uuids() }
    }

    data class Alternative(val items: List<DataElement>) : DataElement() {
        constructor(vararg items: DataElement) : this(items.toList())

        override fun uuids(): List<UUID> = items.flatMap { it.uuids() }
    }

    object Nil : DataElement()
}
