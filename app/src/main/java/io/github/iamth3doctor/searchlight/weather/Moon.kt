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
 * The moon, worked out on the phone (no API has it for free): its phase from the synodic month,
 * and its rise and set for a day by scanning its altitude hour by hour (the method of the SunCalc
 * library, after "Astronomy on the Personal Computer"). Good to a few minutes, plenty for a
 * forecast. Pure; JVM-tested.
 */
object Moon {

    enum class Phase(val label: String) {
        NEW("New moon"), WAXING_CRESCENT("Waxing crescent"), FIRST_QUARTER("First quarter"), WAXING_GIBBOUS("Waxing gibbous"),
        FULL("Full moon"), WANING_GIBBOUS("Waning gibbous"), LAST_QUARTER("Last quarter"), WANING_CRESCENT("Waning crescent"),
    }

    private const val SYNODIC_DAYS = 29.530588853
    /** A known new moon: 2000-01-06 18:14 UTC. */
    private const val NEW_MOON_MS = 947_182_440_000L

    /** How far through its cycle the moon is at [ms]: 0 new, 0.5 full, back to 1. */
    fun age(ms: Long): Double {
        val days = (ms - NEW_MOON_MS) / 86_400_000.0
        return ((days / SYNODIC_DAYS) % 1.0 + 1.0) % 1.0
    }

    /** The lit fraction of the disc (0..1). */
    fun illumination(ms: Long): Double = (1 - cos(2 * PI * age(ms))) / 2

    /** The phase's name: the four main phases each own a day or so around their exact moment. */
    fun phase(ms: Long): Phase {
        val a = age(ms)
        val d = 1.0 / SYNODIC_DAYS // a day, as a fraction of the cycle
        return when {
            a < d || a > 1 - d -> Phase.NEW
            abs(a - 0.25) < d -> Phase.FIRST_QUARTER
            abs(a - 0.5) < d -> Phase.FULL
            abs(a - 0.75) < d -> Phase.LAST_QUARTER
            a < 0.25 -> Phase.WAXING_CRESCENT
            a < 0.5 -> Phase.WAXING_GIBBOUS
            a < 0.75 -> Phase.WANING_GIBBOUS
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

    /** The moon's altitude (radians, with refraction) at [ms], seen from [lat], [lon]. */
    fun altitude(ms: Long, lat: Double, lon: Double): Double {
        val d = toDays(ms)
        val l0 = RAD * (218.316 + 13.176396 * d)
        val m = RAD * (134.963 + 13.064993 * d)
        val f = RAD * (93.272 + 13.229350 * d)
        val l = l0 + RAD * 6.289 * sin(m)
        val b = RAD * 5.128 * sin(f)
        val ra = rightAscension(l, b)
        val dec = declination(l, b)
        val h = siderealTime(d, RAD * -lon) - ra
        val alt = altitude(h, RAD * lat, dec)
        return alt + refraction(alt)
    }

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
