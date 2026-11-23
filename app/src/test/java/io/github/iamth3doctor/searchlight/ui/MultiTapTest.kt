package io.github.iamth3doctor.searchlight.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiTapTest {
    private fun mt() = MultiTap(timeoutMs = 300, slopPx = 50f)

    @Test fun countsOneTwoThree() {
        val m = mt()
        assertEquals(1, m.tap(100f, 100f, 1000))
        assertEquals(2, m.tap(110f, 95f, 1200))
        assertEquals(3, m.tap(105f, 100f, 1400))
    }

    @Test fun afterThreeItStartsAgain() {
        val m = mt()
        m.tap(0f, 0f, 0); m.tap(0f, 0f, 100); m.tap(0f, 0f, 200)
        assertEquals(1, m.tap(0f, 0f, 300))
        assertEquals(2, m.tap(0f, 0f, 400))
    }

    @Test fun tooSlowOrTooFarStartsAgain() {
        val m = mt()
        m.tap(100f, 100f, 1000)
        assertEquals(1, m.tap(100f, 100f, 1400))
        assertEquals(2, m.tap(100f, 100f, 1500))
        assertEquals(1, m.tap(300f, 100f, 1600))
        m.tap(0f, 0f, 5000); m.tap(0f, 0f, 5100)
        assertEquals("third too late", 1, m.tap(0f, 0f, 5500))
    }

    @Test fun resetForgetsTheRun() {
        val m = mt()
        m.tap(0f, 0f, 0); m.tap(0f, 0f, 100)
        m.reset()
        assertEquals(1, m.tap(0f, 0f, 200))
    }
}
