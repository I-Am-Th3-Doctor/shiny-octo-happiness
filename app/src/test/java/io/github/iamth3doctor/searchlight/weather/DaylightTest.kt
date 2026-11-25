package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaylightTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    // 2026-11-25 00:00 PST, Tacoma: sunrise 7:27 AM, sunset 4:23 PM.
    private val midnight = java.time.LocalDateTime.of(2026, 11, 25, 8, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

    private fun data(withDays: Boolean = true, hourlyIsDay: Boolean = true): WeatherData {
        // The hourly flag says day all round, as a blended forecast missing it can: Daylight ignores it.
        val hours = (0 until 48).map { i -> HourPoint(midnight + i * hour, 10.0, 1, hourlyIsDay, cloudPct = 30, precipMm = 0.0, precipProb = 5) }
        val days = if (!withDays) emptyList() else (0 until 2).map { i ->
            DayPoint(midnight + i * 24 * hour, 12.0, 5.0, 1, 5, midnight + i * 24 * hour + 7 * hour + 27 * min, midnight + i * 24 * hour + 16 * hour + 23 * min)
        }
        val d = WeatherDetail(-28800, 9.0, 80, 5.0, 1015.0, 20000.0, 5.0, 180.0, 1.0, hours, days, 20, cloudPct = 30, precipMm = 0.0)
        return WeatherData("Tacoma", 47.25, -122.44, 10.0, 3, false, 12.0, 5.0, midnight + 21 * hour, d)
    }

    @Test fun dayIsSunriseToSunset() {
        val w = data()
        assertFalse("1 AM is night", Daylight.isDay(w, midnight + 1 * hour))
        assertFalse("7 AM, before the 7:27 sunrise", Daylight.isDay(w, midnight + 7 * hour))
        assertTrue("7:27 AM: up", Daylight.isDay(w, midnight + 7 * hour + 27 * min))
        assertTrue("noon", Daylight.isDay(w, midnight + 12 * hour))
        assertTrue("4 PM, before the 4:23 sunset", Daylight.isDay(w, midnight + 16 * hour))
        assertFalse("5 PM", Daylight.isDay(w, midnight + 17 * hour))
        assertFalse("9:08 PM", Daylight.isDay(w, midnight + 21 * hour + 8 * min))
        assertFalse("tomorrow 1 AM", Daylight.isDay(w, midnight + 25 * hour))
        assertTrue("tomorrow noon", Daylight.isDay(w, midnight + 36 * hour))
    }

    @Test fun withoutSunTimesTheSunsOwnPosition() {
        val w = data(withDays = false)
        assertFalse(Daylight.isDay(w, midnight + 1 * hour))
        assertFalse(Daylight.isDay(w, midnight + 21 * hour))
        assertTrue(Daylight.isDay(w, midnight + 12 * hour))
    }

    @Test fun nightHoursDrawTheMoonWhateverTheHourlyFlagSays() {
        val w = data(hourlyIsDay = true)
        val oneAm = w.detail!!.hourly.first { it.time == midnight + 1 * hour }
        assertEquals(ConditionVisual.PARTLY_NIGHT, ConditionVisual.of(oneAm.code, Daylight.isDay(w, oneAm.time)))
        val noon = w.detail!!.hourly.first { it.time == midnight + 12 * hour }
        assertEquals(ConditionVisual.PARTLY_DAY, ConditionVisual.of(noon.code, Daylight.isDay(w, noon.time)))
        // Clear at night: the moon and stars, not the sun.
        assertEquals(ConditionVisual.CLEAR_NIGHT, ConditionVisual.of(0, Daylight.isDay(w, oneAm.time)))
    }

    @Test fun theSceneIsNightAfterSunset() {
        val w = data().copy(weatherCode = 0)
        val night = SceneModel.at(w, midnight + 21 * hour + 8 * min, live = true)
        assertFalse(night.day)
        assertEquals(SceneModel.Sky.SUN, night.sky) // a clear sky, so no sun only because it's night
        assertTrue(night.darkness > 0.9)
        val noon = SceneModel.at(w, midnight + 12 * hour, live = false)
        assertTrue(noon.day)
    }
}
