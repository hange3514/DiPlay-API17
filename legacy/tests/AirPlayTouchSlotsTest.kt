package local.headunit.diplay

import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayHid
import com.shilapi.xcertplay.airplay.AirPlayTouchSlots

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayTouchSlotsTest {
    @Test fun reportsRemainTwelveBytesAndUseExplicitSlotIds() {
        val single = AirPlayHid.touchReport(listOf(contact(0, 1024.0, 600.0)))
        assertArrayEquals(byteArrayOf(0, 1, 0, 4, 88, 2, 1, 0, 0, 0, 0, 0), single)

        val second = contact(1, 256.0, 300.0)
        val first = contact(0, 1024.0, 600.0, down = false)
        val reversed = AirPlayHid.touchReport(listOf(second, first))
        assertArrayEquals(byteArrayOf(0, 0, 0, 4, 88, 2, 1, 1, 0, 1, 44, 1), reversed)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 44, 1),
            AirPlayHid.touchReport(listOf(second)),
        )
    }

    @Test fun reorderingAndroidPointersDoesNotMoveEitherHidSlot() {
        val slots = AirPlayTouchSlots()
        val first = contact(7, 0.15, 0.33)
        val second = contact(22, 0.5, 0.6)
        send(slots, listOf(first), 7)
        assertEquals(listOf(first.copy(id = 0), second.copy(id = 1)), send(slots, listOf(first, second), 22))
        assertEquals(
            listOf(first.copy(id = 0, x = 0.2), second.copy(id = 1, y = 0.5)),
            send(slots, listOf(second.copy(y = 0.5), first.copy(x = 0.2))),
        )
        assertEquals(2, slots.activeCount)
    }

    @Test fun liftingFirstFingerUsesItsFinalCoordinatesAndLeavesSecondInSlotOne() {
        val slots = AirPlayTouchSlots()
        val first = contact(8, 0.2, 0.3)
        val second = contact(4, 0.6, 0.8)
        send(slots, listOf(first), 8)
        send(slots, listOf(first, second), 4)
        val release = first.copy(x = 0.4, y = 0.5, down = false)
        assertEquals(listOf(release.copy(id = 0), second.copy(id = 1)), send(slots, listOf(second, release)))
        assertEquals(listOf(second.copy(id = 1, x = 0.7)), send(slots, listOf(second.copy(x = 0.7))))
        assertEquals(1, slots.activeCount)
        assertEquals(listOf(second.copy(id = 1, x = 0.7, down = false)), send(slots, emptyList()))
        assertEquals(0, slots.activeCount)
    }

    @Test fun ignoredThirdFingerAndStaleMovesNeverBecomeNewPresses() {
        val slots = AirPlayTouchSlots()
        val first = contact(4, 0.3, 0.3)
        val second = contact(9, 0.6, 0.6)
        val third = contact(11, 0.8, 0.8)
        assertEquals(emptyList<AirPlayContact>(), send(slots, listOf(first)))
        send(slots, listOf(first), 4)
        send(slots, listOf(first, second), 9)
        assertEquals(listOf(first.copy(id = 0), second.copy(id = 1)), send(slots, listOf(first, second, third), 11))
        assertEquals(listOf(first.copy(id = 0, down = false), second.copy(id = 1)), send(slots, listOf(second, third)))
        assertEquals(listOf(second.copy(id = 1)), send(slots, listOf(third, second)))
        assertEquals(listOf(second.copy(id = 1, down = false)), send(slots, listOf(third)))
        assertEquals(emptyList<AirPlayContact>(), send(slots, listOf(third)))
        assertEquals(0, slots.activeCount)
        assertEquals(listOf(third.copy(id = 0)), send(slots, listOf(third), 11))
    }

    @Test fun slotsAwaitingTheirReleaseCannotBeReusedInTheSameReport() {
        val slots = AirPlayTouchSlots()
        val first = contact(1, 0.2, 0.2)
        val second = contact(2, 0.4, 0.4)
        val third = contact(3, 0.6, 0.6)
        send(slots, listOf(first), 1)
        send(slots, listOf(first, second), 2)
        assertEquals(listOf(first.copy(id = 0, down = false), second.copy(id = 1)), send(slots, listOf(second, third), 3))
        assertEquals(listOf(second.copy(id = 1)), send(slots, listOf(second, third)))
    }

    @Test fun writeFailureDoesNotCommitPressPositionOrRelease() {
        val slots = AirPlayTouchSlots()
        val first = contact(8, 0.2, 0.3)
        assertFalse(slots.send(listOf(first), 8) { false })
        assertEquals(0, slots.activeCount)
        assertTrue(slots.send(listOf(first), null) { error("Move without held finger must not write") })

        send(slots, listOf(first), 8)
        assertFalse(slots.send(listOf(first.copy(x = 0.9)), null) { false })
        assertFalse(slots.send(emptyList(), null) { false })
        assertEquals(1, slots.activeCount)
        assertEquals(listOf(first.copy(id = 0, down = false)), send(slots, emptyList()))
        assertEquals(0, slots.activeCount)
    }

    @Test fun clearOnSessionEndLeavesNoContactToResurrectOrRelease() {
        val slots = AirPlayTouchSlots()
        val first = contact(8, 0.2, 0.3)
        send(slots, listOf(first), 8)
        slots.clear()
        assertEquals(0, slots.activeCount)
        assertTrue(slots.send(listOf(first), null) { error("Old session move must not write") })
        assertTrue(slots.send(emptyList(), null) { error("Old session release must not write") })
    }

    private fun send(slots: AirPlayTouchSlots, contacts: List<AirPlayContact>, newId: Int? = null): List<AirPlayContact> {
        var written = emptyList<AirPlayContact>()
        assertTrue(slots.send(contacts, newId) { written = it; true })
        return written
    }

    private fun contact(id: Int, x: Double, y: Double, down: Boolean = true): AirPlayContact =
        AirPlayContact(id, x, y, down)
}
