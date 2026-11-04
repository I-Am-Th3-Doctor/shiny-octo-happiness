package io.github.iamth3doctor.searchlight.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapTest {
    private fun dt() = DoubleTap(timeoutMs = 300, slopPx = 50f)

    @Test fun twoQuickTapsInOnePlace() {
        val d = dt()
        assertFalse(d.tap(100f, 100f, 1000))
        assertTrue(d.tap(110f, 95f, 1200))
    }

    @Test fun tooSlowOrTooFarIsNot() {
        val d = dt()
        d.tap(100f, 100f, 1000)
        assertFalse(d.tap(100f, 100f, 1400)) // too slow: this one starts a new pair
        assertTrue(d.tap(100f, 100f, 1500))
        d.tap(100f, 100f, 3000)
        assertFalse(d.tap(300f, 100f, 3100)) // too far
    }

    @Test fun aThirdTapStartsANewPair() {
        val d = dt()
        d.tap(0f, 0f, 0)
        assertTrue(d.tap(0f, 0f, 100))
        assertFalse(d.tap(0f, 0f, 200))
    }

    @Test fun resetForgetsTheFirstTap() {
        val d = dt()
        d.tap(0f, 0f, 0)
        d.reset()
        assertFalse(d.tap(0f, 0f, 100))
    }
}
