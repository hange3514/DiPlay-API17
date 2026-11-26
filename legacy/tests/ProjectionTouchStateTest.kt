package local.headunit.diplay

import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayHid
import org.junit.Assert.*
import org.junit.Test

class ProjectionTouchStateTest {
    private val state = ProjectionTouchState()
    private val reports = mutableListOf<List<AirPlayContact>>()
    private val write: (List<AirPlayContact>) -> Boolean = { reports.add(it); true }
    private val first = AirPlayContact(5, .25, .45, true)
    private val second = AirPlayContact(9, .75, .65, true)

    @Test fun clearingTheQueueDoesNotAdmitAnotherPointerUntilFreshDown() {
        assertTrue(state.dispatch(7, MotionEvent.ACTION_DOWN, listOf(first), first.id, write))
        assertTrue(state.dispatch(7, MotionEvent.ACTION_POINTER_DOWN, listOf(first, second), second.id, write))
        assertTrue(state.cancel(7, write))
        assertEquals(listOf(first.copy(id = 0, down = false), second.copy(id = 1, down = false)), reports.last())
        assertFalse(state.dispatch(7, MotionEvent.ACTION_MOVE, listOf(first, second), null, write))
        assertFalse(state.dispatch(7, MotionEvent.ACTION_POINTER_DOWN, listOf(first, second), second.id, write))
        assertEquals(3, reports.size)
        assertTrue(state.dispatch(7, MotionEvent.ACTION_DOWN, listOf(first), first.id, write))
        assertEquals(listOf(first.copy(id = 0)), reports.last())
    }

    @Test fun detachingAnOldSurfaceCannotReleaseTheNewSurfaceGesture() {
        state.dispatch(7, MotionEvent.ACTION_DOWN, listOf(first), first.id, write)
        assertTrue(state.cancel(7, write))
        state.dispatch(8, MotionEvent.ACTION_DOWN, listOf(second), second.id, write)
        assertFalse(state.cancel(7, write))
        assertEquals(1, state.activeCount)
        assertEquals(listOf(second.copy(id = 0)), reports.last())
        assertTrue(state.cancel(8, write))
        assertEquals(listOf(second.copy(id = 0, down = false)), reports.last())
    }

    @Test fun failedTerminalReportIsRetriedBeforeNewGestureBegins() {
        state.dispatch(7, MotionEvent.ACTION_DOWN, listOf(first), first.id, write)
        assertFalse(state.dispatch(7, MotionEvent.ACTION_UP, listOf(first.copy(down = false)), null) { false })
        assertEquals(1, state.activeCount)
        assertFalse(state.dispatch(7, MotionEvent.ACTION_MOVE, listOf(first), null, write))
        assertTrue(state.dispatch(7, MotionEvent.ACTION_DOWN, listOf(second), second.id, write))
        assertEquals(listOf(first.copy(id = 0, down = false)), reports[1])
        assertEquals(listOf(second.copy(id = 0)), reports[2])
    }

    @Test fun androidSequencePreservesBothSlotsThroughTheLastWireRelease() {
        val reports = mutableListOf<ByteArray>()
        val input = ProjectionTouchInput { action, contacts, newId, _ ->
            state.dispatch(7, action, contacts, newId) { report ->
                reports.add(AirPlayHid.touchReport(report.map {
                    it.copy(x = it.x * ProjectionConfig.WIDTH, y = it.y * ProjectionConfig.HEIGHT)
                }))
            }
        }
        input.setEnabled(true)
        fun event(action: Int, index: Int, vararg contacts: AirPlayContact) {
            input.onSnapshot(action, index, contacts.size, 0L) { pointer, down -> contacts[pointer].copy(down = down) }
        }
        event(MotionEvent.ACTION_DOWN, 0, first)
        event(MotionEvent.ACTION_POINTER_DOWN, 1, first, second)
        event(MotionEvent.ACTION_MOVE, 0, second, first)
        event(MotionEvent.ACTION_POINTER_UP, 1, second, first)
        event(MotionEvent.ACTION_MOVE, 0, second.copy(x = .875))
        event(MotionEvent.ACTION_UP, 0, second.copy(x = .875))

        assertEquals(listOf(1 to 0, 1 to 1, 1 to 1, 0 to 1, 0 to 1, 0 to 0),
            reports.map { it[1].toInt() to it[7].toInt() })
        assertEquals(1, reports.last()[6].toInt())
        val lastX = (reports.last()[8].toInt() and 0xff) or ((reports.last()[9].toInt() and 0xff) shl 8)
        assertEquals(896, lastX)
        assertEquals(0, state.activeCount)
    }
}
