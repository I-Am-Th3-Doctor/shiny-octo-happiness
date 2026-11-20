package io.github.iamth3doctor.searchlight.weather

/**
 * What the forecast widgets show, worked out from the one forecast everything else uses (HRRR for
 * the next 48 hours, ECMWF IFS after: [ModelBlend]). Pure; JVM-tested.
 */
object WidgetForecast {

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    /** One column of the 3-day widget: the day (its local midnight), whether it's today, its condition, high and low. */
    data class Day(val date: Long, val today: Boolean, val code: Int, val highC: Double, val lowC: Double)

    /**
     * Today and the next two days, as they are where the place is: from the day [now] falls in (an
     * old forecast's earlier days are skipped), each day once, in order. Fewer if the forecast runs out.
     */
    fun threeDays(data: WeatherData, now: Long): List<Day> {
        val d = data.detail ?: return emptyList()
        val todayStart = Moon.dayStart(now, d.utcOffsetSec)
        val seen = HashSet<Long>()
        return d.daily.asSequence()
            .filter { it.date + DAY > todayStart + HOUR } // today onwards (a day of 23 hours still counts)
            .sortedBy { it.date }
            .filter { seen.add(Moon.dayStart(it.date + 12 * HOUR, d.utcOffsetSec)) }
            .take(3)
            .mapIndexed { i, p ->
                val isToday = Moon.dayStart(p.date + 12 * HOUR, d.utcOffsetSec) == todayStart
                // Today's high and low take in the reading now (as the Weather tile's do).
                if (isToday) Day(p.date, true, p.code, maxOf(p.highC, data.temperatureC), minOf(p.lowC, data.temperatureC))
                else Day(p.date, false, p.code, p.highC, p.lowC)
            }
            .toList()
    }

    /** The wide widget's bar: the temperature from now on, hour by hour, with its high and low and the hour marks. */
    data class Curve(
        val start: Long,
        val end: Long,
        /** (time, °C): now first (the reading now), then each hour. */
        val points: List<Pair<Long, Double>>,
        /** Indexes into [points] of the highest and lowest. */
        val high: Int,
        val low: Int,
        /** Where the hour marks go: now, then every [STEP_H] hours on the hour. */
        val marks: List<Long>,
    ) {
        /** How far along the bar [t] is (0..1). */
        fun x(t: Long): Float = ((t - start).toDouble() / (end - start)).coerceIn(0.0, 1.0).toFloat()
    }

    const val SPAN_H = 12
    const val STEP_H = 3

    /** The next [SPAN_H] hours from [now]; null without hours to draw. */
    fun curve(data: WeatherData, now: Long): Curve? {
        val d = data.detail ?: return null
        val end = now + SPAN_H * HOUR
        val hours = d.hourly.filter { it.time > now && it.time <= end }.sortedBy { it.time }
        if (hours.size < 2) return null
        val points = listOf(now to data.temperatureC) + hours.map { it.time to it.tempC }
        val high = points.indices.maxBy { points[it].second }
        val low = points.indices.minBy { points[it].second }
        val firstMark = (Math.floorDiv(now + d.utcOffsetSec * 1000L, STEP_H * HOUR) + 1) * STEP_H * HOUR - d.utcOffsetSec * 1000L
        val marks = listOf(now) + generateSequence(firstMark) { it + STEP_H * HOUR }
            // Not crowding NOW: the first hour mark at least an hour on.
            .dropWhile { it - now < HOUR }
            .takeWhile { it <= end }
        return Curve(now, end, points, high, low, marks)
    }
}
