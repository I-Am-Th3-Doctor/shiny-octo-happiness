package io.github.iamth3doctor.searchlight.ui

import io.github.iamth3doctor.searchlight.ui.SlideToLaunch.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideToLaunchTest {

    // A row of six 100px slots from x=0 to 600, between y=1000 and 1200.
    private val lefts = IntArray(6) { it * 100 }
    private val rights = IntArray(6) { (it + 1) * 100 }
    private fun at(x: Float, y: Float) = SlideToLaunch.slotAt(x, y, 1000, 1200, lefts, rights)

    @Test fun findsTheSlotUnderTheFinger() {
        assertEquals(0, at(10f, 1100f))
        assertEquals(2, at(250f, 1001f))
        assertEquals(5, at(599f, 1199f))
        assertNull(at(250f, 999f)) // above the row
        assertNull(at(250f, 1200f)) // below it
        assertNull(at(650f, 1100f)) // beside it
    }

    @Test fun selectionFollowsTheFingerSlotBySlot() {
        val s = SlideToLaunch()
        assertFalse(s.move(null, false)) // still on the search icon
        assertNull(s.selected)
        for (slot in 0..5) {
            assertTrue("moving onto $slot", s.move(slot, true))
            assertEquals(slot, s.selected)
            assertFalse("same slot again: no change", s.move(slot, true))
        }
        assertTrue(s.move(3, true))
        assertEquals(3, s.selected)
    }

    @Test fun releaseOverAnAppLaunchesIt() {
        val s = SlideToLaunch()
        s.move(1, true)
        s.move(4, true)
        assertEquals(Outcome.Launch(4), s.release(4, true))
        assertNull(s.selected)
    }

    @Test fun liftingWithoutEnteringTheRowKeepsItOpen() {
        val s = SlideToLaunch()
        s.move(null, false)
        assertEquals(Outcome.StayOpen, s.release(null, false))
        assertEquals("even a cancelled touch", Outcome.StayOpen, SlideToLaunch().release(null, false, cancelled = true))
    }

    @Test fun slidingOffTheRowAndLiftingLaunchesNothing() {
        val s = SlideToLaunch()
        s.move(2, true)
        assertTrue(s.move(null, false)) // off the row: no selection
        assertNull(s.selected)
        assertTrue(s.entered)
        assertEquals(Outcome.Dismiss, s.release(null, false))
    }

    @Test fun emptySlotsAreNeverSelectedOrLaunched() {
        val s = SlideToLaunch()
        s.move(5, false)
        assertNull(s.selected)
        assertEquals(Outcome.Dismiss, s.release(5, false))
    }

    @Test fun aCancelledSlideLaunchesNothing() {
        val s = SlideToLaunch()
        s.move(3, true)
        assertEquals(Outcome.Dismiss, s.release(3, true, cancelled = true))
    }

    @Test fun eachHoldStartsFresh() {
        val s = SlideToLaunch()
        s.move(3, true)
        s.release(3, true)
        assertFalse(s.entered)
        assertEquals(Outcome.StayOpen, s.release(null, false))
    }
}
