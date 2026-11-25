package io.github.iamth3doctor.searchlight.weather

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the weather scene shows at one moment: the numbers that drive it (cloud cover, rain and
 * snow intensity, wind, fog, lightning, where the sun and moon are). Live it's the current
 * reading; scrubbed to another hour it's that hour's forecast. Pure; JVM-tested.
 */
data class SceneConditions(
    val timeMs: Long,
    val live: Boolean,
    val tempC: Double,
    val code: Int,
    /** 0..1 */
    val cloudCover: Double,
    /** mm per hour (forecast precipitation for the hour). */
    val precipMm: Double,
    val precipProb: Int?,
    /** 0..1: none to a downpour. */
    val rain: Double,
    /** 0..1: none to heavy snow. */
    val snow: Double,
    val thunder: Boolean,
    /** 0..1: clear air to thick fog. */
    val fog: Double,
    val windMph: Double,
    /** Where the wind comes from (meteorological degrees: 0 from the north). */
    val windFromDeg: Double,
    val sunAltDeg: Double,
    /** Radians from south, west positive. */
    val sunAz: Double,
    val moonAltDeg: Double,
    val moonAz: Double,
    /** 0..1 through the lunar cycle (0 new, 0.5 full). */
    val moonAge: Double,
    /** A severe-weather alert is in effect: dark storm sky, whatever its kind adds. */
    val severe: AlertKind? = null,
    /** What the sky shows ([SceneModel.sky]): the sun only when it's [SceneModel.Sky.SUN]. */
    val sky: SceneModel.Sky = SceneModel.Sky.CLOUD,
) {
    /** Where the wind blows to (0 north, 90 east): the way the scene's arrow points. */
    val windTowardDeg: Double get() = (windFromDeg + 180) % 360

    /** The wind's eastward part, mph (negative: blowing west). Rain slants and clouds drift with it. */
    val windEastMph: Double get() = windMph * sin(windTowardDeg * PI / 180)

    /** 0 in daylight, 1 in full night (stars out). */
    val darkness: Double get() = ((4 - sunAltDeg) / 16).coerceIn(0.0, 1.0)
}

object SceneModel {

    private const val HOUR = 3_600_000L

    /**
     * [data] with an hourly forecast made up from the day [timeMs] falls in (its condition, low at
     * dawn, high mid-afternoon, its precipitation spread over the day), or null if no day covers it.
     */
    fun fromDay(data: WeatherData, timeMs: Long): WeatherData? {
        val d = data.detail ?: return null
        val day = d.daily.lastOrNull { it.date <= timeMs && timeMs < it.date + 24 * HOUR } ?: return null
        val wet = isWet(day.code) || isSnow(day.code)
        val hours = (0..24).map { k ->
            val t = day.date + k * HOUR
            // Coolest around 6 AM, warmest around 3 PM.
            val warm = (1 - cos(((k - 6).coerceIn(0, 9) / 9.0) * PI)) / 2 * (if (k <= 15) 1.0 else 1 - (k - 15) / 15.0)
            val (alt, _) = Sun.position(t, data.latitude, data.longitude)
            HourPoint(t, day.lowC + (day.highC - day.lowC) * warm.coerceIn(0.0, 1.0), day.code, alt > 0,
                cloudPct = (coverFromCode(day.code) * 100).roundToInt(),
                precipMm = if (wet) maxOf(0.3, (day.precipMm ?: 2.4) / 24 * 1.5) else 0.0,
                precipProb = day.precipPct, windMph = d.windMph, windFromDeg = d.windFromDeg)
        }
        return data.copy(detail = d.copy(hourly = hours))
    }

    /** Cloud cover when the forecast didn't give a number: from the condition. */
    fun coverFromCode(code: Int): Double = when (code) {
        0 -> 0.03
        1 -> 0.25
        2 -> 0.55
        3 -> 0.95
        45, 48 -> 0.8
        else -> 0.85
    }

    /** What the sky shows: one rule for the scene, the precip island and the readings beside them. */
    enum class Sky { SUN, CLOUD, RAIN, SNOW }

    /**
     * The sky for a condition [code], a precipitation rate ([rateMm] an hour) and the hour's chance
     * ([prob] %): snow for a snow code; rain for a rain or shower code, any rain falling, or a 50%+
     * chance; the sun only for Clear or Mainly clear; otherwise cloud (no sun).
     */
    fun sky(code: Int, rateMm: Double?, prob: Int?): Sky = when {
        isSnow(code) -> Sky.SNOW
        isWet(code) || (rateMm ?: 0.0) > 0.0 || (prob ?: 0) >= 50 -> Sky.RAIN
        code == 0 || code == 1 -> Sky.SUN
        else -> Sky.CLOUD
    }

    fun isSnow(code: Int) = code in 71..77 || code == 85 || code == 86
    fun isThunder(code: Int) = code in 95..99
    fun isWet(code: Int) = code in 51..67 || code in 80..82 || isThunder(code)

    /** How hard it rains or snows, 0..1, from the amount (mm/h); the code alone when there's no amount. */
    fun intensity(code: Int, precipMm: Double?): Double {
        val byCode = when (code) {
            51, 56 -> 0.12
            53 -> 0.2
            55, 57 -> 0.3
            61, 66, 71, 80, 85 -> 0.35
            63, 73, 81, 95 -> 0.6
            65, 67, 75, 82, 86, 96, 99 -> 0.9
            77 -> 0.2
            else -> 0.0
        }
        if (precipMm == null || precipMm <= 0.0) return byCode
        // 0.2 mm/h is a drizzle, 2 a steady rain, 8+ a downpour.
        val byAmount = 1 - exp(-precipMm / 3.5)
        return maxOf(byAmount, byCode * 0.6).coerceIn(0.08, 1.0)
    }

    /** The scene at [timeMs]: live (the current reading) when [live], else the forecast for that time. */
    fun at(data: WeatherData, timeMs: Long, live: Boolean): SceneConditions {
        val d = data.detail
        val hours = d?.hourly.orEmpty()
        // Past the hourly forecast: that day's forecast, played through the day.
        if (!live && d != null && (hours.isEmpty() || timeMs > hours.last().time + 2 * HOUR)) {
            fromDay(data, timeMs)?.let { return at(it, timeMs, live = false) }
        }
        val i = hours.indexOfLast { it.time <= timeMs }
        val h0 = hours.getOrNull(i)
        val h1 = hours.getOrNull(i + 1)
        val f = if (h0 != null && h1 != null) ((timeMs - h0.time).toDouble() / (h1.time - h0.time)).coerceIn(0.0, 1.0) else 0.0
        fun lerp(a: Double?, b: Double?): Double? = when {
            a == null -> b
            b == null -> a
            else -> a + (b - a) * f
        }

        val useLive = live || h0 == null
        val code = if (useLive) data.weatherCode else h0!!.code
        val tempC = if (useLive) data.temperatureC else lerp(h0!!.tempC, h1?.tempC)!!
        val cloudPct = if (useLive) d?.cloudPct?.toDouble() ?: h0?.cloudPct?.toDouble()
            else lerp(h0!!.cloudPct?.toDouble(), h1?.cloudPct?.toDouble())
        // The current "precipitation" is the last interval's amount; per hour, the hour's.
        val precip = if (useLive) d?.precipMm ?: h0?.precipMm else h0!!.precipMm
        val windMph = (if (useLive) d?.windMph else lerp(h0!!.windMph, h1?.windMph)) ?: d?.windMph ?: 0.0
        val windFrom = (if (useLive) d?.windFromDeg else h0!!.windFromDeg) ?: d?.windFromDeg ?: 270.0

        val prob = if (useLive) h0?.precipProb else h0!!.precipProb
        // The same condition the page shows decides the picture: no separate visual condition.
        val sky = sky(code, precip, prob)
        val amount = intensity(code, precip)
        val rain = if (sky == Sky.RAIN) maxOf(amount, if (isThunder(code)) 0.55 else 0.1) else 0.0
        val snow = if (sky == Sky.SNOW) maxOf(amount, 0.15) else 0.0
        // The forecast's own cover when it has one; else from the condition. Rain or snow comes
        // with clouds; no sun shows except in a clear sky.
        var cover = (cloudPct?.div(100.0)) ?: coverFromCode(code)
        if (rain > 0 || snow > 0) cover = maxOf(cover, 0.7)
        if (code == 0 && sky == Sky.SUN) cover = minOf(cover, 0.2)
        val vis = if (useLive) d?.visibilityM else null
        val fog = when {
            code == 45 || code == 48 -> 0.85
            vis != null && vis < 1000 -> 0.6
            vis != null && vis < 4000 -> 0.25
            else -> 0.0
        }
        // A severe-weather alert in effect then: a darker, stormier sky, and the rain or snow that's
        // falling falls harder. It never makes rain or snow the condition doesn't have.
        val severe = NwsAlerts.active(data.alerts, timeMs).firstOrNull()?.let { NwsAlerts.kind(it.event) }
        var thunder = isThunder(code)
        var rainOut = rain
        var snowOut = snow
        var fogOut = fog
        var windOut = windMph
        if (severe != null) {
            cover = 1.0
            when (severe) {
                AlertKind.THUNDER -> if (rain > 0) { thunder = true; rainOut = maxOf(rainOut, 0.85) }
                AlertKind.RAIN -> if (rain > 0) rainOut = maxOf(rainOut, 0.9)
                AlertKind.WIND -> { if (rain > 0) rainOut = maxOf(rainOut, 0.55); windOut = maxOf(windOut, 30.0) }
                AlertKind.SNOW -> if (snow > 0) snowOut = maxOf(snowOut, 0.9)
                AlertKind.FOG -> fogOut = maxOf(fogOut, 0.9)
                AlertKind.HEAT, AlertKind.OTHER -> if (rain > 0) rainOut = maxOf(rainOut, 0.4)
            }
        }
        val (sAlt, sAz) = Sun.position(timeMs, data.latitude, data.longitude)
        val (mAlt, mAz) = Moon.position(timeMs, data.latitude, data.longitude)
        return SceneConditions(
            timeMs = timeMs,
            live = useLive,
            tempC = tempC,
            code = code,
            cloudCover = cover.coerceIn(0.0, 1.0),
            precipMm = precip ?: 0.0,
            precipProb = prob,
            rain = rainOut,
            snow = snowOut,
            thunder = thunder,
            fog = fogOut,
            windMph = windOut,
            windFromDeg = windFrom,
            sunAltDeg = sAlt * 180 / PI,
            sunAz = sAz,
            moonAltDeg = mAlt * 180 / PI,
            moonAz = mAz,
            moonAge = Moon.age(timeMs),
            severe = severe,
            sky = sky,
        )
    }
}

/**
 * The forecast bar under the scene: 24 hours starting a little before now, so "now" sits near its
 * left and the rest of the day plays out to the right. Pure; JVM-tested.
 */
object DayBar {
    const val HOURS = 24
    const val BEFORE_HOURS = 2
    private const val HOUR = 3_600_000L

    /** The bar's start: the top of the hour [BEFORE_HOURS] before now. */
    fun start(nowMs: Long): Long = Math.floorDiv(nowMs, HOUR) * HOUR - BEFORE_HOURS * HOUR

    fun end(nowMs: Long): Long = start(nowMs) + HOURS * HOUR

    /** The time at [fraction] (0..1) across the bar. */
    fun timeAt(fraction: Double, nowMs: Long): Long = start(nowMs) + (fraction.coerceIn(0.0, 1.0) * HOURS * HOUR).toLong()

    /** Where [timeMs] falls across the bar (0..1). */
    fun fractionOf(timeMs: Long, nowMs: Long): Double = ((timeMs - start(nowMs)).toDouble() / (HOURS * HOUR)).coerceIn(0.0, 1.0)

    /** The bar's hours (the forecast's, within it). */
    fun hours(data: WeatherData?, nowMs: Long): List<HourPoint> {
        val s = start(nowMs)
        val e = end(nowMs)
        return data?.detail?.hourly.orEmpty().filter { it.time in s..e }
    }

    /** The warmest and coolest hours on the bar (for its high/low marks); null without hours. */
    fun highLow(hours: List<HourPoint>): Pair<HourPoint, HourPoint>? {
        if (hours.isEmpty()) return null
        return hours.maxBy { it.tempC } to hours.minBy { it.tempC }
    }

    /** The top of the hour [i] hours into the bar. */
    fun hourTime(i: Int, nowMs: Long): Long = start(nowMs) + i.coerceIn(0, HOURS) * HOUR

    /** Which hour of the bar (0..[HOURS]) [timeMs] is nearest. */
    fun hourIndex(timeMs: Long, nowMs: Long): Int =
        Math.round((timeMs - start(nowMs)).toDouble() / HOUR).toInt().coerceIn(0, HOURS)

    /** Extra distance (in hours, beyond halfway) a drag must go before it leaves the hour it's on. */
    const val SNAP_HYSTERESIS = 0.2

    /**
     * The hour a scrub at [position] (in hours across the bar) lands on, given the hour it's on now
     * ([current], null if none): snapped to the nearest hour, but sticky, so a finger resting between
     * two hours (or a short wobble) doesn't flick back and forth or skip past one.
     */
    fun snap(position: Double, current: Int?): Int {
        val p = position.coerceIn(0.0, HOURS.toDouble())
        if (current != null && kotlin.math.abs(p - current) < 0.5 + SNAP_HYSTERESIS) return current
        return Math.round(p).toInt().coerceIn(0, HOURS)
    }

    /** Whether rain (or snow) is expected in [h]: a real chance or a real amount. */
    fun wet(h: HourPoint): Boolean = (h.precipProb ?: 0) >= 30 || (h.precipMm ?: 0.0) >= 0.1 || SceneModel.isWet(h.code) || SceneModel.isSnow(h.code)

    /** "3 PM · 54° · 40%" style parts for a scrubbed time: temperature (°F) and chance of rain. */
    fun readout(data: WeatherData, timeMs: Long): Pair<Int, Int?> {
        val c = SceneModel.at(data, timeMs, live = false)
        return WeatherText.f(c.tempC) to c.precipProb
    }
}

/** The scene's physics in numbers (counts and speeds per area), shared by the screen and the widget. Pure; JVM-tested. */
object ScenePhysics {

    /** How many clouds a [widthDp]-wide sky holds at [cover] (0..1): none when clear, a crowd when overcast. */
    fun cloudCount(cover: Double, widthDp: Float): Int {
        if (cover < 0.06) return 0
        val max = 3 + widthDp / 55f
        return (cover.pow(0.85) * max).roundToInt().coerceAtLeast(1)
    }

    /** Raindrops in flight for [rain] (0..1) over [widthDp] × [heightDp]. */
    fun drops(rain: Double, widthDp: Float, heightDp: Float, cap: Int): Int {
        if (rain <= 0.0) return 0
        val perArea = 0.0035 * rain.pow(1.2) // drops per dp²: about 900 on a phone screen in a downpour
        return (perArea * widthDp * heightDp).roundToInt().coerceIn(12, cap)
    }

    /** Snowflakes for [snow] (0..1). */
    fun flakes(snow: Double, widthDp: Float, heightDp: Float, cap: Int): Int {
        if (snow <= 0.0) return 0
        return (0.0012 * snow * widthDp * heightDp).roundToInt().coerceIn(10, cap)
    }

    /** A raindrop's fall speed, dp/s: heavier rain falls faster. */
    fun fallSpeed(rain: Double): Double = 650 + 650 * rain

    /** Sideways speed from the wind, dp/s (east positive): a 20 mph wind leans rain about 25°. */
    fun drift(windEastMph: Double): Double = windEastMph * 22

    /** Cloud speed, dp/s (east positive): a slow drift on a still day, quick in a gale. */
    fun cloudSpeed(windEastMph: Double): Double {
        val dir = if (windEastMph < 0) -1 else 1
        return dir * (3.0 + kotlin.math.abs(windEastMph) * 1.6)
    }

    /** The screen angle (radians from straight down, positive leaning right) rain falls at. */
    fun rainAngle(rain: Double, windEastMph: Double): Double = kotlin.math.atan2(drift(windEastMph), fallSpeed(rain))

    /** The arrow's screen vector (north up) for wind blowing toward [towardDeg]. */
    fun arrow(towardDeg: Double): Pair<Double, Double> = sin(towardDeg * PI / 180) to -cos(towardDeg * PI / 180)
}

/** The scene's ambient sound for one moment: what [WeatherSound] plays (each 0..1). Pure; JVM-tested. */
object SceneSound {
    data class Levels(val rain: Float, val wind: Float, val crickets: Float)

    /**
     * Rain as hard as it rains; wind once it's more than a breeze; crickets on a dry, mild night
     * (fewer as the wind picks up). A clear, calm day: silence.
     */
    fun levels(c: SceneConditions): Levels {
        val rain = c.rain.coerceIn(0.0, 1.0)
        val wind = ((c.windMph - 6) / 24).coerceIn(0.0, 1.0)
        val crickets = if (c.darkness > 0.6 && rain < 0.02 && c.snow < 0.02 && !c.thunder && c.tempC > 2) 0.8 * (1 - wind) else 0.0
        return Levels(rain.toFloat(), wind.toFloat(), crickets.toFloat())
    }
}
