package local.headunit.diplay

import android.view.MotionEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchDispatchTest {
    @Test fun coalescesPositionsWhileKeepingTapAndDragBoundaries() {
        val sent = drain {
            add(MotionEvent.ACTION_DOWN, "down")
            add(MotionEvent.ACTION_MOVE, "old move")
            add(MotionEvent.ACTION_MOVE, "latest move")
            add(MotionEvent.ACTION_UP, "up")
            add(MotionEvent.ACTION_DOWN, "next down")
            add(MotionEvent.ACTION_UP, "next up")
        }
        assertEquals(listOf("down", "latest move", "up", "next down", "next up"), sent)
    }

    @Test fun doesNotReplayStalePressOrDragButStillRunsRelease() {
        val sent = drain {
            add(MotionEvent.ACTION_DOWN, "stale down")
            now.set(TimeUnit.MILLISECONDS.toNanos(250))
            add(MotionEvent.ACTION_DOWN, "fresh down")
            add(MotionEvent.ACTION_MOVE, "stale move")
            add(MotionEvent.ACTION_UP, "release")
            now.set(TimeUnit.MILLISECONDS.toNanos(371))
        }
        assertEquals(listOf("fresh down", "release"), sent)
    }

    @Test fun secondFingerTransitionsSeparateMoveGroups() {
        val sent = drain {
            add(MotionEvent.ACTION_DOWN, "first down")
            add(MotionEvent.ACTION_MOVE, "first move")
            add(MotionEvent.ACTION_POINTER_DOWN, "second down")
            add(MotionEvent.ACTION_MOVE, "old pinch")
            add(MotionEvent.ACTION_MOVE, "latest pinch")
            add(MotionEvent.ACTION_POINTER_UP, "second up")
            add(MotionEvent.ACTION_MOVE, "surviving move")
            add(MotionEvent.ACTION_UP, "first up")
        }
        assertEquals(listOf(
            "first down", "first move", "second down", "latest pinch",
            "second up", "surviving move", "first up",
        ), sent)
    }

    @Test fun secondFingerDownExpiresButSecondFingerUpDoesNot() {
        val sent = drain {
            add(MotionEvent.ACTION_POINTER_DOWN, "stale second down")
            add(MotionEvent.ACTION_POINTER_UP, "second up")
            now.set(TimeUnit.MILLISECONDS.toNanos(251))
            add(MotionEvent.ACTION_POINTER_DOWN, "fresh second down")
        }
        assertEquals(listOf("second up", "fresh second down"), sent)
    }

    @Test fun cancelDropsUnsentGestureTailButPreservesPreviousTap() {
        val sent = drain {
            add(MotionEvent.ACTION_DOWN, "previous down")
            add(MotionEvent.ACTION_UP, "previous up")
            add(MotionEvent.ACTION_DOWN, "unused down")
            add(MotionEvent.ACTION_MOVE, "unused move")
            add(MotionEvent.ACTION_POINTER_DOWN, "unused second down")
            add(MotionEvent.ACTION_POINTER_UP, "unused second up")
            add(MotionEvent.ACTION_CANCEL, "cancel")
        }
        assertEquals(listOf("previous down", "previous up", "cancel"), sent)
    }

    @Test fun saturatedQueueDropsMoveBeforeCompleteBoundaries() {
        val sent = drain(capacity = 3) {
            add(MotionEvent.ACTION_DOWN, "down")
            add(MotionEvent.ACTION_MOVE, "move")
            add(MotionEvent.ACTION_UP, "up")
            add(MotionEvent.ACTION_UP, "rescue release")
        }
        assertEquals(listOf("down", "up", "rescue release"), sent)
    }

    @Test fun fullySaturatedQueueStillRunsRelease() {
        val sent = drain(capacity = 2) {
            add(MotionEvent.ACTION_DOWN, "unsent down")
            add(MotionEvent.ACTION_UP, "unsent up")
            add(MotionEvent.ACTION_UP, "rescue release")
        }
        assertEquals(listOf("rescue release"), sent)
    }

    @Test fun pointerUpDiscardsPendingMoveBeforeAnyBoundary() {
        val sent = drain(capacity = 3) {
            add(MotionEvent.ACTION_DOWN, "first down")
            add(MotionEvent.ACTION_POINTER_DOWN, "second down")
            add(MotionEvent.ACTION_MOVE, "move")
            add(MotionEvent.ACTION_POINTER_UP, "second up", "overflow cancel")
        }
        assertEquals(listOf("first down", "second down", "second up"), sent)
    }

    @Test fun fullySaturatedPointerUpRescuesAnAllUpAndPreservesTheNewPhysicalDown() {
        val sent = drain(capacity = 2) {
            add(MotionEvent.ACTION_DOWN, "unsent first down")
            add(MotionEvent.ACTION_POINTER_DOWN, "unsent second down")
            add(MotionEvent.ACTION_POINTER_UP, "unsafe partial snapshot", "overflow all up")
            add(MotionEvent.ACTION_DOWN, "new gesture down")
        }
        assertEquals(listOf("overflow all up", "new gesture down"), sent)
    }

    @Test fun fullySaturatedPointerDownAlsoCancelsRatherThanResurrectingContacts() {
        val sent = drain(capacity = 2) {
            add(MotionEvent.ACTION_DOWN, "unsent down")
            add(MotionEvent.ACTION_UP, "unsent up")
            add(MotionEvent.ACTION_POINTER_DOWN, "unsafe second down", "overflow all up")
        }
        assertEquals(listOf("overflow all up"), sent)
    }

    @Test fun overflowPrefersDroppingExpiredMoveOverFreshMove() {
        val sent = drain(capacity = 4) {
            add(MotionEvent.ACTION_DOWN, "first down")
            add(MotionEvent.ACTION_MOVE, "stale first move")
            now.set(TimeUnit.MILLISECONDS.toNanos(121))
            add(MotionEvent.ACTION_POINTER_DOWN, "second down")
            add(MotionEvent.ACTION_MOVE, "fresh pinch")
            add(MotionEvent.ACTION_POINTER_UP, "second up", "overflow all up")
        }
        assertEquals(listOf("first down", "second down", "fresh pinch", "second up"), sent)
    }

    private fun drain(capacity: Int = 16, enqueue: Pending.() -> Unit): List<String> {
        val pending = Pending(capacity)
        try {
            pending.executor.execute { pending.release.await(2, TimeUnit.SECONDS) }
            pending.enqueue()
        } finally {
            pending.executor.shutdown()
            pending.release.countDown()
            assertTrue(pending.executor.awaitTermination(2, TimeUnit.SECONDS))
        }
        return pending.sent
    }

    private class Pending(capacity: Int) {
        val now = AtomicLong()
        val release = CountDownLatch(1)
        val sent = mutableListOf<String>()
        private val queue = LinkedBlockingDeque<Runnable>(capacity)
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue)

        fun add(action: Int, value: String, overflow: String? = null) {
            val onOverflow: (() -> Unit)? = overflow?.let { { sent.add(it) } }
            TouchDispatch.submit(executor, queue, action, now::get, onOverflow) { sent.add(value) }
        }
    }
}
