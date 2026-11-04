package io.github.iamth3doctor.searchlight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileWidthsTest {
    @Test fun equalWhenEveryoneFits() {
        assertEquals(listOf(200, 200, 200, 200), TileWidths.split(800, listOf(0, 0, 200, 200)))
    }

    @Test fun wideTilesKeepTheirMinimumAndTheRestShare() {
        // Unfolded card: weather, calendar, battery, music (184), quick actions (192).
        val w = TileWidths.split(632, listOf(0, 0, 0, 184, 192))
        assertEquals(listOf(86, 85, 85, 184, 192), w)
        assertEquals(632, w.sum())
    }

    @Test fun leftoverPixelsGoToTheFirstTiles() {
        val w = TileWidths.split(10, listOf(0, 0, 0))
        assertEquals(listOf(4, 3, 3), w)
        assertEquals(10, w.sum())
    }

    @Test fun tooNarrowShrinksInProportion() {
        assertEquals(listOf(150, 150), TileWidths.split(300, listOf(200, 200)))
        assertEquals(300, TileWidths.split(300, listOf(100, 250, 0)).sum())
    }

    // ---- The unfolded at-a-glance bar ----
    private val glanceFlex = listOf(false, false, false, true, false) // weather, time, battery, music, toggles

    @Test fun everyGlanceTileShowsWholeAndSpareIsShared() {
        val needs = listOf(100, 110, 80, 136, 192)
        val w = TileWidths.fit(700, needs, glanceFlex)
        assertEquals(700, w.sum())
        w.indices.forEach { assertTrue("tile $it: ${w[it]} < ${needs[it]}", w[it] >= needs[it]) }
        assertEquals(listOf(117, 127, 96, 152, 208), w) // 82 spare, 16-17 each
    }

    @Test fun musicLeavesTheBarBeforeAnyGlanceTileIsCut() {
        val needs = listOf(100, 110, 80, 136, 192)
        val w = TileWidths.fit(560, needs, glanceFlex)
        assertEquals(0, w[3])
        assertEquals(560, w.sum())
        listOf(0, 1, 2, 4).forEach { assertTrue("tile $it: ${w[it]}", w[it] >= needs[it]) }
    }

    @Test fun onlyWhenEvenTheGlanceTilesCantFitDoTheyShrink() {
        val w = TileWidths.fit(400, listOf(100, 110, 80, 136, 192), glanceFlex)
        assertEquals(0, w[3])
        assertEquals(400, w.sum())
    }
}
