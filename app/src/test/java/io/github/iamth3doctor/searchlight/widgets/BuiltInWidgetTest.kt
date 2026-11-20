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

    @Test fun threeWeatherWidgets() {
        assertEquals(BuiltInWidget.WEATHER_3DAY, BuiltInWidget.fromKey("weather_3day"))
        assertEquals(BuiltInWidget.WEATHER_DAY, BuiltInWidget.fromKey("weather_day"))
        assertEquals(2 to 2, BuiltInWidget.WEATHER_3DAY.defaultCols to BuiltInWidget.WEATHER_3DAY.defaultRows)
        assertEquals(4 to 2, BuiltInWidget.WEATHER_DAY.defaultCols to BuiltInWidget.WEATHER_DAY.defaultRows)
        assertEquals(3, BuiltInWidget.entries.count { it.isWeather })
        assertFalse(BuiltInWidget.CALENDAR.isWeather)
    }

    @Test fun stickyNoteIsAFixedTwoByTwo() {
        assertEquals(BuiltInWidget.STICKY_NOTE, BuiltInWidget.fromKey("sticky_note"))
        assertEquals(2 to 2, BuiltInWidget.STICKY_NOTE.defaultCols to BuiltInWidget.STICKY_NOTE.defaultRows)
        assertTrue(BuiltInWidget.STICKY_NOTE.fixedSize)
        assertFalse(BuiltInWidget.STICKY_NOTE.isWeather)
        // Only the note is fixed; every other widget still resizes.
        assertEquals(listOf(BuiltInWidget.STICKY_NOTE), BuiltInWidget.entries.filter { it.fixedSize })
    }

    @Test fun builtInsSurviveStorageAndRefitting() {
        val stored = listOf(GridSpec(-1, 0, 0, 4, 2), GridSpec(5, 0, 2, 2, 2))
        assertEquals(stored, WidgetGrid.parse(WidgetGrid.serialize(stored)))
        // Unfolded: placed in the other layout's first free spot, at its own size.
        val unfolded = WidgetGrid.withMissing(emptyList(), stored, 7, 6)
        assertEquals(GridSpec(-1, 0, 0, 4, 2), unfolded.first { it.id == -1 })
    }
}
