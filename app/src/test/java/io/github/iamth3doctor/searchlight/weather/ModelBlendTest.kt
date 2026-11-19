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
     * Three models as Open-Meteo names them: HRRR 10 °C until 48 h out (then nothing; one hole at
     * +5 h), ECMWF 20 °C, best_match 15 °C. HRRR gives no rain chance or UV; only best_match has
     * visibility.
     */
    private fun multi(): JSONObject {
        val hrrrT = times.map { t -> if (t > now + 48 * hour || t == now + 5 * hour) null else 10.0 }
        val hourly = JSONObject().put("time", arr(times.map { it / 1000 }))
            .put("temperature_2m_gfs_hrrr", arr(hrrrT))
            .put("temperature_2m_ecmwf_ifs", arr(times.map { 20.0 }))
            .put("temperature_2m_best_match", arr(times.map { 15.0 }))
            .put("precipitation_probability_gfs_hrrr", arr(times.map { null }))
            .put("precipitation_probability_ecmwf_ifs", arr(times.map { 60 }))
            .put("precipitation_probability_best_match", arr(times.map { 30 }))
            .put("weather_code_gfs_hrrr", arr(times.map { 61 }))
            .put("weather_code_ecmwf_ifs", arr(times.map { 3 }))
            .put("weather_code_best_match", arr(times.map { 2 }))
            .put("wind_direction_10m_gfs_hrrr", arr(times.map { 350 }))
            .put("wind_direction_10m_ecmwf_ifs", arr(times.map { 10 }))
            .put("wind_direction_10m_best_match", arr(times.map { 0 }))
            .put("precipitation_gfs_hrrr", arr(times.map { 0.0 }))
            .put("precipitation_ecmwf_ifs", arr(times.map { 0.5 }))
            .put("precipitation_best_match", arr(times.map { 0.2 }))
        val daily = JSONObject().put("time", arr(days.map { it / 1000 }))
            .put("temperature_2m_max_gfs_hrrr", arr(days.map { 99.0 }))
            .put("temperature_2m_max_ecmwf_ifs", arr(days.map { 99.0 }))
            .put("temperature_2m_max_best_match", arr(days.map { 99.0 }))
            .put("temperature_2m_min_ecmwf_ifs", arr(days.map { -99.0 }))
            .put("temperature_2m_min_best_match", arr(days.map { -99.0 }))
            .put("weather_code_gfs_hrrr", arr(days.map { 61 }))
            .put("weather_code_ecmwf_ifs", arr(days.map { 3 }))
            .put("weather_code_best_match", arr(days.map { 2 }))
            .put("precipitation_probability_max_ecmwf_ifs", arr(days.map { 60 }))
            .put("precipitation_probability_max_best_match", arr(days.map { 30 }))
            .put("precipitation_sum_best_match", arr(days.map { 0.0 }))
            .put("uv_index_max_ecmwf_ifs", arr(days.map { 4.0 }))
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
        assertEquals(10.0, b.h("temperature_2m", now + 10 * hour), 1e-9)
        assertEquals(10.0, b.h("temperature_2m", now - 6 * hour), 1e-9)
        assertEquals(61.0, b.h("weather_code", now + 10 * hour), 0.0)
        assertEquals(350.0, b.h("wind_direction_10m", now + 10 * hour), 1e-9)
        assertEquals(20.0, b.h("temperature_2m", now + 60 * hour), 1e-9)
        assertEquals(20.0, b.h("temperature_2m", now + 48 * hour), 1e-9)
        assertEquals(3.0, b.h("weather_code", now + 60 * hour), 0.0)
        assertEquals(60.0, b.h("precipitation_probability", now + 60 * hour), 1e-9)
    }

    @Test fun theHandOverIsGradual() {
        val b = ModelBlend.blend(multi(), now)
        // Half way through the cross-fade: half of each.
        assertEquals(15.0, b.h("temperature_2m", now + 44 * hour), 1e-9)
        assertEquals(45.0, b.h("precipitation_probability", now + 44 * hour), 1e-9)
        // 350° and 10° meet at north, not south.
        val dir = b.h("wind_direction_10m", now + 44 * hour)
        assertTrue("$dir", min(dir, 360 - dir) < 1e-6)
        // No jump anywhere between the models (the one HRRR hole falls back to best_match, 5° off).
        val temps = b.getJSONObject("hourly").getJSONArray("temperature_2m")
        val steps = (1 until temps.length()).map { abs(temps.getDouble(it) - temps.getDouble(it - 1)) }
        val fade = (1 until temps.length()).filter { times[it] > now + 40 * hour && times[it] <= now + 48 * hour }
        for (i in fade) assertTrue("step ${steps[i - 1]} at ${i}", steps[i - 1] <= 10.0 / 8 + 1e-9)
        assertEquals(10.0 / 8, fade.maxOf { steps[it - 1] }, 1e-9)
    }

    @Test fun aMissingValueComesFromBestMatchAlone() {
        val b = ModelBlend.blend(multi(), now)
        // HRRR's hole: best_match's 15, not ECMWF's 20.
        assertEquals(15.0, b.h("temperature_2m", now + 5 * hour), 1e-9)
        // HRRR gives no rain chance: best_match's, for that value only (the temperature is still HRRR's).
        assertEquals(30.0, b.h("precipitation_probability", now + 10 * hour), 1e-9)
        val c = b.getJSONObject("current")
        assertEquals(11.0, c.getDouble("temperature_2m"), 1e-9)
        assertEquals(61, c.getInt("weather_code"))
        assertEquals(1.5, c.getDouble("uv_index"), 1e-9)
        assertEquals(24000.0, c.getDouble("visibility"), 1e-9)
    }

    @Test fun daysFollowTheirHours() {
        val b = ModelBlend.blend(multi(), now)
        // Today and tomorrow: HRRR's hours (10°; tomorrow's one hole, 1 AM, at best_match's 15).
        assertEquals(10.0, b.d("temperature_2m_max", 0), 1e-9)
        assertEquals(10.0, b.d("temperature_2m_min", 0), 1e-9)
        assertEquals(0.0, b.d("precipitation_sum", 0), 1e-9)
        assertEquals(15.0, b.d("temperature_2m_max", 1), 1e-9)
        assertEquals(10.0, b.d("temperature_2m_min", 1), 1e-9)
        assertEquals(61.0, b.d("weather_code", 1), 0.0)
        assertEquals(2.0, b.d("uv_index_max", 0), 1e-9)
        // The hand-over day spans both.
        assertEquals(20.0, b.d("temperature_2m_max", 2), 1e-9)
        assertEquals(10.0, b.d("temperature_2m_min", 2), 1e-9)
        // Days 3 to 7: ECMWF.
        for (i in 3..6) {
            assertEquals(20.0, b.d("temperature_2m_max", i), 1e-9)
            assertEquals(20.0, b.d("temperature_2m_min", i), 1e-9)
            assertEquals(60.0, b.d("precipitation_probability_max", i), 1e-9)
            assertEquals(12.0, b.d("precipitation_sum", i), 1e-9)
            assertEquals(3.0, b.d("weather_code", i), 0.0)
            assertEquals(4.0, b.d("uv_index_max", i), 1e-9)
        }
        assertEquals(days[4] / 1000 + 7 * 3600, b.getJSONObject("daily").getJSONArray("sunrise").getLong(4))
    }

    @Test fun theAppReadsTheBlend() {
        val w = OpenMeteo.parseForecast(multi().toString(), "Tacoma", 47.25, -122.44, now)
        assertEquals(11.0, w.temperatureC, 1e-9)
        assertEquals(WeatherCodes.describe(61), w.condition)
        val d = w.detail!!
        assertEquals(7, d.daily.size)
        assertEquals(20.0, d.daily[5].highC, 1e-9)
        assertEquals(60, d.daily[5].precipPct)
        assertEquals(10.0, d.hourly.first { it.time == now + 10 * hour }.tempC, 1e-9)
        assertEquals(20.0, d.hourly.first { it.time == now + 60 * hour }.tempC, 1e-9)
        assertEquals(30, d.hourly.first { it.time == now + 10 * hour }.precipProb)
        assertEquals(1.5, d.uvIndex!!, 1e-9)
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
