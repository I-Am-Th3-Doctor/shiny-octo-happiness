package io.github.iamth3doctor.searchlight.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MarqueeLoopTest {
    // 300 px over at 100 px/s: 2 s hold, 3 s scroll, 2 s hold.
    private val loop = MarqueeLoop(overflowPx = 300, pxPerSecond = 100f)

    @Test fun holdsAtTheStart() {
        assertEquals(0, loop.offsetAt(0))
        assertEquals(0, loop.offsetAt(2000))
    }

    @Test fun scrollsAtSteadySpeed() {
        assertEquals(100, loop.offsetAt(3000))
        assertEquals(150, loop.offsetAt(3500))
    }

    @Test fun holdsAtTheEnd() {
        assertEquals(300, loop.offsetAt(5000))
        assertEquals(300, loop.offsetAt(6999))
        assertEquals(7000L, loop.durationMs)
    }
}
