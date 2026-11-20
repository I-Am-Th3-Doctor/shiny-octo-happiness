package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DayBarTest {

    private val hour = 3_600_000L
    // 2026-11-18 12:05 PST
    private val now = 1_795_550_400_000L + 5 * 60_000L

    private fun data(): WeatherData {
        val h0 = now / hour * hour
        val hours = (-30..60).map { i -> HourPoint(h0 + i * hour, 10.0 + i * 0.5, 2, true) }
        val detail = WeatherDetail(-28800, 8.0, 80, 5.0, 1015.0, 20000.0, 6.0, 200.0, 1.0, hours, emptyList(), 30)
        return WeatherData("Tacoma", 47.25, -122.44, 11.0, 2, true, 14.0, 4.0, now, detail)
    }

    @Test fun theDayFromJustBeforeNow() {
        val start = DayBar.start(now)
        assertEquals(now / hour * hour - 2 * hour, start)
        assertEquals(start + 24 * hour, DayBar.end(now))
        val fNow = DayBar.fractionOf(now, now)
        assertTrue(abs(fNow - (2 + 5 / 60.0) / 24) < 1e-9)
        assertEquals(now.toDouble(), DayBar.timeAt(fNow, now).toDouble(), 1.0)
        assertEquals(0.0, DayBar.fractionOf(start - hour, now), 0.0)
        assertEquals(1.0, DayBar.fractionOf(start + 30 * hour, now), 0.0)
        // Just its 25 hours, out of the forecast's day back and days ahead.
        val hours = DayBar.hours(data(), now)
        assertEquals(25, hours.size)
        assertEquals(start, hours.first().time)
        assertEquals(start + 24 * hour, hours.last().time)
    }
}
