package io.github.iamth3doctor.searchlight.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFingerTapTest {
    private fun t() = TwoFingerTap(timeoutMs = 400, slopPx = 30f)

    @Test fun twoFingersDownAndUpQuickly() {
        val g = t()
        g.down()
        g.pointerDown(2, 100, 100f, 100f, 300f, 100f)
        g.move(2, 105f, 102f, 298f, 101f)
        assertTrue(g.up(250))
    }

    @Test fun oneFingerIsNot() {
        val g = t()
        g.down()
        assertFalse(g.up(100))
    }

    @Test fun travellingOrSlowOrThreeFingersIsNot() {
        val moved = t().apply { down(); pointerDown(2, 0, 0f, 0f, 200f, 0f); move(2, 0f, 80f, 200f, 80f) }
        assertFalse("a two-finger swipe", moved.up(100))
        val slow = t().apply { down(); pointerDown(2, 0, 0f, 0f, 200f, 0f) }
        assertFalse("held", slow.up(900))
        val three = t().apply { down(); pointerDown(2, 0, 0f, 0f, 200f, 0f); pointerDown(3, 20, 0f, 0f, 200f, 0f) }
        assertFalse("three fingers", three.up(100))
    }
}
