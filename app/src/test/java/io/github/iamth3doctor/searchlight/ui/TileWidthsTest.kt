package io.github.iamth3doctor.searchlight.ui

import org.junit.Assert.assertEquals
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
}
