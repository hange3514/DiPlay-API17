package com.shilapi.xcertplay.usbbt

import java.util.SortedMap
import java.util.TreeMap
import java.util.UUID

/**
 * An SDP Service Record holding attribute ID -> DataElement mapping.
 */
class SdpRecord(
    val handle: Long,
    attributes: Map<Int, DataElement>,
) {
    val attributes: SortedMap<Int, DataElement> = TreeMap(
        attributes + (0 to DataElement.UInt(4, handle))
    )

    fun contains(uuid: UUID): Boolean {
        for (element in attributes.values) {
            if (element.uuids().contains(uuid)) {
                return true
            }
        }
        return false
    }

    fun attributeList(ranges: List<IntRange>): DataElement.Sequence {
        val filtered = attributes.filter { (id, _) ->
            ranges.any { range -> id in range }
        }
        val items = mutableListOf<DataElement>()
        for ((id, value) in filtered) {
            items.add(DataElement.UInt(2, id.toLong()))
            items.add(value)
        }
        return DataElement.Sequence(items)
    }
}
