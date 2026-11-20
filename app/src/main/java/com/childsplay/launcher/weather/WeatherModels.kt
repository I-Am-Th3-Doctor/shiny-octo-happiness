package com.childsplay.launcher.weather

import java.time.LocalDate
import java.time.LocalDateTime

/** Which model a number came from, so the UI can be honest about it. */
enum class Source(val label: String) { HRRR("HRRR"), ECMWF("ECMWF") }

/** The scene the living backdrop paints. */
enum class Condition(val label: String) {
    CLEAR("Clear"),
    PARTLY("Partly Cloudy"),
    CLOUDY("Cloudy"),
    FOG("Foggy"),
    DRIZZLE("Drizzle"),
    RAIN("Rain"),
    SLEET("Freezing Rain"),
    SNOW("Snow"),
    STORM("Thunderstorms"),
}

data class Place(val name: String, val lat: Double, val lon: Double)

data class Current(
    val tempC: Double,
    val feelsC: Double?,
    val code: Int,
    val isDay: Boolean,
    val windKmh: Double?,
    val humidity: Double?,
    val source: Source,
)

data class HourPoint(
    val time: LocalDateTime,
    val tempC: Double,
    val feelsC: Double?,
    val code: Int,
    val precipMm: Double?,
    val precipProb: Double?,
    val windKmh: Double?,
    val isDay: Boolean,
    val source: Source,
)

data class DayPoint(
    val date: LocalDate,
    val hiC: Double,
    val loC: Double,
    val code: Int,
    val precipMm: Double?,
    val precipProb: Double?,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    val source: Source,
)

data class Alert(
    val event: String,
    val severity: String,
    val headline: String,
    val description: String,
    val instruction: String,
    val ends: String?,
) {
    val rank: Int
        get() = when (severity.lowercase()) {
            "extreme" -> 4
            "severe" -> 3
            "moderate" -> 2
            "minor" -> 1
            else -> 0
        }
    val isSevere get() = rank >= 3
}

data class Forecast(
    val place: Place,
    val current: Current,
    /** Hours from now on, up to 48. */
    val hours: List<HourPoint>,
    /** Seven days starting today. */
    val days: List<DayPoint>,
    val alerts: List<Alert>,
    /** Location's offset from UTC, so "now" can be computed at the place, not the phone. */
    val utcOffsetSeconds: Int,
    val fetchedAt: Long,
) {
    val today: DayPoint? get() = days.firstOrNull()
    val condition: Condition get() = WeatherCodes.condition(current.code)
    val topAlert: Alert? get() = alerts.maxByOrNull { it.rank }
}

object WeatherCodes {
    /** WMO weather interpretation codes, as used by Open-Meteo. */
    fun condition(code: Int): Condition = when (code) {
        0 -> Condition.CLEAR
        1, 2 -> Condition.PARTLY
        3 -> Condition.CLOUDY
        45, 48 -> Condition.FOG
        51, 53, 55 -> Condition.DRIZZLE
        56, 57, 66, 67 -> Condition.SLEET
        61, 63, 65, 80, 81, 82 -> Condition.RAIN
        71, 73, 75, 77, 85, 86 -> Condition.SNOW
        95, 96, 99 -> Condition.STORM
        else -> Condition.CLOUDY
    }

    fun label(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Overcast"
        45 -> "Fog"
        48 -> "Icy Fog"
        51 -> "Light Drizzle"
        53 -> "Drizzle"
        55 -> "Heavy Drizzle"
        56, 57 -> "Freezing Drizzle"
        61 -> "Light Rain"
        63 -> "Rain"
        65 -> "Heavy Rain"
        66, 67 -> "Freezing Rain"
        71 -> "Light Snow"
        73 -> "Snow"
        75 -> "Heavy Snow"
        77 -> "Snow Grains"
        80 -> "Showers"
        81 -> "Heavy Showers"
        82 -> "Downpours"
        85 -> "Snow Showers"
        86 -> "Heavy Snow Showers"
        95 -> "Thunderstorms"
        96, 99 -> "Storms & Hail"
        else -> "Cloudy"
    }

    /** How dramatic a code is; used to pick a day's headline weather. */
    fun severity(code: Int): Int = when (condition(code)) {
        Condition.CLEAR -> 0
        Condition.PARTLY -> 1
        Condition.CLOUDY -> 2
        Condition.FOG -> 3
        Condition.DRIZZLE -> 4
        Condition.RAIN -> 5
        Condition.SNOW -> 6
        Condition.SLEET -> 7
        Condition.STORM -> 8
    } * 100 + code
}
