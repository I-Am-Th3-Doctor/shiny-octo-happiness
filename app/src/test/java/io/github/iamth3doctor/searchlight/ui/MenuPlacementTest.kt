package io.github.iamth3doctor.searchlight.ui

import io.github.iamth3doctor.searchlight.ui.MenuPlacement.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuPlacementTest {

    // A 1080 x 2400 window; bounds are its safe area minus a margin.
    private val bounds = Box(30, 120, 1050, 2250)

    private fun inside(r: MenuPlacement.Result) =
        r.x >= bounds.left && r.x + r.width <= bounds.right && r.y >= bounds.top && r.y + r.height <= bounds.bottom

    @Test fun belowWhenItFits() {
        val r = MenuPlacement.place(Box(100, 300, 250, 450), bounds, 600, 500, 8)
        assertFalse(r.above)
        assertEquals(458, r.y)
        assertEquals(500, r.height)
        assertTrue(inside(r))
    }

    @Test fun aboveWhenTheIconIsNearTheBottom() {
        val r = MenuPlacement.place(Box(100, 2000, 250, 2150), bounds, 600, 700, 8)
        assertTrue(r.above)
        assertEquals(2000 - 8 - 700, r.y)
        assertEquals(700, r.height)
        assertTrue(inside(r))
    }

    @Test fun tallerThanEitherSideIsCutToTheBiggerSideAndScrolls() {
        val r = MenuPlacement.place(Box(100, 1800, 250, 1950), bounds, 600, 5000, 8)
        assertTrue(r.above)
        assertEquals(bounds.top, r.y)
        assertEquals(1800 - 8 - bounds.top, r.height)
        assertTrue(inside(r))
    }

    @Test fun keptInsideHorizontally() {
        val nearRight = MenuPlacement.place(Box(950, 300, 1050, 400), bounds, 600, 300, 8)
        assertEquals(bounds.right - 600, nearRight.x)
        val nearLeft = MenuPlacement.place(Box(0, 300, 80, 400), bounds, 600, 300, 8)
        assertEquals(bounds.left, nearLeft.x)
        val wide = MenuPlacement.place(Box(0, 300, 80, 400), Box(30, 120, 330, 2250), 600, 300, 8)
        assertEquals(300, wide.width)
    }

    @Test fun anchorFillingTheWindowStillFits() {
        val r = MenuPlacement.place(Box(0, 100, 1080, 2300), bounds, 600, 900, 8)
        assertTrue(inside(r))
        assertEquals(900, r.height)
    }
}
