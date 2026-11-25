package io.github.iamth3doctor.searchlight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapPlacementTest {
    // A folded phone (1080×2400, status bar 100, nav bar 60) and the unfolded inner screen (2200×2480).
    private val m = 30
    private val gap = 40
    private val w = 600
    private val h = 400

    private fun place(x: Float, y: Float, sw: Int = 1080, sh: Int = 2400, top: Int = 100, bottom: Int = 60) =
        TapPlacement.place(x, y, w, h, 0, top, sw, sh - bottom, m, gap)

    private fun inside(p: Pair<Int, Int>, sw: Int = 1080, sh: Int = 2400, top: Int = 100, bottom: Int = 60) {
        val (x, y) = p
        assertTrue("left $x", x >= m)
        assertTrue("right ${x + w}", x + w <= sw - m)
        assertTrue("top $y under the status bar", y >= top + m)
        assertTrue("bottom ${y + h} over the nav bar", y + h <= sh - bottom - m)
    }

    @Test fun theMiddleCentresOnTheTap() {
        val p = place(540f, 1200f)
        assertEquals(540 - w / 2 to 1200 - h / 2, p)
        inside(p)
    }

    @Test fun nearTheTopItOpensBelowTheTap() {
        val p = place(540f, 300f)
        assertEquals(300 + gap, p.second)
        inside(p)
        // Right under the status bar: still below the tap, never under the bar.
        val q = place(540f, 110f)
        assertTrue(q.second >= 110)
        inside(q)
    }

    @Test fun nearTheBottomItOpensAboveTheTap() {
        val p = place(540f, 2100f)
        assertEquals(2100 - gap - h, p.second)
        inside(p)
        val q = place(540f, 2335f) // on the navigation bar's edge
        assertTrue(q.second + h <= 2335)
        inside(q)
    }

    @Test fun nearTheSidesItShiftsInward() {
        val l = place(10f, 1200f)
        assertEquals(m, l.first)
        inside(l)
        val r = place(1075f, 1200f)
        assertEquals(1080 - m - w, r.first)
        inside(r)
        // Corners too.
        inside(place(5f, 105f)); inside(place(1079f, 2339f))
    }

    @Test fun unfoldedTheSameRulesOnTheLargerScreen() {
        val sw = 2200; val sh = 2480
        val mid = place(1100f, 1240f, sw, sh)
        assertEquals(1100 - w / 2 to 1240 - h / 2, mid)
        val farRight = place(2190f, 2400f, sw, sh)
        assertEquals(sw - m - w, farRight.first)
        inside(farRight, sw, sh)
        // A tap at x=1800 unfolded is centred there, not clamped to the folded width.
        assertEquals(1800 - w / 2, place(1800f, 1240f, sw, sh).first)
    }
}
