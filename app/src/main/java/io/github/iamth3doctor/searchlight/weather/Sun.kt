package io.github.iamth3doctor.searchlight.weather

import kotlin.math.PI

/** Where the sun is in the sky (for the weather scene's light and its sun). Pure; JVM-tested. */
object Sun {
    /** Altitude (radians, no refraction) and azimuth (radians from south, west positive) at [ms] from [lat], [lon]. */
    fun position(ms: Long, lat: Double, lon: Double): Pair<Double, Double> {
        val (ra, dec) = Moon.equatorial(Moon.sunLongitude(ms), 0.0)
        return Moon.horizontal(ms, lat, lon, ra, dec, refract = false)
    }

    /** The sun's altitude in degrees. */
    fun altitudeDeg(ms: Long, lat: Double, lon: Double): Double = position(ms, lat, lon).first * 180 / PI
}
