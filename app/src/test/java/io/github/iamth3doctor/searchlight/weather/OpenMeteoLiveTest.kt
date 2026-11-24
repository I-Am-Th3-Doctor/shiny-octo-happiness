package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hits the real Open-Meteo API with the exact URLs the app builds and runs the responses
 * through the app's parsers. Skipped unless OPEN_METEO_LIVE=1 (CI runs it as a non-blocking step).
 */
class OpenMeteoLiveTest {

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        try {
            assertTrue("HTTP ${c.responseCode} for $url", c.responseCode == 200)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    @Test fun forecastAndGeocodingContract() {
        assumeTrue(System.getenv("OPEN_METEO_LIVE") == "1")

        val zip = OpenMeteo.parseGeocode(get(OpenMeteo.geocodeUrl("78701")))
        println("ZIP 78701 -> ${zip.map { it.label }}")
        assertTrue("US ZIP lookup returned nothing", zip.isNotEmpty())

        val city = OpenMeteo.parseGeocode(get(OpenMeteo.geocodeUrl("Chicago")))
        println("Chicago -> ${city.take(3).map { it.label }}")
        assertTrue(city.any { it.countryCode == "US" })

        val place = city.first { it.countryCode == "US" }
        val raw = get(OpenMeteo.forecastUrl(place.latitude, place.longitude))
        // Each model's share of the answer: HRRR for the next 48 hours, ECMWF IFS after, best_match to fill.
        val hourly = org.json.JSONObject(raw).getJSONObject("hourly")
        for (m in ModelBlend.MODELS) {
            val a = hourly.optJSONArray("temperature_2m_$m")
            val n = a?.let { x -> (0 until x.length()).count { !x.isNull(it) } }
            println("Chicago model $m: ${n ?: "absent"} of ${hourly.getJSONArray("time").length()} hours; " +
                "current ${org.json.JSONObject(raw).optJSONObject("current")?.keys()?.asSequence()?.filter { it.endsWith(m) }?.toList()}")
            assertTrue("model $m missing", a != null && n!! > 0)
        }
        val w = OpenMeteo.parseForecast(raw, place.name, place.latitude, place.longitude, System.currentTimeMillis())
        println("Chicago now: ${Temperature.format(w.temperatureC, TempUnit.FAHRENHEIT)}F / ${Temperature.format(w.temperatureC, TempUnit.CELSIUS)}C, ${w.condition}")
        assertTrue(w.temperatureC in -60.0..60.0)
        assertTrue(w.lowC <= w.temperatureC && w.temperatureC <= w.highC)
        assertTrue(w.condition != "Unknown")

        // The Weather widget's detail: hourly, 7 days, sun times, and the readings.
        val d = w.detail
        assertTrue("no detail", d != null)
        d!!
        println("Chicago detail: ${d.hourly.size} hours, ${d.daily.size} days, feels ${d.feelsLikeC}, hum ${d.humidity}, wind ${d.windMph} from ${d.windFromDeg}, vis ${d.visibilityM}, uv ${d.uvIndex}, pres ${d.pressureHpa}")
        assertTrue(d.hourly.size >= 12)
        assertTrue(d.daily.size == 7)
        assertTrue(d.daily[0].sunrise != null && d.daily[0].sunset != null)
        assertTrue(d.feelsLikeC != null && d.humidity != null && d.pressureHpa != null && d.windMph != null)
        val aqi = OpenMeteo.parseAqi(get(OpenMeteo.airQualityUrl(place.latitude, place.longitude)))
        println("Chicago AQI: $aqi")
        assertTrue(aqi != null && aqi in 0..500)

        // The model split, checked against each model on its own (as open-meteo.com shows it with
        // that model picked), for the two places the phone shows.
        for ((name, lat, lon) in listOf(Triple("Tacoma", 47.25, -122.44), Triple("Renton", 47.48, -122.22))) {
            val url = OpenMeteo.forecastUrl(lat, lon)
            val now = System.currentTimeMillis()
            val app = OpenMeteo.parseForecast(get(url), name, lat, lon, now).detail!!
            val only = { m: String -> org.json.JSONObject(get(url.replace(Regex("&models=[^&]*"), "&models=$m"))) }
            val hrrr = only(ModelBlend.SHORT)
            val ecmwf = only(ModelBlend.LONG)

            // Next hours: the app's hours are HRRR's, temperature, rain chance and rain together.
            val hh = hrrr.getJSONObject("hourly")
            val ht = hh.getJSONArray("time")
            var lastHrrr = 0.0
            var checked = 0
            for (i in 0 until ht.length()) {
                val t = ht.getLong(i) * 1000
                val ahead = (t - now) / 3_600_000.0
                if (ahead < 0 || ahead > 48 || hh.getJSONArray("temperature_2m").isNull(i)) continue
                lastHrrr = ahead
            }
            for (i in 0 until ht.length()) {
                val t = ht.getLong(i) * 1000
                val ahead = (t - now) / 3_600_000.0
                if (ahead < 0 || ahead > lastHrrr - ModelBlend.BLEND_H) continue
                val mine = app.hourly.firstOrNull { it.time == t } ?: continue
                val temp = hh.getJSONArray("temperature_2m").getDouble(i)
                val prob = hh.getJSONArray("precipitation_probability").optInt(i, -1)
                val rain = hh.getJSONArray("precipitation").getDouble(i)
                assertTrue("$name +${ahead.toInt()} h: app ${mine.tempC} vs HRRR $temp", kotlin.math.abs(mine.tempC - temp) < 0.05)
                assertTrue("$name +${ahead.toInt()} h: app rain chance ${mine.precipProb} vs HRRR $prob", mine.precipProb == prob)
                assertTrue("$name +${ahead.toInt()} h: app rain ${mine.precipMm} vs HRRR $rain", kotlin.math.abs((mine.precipMm ?: -1.0) - rain) < 0.005)
                checked++
            }
            println("SPLIT $name: $checked hours match HRRR (its run reaches +${lastHrrr.toInt()} h)")
            assertTrue("$name: too few HRRR hours checked ($checked)", checked >= 24)

            // Days 3 to 7: ECMWF IFS's own highs and lows.
            val ed = ecmwf.getJSONObject("daily")
            for (i in 2 until 7) {
                val hi = ed.getJSONArray("temperature_2m_max").getDouble(i)
                val lo = ed.getJSONArray("temperature_2m_min").getDouble(i)
                assertTrue("$name day ${i + 1}: app ${app.daily[i].highC}/${app.daily[i].lowC} vs ECMWF $hi/$lo",
                    kotlin.math.abs(app.daily[i].highC - hi) < 0.05 && kotlin.math.abs(app.daily[i].lowC - lo) < 0.05)
            }
            println("SPLIT $name: days 3-7 match ECMWF IFS " +
                (2 until 7).joinToString { "${Temperature.format(app.daily[it].highC, TempUnit.FAHRENHEIT)}/${Temperature.format(app.daily[it].lowC, TempUnit.FAHRENHEIT)}" })

            // The hand-over: hour to hour, no step bigger than the weather's own across 36-54 h.
            val span = app.hourly.filter { val a = (it.time - now) / 3_600_000.0; a in 36.0..54.0 }
            val steps = span.zipWithNext { a, b -> kotlin.math.abs(b.tempC - a.tempC) * 9 / 5 }
            val probSteps = span.zipWithNext { a, b -> kotlin.math.abs((b.precipProb ?: 0) - (a.precipProb ?: 0)) }
            println("SPLIT $name: hand-over steps ${steps.map { "%.1f".format(it) }} °F, rain chance ${probSteps}")
            assertTrue("$name: a ${steps.maxOrNull()} °F jump at the hand-over", (steps.maxOrNull() ?: 0.0) <= 5.0)
        }

        // National Weather Service alerts for Tacoma: any number (often none), parsed.
        val c = URL(NwsAlerts.url(47.25, -122.44)).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Searchlight-Launcher")
        c.setRequestProperty("Accept", "application/geo+json")
        try {
            assertTrue("NWS HTTP ${c.responseCode}", c.responseCode == 200)
            val alerts = NwsAlerts.parse(c.inputStream.bufferedReader().use { it.readText() }, System.currentTimeMillis())
            println("Tacoma alerts: ${alerts.map { it.event }}")
        } finally {
            c.disconnect()
        }
    }
}
