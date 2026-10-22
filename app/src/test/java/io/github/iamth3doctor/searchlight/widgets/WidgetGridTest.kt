package io.github.iamth3doctor.searchlight.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetGridTest {

    private fun g(id: Int, c: Int, r: Int, w: Int, h: Int) = GridSpec(id, c, r, w, h)

    @Test fun storageRoundTripAndJunk() {
        val list = listOf(g(1, 0, 0, 4, 2), g(2, 1, 3, 2, 2))
        assertEquals(list, WidgetGrid.parse(WidgetGrid.serialize(list)))
        assertEquals(listOf(g(1, 0, 0, 4, 2)), WidgetGrid.parse("1:0:0:4:2;junk;2:1:1;3:0:0:0:1;1:2:2:1:1;-4:0:0:1:1"))
        assertTrue(WidgetGrid.parse(null).isEmpty())
    }

    @Test fun startsAtFourBySixOnAPhone() {
        assertEquals(4 to 6, WidgetGrid.gridSize(411f, 520f))
        assertEquals(6 to 6, WidgetGrid.gridSize(600f, 520f)) // a wide folded window
        assertEquals(7 to 6, WidgetGrid.gridSize(704f, 520f, unfolded = true))
        assertEquals(7 to 6, WidgetGrid.gridSize(900f, 520f, unfolded = true)) // always 7 unfolded
        assertEquals(4 to 3, WidgetGrid.gridSize(300f, 100f)) // never fewer than 4 × 3
    }

    @Test fun overlapAndBounds() {
        val others = listOf(g(1, 0, 0, 2, 2))
        assertFalse(WidgetGrid.fits(g(2, 1, 1, 2, 2), others, 4, 6))
        assertTrue(WidgetGrid.fits(g(2, 2, 0, 2, 2), others, 4, 6))
        assertFalse(WidgetGrid.fits(g(2, 3, 0, 2, 1), others, 4, 6)) // sticks out the right edge
        assertTrue(WidgetGrid.fits(g(1, 1, 1, 2, 2), others, 4, 6)) // a widget never collides with itself
    }

    @Test fun firstFreeIsTopLeftMost() {
        val others = listOf(g(1, 0, 0, 2, 2), g(2, 2, 0, 2, 1))
        assertEquals(g(9, 2, 1, 2, 1), WidgetGrid.firstFree(others, 9, 2, 1, 4, 6))
        assertEquals(g(9, 0, 2, 4, 2), WidgetGrid.firstFree(others, 9, 4, 2, 4, 6))
        assertNull(WidgetGrid.firstFree(others, 9, 5, 1, 4, 6))
    }

    @Test fun placeShrinksWhenFull() {
        // Only a 1-row strip left at the bottom.
        val others = listOf(g(1, 0, 0, 4, 5))
        assertEquals(g(9, 0, 5, 4, 1), WidgetGrid.place(others, 9, 4, 2, 1, 1, 4, 6))
        assertNull(WidgetGrid.place(others, 9, 4, 2, 1, 2, 4, 6)) // can't go below its minimum
    }

    @Test fun fitToSmallerGridKeepsWhatFitsAndMovesTheRest() {
        // Laid out on an unfolded 6-column grid.
        val stored = listOf(g(1, 0, 0, 2, 2), g(2, 4, 0, 2, 2), g(3, 0, 2, 6, 1))
        val fit = WidgetGrid.fitToGrid(stored, 4, 6)
        assertEquals(g(1, 0, 0, 2, 2), fit.shown[0])                  // untouched
        assertEquals(g(2, 2, 0, 2, 2), fit.shown[1])                  // nudged inside
        assertEquals(g(3, 0, 2, 4, 1), fit.shown[2])                  // span clamped to 4 columns
        assertTrue(fit.hidden.isEmpty())
        assertEquals(stored, WidgetGrid.fitToGrid(stored, 6, 6).shown) // unfolding restores exactly
    }

    @Test fun fitHidesWhatCannotFit() {
        val stored = listOf(g(1, 0, 0, 4, 3), g(2, 0, 3, 4, 3), g(3, 0, 6, 4, 2))
        val fit = WidgetGrid.fitToGrid(stored, 4, 6)
        assertEquals(listOf(1, 2), fit.shown.map { it.id })
        assertEquals(listOf(3), fit.hidden)
    }

    @Test fun upsertAndRemove() {
        val list = listOf(g(1, 0, 0, 1, 1), g(2, 1, 0, 1, 1))
        assertEquals(listOf(g(1, 3, 3, 1, 1), g(2, 1, 0, 1, 1)), WidgetGrid.upsert(list, g(1, 3, 3, 1, 1)))
        assertEquals(3, WidgetGrid.upsert(list, g(7, 2, 2, 1, 1)).size)
        assertEquals(listOf(g(2, 1, 0, 1, 1)), WidgetGrid.remove(list, 1))
    }

    @Test fun migratesOldStack() {
        val grid = WidgetGrid.fromStack(listOf(12 to 180, 15 to 90), cols = 4, rows = 6, cellHeightDp = 90f)
        assertEquals(listOf(g(12, 0, 0, 4, 2), g(15, 0, 2, 4, 1)), grid)
    }

    @Test fun spans() {
        assertEquals(2, WidgetGrid.spanFor(180f, 100f, 4))
        assertEquals(1, WidgetGrid.spanFor(105f, 100f, 4)) // a hair over one cell still fits one
        assertEquals(4, WidgetGrid.spanFor(900f, 100f, 4))
    }

    @Test fun eachPostureKeepsItsOwnLayout() {
        val folded = listOf(g(1, 0, 0, 4, 2), g(2, 0, 2, 2, 2))
        val unfolded = listOf(g(1, 3, 1, 4, 2))
        // Widget 2 was never arranged unfolded: it gets the first free spot there, its size kept.
        val u = WidgetGrid.withMissing(unfolded, folded, cols = 7, rows = 6)
        assertEquals(g(1, 3, 1, 4, 2), u[0])
        assertEquals(g(2, 0, 0, 2, 2), u[1])
        // The folded layout isn't touched by any of this.
        assertEquals(folded, WidgetGrid.withMissing(folded, u, cols = 4, rows = 6))
    }

    @Test fun missingWidgetShrinksToFitOrWaits() {
        val full = listOf(g(1, 0, 0, 4, 5))
        val other = listOf(g(2, 0, 0, 4, 2), g(3, 0, 2, 4, 4))
        val out = WidgetGrid.withMissing(full, other, cols = 4, rows = 6)
        assertEquals(listOf(g(1, 0, 0, 4, 5), g(2, 0, 5, 4, 1)), out) // 2 shrinks to the last row; 3 waits
    }

    @Test fun restoredIdsAreRemappedInPlace() {
        val list = listOf(g(10, 0, 0, 4, 2), g(11, 0, 2, 2, 2), g(12, 2, 2, 2, 2))
        val out = WidgetGrid.remapIds(list, intArrayOf(10, 12, 99), intArrayOf(40, 42))
        assertEquals(listOf(g(40, 0, 0, 4, 2), g(11, 0, 2, 2, 2), g(42, 2, 2, 2, 2)), out)
    }
}
