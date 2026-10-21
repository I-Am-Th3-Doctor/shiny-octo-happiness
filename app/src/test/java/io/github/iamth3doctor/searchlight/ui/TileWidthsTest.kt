package io.github.iamth3doctor.searchlight.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TileWidthsTest {
    @Test fun minimumsFirstThenTheRestShared() {
        assertEquals(listOf(100, 100, 300, 300), TileWidths.split(800, listOf(0, 0, 200, 200)))
    }

    @Test fun leftoverPixelsGoToTheFirstTiles() {
        val w = TileWidths.split(10, listOf(0, 0, 0))
        assertEquals(listOf(4, 3, 3), w)
        assertEquals(10, w.sum())
    }

    @Test fun tooNarrowShrinksInProportion() {
        val w = TileWidths.split(300, listOf(200, 200))
        assertEquals(listOf(150, 150), w)
        assertEquals(300, TileWidths.split(300, listOf(100, 250, 0)).sum())
    }
}
