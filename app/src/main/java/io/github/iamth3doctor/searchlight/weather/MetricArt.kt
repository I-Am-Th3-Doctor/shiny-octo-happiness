package io.github.iamth3doctor.searchlight.weather

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The numbers behind the conditions' animated metric cards: all from the forecast already on hand (no other calls). Pure (no Android types); JVM-tested.
 */

/** The details' metric cards, expanded: what each animation shows. */
object MetricArt {
    private const val HOUR = 3_600_000L

    // ---- sun

    /** How far along today's arc the sun is: 0 at sunrise, 1 at sunset (outside: below the horizon). */
    fun sunFraction(rise: Long, set: Long, now: Long): Double = (now - rise).toDouble() / (set - rise)

    /** "9h 12m" of daylight. */
    fun duration(ms: Long): String {
        val min = (ms / 60_000L).coerceAtLeast(0)
        return "${min / 60}h ${(min % 60).toString().padStart(2, '0')}m"
    }

    // ---- moon

    /** One day's moon, sampled every [STEP_MIN] minutes from local midnight. */
    class MoonDay(val up: BooleanArray, val visible: BooleanArray, val dark: BooleanArray) {
        val steps get() = up.size
        val upAtAll get() = up.any { it }
    }

    const val STEP_MIN = 10

    /** When the moon is up, and up in a dark sky (sun 6° or more below the horizon), through the day. */
    fun moonDay(dayStart: Long, lat: Double, lon: Double): MoonDay {
        val n = 24 * 60 / STEP_MIN
        val up = BooleanArray(n)
        val visible = BooleanArray(n)
        val dark = BooleanArray(n)
        for (i in 0 until n) {
            val t = dayStart + i * STEP_MIN * 60_000L
            up[i] = Moon.altitude(t, lat, lon) > 0
            dark[i] = Sun.altitudeDeg(t, lat, lon) < -6
            visible[i] = up[i] && dark[i]
        }
        return MoonDay(up, visible, dark)
    }

    /** Waxing (lit on the right, from the north) or waning. */
    fun waxing(ms: Long): Boolean = Moon.age(ms) < 0.5

    // ---- humidity

    /** "air is close to saturation" ... "dry air": from how far the air is above its dew point (°F). */
    fun humidityNote(tempC: Double, dewC: Double): String {
        val spread = WeatherText.f(tempC) - WeatherText.f(dewC)
        return when {
            spread <= 4 -> "air is close to saturation"
            spread <= 10 -> "humid air; dew or fog likely overnight"
            spread <= 25 -> "comfortable air"
            else -> "dry air"
        }
    }

    // ---- visibility

    /** How far, as a share of the 10 miles the picture shows (10 or more: all of it). */
    fun visibilityFraction(meters: Double): Double = (WeatherText.miles(meters) / 10).coerceIn(0.0, 1.0)

    /** How clearly a landmark [miles] away shows when you can see [visMiles]: 1 sharp, 0 gone. */
    fun clarity(miles: Double, visMiles: Double): Double =
        if (visMiles <= 0) 0.0 else (1 - miles / visMiles).coerceIn(0.0, 1.0).let { Math.pow(it, 0.6) }

    // ---- pressure

    const val GAUGE_LOW_INHG = 28.6
    const val GAUGE_HIGH_INHG = 31.0

    fun inHg(hpa: Double) = hpa * 0.0295299830714

    /** Where the needle sits on the gauge (0 left, 1 right). */
    fun pressureFraction(hpa: Double): Double = ((inHg(hpa) - GAUGE_LOW_INHG) / (GAUGE_HIGH_INHG - GAUGE_LOW_INHG)).coerceIn(0.0, 1.0)

    enum class Trend(val arrow: String, val note: String) {
        RISING("↑", "Rising: weather improving"),
        FALLING("↓", "Falling: clouds or rain may be on the way"),
        STEADY("→", "Steady: little change expected"),
    }

    /** Over the last three hours (or, without them, the next three): 1 hPa or more either way. */
    fun pressureTrend(hourly: List<HourPoint>, now: Long): Trend? {
        fun at(t: Long): Double? {
            val pts = hourly.filter { it.pressureHpa != null }
            val i = pts.indexOfLast { it.time <= t }
            val a = pts.getOrNull(i) ?: return null
            val b = pts.getOrNull(i + 1) ?: return a.pressureHpa.takeIf { t - a.time <= HOUR }
            val f = ((t - a.time).toDouble() / (b.time - a.time)).coerceIn(0.0, 1.0)
            return a.pressureHpa!! + (b.pressureHpa!! - a.pressureHpa!!) * f
        }
        val p = at(now) ?: return null
        val change = at(now - 3 * HOUR)?.let { p - it } ?: at(now + 3 * HOUR)?.let { it - p } ?: return null
        return when {
            change >= 1.0 -> Trend.RISING
            change <= -1.0 -> Trend.FALLING
            else -> Trend.STEADY
        }
    }

    /** What the level itself means. */
    fun pressureLevel(hpa: Double): String {
        val v = inHg(hpa)
        return when {
            v < 29.6 -> "Low pressure: unsettled"
            v > 30.2 -> "High pressure: settled"
            else -> "Normal pressure"
        }
    }

    // ---- UV

    /** How strong the rays draw (0..1): 11+ is all the way. */
    fun uvIntensity(uv: Double): Double = (uv / 11).coerceIn(0.0, 1.0)

    fun uvAdvice(uv: Double): String = when {
        uv < 3 -> "no protection needed"
        uv < 6 -> "sunscreen and shade at midday"
        uv < 8 -> "cover up; shade 10 AM to 4 PM"
        uv < 11 -> "extra care; avoid midday sun"
        else -> "avoid the sun at midday"
    }

    // ---- air quality

    private val AQI_BANDS = intArrayOf(0, 50, 100, 150, 200, 300, 500)

    /** Where [aqi] sits on the scale's six equal bands (0..1). */
    fun aqiFraction(aqi: Int): Double {
        val v = aqi.coerceIn(0, 500)
        val b = (1 until AQI_BANDS.size).first { v <= AQI_BANDS[it] } - 1
        val within = (v - AQI_BANDS[b]).toDouble() / (AQI_BANDS[b + 1] - AQI_BANDS[b])
        return (b + within) / (AQI_BANDS.size - 1)
    }

    /** How many particles drift through the picture. */
    fun aqiParticles(aqi: Int): Int = (6 + aqi.coerceIn(0, 300) * 0.6).roundToInt()

    fun aqiAdvice(aqi: Int): String = when {
        aqi <= 50 -> "the air is clean"
        aqi <= 100 -> "fine for most; sensitive people take it easy"
        aqi <= 150 -> "sensitive groups should limit time outside"
        aqi <= 200 -> "everyone should limit time outside"
        else -> "stay indoors if you can"
    }

    // ---- wind

    /** How fast the streamlines move (dp a second) for [mph]: a calm drift to a rush. */
    fun streamSpeedDp(mph: Double): Double = 8 + abs(mph).coerceAtMost(60.0) * 7

    /** The Beaufort-style word for [mph]. */
    fun windWord(mph: Double): String = when {
        mph < 1 -> "calm"
        mph < 4 -> "light air"
        mph < 8 -> "gentle drift"
        mph < 13 -> "light breeze"
        mph < 19 -> "breezy"
        mph < 25 -> "windy"
        mph < 39 -> "strong wind"
        else -> "gale"
    }
}
