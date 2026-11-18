package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WeatherDetailTest {

    // 2026-11-18 12:00 PST (UTC-8) = 20:00 UTC
    private val now = 1_795_550_400_000L
    private val off = -8 * 3600

    private fun forecastJson(): String {
        val hourStart = now / 1000 - 3600
        val hours = (0 until 25).map { hourStart + it * 3600L }
        val codes = hours.map { t -> val h = Math.floorMod(t + off, 86_400L) / 3600; if (h < 18) 3 else 0 }
        val dayStart = Moon.dayStart(now, off) / 1000
        val days = (0 until 7).map { dayStart + it * 86_400L }
        return """
            {"utc_offset_seconds":$off,
             "current":{"time":${now / 1000},"temperature_2m":9.5,"weather_code":3,"is_day":1,"apparent_temperature":7.2,
               "relative_humidity_2m":81,"dew_point_2m":6.4,"pressure_msl":1016.6,"visibility":24140.0,
               "wind_speed_10m":4.2,"wind_direction_10m":44,"uv_index":1.35},
             "hourly":{"time":${hours},"temperature_2m":${hours.map { 9.0 }},"weather_code":$codes,"is_day":${hours.map { 1 }}},
             "daily":{"time":$days,"temperature_2m_max":${days.map { 11.1 }},"temperature_2m_min":${days.map { 4.0 }},
               "weather_code":${days.map { 61 }},"precipitation_probability_max":${days.map { 40 }},
               "sunrise":${days.map { it + 7 * 3600 + 25 * 60 }},"sunset":${days.map { it + 16 * 3600 + 30 * 60 }}}}
        """.trimIndent()
    }

    @Test fun parsesTheFullForecast() {
        val w = OpenMeteo.parseForecast(forecastJson(), "Tacoma", 47.25, -122.44, now)
        val d = assertNotNull(w.detail).let { w.detail!! }
        assertEquals(off, d.utcOffsetSec)
        assertEquals(7.2, d.feelsLikeC!!, 1e-9)
        assertEquals(81, d.humidity)
        assertEquals(4.2, d.windMph!!, 1e-9)
        assertEquals(1016.6, d.pressureHpa!!, 1e-9)
        assertEquals(25, d.hourly.size) // from the current hour on
        assertEquals(7, d.daily.size)
        assertEquals(40, d.daily[0].precipPct)
        assertNotNull(d.daily[0].sunrise)
        assertNull(d.aqi)
    }

    @Test fun hoursKeptAroundNow() {
        // A few hours back (the day bar starts before now), up to two days ahead.
        val later = now + 6 * 3_600_000L
        val w = OpenMeteo.parseForecast(forecastJson(), "Tacoma", 47.25, -122.44, later)
        assertTrue(w.detail!!.hourly.first().time >= later - WeatherDetail.KEEP_BEFORE_MS)
    }

    @Test fun cacheRoundTrip() {
        val w = OpenMeteo.parseForecast(forecastJson(), "Tacoma", 47.25, -122.44, now)
        val withAqi = w.copy(detail = w.detail!!.copy(aqi = 42))
        assertEquals(withAqi, WeatherData.fromJson(withAqi.toJson()))
    }

    @Test fun oldCacheWithoutDetailStillReads() {
        val w = OpenMeteo.parseForecast(forecastJson(), "Tacoma", 47.25, -122.44, now).copy(detail = null)
        assertEquals(w, WeatherData.fromJson(w.toJson()))
    }

    @Test fun missingFieldsDontBreakTheTileReading() {
        val json = """{"current":{"temperature_2m":10.0,"weather_code":0,"is_day":1},"daily":{"time":["2026-11-18"],"temperature_2m_max":[12.0],"temperature_2m_min":[3.0]}}"""
        val w = OpenMeteo.parseForecast(json, "X", 0.0, 0.0, now)
        assertEquals(10.0, w.temperatureC, 1e-9)
        // the daily time isn't unixtime here, so the detail may or may not come; the reading always does
    }

    @Test fun aqi() {
        assertTrue(OpenMeteo.airQualityUrl(47.2529, -122.4443).contains("latitude=47.25&longitude=-122.44&current=us_aqi"))
        assertEquals(37, OpenMeteo.parseAqi("""{"current":{"time":1,"us_aqi":37}}"""))
        assertNull(OpenMeteo.parseAqi("""{"current":{"time":1,"us_aqi":null}}"""))
        assertNull(OpenMeteo.parseAqi("""{}"""))
    }

    @Test fun forecastUrlAsksForEverything() {
        val url = OpenMeteo.forecastUrl(47.25, -122.44)
        for (k in listOf("apparent_temperature", "relative_humidity_2m", "dew_point_2m", "pressure_msl", "visibility",
                "wind_speed_10m", "wind_direction_10m", "uv_index", "precipitation_probability_max", "sunrise", "sunset",
                "hourly=temperature_2m,weather_code,is_day", "forecast_days=7", "wind_speed_unit=mph", "timeformat=unixtime")) {
            assertTrue(k, url.contains(k))
        }
    }

    @Test fun visualsFollowTheTilesCode() {
        assertEquals(ConditionVisual.CLEAR_DAY, ConditionVisual.of(0, true))
        assertEquals(ConditionVisual.CLEAR_NIGHT, ConditionVisual.of(0, false))
        assertEquals(ConditionVisual.PARTLY_DAY, ConditionVisual.of(1, true))
        assertEquals(ConditionVisual.PARTLY_NIGHT, ConditionVisual.of(2, false))
        assertEquals(ConditionVisual.CLOUDY, ConditionVisual.of(3, true))
        assertEquals(ConditionVisual.FOG, ConditionVisual.of(45, true))
        assertEquals(ConditionVisual.RAIN, ConditionVisual.of(53, true))
        assertEquals(ConditionVisual.RAIN, ConditionVisual.of(81, false))
        assertEquals(ConditionVisual.SNOW, ConditionVisual.of(75, true))
        assertEquals(ConditionVisual.THUNDER, ConditionVisual.of(95, true))
        // every code the tile knows maps to something
        for (c in 0..99) ConditionVisual.of(c, true)
    }

    @Test fun wording() {
        assertEquals("4 mph from NE", WeatherText.wind(4.2, 44.0))
        assertEquals("Calm", WeatherText.wind(0.3, 180.0))
        assertEquals("N", WeatherText.compass(355.0))
        assertEquals("SSW", WeatherText.compass(200.0))
        assertEquals("15 mi", WeatherText.visibility(24140.0))
        assertEquals("Good", WeatherText.visibilityRating(24140.0))
        assertEquals("Moderate", WeatherText.visibilityRating(4000.0))
        assertEquals("Poor", WeatherText.visibilityRating(800.0))
        assertEquals("0.5 mi", WeatherText.visibility(800.0))
        assertEquals("30.02 inHg", WeatherText.pressure(1016.6))
        assertEquals("Low", WeatherText.uvRating(1.35))
        assertEquals("Moderate", WeatherText.uvRating(4.0))
        assertEquals("High", WeatherText.uvRating(6.5))
        assertEquals("Good", WeatherText.aqiRating(42))
        assertEquals("Moderate", WeatherText.aqiRating(51))
    }

    @Test fun daySummary() {
        val s = WeatherText.daySummary(11.1, 3, 0)
        assertTrue(s, s.startsWith("Conditions will turn clear sky at night."))
        assertTrue(s, s.endsWith("It will be cool during the day with highs around 52°."))
        val same = WeatherText.daySummary(11.1, 3, 3)
        assertTrue(same, same.startsWith("Overcast through the day."))
    }

    @Test fun dayAndNightFromTheHours() {
        val w = OpenMeteo.parseForecast(forecastJson(), "Tacoma", 47.25, -122.44, now)
        val (day, night) = WeatherText.dayAndNight(w.detail!!.hourly, 3, now, off)
        assertEquals(3, day)
        assertEquals(0, night)
    }

    // ------------------------------------------------------------------ the moon

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        java.time.LocalDateTime.of(y, mo, d, h, mi).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

    @Test fun knownPhases() {
        // The true moon: within a degree (about two hours) of the almanac's exact moments.
        fun near(target: Double, ms: Long) {
            val e = Moon.elongation(ms)
            val diff = Math.abs(((e - target + 540) % 360) - 180)
            assertTrue("elongation $e vs $target", diff < 1.0)
        }
        near(0.0, utc(2024, 4, 8, 18, 21))
        near(90.0, utc(2024, 4, 15, 19, 13))
        near(180.0, utc(2024, 4, 23, 23, 49))
        near(270.0, utc(2024, 5, 1, 11, 27))
        near(0.0, utc(2025, 9, 21, 19, 54))
        near(180.0, utc(2026, 1, 3, 10, 3))
        // New moon 2024-04-08 18:21 UTC (the eclipse); full moon 2024-04-23 23:49 UTC; first quarter 2024-04-15 19:13 UTC
        assertEquals(Moon.Phase.NEW, Moon.phase(utc(2024, 4, 8, 18, 21)))
        assertEquals(Moon.Phase.FULL, Moon.phase(utc(2024, 4, 23, 23, 49)))
        assertEquals(Moon.Phase.FIRST_QUARTER, Moon.phase(utc(2024, 4, 15, 19, 13)))
        assertEquals(Moon.Phase.WAXING_GIBBOUS, Moon.phase(utc(2024, 4, 19, 12, 0)))
        assertEquals(Moon.Phase.WANING_CRESCENT, Moon.phase(utc(2024, 5, 4, 12, 0)))
        // Full moon 2026-11-24 14:53 UTC
        assertEquals(Moon.Phase.FULL, Moon.phase(utc(2026, 11, 24, 14, 53)))
        assertTrue(Moon.illumination(utc(2024, 4, 23, 23, 49)) > 0.98)
        assertTrue(Moon.illumination(utc(2024, 4, 8, 18, 21)) < 0.02)
    }

    @Test fun riseAndSetAreWhereTheMoonCrossesTheHorizon() {
        val lat = 47.25
        val lon = -122.44
        var checked = 0
        for (k in 0 until 30) {
            val start = Moon.dayStart(utc(2026, 11, 1, 20, 0), off) + k * 86_400_000L
            val t = Moon.times(start, lat, lon)
            for (x in listOfNotNull(t.rise, t.set)) {
                assertTrue(x in start..start + 86_400_000L)
                val before = Moon.altitude(x - 20 * 60_000L, lat, lon)
                val after = Moon.altitude(x + 20 * 60_000L, lat, lon)
                assertTrue("not a crossing at $x", (before < 0.0023) != (after < 0.0023))
                if (x == t.rise) assertTrue(after > before) else assertTrue(after < before)
                checked++
            }
            // Over a month, nearly every day has both.
        }
        assertTrue(checked >= 55)
    }

    @Test fun moonriseInTacomaMatchesAnAlmanac() {
        // 2026-11-24 (full moon), Tacoma: moonrise ~16:22 PST, moonset ~08:20 PST (USNO-style, ±15 min)
        val start = Moon.dayStart(utc(2026, 11, 24, 20, 0), off)
        val t = Moon.times(start, 47.25, -122.44)
        val riseLocal = (t.rise!! - start) / 60_000.0
        assertTrue("rise $riseLocal", abs(riseLocal - (16 * 60 + 22)) < 45)
    }
}
