package io.github.iamth3doctor.searchlight.weatherapp

/** Color sums for the weather screen's drawings. */
object WeatherColors {
    /** [a] toward [b] by [t] (0..1), alpha too. */
    fun mix(a: Int, b: Int, t: Double): Int {
        val f = t.coerceIn(0.0, 1.0)
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * f).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun withAlpha(c: Int, a: Double): Int = (c and 0x00FFFFFF) or ((255 * a.coerceIn(0.0, 1.0)).toInt() shl 24)
}
