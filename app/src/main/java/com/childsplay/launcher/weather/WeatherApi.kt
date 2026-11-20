package com.childsplay.launcher.weather

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Talks to Open-Meteo (no key) and the NWS alerts API, and merges models:
 *  - HRRR (gfs_hrrr) for the hourly strip, current conditions and days 1–2 (hours 0–48)
 *  - ECMWF IFS (ecmwf_ifs025) for days 3–7, and as a fallback anywhere HRRR has no data
 *    (HRRR only covers the continental US).
 * The parse/merge functions are pure so they can be unit-tested with canned JSON.
 */
object WeatherApi {
    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private const val HOURLY = "temperature_2m,apparent_temperature,weather_code,precipitation,wind_speed_10m,is_day,relative_humidity_2m"
    private const val CURRENT = "temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m,relative_humidity_2m"
    private const val DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,sunrise,sunset"
    const val USER_AGENT = "ChildsPlayLauncher/0.1 (Android; hero-weather)"

    private fun coord(v: Double) = String.format(Locale.US, "%.4f", v)

    fun hrrrUrl(lat: Double, lon: Double) =
        "$BASE?latitude=${coord(lat)}&longitude=${coord(lon)}&models=gfs_hrrr" +
            "&current=$CURRENT&hourly=$HOURLY&past_hours=24&forecast_hours=48&timezone=auto"

    fun ecmwfUrl(lat: Double, lon: Double, model: String = "ecmwf_ifs025") =
        "$BASE?latitude=${coord(lat)}&longitude=${coord(lon)}&models=$model" +
            "&current=$CURRENT&hourly=$HOURLY&daily=$DAILY&forecast_days=8&timezone=auto"

    fun alertsUrl(lat: Double, lon: Double) =
        "https://api.weather.gov/alerts/active?point=${coord(lat)},${coord(lon)}"

    fun geocodeUrl(query: String) =
        "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name=" +
            URLEncoder.encode(query, "UTF-8")

    // ---- Networking ----------------------------------------------------------------------

    @Throws(IOException::class)
    fun get(url: String, accept: String = "application/json"): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", accept)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}: ${body.take(200)}")
            return body
        } finally {
            conn.disconnect()
        }
    }

    /** Fetch everything for a place and merge. Throws only if no forecast at all could be had. */
    fun fetch(place: Place, now: Long = System.currentTimeMillis()): Forecast {
        val hrrr = runCatching { JSONObject(get(hrrrUrl(place.lat, place.lon))) }.getOrNull()
            ?.takeUnless { it.optBoolean("error") }
        val ecmwf = runCatching { JSONObject(get(ecmwfUrl(place.lat, place.lon))) }.getOrNull()
            ?.takeUnless { it.optBoolean("error") }
            // If the ECMWF model id is ever unavailable, fall back to Open-Meteo's best match.
            ?: runCatching { JSONObject(get(ecmwfUrl(place.lat, place.lon, "best_match"))) }.getOrNull()
                ?.takeUnless { it.optBoolean("error") }
        val alerts = runCatching { parseAlerts(get(alertsUrl(place.lat, place.lon), "application/geo+json")) }
            .getOrDefault(emptyList())
        return merge(place, hrrr, ecmwf, alerts, now)
            ?: throw IOException("No forecast available for ${place.name}")
    }

    fun searchPlaces(query: String): List<Place> {
        if (query.isBlank()) return emptyList()
        val o = JSONObject(get(geocodeUrl(query.trim())))
        val arr = o.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val parts = listOf(r.optString("name"), r.optString("admin1"), r.optString("country_code"))
                .filter { it.isNotBlank() && it != "null" }
            Place(parts.joinToString(", "), r.optDouble("latitude"), r.optDouble("longitude"))
        }
    }

    // ---- Parsing -------------------------------------------------------------------------

    private class RawHour(
        val time: LocalDateTime,
        val temp: Double?,
        val feels: Double?,
        val code: Int?,
        val precip: Double?,
        val wind: Double?,
        val isDay: Boolean?,
    ) {
        val usable get() = temp != null && code != null
    }

    private fun JSONArray?.dbl(i: Int): Double? =
        if (this == null || i >= length() || isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }

    private fun JSONObject.dbl(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    private fun parseHours(o: JSONObject?): List<RawHour> {
        val h = o?.optJSONObject("hourly") ?: return emptyList()
        val times = h.optJSONArray("time") ?: return emptyList()
        val t = h.optJSONArray("temperature_2m")
        val f = h.optJSONArray("apparent_temperature")
        val c = h.optJSONArray("weather_code")
        val p = h.optJSONArray("precipitation")
        val w = h.optJSONArray("wind_speed_10m")
        val d = h.optJSONArray("is_day")
        return (0 until times.length()).mapNotNull { i ->
            val time = runCatching { LocalDateTime.parse(times.getString(i)) }.getOrNull() ?: return@mapNotNull null
            RawHour(time, t.dbl(i), f.dbl(i), c.dbl(i)?.toInt(), p.dbl(i), w.dbl(i), d.dbl(i)?.let { it >= 0.5 })
        }
    }

    private fun parseCurrent(o: JSONObject?, source: Source): Current? {
        val c = o?.optJSONObject("current") ?: return null
        val temp = c.dbl("temperature_2m") ?: return null
        val code = c.dbl("weather_code")?.toInt() ?: return null
        return Current(
            tempC = temp,
            feelsC = c.dbl("apparent_temperature"),
            code = code,
            isDay = (c.dbl("is_day") ?: 1.0) >= 0.5,
            windKmh = c.dbl("wind_speed_10m"),
            humidity = c.dbl("relative_humidity_2m"),
            source = source,
        )
    }

    private class RawDay(
        val date: LocalDate,
        val hi: Double?,
        val lo: Double?,
        val code: Int?,
        val precip: Double?,
        val sunrise: LocalDateTime?,
        val sunset: LocalDateTime?,
    )

    private fun parseDays(o: JSONObject?): Map<LocalDate, RawDay> {
        val d = o?.optJSONObject("daily") ?: return emptyMap()
        val times = d.optJSONArray("time") ?: return emptyMap()
        val hi = d.optJSONArray("temperature_2m_max")
        val lo = d.optJSONArray("temperature_2m_min")
        val code = d.optJSONArray("weather_code")
        val pr = d.optJSONArray("precipitation_sum")
        val sr = d.optJSONArray("sunrise")
        val ss = d.optJSONArray("sunset")
        fun dt(a: JSONArray?, i: Int) =
            if (a == null || i >= a.length() || a.isNull(i)) null else runCatching { LocalDateTime.parse(a.getString(i)) }.getOrNull()
        return (0 until times.length()).mapNotNull { i ->
            val date = runCatching { LocalDate.parse(times.getString(i)) }.getOrNull() ?: return@mapNotNull null
            date to RawDay(date, hi.dbl(i), lo.dbl(i), code.dbl(i)?.toInt(), pr.dbl(i), dt(sr, i), dt(ss, i))
        }.toMap()
    }

    fun parseAlerts(body: String): List<Alert> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val p = features.optJSONObject(i)?.optJSONObject("properties") ?: return@mapNotNull null
            if (p.optString("status") != "Actual") return@mapNotNull null
            if (p.optString("messageType") == "Cancel") return@mapNotNull null
            fun s(k: String) = if (p.isNull(k)) "" else p.optString(k)
            Alert(
                event = s("event").ifEmpty { "Weather Alert" },
                severity = s("severity"),
                headline = s("headline"),
                description = s("description"),
                instruction = s("instruction"),
                ends = s("ends").ifEmpty { s("expires") }.ifEmpty { null },
            )
        }.distinctBy { it.event to it.ends }.sortedByDescending { it.rank }
    }

    // ---- Merge ---------------------------------------------------------------------------

    /**
     * Merge HRRR and ECMWF responses. Either may be null. Returns null when neither has data.
     */
    fun merge(place: Place, hrrr: JSONObject?, ecmwf: JSONObject?, alerts: List<Alert>, nowMillis: Long): Forecast? {
        val offset = (hrrr ?: ecmwf)?.optInt("utc_offset_seconds", 0) ?: return null
        val localNow = LocalDateTime.ofEpochSecond(nowMillis / 1000, 0, ZoneOffset.ofTotalSeconds(offset))
            .truncatedTo(ChronoUnit.HOURS)
        val today = localNow.toLocalDate()

        val hrrrHours = parseHours(hrrr).filter { it.usable }.associateBy { it.time }
        val ecHours = parseHours(ecmwf).filter { it.usable }.associateBy { it.time }
        val ecDays = parseDays(ecmwf)

        fun point(r: RawHour, src: Source) = HourPoint(
            time = r.time, tempC = r.temp!!, feelsC = r.feels, code = r.code!!, precipMm = r.precip,
            precipProb = null, windKmh = r.wind, isDay = r.isDay ?: true, source = src,
        )

        // Hours 0..47 from now: HRRR first, ECMWF to fill any holes.
        val hours = (0 until 48).mapNotNull { i ->
            val t = localNow.plusHours(i.toLong())
            hrrrHours[t]?.let { point(it, Source.HRRR) } ?: ecHours[t]?.let { point(it, Source.ECMWF) }
        }

        val current = parseCurrent(hrrr, Source.HRRR)
            ?: parseCurrent(ecmwf, Source.ECMWF)
            ?: hours.firstOrNull()?.let { Current(it.tempC, it.feelsC, it.code, it.isDay, it.windKmh, null, it.source) }
            ?: return null

        fun aggregate(date: LocalDate, from: Map<LocalDateTime, RawHour>): List<RawHour> =
            from.values.filter { it.time.toLocalDate() == date }

        val days = (0 until 7).mapNotNull { d ->
            val date = today.plusDays(d.toLong())
            val ec = ecDays[date]
            val hr = aggregate(date, hrrrHours)
            // Days 1–2 (today, tomorrow) sit inside HRRR's 0–48 h window; use it when it covers the day.
            if (d <= 1 && hr.size >= 20) {
                val daytime = hr.filter { it.time.hour in 7..20 }.ifEmpty { hr }
                DayPoint(
                    date = date,
                    hiC = hr.maxOf { it.temp!! },
                    loC = hr.minOf { it.temp!! },
                    code = daytime.maxBy { WeatherCodes.severity(it.code!!) }.code!!,
                    precipMm = hr.mapNotNull { it.precip }.takeIf { it.isNotEmpty() }?.sum(),
                    precipProb = null,
                    sunrise = ec?.sunrise,
                    sunset = ec?.sunset,
                    source = Source.HRRR,
                )
            } else if (ec?.hi != null && ec.lo != null && ec.code != null) {
                DayPoint(date, ec.hi, ec.lo, ec.code, ec.precip, null, ec.sunrise, ec.sunset, Source.ECMWF)
            } else {
                // Last resort: build the day from whatever hours ECMWF gave.
                val eh = aggregate(date, ecHours)
                if (eh.size < 12) null
                else DayPoint(
                    date, eh.maxOf { it.temp!! }, eh.minOf { it.temp!! },
                    eh.maxBy { WeatherCodes.severity(it.code!!) }.code!!,
                    eh.mapNotNull { it.precip }.sum(), null, null, null, Source.ECMWF,
                )
            }
        }

        return Forecast(place, current, hours, days, alerts, offset, nowMillis)
    }
}
