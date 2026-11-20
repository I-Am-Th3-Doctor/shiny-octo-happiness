package com.something.launcher

import com.something.launcher.weather.Place
import com.something.launcher.weather.Source
import com.something.launcher.weather.WeatherApi
import com.something.launcher.weather.WeatherStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class WeatherMergeTest {
    private val offset = -5 * 3600
    private val place = Place("Testville", 40.0, -74.0)
    // 2026-11-20 14:30 local (-05:00)
    private val now = LocalDateTime.of(2026, 11, 20, 14, 30).toEpochSecond(ZoneOffset.ofTotalSeconds(offset)) * 1000

    private fun hourly(start: LocalDateTime, count: Int, temp: (Int) -> Double?, code: Int = 2): JSONObject {
        val time = JSONArray(); val t = JSONArray(); val c = JSONArray(); val d = JSONArray(); val p = JSONArray()
        for (i in 0 until count) {
            val ts = start.plusHours(i.toLong())
            time.put(ts.toString())
            val v = temp(i)
            t.put(v ?: JSONObject.NULL); c.put(if (v == null) JSONObject.NULL else code)
            d.put(if (ts.hour in 7..17) 1 else 0); p.put(0.1)
        }
        return JSONObject().put("time", time).put("temperature_2m", t).put("weather_code", c).put("is_day", d)
            .put("precipitation", p)
    }

    private fun hrrr(temp: (Int) -> Double? = { 10.0 + it % 24 }) = JSONObject()
        .put("utc_offset_seconds", offset)
        .put("current", JSONObject().put("temperature_2m", 21.5).put("weather_code", 3).put("is_day", 1).put("apparent_temperature", 20.0))
        // past_hours=24 + forecast_hours=48 starting at 14:00 yesterday
        .put("hourly", hourly(LocalDateTime.of(2026, 11, 19, 14, 0), 72, temp, code = 61))

    private fun ecmwf(): JSONObject {
        val days = JSONArray(); val hi = JSONArray(); val lo = JSONArray(); val code = JSONArray(); val sr = JSONArray(); val ss = JSONArray()
        for (i in 0 until 8) {
            val d = LocalDate.of(2026, 11, 20).plusDays(i.toLong())
            days.put(d.toString()); hi.put(30.0 + i); lo.put(-5.0 - i); code.put(0)
            sr.put(d.atTime(6, 50).toString()); ss.put(d.atTime(16, 35).toString())
        }
        return JSONObject()
            .put("utc_offset_seconds", offset)
            .put("current", JSONObject().put("temperature_2m", 5.0).put("weather_code", 0).put("is_day", 1))
            .put("hourly", hourly(LocalDateTime.of(2026, 11, 20, 0, 0), 192, { 0.0 }, code = 0))
            .put("daily", JSONObject().put("time", days).put("temperature_2m_max", hi).put("temperature_2m_min", lo)
                .put("weather_code", code).put("sunrise", sr).put("sunset", ss))
    }

    @Test fun hrrrForFirst48HoursAndFirstTwoDays_ecmwfAfter() {
        val f = WeatherApi.merge(place, hrrr(), ecmwf(), emptyList(), now)!!
        assertEquals(48, f.hours.size)
        assertEquals(LocalDateTime.of(2026, 11, 20, 14, 0), f.hours.first().time)
        assertTrue(f.hours.all { it.source == Source.HRRR })
        assertEquals(Source.HRRR, f.current.source)
        assertEquals(21.5, f.current.tempC, 0.0)
        assertEquals(7, f.days.size)
        assertEquals(listOf(Source.HRRR, Source.HRRR, Source.ECMWF, Source.ECMWF, Source.ECMWF, Source.ECMWF, Source.ECMWF), f.days.map { it.source })
        assertEquals(LocalDate.of(2026, 11, 20), f.days[0].date)
        // Day 3 is straight from ECMWF daily.
        assertEquals(32.0, f.days[2].hiC, 0.0)
        // HRRR days take sunrise/sunset from ECMWF.
        assertNotNull(f.days[0].sunrise)
        // HRRR rain code wins the day's headline.
        assertEquals(61, f.days[0].code)
    }

    @Test fun outsideHrrrCoverage_fallsBackToEcmwfEverywhere() {
        val f = WeatherApi.merge(place, hrrr { null }.apply { remove("current") }, ecmwf(), emptyList(), now)!!
        assertTrue(f.hours.isNotEmpty())
        assertTrue(f.hours.all { it.source == Source.ECMWF })
        assertEquals(Source.ECMWF, f.current.source)
        assertTrue(f.days.all { it.source == Source.ECMWF })
    }

    @Test fun hrrrGap_isFilledByEcmwfHours() {
        // HRRR stops after 18 forecast hours (non-synoptic runs).
        val f = WeatherApi.merge(place, hrrr { if (it < 24 + 18) 12.0 else null }, ecmwf(), emptyList(), now)!!
        assertEquals(48, f.hours.size)
        assertEquals(Source.HRRR, f.hours[17].source)
        assertEquals(Source.ECMWF, f.hours[18].source)
        // Tomorrow isn't covered well enough by HRRR, so it comes from ECMWF.
        assertEquals(Source.ECMWF, f.days[1].source)
    }

    @Test fun noData_returnsNull() {
        assertNull(WeatherApi.merge(place, null, null, emptyList(), now))
    }

    @Test fun alertsParse_sortedBySeverity_cancelsDropped() {
        val body = """
        {"features":[
          {"properties":{"status":"Actual","messageType":"Alert","event":"Wind Advisory","severity":"Moderate","headline":"h1","description":"d","instruction":null,"ends":"2026-11-20T19:00:00-05:00"}},
          {"properties":{"status":"Actual","messageType":"Alert","event":"Tornado Warning","severity":"Extreme","headline":"h2","description":"d","instruction":"Take cover","ends":null,"expires":"2026-11-20T15:45:00-05:00"}},
          {"properties":{"status":"Actual","messageType":"Cancel","event":"Flood Watch","severity":"Severe"}},
          {"properties":{"status":"Test","messageType":"Alert","event":"Test Message","severity":"Unknown"}}
        ]}
        """.trimIndent()
        val alerts = WeatherApi.parseAlerts(body)
        assertEquals(listOf("Tornado Warning", "Wind Advisory"), alerts.map { it.event })
        assertTrue(alerts[0].isSevere)
        assertEquals("2026-11-20T15:45:00-05:00", alerts[0].ends)
        assertEquals("", alerts[1].instruction)
    }

    @Test fun forecastCacheRoundTrips() {
        val f = WeatherApi.merge(place, hrrr(), ecmwf(), WeatherApi.parseAlerts(
            """{"features":[{"properties":{"status":"Actual","event":"Heat Advisory","severity":"Severe","ends":"2026-11-20T19:00:00-05:00"}}]}"""
        ), now)!!
        val back = WeatherStore.decode(JSONObject(WeatherStore.encode(f).toString()))
        assertEquals(f.copy(), back.copy())
    }
}
