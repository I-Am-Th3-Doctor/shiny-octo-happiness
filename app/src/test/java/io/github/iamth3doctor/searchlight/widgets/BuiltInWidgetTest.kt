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

    @Test fun calendarIsFourByThree() {
        assertEquals(BuiltInWidget.CALENDAR, BuiltInWidget.fromKey("calendar"))
        assertNull(BuiltInWidget.fromKey(null))
        assertNull(BuiltInWidget.fromKey("clock"))
        assertEquals(4 to 3, BuiltInWidget.CALENDAR.defaultCols to BuiltInWidget.CALENDAR.defaultRows)
    }

    @Test fun weatherIsTwoByTwo() {
        assertEquals(BuiltInWidget.WEATHER, BuiltInWidget.fromKey("weather"))
        assertEquals(2 to 2, BuiltInWidget.WEATHER.defaultCols to BuiltInWidget.WEATHER.defaultRows)
    }

    @Test fun oneWeatherWidgetFixedAtTwoByTwo() {
        assertEquals(1, BuiltInWidget.entries.count { it.isWeather })
        assertTrue(BuiltInWidget.WEATHER.fixedSize)
        assertFalse(BuiltInWidget.CALENDAR.isWeather)
        // The 3-day and today widgets are retired: stored ones read as the one Weather widget.
        assertEquals(BuiltInWidget.WEATHER, BuiltInWidget.fromKey("weather_3day"))
        assertEquals(BuiltInWidget.WEATHER, BuiltInWidget.fromKey("weather_day"))
    }

    @Test fun retiredWeatherWidgetsBecomeTheOneAtItsSize() {
        val types = mapOf(-1 to "weather_day", -2 to "weather_3day", -3 to "calendar", -4 to "weather", -5 to "sticky_note", -6 to "weather")
        // (A Sticky note placed before: taken off the grid, not turned into a Calendar.)
        val placed = listOf(
            GridSpec(-1, 0, 0, 4, 2), GridSpec(-2, 0, 2, 2, 2), GridSpec(-3, 0, 4, 4, 3),
            GridSpec(-4, 2, 2, 2, 1), GridSpec(-5, 0, 7, 2, 2), GridSpec(9, 2, 7, 3, 1),
            // A 2 × 1 Weather widget with another widget right under it: it can't grow, so it stays.
            GridSpec(-6, 4, 0, 2, 1), GridSpec(10, 4, 1, 2, 1),
        )
        val (t, specs) = BuiltInWidget.migrate(types, placed)
        assertEquals(mapOf(-1 to "weather", -2 to "weather", -3 to "calendar", -4 to "weather", -6 to "weather"), t)
        assertEquals(listOf(
            GridSpec(-1, 0, 0, 2, 2), GridSpec(-2, 0, 2, 2, 2), GridSpec(-3, 0, 4, 4, 3),
            GridSpec(-4, 2, 2, 2, 2), GridSpec(9, 2, 7, 3, 1),
            GridSpec(-6, 4, 0, 2, 1), GridSpec(10, 4, 1, 2, 1),
        ), specs)
        // Already migrated: nothing changes.
        assertEquals(t to specs, BuiltInWidget.migrate(t, specs))
    }

    @Test fun theStickyNoteIsNotInSearchlight() {
        assertNull(BuiltInWidget.fromKey("sticky_note"))
        assertEquals(listOf(BuiltInWidget.CALENDAR, BuiltInWidget.WEATHER), BuiltInWidget.entries.toList())
        // Only the Weather widget is fixed; the Calendar still resizes.
        assertEquals(listOf(BuiltInWidget.WEATHER), BuiltInWidget.entries.filter { it.fixedSize })
    }

    @Test fun builtInsSurviveStorageAndRefitting() {
        val stored = listOf(GridSpec(-1, 0, 0, 4, 2), GridSpec(5, 0, 2, 2, 2))
        assertEquals(stored, WidgetGrid.parse(WidgetGrid.serialize(stored)))
        // Unfolded: placed in the other layout's first free spot, at its own size.
        val unfolded = WidgetGrid.withMissing(emptyList(), stored, 7, 6)
        assertEquals(GridSpec(-1, 0, 0, 4, 2), unfolded.first { it.id == -1 })
    }
}
