package local.headunit.diplay

import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayTouchSlots

/** State for the reports written by one input worker, bound to its active Surface generation. */
internal class ProjectionTouchState {
    private val slots = AirPlayTouchSlots()
    private var generation = Long.MIN_VALUE
    private var acceptingGesture = false
    val activeCount: Int get() = slots.activeCount

    fun reset() {
        slots.clear()
        generation = Long.MIN_VALUE
        acceptingGesture = false
    }

    fun dispatch(
        surfaceGeneration: Long,
        action: Int,
        contacts: List<AirPlayContact>,
        newPointerId: Int?,
        write: (List<AirPlayContact>) -> Boolean,
    ): Boolean {
        if (action == MotionEvent.ACTION_CANCEL) return cancel(surfaceGeneration, write)
        if (action == MotionEvent.ACTION_DOWN) {
            if (!cancel(surfaceGeneration, write)) return false
            generation = surfaceGeneration
            val sent = slots.send(contacts, newPointerId, write)
            acceptingGesture = sent && activeCount > 0
            return sent
        }
        if (surfaceGeneration != generation || !acceptingGesture) return false
        if (action == MotionEvent.ACTION_UP) acceptingGesture = false
        val admitted = newPointerId.takeIf { action == MotionEvent.ACTION_POINTER_DOWN }
        return slots.send(contacts, admitted, write)
    }

    fun cancel(surfaceGeneration: Long, write: (List<AirPlayContact>) -> Boolean): Boolean {
        if (surfaceGeneration < generation) return false
        acceptingGesture = false
        return slots.send(emptyList(), null, write)
    }
}
