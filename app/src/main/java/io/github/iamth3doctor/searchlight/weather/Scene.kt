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

    /** Cloud cover when the forecast didn't give a number: from the condition. */
    fun coverFromCode(code: Int): Double = when (code) {
        0 -> 0.03
        1 -> 0.25
        2 -> 0.55
        3 -> 0.95
        45, 48 -> 0.8
        else -> 0.85
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

        val snowy = isSnow(code)
        val wet = isWet(code) || (!snowy && (precip ?: 0.0) >= 0.1)
        val amount = intensity(code, precip)
        val rain = if (wet && !snowy) maxOf(amount, if (isThunder(code)) 0.55 else 0.1) else 0.0
        val snow = if (snowy) maxOf(amount, 0.15) else 0.0
        // The forecast's own cover when it has one (a sunny shower stays sunny); else from the condition.
        var cover = (cloudPct?.div(100.0)) ?: coverFromCode(code)
        if (rain > 0 || snow > 0) cover = maxOf(cover, if (cloudPct != null) 0.3 else 0.7)
        if (code == 0) cover = minOf(cover, 0.2)
        val vis = if (useLive) d?.visibilityM else null
        val fog = when {
            code == 45 || code == 48 -> 0.85
            vis != null && vis < 1000 -> 0.6
            vis != null && vis < 4000 -> 0.25
            else -> 0.0
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
            precipProb = if (useLive) h0?.precipProb else h0!!.precipProb,
            rain = rain,
            snow = snow,
            thunder = isThunder(code),
            fog = fog,
            windMph = windMph,
            windFromDeg = windFrom,
            sunAltDeg = sAlt * 180 / PI,
            sunAz = sAz,
            moonAltDeg = mAlt * 180 / PI,
            moonAz = mAz,
            moonAge = Moon.age(timeMs),
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
