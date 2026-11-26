package local.headunit.diplay

import android.view.MotionEvent
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Coalesces positions while preserving the transitions of gestures already visible to CarPlay. */
object TouchDispatch {
    private const val MAX_DOWN_AGE_MS = 250L
    private const val MAX_MOVE_AGE_MS = 120L

    private class Event(
        val action: Int,
        private val nowNs: () -> Long,
        private val work: () -> Unit,
    ) : Runnable {
        private val queuedAtNs = nowNs()

        fun isFresh(): Boolean = fresh(action, TimeUnit.NANOSECONDS.toMillis(nowNs() - queuedAtNs))

        override fun run() {
            if (isFresh()) work()
        }
    }

    fun fresh(action: Int, ageMs: Long): Boolean = when (action) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> ageMs <= MAX_DOWN_AGE_MS
        MotionEvent.ACTION_MOVE -> ageMs <= MAX_MOVE_AGE_MS
        else -> true
    }

    fun submit(
        executor: ThreadPoolExecutor,
        queue: LinkedBlockingDeque<Runnable>,
        action: Int,
        nowNs: () -> Long = System::nanoTime,
        onOverflow: (() -> Unit)? = null,
        work: () -> Unit,
    ) {
        synchronized(queue) {
            if (executor.isShutdown) return
            val last = queue.peekLast() as? Event
            if (action == MotionEvent.ACTION_MOVE && last?.action == MotionEvent.ACTION_MOVE) {
                queue.removeLastOccurrence(last)
            } else if (action == MotionEvent.ACTION_CANCEL) {
                // This all-up replaces the current gesture's unsent transitions and positions.
                // Leave earlier complete gestures ordered before it.
                val tail = queue.descendingIterator()
                while (tail.hasNext()) {
                    val event = tail.next() as? Event ?: break
                    if (isAllUp(event.action)) break
                    tail.remove()
                    if (event.action == MotionEvent.ACTION_DOWN) break
                }
            }
            val event = Event(action, nowNs, work)
            if (enqueue(executor, event) || executor.isShutdown || !isBoundary(action)) return

            val moves = queue.filterIsInstance<Event>().filter { it.action == MotionEvent.ACTION_MOVE }
            val expendable = moves.firstOrNull { !it.isFresh() } ?: moves.firstOrNull()
            expendable?.let { queue.removeFirstOccurrence(it) }
            if (enqueue(executor, event) || executor.isShutdown) return

            // A partial snapshot may contain fingers whose DOWN we are discarding. Only
            // release the sent gesture and let the worker await a new physical ACTION_DOWN.
            val release = onOverflow ?: work.takeIf { isAllUp(action) } ?: return
            queue.clear()
            enqueue(executor, Event(MotionEvent.ACTION_CANCEL, nowNs, release))
        }
    }

    private fun enqueue(executor: ThreadPoolExecutor, event: Event): Boolean = try {
        executor.execute(event)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun isBoundary(action: Int): Boolean = when (action) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
        else -> false
    }

    private fun isAllUp(action: Int): Boolean =
        action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
}
