package local.headunit.diplay

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.shilapi.xcertplay.airplay.AirPlayContact

/** Snapshots Android contacts while the framework event can still be read on the UI thread. */
class ProjectionTouchInput internal constructor(
    private val send: (Int, List<AirPlayContact>, Int?, Int) -> Unit,
) : View.OnTouchListener, View.OnAttachStateChangeListener {
    constructor() : this({ action, contacts, newPointerId, count ->
        WiredService.touch(action, contacts, newPointerId, count)
    })

    private var enabled = false
    private var gestureActive = false
    private var observedView: View? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) cancel()
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (!enabled) return false
        if (observedView !== view) {
            observedView?.removeOnAttachStateChangeListener(this)
            cancel()
            observedView = view
            view.addOnAttachStateChangeListener(this)
        }
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        onSnapshot(
            event.actionMasked, event.actionIndex, event.pointerCount, SystemClock.uptimeMillis() - event.eventTime,
        ) { index, down ->
            AirPlayContact(
                event.getPointerId(index),
                (event.getX(index).toDouble() / width).coerceIn(0.0, 1.0),
                (event.getY(index).toDouble() / height).coerceIn(0.0, 1.0),
                down,
            )
        }
        return true
    }

    internal fun onSnapshot(
        action: Int,
        actionIndex: Int,
        pointerCount: Int,
        ageMs: Long,
        contactAt: (index: Int, down: Boolean) -> AirPlayContact,
    ) {
        if (!enabled) return
        when (action) {
            MotionEvent.ACTION_CANCEL -> {
                cancel(pointerCount)
                return
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> Unit
            else -> return
        }
        if (action == MotionEvent.ACTION_DOWN) cancel()
        else if (!gestureActive) return
        if (!TouchDispatch.fresh(action, ageMs)) return
        if (pointerCount <= 0 || (action != MotionEvent.ACTION_MOVE && actionIndex !in 0 until pointerCount)) {
            cancel()
            return
        }

        val contacts = List(pointerCount) { index ->
            val down = action != MotionEvent.ACTION_UP &&
                (action != MotionEvent.ACTION_POINTER_UP || index != actionIndex)
            contactAt(index, down)
        }
        // A MOVE or release may still list ignored Android fingers. Only an explicit, fresh
        // press authorizes the worker to allocate that pointer ID to an available HID slot.
        val newPointerId = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> contacts[actionIndex].id
            else -> null
        }
        gestureActive = action != MotionEvent.ACTION_UP
        send(action, contacts, newPointerId, pointerCount)
    }

    override fun onViewAttachedToWindow(view: View) = Unit

    override fun onViewDetachedFromWindow(view: View) {
        if (observedView !== view) return
        observedView = null
        view.removeOnAttachStateChangeListener(this)
        cancel()
    }

    private fun cancel(androidPointerCount: Int = 0) {
        if (!gestureActive) return
        gestureActive = false
        send(MotionEvent.ACTION_CANCEL, emptyList(), null, androidPointerCount)
    }
}
