package io.github.iamth3doctor.searchlight.weather

/**
 * Day or night at a moment, the one rule every sun-or-moon picture follows (the hourly strip, the
 * precipitation island, the hero scene, the widget): day from sunrise until sunset, night outside
 * it. From the forecast's own sunrise and sunset for that day; for a time no day of the forecast
 * covers, from where the sun actually is (its upper edge on the horizon, as sunrise is timed).
 * Never from the hourly "is day" flag, which a blended forecast can leave unset.
 */
object Daylight {
    private const val DAY_MS = 24 * 3_600_000L

    fun isDay(data: WeatherData, t: Long): Boolean {
        val days = data.detail?.daily.orEmpty().filter { it.sunrise != null && it.sunset != null }
        // The forecast day holding t: its own local day (date is the day's start).
        val day = days.lastOrNull { it.date <= t && t < it.date + DAY_MS }
        if (day != null) return t >= day.sunrise!! && t < day.sunset!!
        return Sun.altitudeDeg(t, data.latitude, data.longitude) > SUNRISE_ALT
    }

    /** The sun's centre's altitude at sunrise and sunset: refraction and its radius. */
    private const val SUNRISE_ALT = -0.833
}
