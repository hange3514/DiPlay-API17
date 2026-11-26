package com.shilapi.xcertplay.airplay

/** Owns the last successfully sent contacts for one event channel. Use on its input worker. */
internal class AirPlayTouchSlots {
    private var held = arrayOfNulls<AirPlayContact>(AirPlayHid.TOUCH_CONTACTS)

    val activeCount: Int get() = held.count { it != null }

    /** Only the pointer named by a new Android press can occupy a free slot. */
    fun send(
        contacts: List<AirPlayContact>,
        newPointerId: Int?,
        write: (List<AirPlayContact>) -> Boolean,
    ): Boolean {
        val next = arrayOfNulls<AirPlayContact>(held.size)
        val report = ArrayList<AirPlayContact>(held.size)
        for (slot in held.indices) {
            val previous = held[slot] ?: continue
            val current = contacts.firstOrNull { it.id == previous.id }
                ?: previous.copy(down = false)
            report.add(current.copy(id = slot))
            if (current.down) next[slot] = current
        }

        // A slot still owed a release cannot be reused in the same report.
        val freeSlot = held.indexOfFirst { it == null }
        if (freeSlot >= 0 && newPointerId != null && held.none { it?.id == newPointerId }) {
            val press = contacts.firstOrNull { it.id == newPointerId && it.down }
            if (press != null) {
                report.add(press.copy(id = freeSlot))
                next[freeSlot] = press
            }
        }

        if (report.isEmpty()) return true
        if (!write(report)) return false
        held = next
        return true
    }

    fun clear() { held.fill(null) }
}
