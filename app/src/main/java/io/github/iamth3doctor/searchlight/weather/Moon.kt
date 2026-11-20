package io.github.iamth3doctor.searchlight.weather

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The moon, worked out on the phone (no free API has it): where it truly is, from the main terms
 * of Meeus's lunar theory ("Astronomical Algorithms", ch. 47; good to a few arcminutes), against
 * where the sun is. Its phase is the real angle between them (not an average month), so a new or
 * full moon lands within minutes of the almanac's. Rise and set come from its altitude through
 * the day (SunCalc's scan). Pure; JVM-tested.
 */
object Moon {

    enum class Phase(val label: String) {
        NEW("New moon"), WAXING_CRESCENT("Waxing crescent"), FIRST_QUARTER("First quarter"), WAXING_GIBBOUS("Waxing gibbous"),
        FULL("Full moon"), WANING_GIBBOUS("Waning gibbous"), LAST_QUARTER("Last quarter"), WANING_CRESCENT("Waning crescent"),
    }

    private fun centuries(ms: Long) = toDays(ms) / 36525.0
    private fun norm(deg: Double) = ((deg % 360) + 360) % 360

    /** The moon's apparent ecliptic longitude and latitude (degrees) at [ms]. */
    fun ecliptic(ms: Long): Pair<Double, Double> {
        val t = centuries(ms)
        val lp = 218.3164477 + 481267.88123421 * t
        val d = RAD * (297.8501921 + 445267.1114034 * t)
        val m = RAD * (357.5291092 + 35999.0502909 * t)
        val mp = RAD * (134.9633964 + 477198.8675055 * t)
        val f = RAD * (93.2720950 + 483202.0175233 * t)
        val lon = lp + 6.288774 * sin(mp) + 1.274027 * sin(2 * d - mp) + 0.658314 * sin(2 * d) +
            0.213618 * sin(2 * mp) - 0.185116 * sin(m) - 0.114332 * sin(2 * f) + 0.058793 * sin(2 * d - 2 * mp) +
            0.057066 * sin(2 * d - m - mp) + 0.053322 * sin(2 * d + mp) + 0.045758 * sin(2 * d - m) -
            0.040923 * sin(m - mp) - 0.034720 * sin(d) - 0.030383 * sin(m + mp) + 0.015327 * sin(2 * d - 2 * f) -
            0.012528 * sin(mp + 2 * f) + 0.010980 * sin(mp - 2 * f) + 0.010675 * sin(4 * d - mp) + 0.010034 * sin(3 * mp)
        val lat = 5.128122 * sin(f) + 0.280602 * sin(mp + f) + 0.277693 * sin(mp - f) + 0.173237 * sin(2 * d - f) +
            0.055413 * sin(2 * d - mp + f) + 0.046271 * sin(2 * d - mp - f)
        return norm(lon) to lat
    }

    /** The sun's apparent ecliptic longitude (degrees) at [ms]. */
    fun sunLongitude(ms: Long): Double {
        val t = centuries(ms)
        val m = RAD * (357.52911 + 35999.05029 * t)
        val c = (1.914602 - 0.004817 * t) * sin(m) + 0.019993 * sin(2 * m) + 0.000289 * sin(3 * m)
        return norm(280.46646 + 36000.76983 * t + c)
    }

    /** How far the moon is ahead of the sun (degrees, 0..360): 0 new, 90 first quarter, 180 full. */
    fun elongation(ms: Long): Double = norm(ecliptic(ms).first - sunLongitude(ms))

    /** How far through its cycle the moon is at [ms]: 0 new, 0.5 full, back to 1. */
    fun age(ms: Long): Double = elongation(ms) / 360.0

    /** The lit fraction of the disc (0..1). */
    fun illumination(ms: Long): Double = (1 - cos(RAD * elongation(ms))) / 2

    /** The phase's name: the four main phases each own the day or so around their exact moment. */
    fun phase(ms: Long): Phase {
        val e = elongation(ms)
        val d = 12.2 // the moon gains about 12.2° a day on the sun
        return when {
            e < d / 2 || e > 360 - d / 2 -> Phase.NEW
            abs(e - 90) < d / 2 -> Phase.FIRST_QUARTER
            abs(e - 180) < d / 2 -> Phase.FULL
            abs(e - 270) < d / 2 -> Phase.LAST_QUARTER
            e < 90 -> Phase.WAXING_CRESCENT
            e < 180 -> Phase.WAXING_GIBBOUS
            e < 270 -> Phase.WANING_GIBBOUS
            else -> Phase.WANING_CRESCENT
        }
    }

    // ------------------------------------------------------------------ rise and set

    private const val RAD = PI / 180
    private const val DAY_MS = 86_400_000.0
    private const val J1970 = 2440588.0
    private const val J2000 = 2451545.0
    private const val OBLIQUITY = RAD * 23.4397

    private fun toDays(ms: Long) = ms / DAY_MS - 0.5 + J1970 - J2000
    private fun rightAscension(l: Double, b: Double) = atan2(sin(l) * cos(OBLIQUITY) - tan(b) * sin(OBLIQUITY), cos(l))
    private fun declination(l: Double, b: Double) = asin(sin(b) * cos(OBLIQUITY) + cos(b) * sin(OBLIQUITY) * sin(l))
    private fun siderealTime(d: Double, lw: Double) = RAD * (280.16 + 360.9856235 * d) - lw
    private fun altitude(h: Double, phi: Double, dec: Double) = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))
    private fun refraction(h0: Double): Double {
        val h = if (h0 < 0) 0.0 else h0
        return 0.0002967 / tan(h + 0.00312536 / (h + 0.08901179))
    }

    /** Where the moon is in the sky at [ms] from [lat], [lon]: altitude (radians, with refraction) and azimuth (radians from south, west positive). */
    fun position(ms: Long, lat: Double, lon: Double): Pair<Double, Double> {
        val (l, b) = ecliptic(ms)
        return horizontal(ms, lat, lon, rightAscension(RAD * l, RAD * b), declination(RAD * l, RAD * b), refract = true)
    }

    /** The moon's altitude (radians, with refraction) at [ms], seen from [lat], [lon]. */
    fun altitude(ms: Long, lat: Double, lon: Double): Double = position(ms, lat, lon).first

    internal fun horizontal(ms: Long, lat: Double, lon: Double, ra: Double, dec: Double, refract: Boolean): Pair<Double, Double> {
        val d = toDays(ms)
        val h = siderealTime(d, RAD * -lon) - ra
        val phi = RAD * lat
        val alt = altitude(h, phi, dec)
        val az = atan2(sin(h), cos(h) * sin(phi) - tan(dec) * cos(phi))
        return (if (refract) alt + refraction(alt) else alt) to az
    }

    internal fun equatorial(lonDeg: Double, latDeg: Double): Pair<Double, Double> =
        rightAscension(RAD * lonDeg, RAD * latDeg) to declination(RAD * lonDeg, RAD * latDeg)

    /** Rise and set within the day starting at [dayStartMs] (local midnight); null when it doesn't happen that day. */
    data class Times(val rise: Long?, val set: Long?)

    fun times(dayStartMs: Long, lat: Double, lon: Double): Times {
        val hc = 0.133 * RAD
        fun at(hours: Double) = altitude(dayStartMs + (hours * 3_600_000).toLong(), lat, lon) - hc
        var h0 = at(0.0)
        var rise: Double? = null
        var set: Double? = null
        var i = 1
        while (i <= 24) {
            val h1 = at(i.toDouble())
            val h2 = at(i + 1.0)
            val a = (h0 + h2) / 2 - h1
            val b = (h2 - h0) / 2
            val xe = -b / (2 * a)
            val ye = (a * xe + b) * xe + h1
            val disc = b * b - 4 * a * h1
            var roots = 0
            var x1 = 0.0
            var x2 = 0.0
            if (disc >= 0) {
                val dx = sqrt(disc) / (abs(a) * 2)
                x1 = xe - dx
                x2 = xe + dx
                if (abs(x1) <= 1) roots++
                if (abs(x2) <= 1) roots++
                if (x1 < -1) x1 = x2
            }
            if (roots == 1) {
                if (h0 < 0) rise = i + x1 else set = i + x1
            } else if (roots == 2) {
                rise = i + if (ye < 0) x2 else x1
                set = i + if (ye < 0) x1 else x2
            }
            if (rise != null && set != null) break
            h0 = h2
            i += 2
        }
        fun ms(h: Double?) = h?.let { dayStartMs + (it * 3_600_000).toLong() }
        return Times(ms(rise), ms(set))
    }

    /** Local midnight (epoch ms) of the day holding [ms] at [utcOffsetSec]. */
    fun dayStart(ms: Long, utcOffsetSec: Int): Long {
        val off = utcOffsetSec * 1000L
        return floor((ms + off) / DAY_MS).toLong() * DAY_MS.toLong() - off
    }
}
