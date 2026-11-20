package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetForecastTest {

    private val hour = 3_600_000L
    private val off = -8 * 3600
    // 2026-11-18 14:20 PST
    private val now = 1_795_550_400_000L + 2 * hour + 20 * 60_000L
    private val today = Moon.dayStart(now, off)

    private fun data(days: List<DayPoint>, hours: List<HourPoint> = emptyList(), temp: Double = 9.0) =
        WeatherData("Tacoma", 47.25, -122.44, temp, 3, true, 10.0, 4.0, now,
            WeatherDetail(off, null, null, null, null, null, null, null, null, hours, days, null))

    private fun day(i: Int, hi: Double, lo: Double, code: Int = 3) = DayPoint(today + i * 24 * hour, hi, lo, code, 20, null, null)

    @Test fun todayThenTheNextTwoDaysEachOnce() {
        val d = WidgetForecast.threeDays(data((0 until 7).map { day(it, 10.0 + it, 4.0 + it, 60 + it) }), now)
        assertEquals(listOf(today, today + 24 * hour, today + 48 * hour), d.map { it.date })
        assertEquals(listOf(true, false, false), d.map { it.today })
        assertEquals(listOf(11.0, 12.0), d.drop(1).map { it.highC })
        assertEquals(listOf(5.0, 6.0), d.drop(1).map { it.lowC })
        assertEquals(listOf(60, 61, 62), d.map { it.code })
    }

    @Test fun todayTakesInTheReadingNow() {
        val d = WidgetForecast.threeDays(data(listOf(day(0, 8.0, 5.0), day(1, 9.0, 3.0), day(2, 9.0, 3.0)), temp = 9.5), now)
        assertEquals(9.5, d[0].highC, 0.0)
        assertEquals(5.0, d[0].lowC, 0.0)
        val cold = WidgetForecast.threeDays(data(listOf(day(0, 8.0, 5.0), day(1, 9.0, 3.0), day(2, 9.0, 3.0)), temp = 2.0), now)
        assertEquals(2.0, cold[0].lowC, 0.0)
    }

    @Test fun anOldForecastStartsAtToday() {
        // Cached yesterday: its first day is yesterday, skipped.
        val d = WidgetForecast.threeDays(data((-1 until 6).map { day(it, 10.0 + it, 4.0) }), now)
        assertEquals(listOf(today, today + 24 * hour, today + 48 * hour), d.map { it.date })
        assertTrue(d[0].today)
    }

    @Test fun neverTheSameDayTwice() {
        // A repeated day in the forecast (or an hour off at a clock change) is still one day.
        val days = listOf(day(0, 10.0, 4.0), day(1, 11.0, 4.0), day(1, 11.0, 4.0).copy(date = today + 24 * hour + hour), day(2, 12.0, 4.0), day(3, 13.0, 4.0))
        val d = WidgetForecast.threeDays(data(days), now)
        assertEquals(3, d.map { Moon.dayStart(it.date + 12 * hour, off) }.toSet().size)
        assertEquals(listOf(10.0, 11.0, 12.0), d.map { it.highC })
        assertEquals(12.0, d[2].highC, 0.0)
    }

    @Test fun noForecastNoDays() {
        assertEquals(emptyList<WidgetForecast.Day>(), WidgetForecast.threeDays(data(emptyList()).copy(detail = null), now))
    }

    @Test fun theCurveIsTheNext12HoursWithItsHighAndLow() {
        val start = now / hour * hour
        val hours = (-3..30).map { i -> HourPoint(start + i * hour, 5.0 + 6 * kotlin.math.sin(i / 4.0), 3, true) }
        val c = WidgetForecast.curve(data(listOf(day(0, 10.0, 4.0)), hours, temp = 7.0), now)!!
        assertEquals(now to 7.0, c.points.first())
        val want = hours.filter { it.time > now && it.time <= now + 12 * hour }
        assertEquals(want.map { it.time to it.tempC }, c.points.drop(1))
        assertEquals(c.points.maxOf { it.second }, c.points[c.high].second, 0.0)
        assertEquals(c.points.minOf { it.second }, c.points[c.low].second, 0.0)
        // NOW (2:20 PM), then 6 PM, 9 PM, midnight, 3 AM: on the hour, every 3, local time; the first an hour or more on.
        assertEquals(now, c.marks.first())
        val local = c.marks.drop(1).map { Math.floorMod(it + off * 1000L, 24 * hour) / hour }
        assertEquals(listOf(18L, 21L, 0L), local)
        assertEquals(0f, c.x(now), 0f)
        assertEquals(1f, c.x(now + 12 * hour), 0f)
        assertEquals(0.5f, c.x(now + 6 * hour), 1e-6f)
    }

    @Test fun theFirstMarkLeavesRoomForNow() {
        // 2:50 PM: 3 PM is too close to NOW; the first hour mark is 6 PM.
        val at = today + 14 * hour + 50 * 60_000L
        val start = at / hour * hour
        val hours = (0..14).map { HourPoint(start + it * hour, 5.0, 3, true) }
        val c = WidgetForecast.curve(data(listOf(day(0, 10.0, 4.0)), hours), at)!!
        assertEquals(18L, Math.floorMod(c.marks[1] + off * 1000L, 24 * hour) / hour)
    }

    @Test fun noHoursNoCurve() {
        assertNull(WidgetForecast.curve(data(listOf(day(0, 10.0, 4.0))), now))
    }
}
