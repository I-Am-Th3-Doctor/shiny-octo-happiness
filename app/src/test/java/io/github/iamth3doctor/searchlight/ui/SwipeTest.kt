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
}
