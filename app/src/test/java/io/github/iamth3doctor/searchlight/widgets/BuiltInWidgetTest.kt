package io.github.iamth3doctor.searchlight.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInWidgetTest {

    @Test fun builtInsUseNegativeIdsThatNeverCollide() {
        assertEquals(-1, BuiltInWidget.newId(emptyList()))
        // App widgets (positive ids) don't matter; each new built-in goes below the last.
        assertEquals(-1, BuiltInWidget.newId(listOf(GridSpec(7, 0, 0, 2, 2))))
        assertEquals(-3, BuiltInWidget.newId(listOf(GridSpec(7, 0, 0, 2, 2), GridSpec(-2, 2, 0, 2, 2), GridSpec(-1, 0, 2, 4, 2))))
        assertTrue(BuiltInWidget.isBuiltIn(-1))
        assertFalse(BuiltInWidget.isBuiltIn(12))
    }

    @Test fun calendarIsFourByTwo() {
        assertEquals(BuiltInWidget.CALENDAR, BuiltInWidget.fromKey("calendar"))
        assertNull(BuiltInWidget.fromKey(null))
        assertNull(BuiltInWidget.fromKey("clock"))
        assertEquals(4 to 2, BuiltInWidget.CALENDAR.defaultCols to BuiltInWidget.CALENDAR.defaultRows)
    }

    @Test fun builtInsSurviveStorageAndRefitting() {
        val stored = listOf(GridSpec(-1, 0, 0, 4, 2), GridSpec(5, 0, 2, 2, 2))
        assertEquals(stored, WidgetGrid.parse(WidgetGrid.serialize(stored)))
        // Unfolded: placed in the other layout's first free spot, at its own size.
        val unfolded = WidgetGrid.withMissing(emptyList(), stored, 7, 6)
        assertEquals(GridSpec(-1, 0, 0, 4, 2), unfolded.first { it.id == -1 })
    }
}
