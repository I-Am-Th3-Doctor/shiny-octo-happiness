package io.github.iamth3doctor.searchlight.weather

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the weather screen's hero says around its scene: five callouts, each pointing (with a leader
 * line) at the part of the picture it describes, and the short timeline under the condition. For
 * the moment showing: now, or the hour scrubbed to. Pure; JVM-tested. All temperatures °F.
 */
object HeroReadings {

    /** A callout: what it points at ([key]), its label, and its lines. */
    data class Callout(val key: String, val label: String, val lines: List<String>)

    const val WIND = "wind"
    const val SKY = "sky"
    const val SUN = "sun"
    const val TEMP = "temp"
    const val UV = "uv"
    val KEYS = listOf(WIND, SKY, SUN, TEMP, UV)

    private const val HOUR = 3_600_000L
    private const val NONE = "—"

    private fun deg(c: Double) = "${WeatherText.f(c)}°"

    /** An arrow the way the wind blows ([towardDeg]: 0 north, 90 east). */
    fun arrow(towardDeg: Double): String = ARROWS[(((towardDeg % 360 + 360) % 360) / 45 + 0.5).toInt() % 8]
    private val ARROWS = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")

    /** Rain rate ([mm] an hour) in inches an hour: "0.02 in/h"; "None" when dry. */
    fun rainRate(mm: Double): String = when {
        mm < 0.005 -> "None"
        mm / 25.4 < 0.01 -> "< 0.01 in/h"
        else -> "%.2f in/h".format(java.util.Locale.US, mm / 25.4)
    }

    /** "30.02 inHg". */
    fun pressure(hpa: Double): String = "%.2f inHg".format(java.util.Locale.US, MetricArt.inHg(hpa))

    /** A scrubbed hour has no feels-like of its own: wind chill below 50°F, else the temperature (°F). */
    fun approxFeelsF(c: SceneConditions): Int {
        val f = c.tempC * 9 / 5 + 32
        val v = c.windMph
        val feels = if (f <= 50 && v > 3) 35.74 + 0.6215 * f - 35.75 * Math.pow(v, 0.16) + 0.4275 * f * Math.pow(v, 0.16) else f
        return feels.roundToInt()
    }

    /** The hour of [hourly] at [t] (the one it falls in), if any. */
    private fun hourAt(hourly: List<HourPoint>, t: Long): HourPoint? =
        hourly.lastOrNull { it.time <= t && t - it.time < HOUR }

    /**
     * The five callouts for [c] (the scene showing), [time] formatting a time of day there. Without
     * a reading, dashes.
     */
    fun callouts(data: WeatherData?, c: SceneConditions, time: (Long) -> String): List<Callout> {
        val d = data?.detail
        if (data == null) return KEYS.map { Callout(it, label(it), listOf(NONE)) }
        val day = d?.daily?.lastOrNull { it.date <= c.timeMs } ?: d?.daily?.firstOrNull()
        val hour = d?.hourly?.let { hourAt(it, c.timeMs) }
        val feels = if (c.live) d?.feelsLikeC?.let { deg(it) } ?: "${approxFeelsF(c)}°" else "${approxFeelsF(c)}°"
        val pres = (if (c.live) d?.pressureHpa else hour?.pressureHpa ?: d?.pressureHpa)?.let { pressure(it) } ?: NONE
        val wind = if (c.windMph < 0.5) "Calm" else "${arrow(c.windTowardDeg)} ${c.windMph.roundToInt()} mph ${WeatherText.compass(c.windFromDeg)}"
        val uv = (if (c.live) d?.uvIndex?.let { maxOf(it, day?.uvMax ?: it) } ?: day?.uvMax else day?.uvMax ?: d?.uvIndex)
        return listOf(
            Callout(WIND, label(WIND), listOf(wind)),
            Callout(SKY, label(SKY), listOf("Cloud ${(c.cloudCover * 100).roundToInt()}%", "Rain ${rainRate(c.precipMm)}")),
            Callout(SUN, label(SUN), listOf(
                "↑ ${day?.sunrise?.let(time) ?: NONE}  ↓ ${day?.sunset?.let(time) ?: NONE}",
                "${Moon.phase(c.timeMs).label} · ${(Moon.illumination(c.timeMs) * 100).roundToInt()}% lit",
            )),
            Callout(TEMP, label(TEMP), listOf(
                "${deg(c.tempC)}F · Feels $feels",
                "Hum ${d?.humidity?.let { "$it%" } ?: NONE} · Dew ${d?.dewPointC?.let { deg(it) } ?: NONE}",
                pres,
            )),
            Callout(UV, label(UV), listOf(
                "Max UV ${uv?.let { "${it.roundToInt()} ${WeatherText.uvRating(it)}" } ?: NONE}",
                "Vis ${d?.visibilityM?.let { WeatherText.visibility(it) } ?: NONE}",
            )),
        )
    }

    fun label(key: String): String = when (key) {
        WIND -> "WIND"
        SKY -> "CLOUDS & RAIN"
        SUN -> "SUN & MOON"
        TEMP -> "TEMPERATURE"
        else -> "UV & VISIBILITY"
    }

    /** One mark of the timeline: when, its temperature (°C; null: none), whether it's now. */
    data class Mark(val time: Long, val tempC: Double?, val now: Boolean)

    /**
     * The short timeline under the condition: the moment showing ([t]: now, labeled NOW, or the
     * scrubbed hour), then the next [n] whole hours. The first mark's temperature is the scene's.
     */
    fun timeline(data: WeatherData?, c: SceneConditions, n: Int = 4): List<Mark> {
        val t = c.timeMs
        val first = Mark(t, c.tempC.takeIf { data != null }, c.live)
        val hourly = data?.detail?.hourly.orEmpty()
        val nextHour = (Math.floorDiv(t, HOUR) + 1) * HOUR
        val marks = (0 until n).map { k ->
            val at = nextHour + k * HOUR
            Mark(at, hourly.firstOrNull { abs(it.time - at) < HOUR / 2 }?.tempC, false)
        }
        return listOf(first) + marks
    }
}
