package io.github.iamth3doctor.searchlight.weather

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Pure weather model + Open-Meteo client logic (no Android types; JVM-tested).
 *
 * Units rule: we ALWAYS fetch and store Celsius, and convert only when displaying.
 * Flipping °F/°C never refetches and can never leave a stale number on screen.
 */

enum class TempUnit(val symbol: String) { FAHRENHEIT("°F"), CELSIUS("°C") }

object Temperature {
    fun convert(celsius: Double, unit: TempUnit): Int = when (unit) {
        TempUnit.CELSIUS -> celsius.roundToInt()
        TempUnit.FAHRENHEIT -> (celsius * 9.0 / 5.0 + 32.0).roundToInt()
    }

    /** "72°" — the unit letter is shown once on the card, not on every number. */
    fun format(celsius: Double, unit: TempUnit): String = "${convert(celsius, unit)}°"
}

data class WeatherData(
    val locationName: String,
    val latitude: Double,
    val longitude: Double,
    val temperatureC: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val highC: Double,
    val lowC: Double,
    val fetchedAt: Long,
) {
    val condition: String get() = WeatherCodes.describe(weatherCode)
    val emoji: String get() = WeatherCodes.emoji(weatherCode, isDay)

    fun toJson(): String = JSONObject()
        .put("name", locationName).put("lat", latitude).put("lon", longitude)
        .put("t", temperatureC).put("code", weatherCode).put("day", isDay)
        .put("hi", highC).put("lo", lowC).put("at", fetchedAt)
        .toString()

    companion object {
        fun fromJson(s: String): WeatherData? = try {
            val o = JSONObject(s)
            WeatherData(
                o.getString("name"), o.getDouble("lat"), o.getDouble("lon"),
                o.getDouble("t"), o.getInt("code"), o.getBoolean("day"),
                o.getDouble("hi"), o.getDouble("lo"), o.getLong("at"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class GeoPlace(
    val name: String,
    val admin: String?,
    val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
) {
    /** "Austin, Texas, US" */
    val label: String get() = listOfNotNull(name, admin?.takeIf { it != name }, countryCode).joinToString(", ")
}

/** WMO weather interpretation codes, as returned by Open-Meteo's `weather_code`. */
object WeatherCodes {
    fun describe(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51 -> "Light drizzle"
        53 -> "Drizzle"
        55 -> "Heavy drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }

    fun emoji(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }
}

object OpenMeteo {

    /**
     * Coordinates are rounded to 2 decimals (~1 km) before they leave the phone: plenty for a
     * forecast, and Open-Meteo never sees your exact position. No API key, no account.
     */
    fun forecastUrl(latitude: Double, longitude: Double): String = String.format(
        Locale.US,
        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min" +
            "&temperature_unit=celsius&timezone=auto&forecast_days=1",
        latitude, longitude,
    )

    /** City names and postal codes both work ("Austin", "78701", "London"). */
    fun geocodeUrl(query: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(query.trim(), "UTF-8") + "&count=8&language=en&format=json"

    fun parseForecast(json: String, locationName: String, latitude: Double, longitude: Double, now: Long): WeatherData {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val t = current.getDouble("temperature_2m")
        val hi = daily.getJSONArray("temperature_2m_max").optDouble(0, t)
        val lo = daily.getJSONArray("temperature_2m_min").optDouble(0, t)
        return WeatherData(
            locationName = locationName,
            latitude = latitude,
            longitude = longitude,
            temperatureC = t,
            weatherCode = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            // The current reading can poke outside the daily forecast; keep the card consistent.
            highC = maxOf(hi, t),
            lowC = minOf(lo, t),
            fetchedAt = now,
        )
    }

    fun parseGeocode(json: String): List<GeoPlace> {
        val results: JSONArray = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            GeoPlace(
                name = r.getString("name"),
                admin = r.optString("admin1").takeIf { it.isNotBlank() },
                countryCode = r.optString("country_code").takeIf { it.isNotBlank() },
                latitude = r.getDouble("latitude"),
                longitude = r.getDouble("longitude"),
            )
        }
    }
}
