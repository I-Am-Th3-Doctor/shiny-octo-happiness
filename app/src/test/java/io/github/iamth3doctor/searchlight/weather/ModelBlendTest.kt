package io.github.iamth3doctor.searchlight.weather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class ModelBlendTest {

    // 2026-11-18 20:00 UTC; the place keeps UTC so its days start at UTC midnight.
    private val now = 1_795_550_400_000L
    private val hour = 3_600_000L
    private val dayStart = now - Math.floorMod(now, 24 * hour)
    private val times = (0 until 7 * 24).map { dayStart + it * hour }
    private val days = (0 until 7).map { dayStart + it * 24 * hour }

    private fun at(t: Long) = times.indexOf(t)
    private fun arr(vs: List<Any?>) = JSONArray().apply { vs.forEach { put(it ?: JSONObject.NULL) } }

    /**
     * Three models as Open-Meteo names them: HRRR 10 °C until [hrrrEndH] hours out (then nothing; one
     * hole at +5 h), ECMWF 20 °C, best_match 15 °C. Each model gives a rain chance (HRRR 80, ECMWF 60,
     * best_match 30), as Open-Meteo does; only best_match has daily UV.
     */
    private fun multi(hrrrEndH: Int = 48): JSONObject {
        val hrrr = { t: Long, v: Any -> if (t > now + hrrrEndH * hour || t == now + 5 * hour) null else v }
        val hourly = JSONObject().put("time", arr(times.map { it / 1000 }))
            .put("temperature_2m_gfs_hrrr", arr(times.map { hrrr(it, 10.0) }))
            .put("temperature_2m_ecmwf_ifs", arr(times.map { 20.0 }))
            .put("temperature_2m_best_match", arr(times.map { 15.0 }))
            .put("precipitation_probability_gfs_hrrr", arr(times.map { 80 }))
            .put("precipitation_probability_ecmwf_ifs", arr(times.map { 60 }))
            .put("precipitation_probability_best_match", arr(times.map { 30 }))
            .put("weather_code_gfs_hrrr", arr(times.map { hrrr(it, 61) }))
            .put("weather_code_ecmwf_ifs", arr(times.map { 3 }))
            .put("weather_code_best_match", arr(times.map { 2 }))
            .put("is_day_gfs_hrrr", arr(times.map { 1 }))
            .put("is_day_ecmwf_ifs", arr(times.map { 1 }))
            .put("is_day_best_match", arr(times.map { 1 }))
            .put("wind_direction_10m_gfs_hrrr", arr(times.map { hrrr(it, 350) }))
            .put("wind_direction_10m_ecmwf_ifs", arr(times.map { 10 }))
            .put("wind_direction_10m_best_match", arr(times.map { 0 }))
            .put("precipitation_gfs_hrrr", arr(times.map { hrrr(it, 0.0) }))
            .put("precipitation_ecmwf_ifs", arr(times.map { 0.5 }))
            .put("precipitation_best_match", arr(times.map { 0.2 }))
        val daily = JSONObject().put("time", arr(days.map { it / 1000 }))
            .put("temperature_2m_max_gfs_hrrr", arr(days.mapIndexed { i, _ -> if (i < 2) 99.0 else null }))
            .put("temperature_2m_max_ecmwf_ifs", arr(days.map { 21.0 }))
            .put("temperature_2m_max_best_match", arr(days.map { 16.0 }))
            .put("temperature_2m_min_gfs_hrrr", arr(days.mapIndexed { i, _ -> if (i < 2) -99.0 else null }))
            .put("temperature_2m_min_ecmwf_ifs", arr(days.map { 19.0 }))
            .put("temperature_2m_min_best_match", arr(days.map { 14.0 }))
            .put("weather_code_gfs_hrrr", arr(days.mapIndexed { i, _ -> if (i < 2) 61 else null }))
            .put("weather_code_ecmwf_ifs", arr(days.map { 3 }))
            .put("weather_code_best_match", arr(days.map { 2 }))
            .put("precipitation_probability_max_gfs_hrrr", arr(days.map { 80 }))
            .put("precipitation_probability_max_ecmwf_ifs", arr(days.map { 60 }))
            .put("precipitation_probability_max_best_match", arr(days.map { 30 }))
            .put("precipitation_sum_gfs_hrrr", arr(days.mapIndexed { i, _ -> if (i < 2) 0.0 else null }))
            .put("precipitation_sum_ecmwf_ifs", arr(days.map { 12.0 }))
            .put("precipitation_sum_best_match", arr(days.map { 4.8 }))
            .put("uv_index_max_gfs_hrrr", arr(days.map { null }))
            .put("uv_index_max_ecmwf_ifs", arr(days.map { null }))
            .put("uv_index_max_best_match", arr(days.map { 2.0 }))
            .put("sunrise_best_match", arr(days.map { it / 1000 + 7 * 3600 }))
            .put("sunset_best_match", arr(days.map { it / 1000 + 16 * 3600 }))
        val current = JSONObject().put("time", now / 1000).put("interval", 900)
            .put("temperature_2m_gfs_hrrr", 11.0).put("temperature_2m_ecmwf_ifs", 13.0).put("temperature_2m_best_match", 12.0)
            .put("weather_code_gfs_hrrr", 61).put("weather_code_ecmwf_ifs", 3).put("weather_code_best_match", 2)
            .put("is_day_gfs_hrrr", 1).put("is_day_ecmwf_ifs", 1).put("is_day_best_match", 1)
            .put("uv_index_gfs_hrrr", JSONObject.NULL).put("uv_index_best_match", 1.5)
            .put("visibility_best_match", 24000.0)
        return JSONObject().put("utc_offset_seconds", 0).put("current", current).put("hourly", hourly).put("daily", daily)
    }

    private fun JSONObject.h(key: String, t: Long) = getJSONObject("hourly").getJSONArray(key).optDouble(at(t))
    private fun JSONObject.d(key: String, i: Int) = getJSONObject("daily").getJSONArray(key).optDouble(i)

    @Test fun theNext48HoursAreHrrrAndLaterDaysAreEcmwf() {
        val b = ModelBlend.blend(multi(), now)
        for (t in listOf(now - 6 * hour, now + 10 * hour, now + 42 * hour)) {
            assertEquals(10.0, b.h("temperature_2m", t), 1e-9)
            assertEquals(80.0, b.h("precipitation_probability", t), 1e-9)
            assertEquals(0.0, b.h("precipitation", t), 1e-9)
            assertEquals(61.0, b.h("weather_code", t), 0.0)
            assertEquals(350.0, b.h("wind_direction_10m", t), 1e-9)
        }
        for (t in listOf(now + 48 * hour, now + 60 * hour, now + 150 * hour)) {
            assertEquals(20.0, b.h("temperature_2m", t), 1e-9)
            assertEquals(60.0, b.h("precipitation_probability", t), 1e-9)
            assertEquals(0.5, b.h("precipitation", t), 1e-9)
            assertEquals(3.0, b.h("weather_code", t), 0.0)
        }
    }

    @Test fun theHandOverIsGradualOverHours42To48() {
        val b = ModelBlend.blend(multi(), now)
        // Half way through the cross-fade: half of each, temperature and rain chance alike.
        assertEquals(15.0, b.h("temperature_2m", now + 45 * hour), 1e-9)
        assertEquals(70.0, b.h("precipitation_probability", now + 45 * hour), 1e-9)
        assertEquals(0.25, b.h("precipitation", now + 45 * hour), 1e-9)
        assertEquals(10.0 + 10.0 / 6, b.h("temperature_2m", now + 43 * hour), 1e-9)
        // 350° and 10° meet at north, not south.
        val dir = b.h("wind_direction_10m", now + 45 * hour)
        assertTrue("$dir", min(dir, 360 - dir) < 1e-6)
        assertNoJump(b, from = now + 30 * hour, to = now + 60 * hour)
    }

    /** No hour-to-hour step in temperature or rain chance bigger than an even 6-hour cross-fade's. */
    private fun assertNoJump(b: JSONObject, from: Long, to: Long) {
        for (key in listOf("temperature_2m", "precipitation_probability")) {
            val a = b.getJSONObject("hourly").getJSONArray(key)
            val gap = abs(a.getDouble(at(from)) - a.getDouble(at(to)))
            for (i in (at(from) + 1)..at(to)) {
                val step = abs(a.getDouble(i) - a.getDouble(i - 1))
                assertTrue("$key step $step at ${(times[i] - now) / hour} h", step <= gap / 6 + 1e-9)
            }
        }
    }

    @Test fun whenHrrrsRunEndsEarlyTheHandOverEndsWithIt() {
        // HRRR's latest run reaches 43 h: its last 6 hours fade into ECMWF, no jump at 43.
        val b = ModelBlend.blend(multi(hrrrEndH = 43), now)
        assertEquals(10.0, b.h("temperature_2m", now + 37 * hour), 1e-9)
        assertEquals(15.0, b.h("temperature_2m", now + 40 * hour), 1e-9)
        assertEquals(70.0, b.h("precipitation_probability", now + 40 * hour), 1e-9)
        assertEquals(20.0, b.h("temperature_2m", now + 43 * hour), 1e-9)
        assertEquals(20.0, b.h("temperature_2m", now + 46 * hour), 1e-9)
        assertNoJump(b, from = now + 30 * hour, to = now + 50 * hour)
    }

    @Test fun anHourAModelLacksIsBestMatchWhole() {
        val b = ModelBlend.blend(multi(), now)
        // HRRR's hole at +5 h: every value of that hour is best_match's (not HRRR's rain chance
        // with best_match's temperature, nor ECMWF's).
        val t = now + 5 * hour
        assertEquals(15.0, b.h("temperature_2m", t), 1e-9)
        assertEquals(30.0, b.h("precipitation_probability", t), 1e-9)
        assertEquals(0.2, b.h("precipitation", t), 1e-9)
        assertEquals(2.0, b.h("weather_code", t), 0.0)
        assertEquals(0.0, b.h("wind_direction_10m", t), 1e-9)
        // The hours either side are HRRR's whole.
        assertEquals(80.0, b.h("precipitation_probability", t - hour), 1e-9)
        assertEquals(80.0, b.h("precipitation_probability", t + hour), 1e-9)
        val c = b.getJSONObject("current")
        assertEquals(11.0, c.getDouble("temperature_2m"), 1e-9)
        assertEquals(61, c.getInt("weather_code"))
        assertEquals(1.5, c.getDouble("uv_index"), 1e-9)
        assertEquals(24000.0, c.getDouble("visibility"), 1e-9)
    }

    @Test fun noHrrrHereMeansBestMatchForTheFirst48Hours() {
        // A place outside HRRR's map: best_match stands in for it, hour by hour, still fading into ECMWF at 42-48.
        val m = multi()
        val h = m.getJSONObject("hourly")
        for (k in h.keys().asSequence().filter { it.endsWith("_gfs_hrrr") && !it.startsWith("is_day") && !it.startsWith("precipitation_probability") }.toList()) {
            h.put(k, arr(times.map { null }))
        }
        val b = ModelBlend.blend(m, now)
        assertEquals(15.0, b.h("temperature_2m", now + 10 * hour), 1e-9)
        assertEquals(30.0, b.h("precipitation_probability", now + 10 * hour), 1e-9)
        assertEquals(17.5, b.h("temperature_2m", now + 45 * hour), 1e-9)
        assertEquals(20.0, b.h("temperature_2m", now + 60 * hour), 1e-9)
    }

    @Test fun todayAndTomorrowFollowTheirHoursAndDay3OnIsEcmwf() {
        val b = ModelBlend.blend(multi(), now)
        // Today and tomorrow: the strip's own hours (10°; tomorrow's one hole, 1 AM, best_match's 15).
        assertEquals(10.0, b.d("temperature_2m_max", 0), 1e-9)
        assertEquals(10.0, b.d("temperature_2m_min", 0), 1e-9)
        assertEquals(0.0, b.d("precipitation_sum", 0), 1e-9)
        assertEquals(80.0, b.d("precipitation_probability_max", 0), 1e-9)
        assertEquals(15.0, b.d("temperature_2m_max", 1), 1e-9)
        assertEquals(10.0, b.d("temperature_2m_min", 1), 1e-9)
        assertEquals(61.0, b.d("weather_code", 1), 0.0)
        assertEquals(2.0, b.d("uv_index_max", 0), 1e-9)
        // Day 3 on: ECMWF IFS's own days, the hand-over day included.
        for (i in 2..6) {
            assertEquals(21.0, b.d("temperature_2m_max", i), 1e-9)
            assertEquals(19.0, b.d("temperature_2m_min", i), 1e-9)
            assertEquals(60.0, b.d("precipitation_probability_max", i), 1e-9)
            assertEquals(12.0, b.d("precipitation_sum", i), 1e-9)
            assertEquals(3.0, b.d("weather_code", i), 0.0)
            // ECMWF publishes no UV: best_match's.
            assertEquals(2.0, b.d("uv_index_max", i), 1e-9)
        }
        assertEquals(days[4] / 1000 + 7 * 3600, b.getJSONObject("daily").getJSONArray("sunrise").getLong(4))
    }

    @Test fun aDayEcmwfLacksIsBestMatchWhole() {
        val m = multi()
        m.getJSONObject("daily").getJSONArray("temperature_2m_max_ecmwf_ifs").put(5, JSONObject.NULL)
        val b = ModelBlend.blend(m, now)
        assertEquals(16.0, b.d("temperature_2m_max", 5), 1e-9)
        assertEquals(14.0, b.d("temperature_2m_min", 5), 1e-9)
        assertEquals(30.0, b.d("precipitation_probability_max", 5), 1e-9)
        assertEquals(2.0, b.d("weather_code", 5), 0.0)
        assertEquals(21.0, b.d("temperature_2m_max", 4), 1e-9)
    }

    @Test fun eachPlaceGetsItsOwnSplit() {
        // Two places in one session, HRRR's runs ending at different hours: each answer is split on its own.
        val tacoma = ModelBlend.blend(multi(hrrrEndH = 48), now)
        val renton = ModelBlend.blend(multi(hrrrEndH = 43), now)
        assertEquals(10.0, tacoma.h("temperature_2m", now + 42 * hour), 1e-9)
        assertEquals(20.0, renton.h("temperature_2m", now + 43 * hour), 1e-9)
        assertEquals(10.0, ModelBlend.blend(multi(hrrrEndH = 48), now).h("temperature_2m", now + 42 * hour), 1e-9)
    }

    @Test fun theAppReadsTheBlend() {
        val w = OpenMeteo.parseForecast(multi().toString(), "Tacoma", 47.25, -122.44, now)
        assertEquals(11.0, w.temperatureC, 1e-9)
        assertEquals(WeatherCodes.describe(61), w.condition)
        val d = w.detail!!
        assertEquals(7, d.daily.size)
        assertEquals(21.0, d.daily[5].highC, 1e-9)
        assertEquals(19.0, d.daily[2].lowC, 1e-9)
        assertEquals(60, d.daily[5].precipPct)
        val h10 = d.hourly.first { it.time == now + 10 * hour }
        assertEquals(10.0, h10.tempC, 1e-9)
        assertEquals(80, h10.precipProb)
        assertEquals(0.0, h10.precipMm!!, 1e-9)
        assertEquals(20.0, d.hourly.first { it.time == now + 60 * hour }.tempC, 1e-9)
        assertEquals(1.5, d.uvIndex!!, 1e-9)
    }

    @Test fun nowIsHrrrsEvenWhenCurrentIsAnsweredOnce() {
        // As Open-Meteo answers: `current` once (best_match's), the hours per model.
        val m = multi()
        m.put("current", JSONObject().put("time", now / 1000).put("temperature_2m", 12.0).put("weather_code", 2)
            .put("is_day", 1).put("wind_direction_10m", 0).put("apparent_temperature", 9.0).put("uv_index", 1.5))
        val h = m.getJSONObject("hourly")
        val temps = h.getJSONArray("temperature_2m_gfs_hrrr")
        temps.put(at(now), 10.0)
        temps.put(at(now + hour), 14.0)
        val half = now + hour / 2
        val c = ModelBlend.blend(m, half).getJSONObject("current")
        assertEquals("HRRR, half way between its hours", 12.0, ModelBlend.blend(m, half).getJSONObject("current").getDouble("temperature_2m"), 1e-9)
        val c0 = ModelBlend.blend(m, now).getJSONObject("current")
        assertEquals(10.0, c0.getDouble("temperature_2m"), 1e-9)
        assertEquals("HRRR's code", 61, c.getInt("weather_code"))
        assertEquals(350.0, c.getDouble("wind_direction_10m"), 1e-6)
        // Not reported hour by hour: best_match's.
        assertEquals(9.0, c.getDouble("apparent_temperature"), 1e-9)
        assertEquals(1.5, c.getDouble("uv_index"), 1e-9)
        assertEquals(1, c.getInt("is_day"))
        // Where HRRR has no hour (its hole at +5 h), best_match's reading stands.
        assertEquals(12.0, ModelBlend.blend(m, now + 5 * hour).getJSONObject("current").getDouble("temperature_2m"), 1e-9)
    }

    @Test fun aSingleModelAnswerPassesThrough() {
        val one = JSONObject("""{"current":{"temperature_2m":5.0,"weather_code":0},"daily":{"temperature_2m_max":[6.0]}}""")
        assertSame(one, ModelBlend.blend(one, now))
    }

    @Test fun theRequestNamesTheModels() {
        val url = OpenMeteo.forecastUrl(47.25, -122.44)
        assertTrue(url, url.contains("&models=best_match,gfs_hrrr,ecmwf_ifs"))
        assertTrue(!OpenMeteo.forecastUrl(47.25, -122.44, models = false).contains("models="))
    }
}
