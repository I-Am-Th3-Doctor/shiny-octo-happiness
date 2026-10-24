package io.github.iamth3doctor.searchlight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeTest {
    @Test fun upAndDown() {
        assertEquals(Swipe.Direction.UP, Swipe.vertical(dx = 5f, dy = -120f, minDistance = 100f))
        assertEquals(Swipe.Direction.DOWN, Swipe.vertical(dx = -5f, dy = 120f, minDistance = 100f))
    }

    @Test fun tooShortIsNotASwipe() = assertNull(Swipe.vertical(0f, -60f, 100f))

    @Test fun sidewaysOrDiagonalIsNotASwipe() {
        assertNull(Swipe.vertical(dx = 200f, dy = -120f, minDistance = 100f))
        assertNull(Swipe.vertical(dx = 70f, dy = -120f, minDistance = 100f))
    }

    @Test fun sideways() {
        assertEquals(Swipe.Direction.RIGHT, Swipe.horizontal(dx = 120f, dy = 10f, minDistance = 100f))
        assertEquals(Swipe.Direction.LEFT, Swipe.horizontal(dx = -120f, dy = 10f, minDistance = 100f))
        assertNull(Swipe.horizontal(dx = 120f, dy = 80f, minDistance = 100f))
        assertNull(Swipe.horizontal(dx = 60f, dy = 0f, minDistance = 100f))
    }

    @Test fun dominantNeedsDistanceAndOneAndAHalfTimesTheOtherAxis() {
        assertEquals(Swipe.Direction.DOWN, Swipe.dominant(dx = 30f, dy = 60f, minDistance = 56f))
        assertEquals(Swipe.Direction.LEFT, Swipe.dominant(dx = -60f, dy = 40f, minDistance = 56f))
        assertEquals(Swipe.Direction.RIGHT, Swipe.dominant(dx = 90f, dy = -60f, minDistance = 56f))
        assertNull(Swipe.dominant(dx = 90f, dy = 61f, minDistance = 56f)) // diagonal
        assertNull(Swipe.dominant(dx = 50f, dy = 0f, minDistance = 56f)) // too short
    }

    @Test fun trackerReportsOneSwipePerTouch() {
        val t = SwipeTracker(upDistance = 64f, distance = 56f)
        t.down(100f, 100f)
        assertNull(t.move(100f, 140f))
        assertEquals(Swipe.Direction.DOWN, t.move(105f, 160f))
        assertNull(t.move(105f, 400f)) // still the same swipe
        t.down(100f, 100f) // a new touch
        assertEquals(Swipe.Direction.RIGHT, t.move(170f, 110f))
    }

    @Test fun trackerUpUsesTheAppTableRule() {
        val t = SwipeTracker(upDistance = 64f, distance = 56f)
        t.down(0f, 0f)
        assertNull(t.move(0f, -60f)) // past the gesture distance, short of the app table's
        assertNull(t.move(40f, -70f)) // up-ish, but not 2x: not the app table, and never "down/left/right"
        t.down(0f, 0f)
        assertEquals(Swipe.Direction.UP, t.move(5f, -70f))
    }
}
