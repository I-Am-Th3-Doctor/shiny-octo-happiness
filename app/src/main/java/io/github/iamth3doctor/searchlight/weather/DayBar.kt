package io.github.iamth3doctor.searchlight.weather

/**
 * The day's hours as the precipitation card draws them: 24 hours starting a little before now, so
 * "now" sits near the left and the rest of the day plays out to the right. Pure; JVM-tested.
 */
object DayBar {
    const val HOURS = 24
    const val BEFORE_HOURS = 2
    private const val HOUR = 3_600_000L

    /** The start: the top of the hour [BEFORE_HOURS] before now. */
    fun start(nowMs: Long): Long = Math.floorDiv(nowMs, HOUR) * HOUR - BEFORE_HOURS * HOUR

    fun end(nowMs: Long): Long = start(nowMs) + HOURS * HOUR

    /** The time at [fraction] (0..1) across. */
    fun timeAt(fraction: Double, nowMs: Long): Long = start(nowMs) + (fraction.coerceIn(0.0, 1.0) * HOURS * HOUR).toLong()

    /** Where [timeMs] falls across (0..1). */
    fun fractionOf(timeMs: Long, nowMs: Long): Double = ((timeMs - start(nowMs)).toDouble() / (HOURS * HOUR)).coerceIn(0.0, 1.0)

    /** The forecast's hours within it. */
    fun hours(data: WeatherData?, nowMs: Long): List<HourPoint> {
        val s = start(nowMs)
        val e = end(nowMs)
        return data?.detail?.hourly.orEmpty().filter { it.time in s..e }
    }
}
