package io.github.iamth3doctor.searchlight.weather

import org.json.JSONArray
import org.json.JSONObject

/*
 * The fuller forecast behind the Weather widget and its detail view: comes back in the same
 * Open-Meteo request as the Weather tile's reading (plus Open-Meteo's free air-quality endpoint
 * for the AQI), and is cached with it, so offline the last good data still shows. Pure (no
 * Android types); JVM-tested. Temperatures stay Celsius, like the rest of WeatherData.
 */

/** One hour: [time] in epoch ms; what drives the scene at that hour (null: not reported). */
data class HourPoint(
    val time: Long,
    val tempC: Double,
    val code: Int,
    val isDay: Boolean,
    val cloudPct: Int? = null,
    val precipMm: Double? = null,
    val precipProb: Int? = null,
    val windMph: Double? = null,
    val windFromDeg: Double? = null,
)

/** One day: [date] is its local midnight in epoch ms; sun times in epoch ms. */
data class DayPoint(
    val date: Long,
    val highC: Double,
    val lowC: Double,
    val code: Int,
    val precipPct: Int?,
    val sunrise: Long?,
    val sunset: Long?,
    /** The day's total precipitation (mm). */
    val precipMm: Double? = null,
    /** The day's highest UV index. */
    val uvMax: Double? = null,
)

data class WeatherDetail(
    /** The place's offset from UTC (seconds): its times read as local there, wherever the phone is. */
    val utcOffsetSec: Int,
    val feelsLikeC: Double?,
    val humidity: Int?,
    val dewPointC: Double?,
    val pressureHpa: Double?,
    val visibilityM: Double?,
    val windMph: Double?,
    val windFromDeg: Double?,
    val uvIndex: Double?,
    val hourly: List<HourPoint>,
    val daily: List<DayPoint>,
    /** US AQI; null when the air-quality call didn't answer. */
    val aqi: Int?,
    /** Cloud cover now (%), and the precipitation now (mm in the last 15 min to hour, as reported). */
    val cloudPct: Int? = null,
    val precipMm: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("off", utcOffsetSec)
        .putOpt("feel", feelsLikeC).putOpt("hum", humidity).putOpt("dew", dewPointC).putOpt("pres", pressureHpa)
        .putOpt("vis", visibilityM).putOpt("wind", windMph).putOpt("wdir", windFromDeg).putOpt("uv", uvIndex).putOpt("aqi", aqi)
        .putOpt("cc", cloudPct).putOpt("pr", precipMm)
        .put("hours", JSONArray().apply {
            hourly.forEach {
                put(JSONObject().put("t", it.time).put("c", it.tempC).put("k", it.code).put("d", it.isDay)
                    .putOpt("cc", it.cloudPct).putOpt("pr", it.precipMm).putOpt("pp", it.precipProb)
                    .putOpt("ws", it.windMph).putOpt("wd", it.windFromDeg))
            }
        })
        .put("days", JSONArray().apply {
            daily.forEach {
                put(JSONObject().put("t", it.date).put("hi", it.highC).put("lo", it.lowC).put("k", it.code)
                    .putOpt("p", it.precipPct).putOpt("rise", it.sunrise).putOpt("set", it.sunset).putOpt("pm", it.precipMm).putOpt("uv", it.uvMax))
            }
        })

    companion object {
        private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
        private fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
        private fun JSONObject.lng(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null

        fun fromJson(o: JSONObject): WeatherDetail = WeatherDetail(
            utcOffsetSec = o.optInt("off"),
            feelsLikeC = o.dbl("feel"), humidity = o.int("hum"), dewPointC = o.dbl("dew"), pressureHpa = o.dbl("pres"),
            visibilityM = o.dbl("vis"), windMph = o.dbl("wind"), windFromDeg = o.dbl("wdir"), uvIndex = o.dbl("uv"),
            hourly = o.optJSONArray("hours")?.let { a ->
                (0 until a.length()).map { i ->
                    a.getJSONObject(i).let {
                        HourPoint(it.getLong("t"), it.getDouble("c"), it.getInt("k"), it.getBoolean("d"),
                            it.int("cc"), it.dbl("pr"), it.int("pp"), it.dbl("ws"), it.dbl("wd"))
                    }
                }
            }.orEmpty(),
            daily = o.optJSONArray("days")?.let { a ->
                (0 until a.length()).map { i ->
                    a.getJSONObject(i).let { DayPoint(it.getLong("t"), it.getDouble("hi"), it.getDouble("lo"), it.getInt("k"), it.int("p"), it.lng("rise"), it.lng("set"), it.dbl("pm"), it.dbl("uv")) }
                }
            }.orEmpty(),
            aqi = o.int("aqi"),
            cloudPct = o.int("cc"),
            precipMm = o.dbl("pr"),
        )

        /**
         * The detail from an Open-Meteo forecast asked for with [OpenMeteo.forecastUrl]
         * (`timeformat=unixtime`, wind in mph). Hours from [KEEP_BEFORE_MS] before now to [KEEP_AFTER_MS] after
         * (the day bar plays a few hours back; the 7-day view previews any day hour by hour).
         */
        fun parse(root: JSONObject, nowMs: Long): WeatherDetail {
            val current = root.optJSONObject("current") ?: JSONObject()
            val hourlyJ = root.optJSONObject("hourly")
            val dailyJ = root.optJSONObject("daily")
            fun arr(o: JSONObject?, k: String): JSONArray? = o?.optJSONArray(k)
            fun dAt(a: JSONArray?, i: Int): Double? = a?.takeIf { i < it.length() && !it.isNull(i) }?.optDouble(i)?.takeIf { !it.isNaN() }
            fun lAt(a: JSONArray?, i: Int): Long? = a?.takeIf { i < it.length() && !it.isNull(i) }?.optLong(i)

            val hours = ArrayList<HourPoint>()
            val ht = arr(hourlyJ, "time")
            if (ht != null) for (i in 0 until ht.length()) {
                val t = ht.getLong(i) * 1000L
                if (t < nowMs - KEEP_BEFORE_MS || t > nowMs + KEEP_AFTER_MS) continue
                val c = dAt(arr(hourlyJ, "temperature_2m"), i) ?: continue
                hours += HourPoint(
                    t, c, arr(hourlyJ, "weather_code")?.optInt(i) ?: 0, (arr(hourlyJ, "is_day")?.optInt(i, 1) ?: 1) == 1,
                    cloudPct = dAt(arr(hourlyJ, "cloud_cover"), i)?.toInt(),
                    precipMm = dAt(arr(hourlyJ, "precipitation"), i),
                    precipProb = dAt(arr(hourlyJ, "precipitation_probability"), i)?.toInt(),
                    windMph = dAt(arr(hourlyJ, "wind_speed_10m"), i),
                    windFromDeg = dAt(arr(hourlyJ, "wind_direction_10m"), i),
                )
            }
            val days = ArrayList<DayPoint>()
            val dt = arr(dailyJ, "time")
            if (dt != null) for (i in 0 until dt.length()) {
                val hi = dAt(arr(dailyJ, "temperature_2m_max"), i) ?: continue
                val lo = dAt(arr(dailyJ, "temperature_2m_min"), i) ?: continue
                days += DayPoint(
                    date = dt.getLong(i) * 1000L,
                    highC = hi,
                    lowC = lo,
                    code = arr(dailyJ, "weather_code")?.optInt(i) ?: 0,
                    precipPct = dAt(arr(dailyJ, "precipitation_probability_max"), i)?.toInt(),
                    sunrise = lAt(arr(dailyJ, "sunrise"), i)?.times(1000L),
                    sunset = lAt(arr(dailyJ, "sunset"), i)?.times(1000L),
                    precipMm = dAt(arr(dailyJ, "precipitation_sum"), i),
                    uvMax = dAt(arr(dailyJ, "uv_index_max"), i),
                )
            }
            fun cur(k: String): Double? = if (current.has(k) && !current.isNull(k)) current.optDouble(k).takeIf { !it.isNaN() } else null
            return WeatherDetail(
                utcOffsetSec = root.optInt("utc_offset_seconds"),
                feelsLikeC = cur("apparent_temperature"),
                humidity = cur("relative_humidity_2m")?.toInt(),
                dewPointC = cur("dew_point_2m"),
                pressureHpa = cur("pressure_msl"),
                visibilityM = cur("visibility"),
                windMph = cur("wind_speed_10m"),
                windFromDeg = cur("wind_direction_10m"),
                uvIndex = cur("uv_index"),
                hourly = hours,
                daily = days,
                aqi = null,
                cloudPct = cur("cloud_cover")?.toInt(),
                precipMm = cur("precipitation"),
            )
        }

        const val KEEP_BEFORE_MS = 4 * 3_600_000L
        const val KEEP_AFTER_MS = 7 * 24 * 3_600_000L
    }
}

/** What each condition looks like on the Weather widget: from the same WMO code the Weather tile uses. */
enum class ConditionVisual {
    CLEAR_DAY, CLEAR_NIGHT, PARTLY_DAY, PARTLY_NIGHT, CLOUDY, RAIN, THUNDER, SNOW, FOG;

    companion object {
        /**
         * The Weather tile's emoji, as a drawn visual: 0 clear; 1-2 a little cloud (the tile's
         * 🌤️/⛅); 3 cloud; 45/48 fog; drizzle, rain, showers rain; snow; thunder.
         */
        fun of(code: Int, isDay: Boolean): ConditionVisual = when (code) {
            0 -> if (isDay) CLEAR_DAY else CLEAR_NIGHT
            1, 2 -> if (isDay) PARTLY_DAY else PARTLY_NIGHT
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> THUNDER
            else -> CLOUDY
        }
    }
}

/** How the widget and detail view word numbers: °F, mph, miles, inHg, and the ratings. Pure; JVM-tested. */
object WeatherText {

    fun f(celsius: Double): Int = Temperature.convert(celsius, TempUnit.FAHRENHEIT)

    fun miles(meters: Double): Double = meters / 1609.344

    /** "10 mi" ("0.4 mi" under a mile). */
    fun visibility(meters: Double): String {
        val mi = miles(meters)
        return if (mi < 1) String.format(java.util.Locale.US, "%.1f mi", mi) else "${Math.round(mi)} mi"
    }

    fun visibilityRating(meters: Double): String {
        val mi = miles(meters)
        return when {
            mi >= 5 -> "Good"
            mi >= 2 -> "Moderate"
            else -> "Poor"
        }
    }

    /** "30.02 inHg" from hPa. */
    fun pressure(hpa: Double): String = String.format(java.util.Locale.US, "%.2f inHg", hpa * 0.0295299830714)

    fun uvRating(uv: Double): String = when {
        uv < 3 -> "Low"
        uv < 6 -> "Moderate"
        uv < 8 -> "High"
        uv < 11 -> "Very high"
        else -> "Extreme"
    }

    /** The US AQI's own bands. */
    fun aqiRating(aqi: Int): String = when {
        aqi <= 50 -> "Good"
        aqi <= 100 -> "Moderate"
        aqi <= 150 -> "Unhealthy for sensitive groups"
        aqi <= 200 -> "Unhealthy"
        aqi <= 300 -> "Very unhealthy"
        else -> "Hazardous"
    }

    private val POINTS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    /** The 16-point compass name of the direction the wind blows from. */
    fun compass(deg: Double): String = POINTS[(((deg % 360 + 360) % 360) / 22.5 + 0.5).toInt() % 16]

    /** "4 mph from NE"; "Calm" under 1 mph. */
    fun wind(mph: Double, fromDeg: Double?): String {
        val n = Math.round(mph)
        if (n < 1) return "Calm"
        return if (fromDeg == null) "$n mph" else "$n mph from ${compass(fromDeg)}"
    }

    /** cold / cool / mild / warm / hot, by the day's high (°F). */
    fun feel(highF: Int): String = when {
        highF < 40 -> "cold"
        highF < 58 -> "cool"
        highF < 72 -> "mild"
        highF < 86 -> "warm"
        else -> "hot"
    }

    /**
     * One plain sentence or two about today: how the evening turns (when it differs from the
     * day), then how it feels with the high. "Conditions will turn clear sky at night. It will
     * be cool during the day with highs around 52°."
     */
    fun daySummary(highC: Double, dayCode: Int, nightCode: Int?): String {
        val hi = f(highC)
        val day = WeatherCodes.describe(dayCode)
        val first = if (nightCode != null && WeatherCodes.describe(nightCode) != day) {
            "Conditions will turn ${WeatherCodes.describe(nightCode).lowercase()} at night."
        } else {
            "${day.replaceFirstChar { it.uppercase() }} through the day."
        }
        return "$first It will be ${feel(hi)} during the day with highs around $hi°."
    }

    /**
     * Today's day and night conditions from the hours ahead (local [utcOffsetSec]): the most
     * frequent code from 9:00 to 18:00 and from 18:00 to midnight; the current one when the day
     * part's already gone.
     */
    fun dayAndNight(hours: List<HourPoint>, currentCode: Int, nowMs: Long, utcOffsetSec: Int): Pair<Int, Int?> {
        val off = utcOffsetSec * 1000L
        val today = Math.floorDiv(nowMs + off, 86_400_000L)
        fun hourOf(t: Long) = Math.floorMod(t + off, 86_400_000L) / 3_600_000L
        val todays = hours.filter { Math.floorDiv(it.time + off, 86_400_000L) == today }
        fun mode(l: List<HourPoint>) = l.groupingBy { it.code }.eachCount().maxByOrNull { it.value }?.key
        val day = mode(todays.filter { hourOf(it.time) in 9 until 18 }) ?: currentCode
        val night = mode(todays.filter { hourOf(it.time) >= 18 })
        return day to night
    }
}
