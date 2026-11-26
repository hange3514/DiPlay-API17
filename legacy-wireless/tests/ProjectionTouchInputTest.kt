package local.headunit.diplay

import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTouchInputTest {
    @Test fun snapshotsBothContactsByRawIdWhenAndroidChangesTheirOrder() {
        val gesture = Gesture()
        gesture.send(MotionEvent.ACTION_DOWN, 0, 12)
        gesture.send(MotionEvent.ACTION_POINTER_DOWN, 1, 12, 4)
        gesture.send(MotionEvent.ACTION_MOVE, 0, 4, 12)
        gesture.send(MotionEvent.ACTION_POINTER_UP, 1, 4, 12)
        gesture.send(MotionEvent.ACTION_MOVE, 0, 4)
        gesture.send(MotionEvent.ACTION_UP, 0, 4)

        assertEquals(listOf(12, 4, null, null, null, null), gesture.events.map { it.newPointerId })
        assertEquals(listOf(1, 2, 2, 2, 1, 1), gesture.events.map { it.androidPointerCount })
        assertEquals(listOf(4, 12), gesture.events[2].contacts.map { it.id })
        assertEquals(listOf(.125, .375), gesture.events[2].contacts.map { it.x })
        assertEquals(listOf(4 to true, 12 to false), gesture.events[3].contacts.map { it.id to it.down })
        assertTrue(gesture.events[4].contacts.single().down)
        assertFalse(gesture.events.last().contacts.single().down)
    }

    @Test fun thirdContactRemainsVisibleInSnapshotWithoutCreatingAReportOnMove() {
        val gesture = Gesture()
        gesture.send(MotionEvent.ACTION_DOWN, 0, 3)
        gesture.send(MotionEvent.ACTION_POINTER_DOWN, 1, 3, 9)
        gesture.send(MotionEvent.ACTION_POINTER_DOWN, 2, 3, 9, 17)
        gesture.send(MotionEvent.ACTION_POINTER_UP, 0, 3, 9, 17)
        gesture.send(MotionEvent.ACTION_MOVE, 0, 9, 17)

        assertEquals(17, gesture.events[2].newPointerId)
        assertEquals(3, gesture.events[2].androidPointerCount)
        assertEquals(listOf(3, 9, 17), gesture.events[2].contacts.map { it.id })
        assertEquals(listOf(9, 17), gesture.events.last().contacts.map { it.id })
        assertNull(gesture.events.last().newPointerId)
    }

    @Test fun stalePointerDownCannotBeIntroducedByARecentMove() {
        val gesture = Gesture()
        gesture.send(MotionEvent.ACTION_DOWN, 0, 7)
        gesture.send(MotionEvent.ACTION_POINTER_DOWN, 1, 7, 14, ageMs = 251)
        gesture.send(MotionEvent.ACTION_MOVE, 0, 14, 7)
        gesture.send(MotionEvent.ACTION_MOVE, 0, 14, 7, ageMs = 121)
        gesture.send(MotionEvent.ACTION_POINTER_UP, 0, 14, 7, ageMs = 500)

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP),
            gesture.events.map { it.action })
        assertNull(gesture.events[1].newPointerId)
        assertEquals(listOf(14, 7), gesture.events[1].contacts.map { it.id })
        assertEquals(listOf(false, true), gesture.events.last().contacts.map { it.down })
    }

    @Test fun stalePrimaryDownCancelsOldGestureAndRequiresAFreshDown() {
        val gesture = Gesture()
        gesture.send(MotionEvent.ACTION_DOWN, 0, 3)
        gesture.send(MotionEvent.ACTION_DOWN, 0, 5, ageMs = 251)
        gesture.send(MotionEvent.ACTION_POINTER_DOWN, 1, 5, 2)
        gesture.send(MotionEvent.ACTION_MOVE, 0, 5, 2)
        gesture.send(MotionEvent.ACTION_UP, 0, 5)
        gesture.send(MotionEvent.ACTION_DOWN, 0, 1, ageMs = 250)

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN),
            gesture.events.map { it.action })
        assertTrue(gesture.events[1].contacts.isEmpty())
        assertEquals(1, gesture.events.last().newPointerId)
    }

    @Test fun visibilityChangeCancelsAllContactsOnlyOnceAndFreshStreamResumes() {
        val gesture = Gesture()
        gesture.send(MotionEvent.ACTION_DOWN, 0, 3)
        gesture.send(MotionEvent.ACTION_POINTER_DOWN, 1, 3, 8)
        gesture.listener.setEnabled(false)
        gesture.listener.setEnabled(false)
        gesture.send(MotionEvent.ACTION_DOWN, 0, 9)
        gesture.listener.setEnabled(true)
        gesture.send(MotionEvent.ACTION_MOVE, 0, 3, 8)
        gesture.send(MotionEvent.ACTION_UP, 0, 3)
        gesture.send(MotionEvent.ACTION_DOWN, 0, 1)
        gesture.send(MotionEvent.ACTION_CANCEL, 0, 1)

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL),
            gesture.events.map { it.action })
        assertEquals(0, gesture.events[2].androidPointerCount)
        assertEquals(1, gesture.events.last().androidPointerCount)
        assertTrue(gesture.events.last().contacts.isEmpty())
    }

    @Test fun terminalUpReleasesEveryContactAndInvalidActionIndexCancels() {
        val gesture = Gesture()
        gesture.send(MotionEvent.ACTION_DOWN, 0, 6)
        gesture.send(MotionEvent.ACTION_POINTER_DOWN, 1, 6, 9)
        gesture.send(MotionEvent.ACTION_UP, 0, 6, 9, ageMs = 1_000)
        gesture.send(MotionEvent.ACTION_DOWN, 0, 11)
        gesture.send(MotionEvent.ACTION_POINTER_UP, 2, 11, 13)
        gesture.send(MotionEvent.ACTION_MOVE, 0, 11)

        assertEquals(listOf(false, false), gesture.events[2].contacts.map { it.down })
        assertEquals(MotionEvent.ACTION_CANCEL, gesture.events.last().action)
        assertTrue(gesture.events.last().contacts.isEmpty())
        assertEquals(5, gesture.events.size)
    }

    private data class Event(
        val action: Int,
        val contacts: List<AirPlayContact>,
        val newPointerId: Int?,
        val androidPointerCount: Int,
    )

    private class Gesture {
        val events = mutableListOf<Event>()
        val listener = ProjectionTouchInput { action, contacts, newPointerId, count ->
            events.add(Event(action, contacts, newPointerId, count))
        }.apply { setEnabled(true) }

        fun send(action: Int, actionIndex: Int, vararg ids: Int, ageMs: Long = 0) {
            listener.onSnapshot(action, actionIndex, ids.size, ageMs) { index, down ->
                AirPlayContact(ids[index], .125 + index * .25, .5, down)
            }
        }
    }
}
