package io.github.iamth3doctor.searchlight.weather

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Picks the best Open-Meteo model for each stretch of the forecast, from one request that asks for
 * several (`models=best_match,gfs_hrrr,ecmwf_ifs`; Open-Meteo then names each variable after its
 * model, e.g. `temperature_2m_gfs_hrrr`). Worked out afresh for each answer, so each place (Tacoma,
 * Renton, anywhere) gets its own split:
 *
 *  - the next 48 hours (now, the hourly strip, the scrub bar, the precip card, the time-lapse hours)
 *    come from HRRR, NOAA's 3 km model, updated every hour: the best at short-range rain timing in
 *    the US;
 *  - days 3 to 7 come from ECMWF IFS (9 km), the best global model at medium range: the 7-day list
 *    takes its daily highs, lows and rain straight from it;
 *  - one model per hour: an hour's values (temperature, rain chance, rain, code, wind...) all come
 *    from the same model, so the strip, the rain chance and the precip graph can't disagree. If that
 *    model has no forecast for the hour, the whole hour is Open-Meteo's `best_match`;
 *  - over the last [BLEND_H] hours of HRRR's stretch (hours 42-48, or earlier when HRRR's latest
 *    run ends before 48) each hour is cross-faded from the HRRR hour into the ECMWF hour, so the
 *    temperature and rain chance don't jump where one hands over to the other.
 *
 * A value a model never publishes at all (daily UV) comes from best_match. The result is shaped
 * like a single-model answer (plain variable names), so the parsers read it as before. An answer
 * with no model names in it (a single-model request) passes through unchanged. Pure; JVM-tested.
 */
object ModelBlend {

    const val BEST = "best_match"
    const val SHORT = "gfs_hrrr"
    const val LONG = "ecmwf_ifs"
    val MODELS = listOf(BEST, SHORT, LONG)

    /** HRRR's stretch: the next 48 hours (less if its latest run ends sooner). */
    const val SHORT_RANGE_H = 48.0
    /** The cross-fade: the last 6 hours of that stretch. */
    const val BLEND_H = 6.0
    /** Today and tomorrow follow the hours; from the third day on, ECMWF's days. */
    const val SHORT_DAYS = 2

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    /**
     * How much of the moment [t] comes from the short-range model (1: all HRRR; 0: all ECMWF), when
     * HRRR's stretch ends [endH] hours after [nowMs]: 1 up to [BLEND_H] before the end, 0 from the end.
     */
    fun shortWeight(t: Long, nowMs: Long, endH: Double = SHORT_RANGE_H): Double {
        val h = (t - nowMs) / HOUR_MS.toDouble()
        val from = endH - BLEND_H
        return when {
            h <= from -> 1.0
            h >= endH -> 0.0
            else -> (endH - h) / BLEND_H
        }
    }

    /** Variables that are a category or a flag: taken from one model, never averaged. */
    private val CATEGORICAL = setOf("weather_code", "is_day")
    /** Times (sunrise, sunset): the same in every model; any that has them. */
    private val TIMES = setOf("sunrise", "sunset")
    private const val DIRECTION = "wind_direction_10m"

    private fun suffixOf(key: String): String? = MODELS.firstOrNull { key.endsWith("_$it") }
    private fun baseOf(key: String): String = suffixOf(key)?.let { key.removeSuffix("_$it") } ?: key

    private fun hasModelKeys(o: JSONObject?): Boolean = o?.keys()?.asSequence()?.any { suffixOf(it) != null } == true

    fun blend(root: JSONObject, nowMs: Long): JSONObject {
        val current = root.optJSONObject("current")
        val hourly = root.optJSONObject("hourly")
        val daily = root.optJSONObject("daily")
        if (!hasModelKeys(current) && !hasModelKeys(hourly) && !hasModelKeys(daily)) return root
        val out = JSONObject(root.toString())
        val hours = hourly?.let { blendHourly(it, nowMs) }
        current?.let { out.put("current", blendCurrent(it, hourly, nowMs)) }
        hours?.let { out.put("hourly", it) }
        daily?.let { out.put("daily", blendDaily(it, hours, nowMs)) }
        return out
    }

    // ------------------------------------------------------------------ values

    private fun Any?.num(): Double? = when (this) {
        null, JSONObject.NULL -> null
        is Number -> toDouble().takeIf { !it.isNaN() }
        is String -> toDoubleOrNull()
        else -> null
    }

    /** [model]'s value of [base] (an array's element at [i], or a plain value when [i] is null). */
    private fun JSONObject.raw(base: String, model: String, i: Int?): Any? {
        val v = opt("${base}_$model") ?: if (model == BEST) opt(base) else null
        val x = if (i == null) v else (v as? JSONArray)?.let { if (i < it.length()) it.opt(i) else null }
        return x?.takeIf { it != JSONObject.NULL }
    }

    private fun JSONObject.num(base: String, model: String, i: Int?) = raw(base, model, i).num()

    private fun mixNum(base: String, s: Double?, l: Double?, w: Double): Double? = when {
        s == null || l == null -> s ?: l
        w >= 1.0 -> s
        w <= 0.0 -> l
        base == DIRECTION -> {
            // Directions blend round the compass (350° and 10° make 0°, not 180°).
            val r = Math.toRadians(s)
            val q = Math.toRadians(l)
            val deg = Math.toDegrees(atan2(w * sin(r) + (1 - w) * sin(q), w * cos(r) + (1 - w) * cos(q)))
            (deg + 360.0) % 360.0
        }
        else -> w * s + (1 - w) * l
    }

    private fun bases(o: JSONObject): List<String> =
        o.keys().asSequence().map { baseOf(it) }.filter { it != "time" && it != "interval" }.distinct().toList()

    /** The variables [model] publishes in [o] (a value for at least one entry). */
    private fun published(o: JSONObject, model: String, bs: List<String>): List<String> = bs.filter { b ->
        val a = o.optJSONArray("${b}_$model") ?: (if (model == BEST) o.optJSONArray(b) else null)
        a != null && (0 until a.length()).any { !a.isNull(it) }
    }

    /**
     * Temperatures: without them a model has no forecast for the entry, whatever else it fills in
     * (Open-Meteo gives every model a rain chance and day/night flag, even past the end of its run).
     */
    private val CORE = setOf("temperature_2m", "temperature_2m_max", "temperature_2m_min")

    /** Whether [model] gives entry [i] whole: its temperature, and every other variable it publishes. */
    private fun JSONObject.covers(model: String, vars: List<String>, i: Int): Boolean {
        val core = keys().asSequence().map { baseOf(it) }.filter { it in CORE }.toSet()
        return core.isNotEmpty() && core.all { raw(it, model, i) != null } && vars.all { raw(it, model, i) != null }
    }

    /**
     * Entry [i]'s value of [base]: [w] of it from [s], the rest from [l] (each a model that gives the
     * entry whole). A value neither publishes comes from best_match.
     */
    private fun JSONObject.pick(base: String, i: Int, s: String, l: String, w: Double): Any? {
        if (base in TIMES) return raw(base, BEST, i) ?: raw(base, SHORT, i) ?: raw(base, LONG, i)
        if (base in CATEGORICAL) {
            val a = raw(base, s, i)
            val b = raw(base, l, i)
            return (if (w >= 0.5) a ?: b else b ?: a) ?: raw(base, BEST, i)
        }
        val a = num(base, s, i)
        val b = num(base, l, i)
        return when {
            w >= 1.0 -> a
            w <= 0.0 -> b
            else -> mixNum(base, a, b, w)
        } ?: num(base, BEST, i)
    }

    // ------------------------------------------------------------------ sections

    /** HRRR's stretch for this answer: the hours ahead of [nowMs] to its last whole hour, at most 48. */
    private fun shortEndH(h: JSONObject, nowMs: Long): Double {
        val time = h.optJSONArray("time") ?: return SHORT_RANGE_H
        val vars = published(h, SHORT, bases(h))
        var last: Double? = null
        for (i in 0 until time.length()) {
            val ahead = (time.optLong(i) * 1000L - nowMs) / HOUR_MS.toDouble()
            if (ahead > SHORT_RANGE_H) break
            if (ahead >= 0 && h.covers(SHORT, vars, i)) last = ahead
        }
        // No HRRR here (outside the US): best_match stands in for it over the same 48 hours.
        return last ?: SHORT_RANGE_H
    }

    private class Plan(val weights: List<Double>, val short: List<String>, val long: List<String>)

    /** For each hour: its share from the short side, and which model gives each side the hour whole. */
    private fun plan(h: JSONObject, nowMs: Long): Plan? {
        val time = h.optJSONArray("time") ?: return null
        val bs = bases(h)
        val sv = published(h, SHORT, bs)
        val lv = published(h, LONG, bs)
        val end = shortEndH(h, nowMs)
        val n = time.length()
        val weights = (0 until n).map { shortWeight(time.optLong(it) * 1000L, nowMs, end) }
        val short = (0 until n).map { if (h.covers(SHORT, sv, it)) SHORT else BEST }
        val long = (0 until n).map { if (h.covers(LONG, lv, it)) LONG else BEST }
        return Plan(weights, short, long)
    }

    private fun blendHourly(h: JSONObject, nowMs: Long): JSONObject {
        val time = h.optJSONArray("time") ?: return h
        val p = plan(h, nowMs) ?: return h
        val out = JSONObject().put("time", time)
        for (b in bases(h)) {
            out.put(b, JSONArray().apply {
                for (i in 0 until time.length()) put(h.pick(b, i, p.short[i], p.long[i], p.weights[i]) ?: JSONObject.NULL)
            })
        }
        return out
    }

    /**
     * Now: read from the hours either side of now, the same models and shares as the hourly strip
     * (HRRR's, or best_match's where HRRR has no hour). Open-Meteo answers `current` once, not per
     * model (it's best_match's); a reading it doesn't give hour by hour (feels like, humidity, UV...)
     * stays best_match's.
     */
    private fun blendCurrent(c: JSONObject, hourly: JSONObject?, nowMs: Long): JSONObject {
        val out = JSONObject()
        c.opt("time")?.let { out.put("time", it) }
        c.opt("interval")?.let { out.put("interval", it) }
        if (hasModelKeys(c)) {
            for (b in bases(c)) {
                val v = if (b in CATEGORICAL) c.raw(b, SHORT, null) ?: c.raw(b, BEST, null)
                else c.num(b, SHORT, null) ?: c.num(b, BEST, null)
                out.put(b, v ?: JSONObject.NULL)
            }
            return out
        }
        val at = hourly?.let { hourAt(it, nowMs) }
        for (b in bases(c)) out.put(b, at?.invoke(b) ?: c.opt(b) ?: JSONObject.NULL)
        return out
    }

    /** The hourly answer at [nowMs], between the hours either side (a code: the nearer hour's). */
    private fun hourAt(h: JSONObject, nowMs: Long): ((String) -> Any?)? {
        val time = h.optJSONArray("time") ?: return null
        val p = plan(h, nowMs) ?: return null
        var i = -1
        for (k in 0 until time.length()) if (time.optLong(k) * 1000L <= nowMs) i = k else break
        if (i < 0) return null
        val f = ((nowMs - time.optLong(i) * 1000L) / HOUR_MS.toDouble()).coerceIn(0.0, 1.0)
        val j = if (i + 1 < time.length()) i + 1 else i
        // A whole hour from a model with no forecast for it is best_match's; best_match's own
        // reading of now is better than an hourly guess at it, so `current` stands then.
        if (p.short[i] == BEST || (f > 0 && p.short[j] == BEST)) return null
        return { b ->
            if (b == "is_day") null else {
                val v0 = h.pick(b, i, p.short[i], p.long[i], p.weights[i])
                val v1 = h.pick(b, j, p.short[j], p.long[j], p.weights[j])
                when {
                    !h.has("${b}_$SHORT") -> null
                    b in CATEGORICAL -> (if (f < 0.5) v0 ?: v1 else v1 ?: v0).num()?.toInt()
                    else -> {
                        val a = v0.num()
                        val z = v1.num()
                        if (a == null || z == null) (if (f < 0.5) a else z)
                        else if (b == DIRECTION) mixNum(b, z, a, f) else a + (z - a) * f
                    }
                }
            }
        }
    }

    /**
     * Today and tomorrow follow their hours: high, low, rain chance and rain total from the same
     * hours the strip shows, so the two can't disagree. From the third day on, ECMWF IFS's own days
     * (each day whole: a day it has no forecast for is best_match's).
     */
    private fun blendDaily(d: JSONObject, hours: JSONObject?, nowMs: Long): JSONObject {
        val time = d.optJSONArray("time") ?: return d
        val out = JSONObject().put("time", time)
        val n = time.length()
        val starts = (0 until n).map { time.optLong(it) * 1000L }
        val bs = bases(d)
        val sv = published(d, SHORT, bs)
        val lv = published(d, LONG, bs)
        // Today's index: the day [nowMs] falls in.
        val today = starts.indexOfLast { it <= nowMs }.coerceAtLeast(0)
        for (b in bs) out.put(b, JSONArray().apply {
            for (i in 0 until n) {
                val near = i < today + SHORT_DAYS
                val m = when {
                    near && d.covers(SHORT, sv, i) -> SHORT
                    near -> BEST
                    d.covers(LONG, lv, i) -> LONG
                    else -> BEST
                }
                put(d.pick(b, i, m, m, 1.0) ?: JSONObject.NULL)
            }
        })

        val ht = hours?.optJSONArray("time") ?: return out
        fun dayValues(key: String, start: Long): List<Double>? {
            val a = hours.optJSONArray(key) ?: return null
            val vs = ArrayList<Double>()
            for (i in 0 until ht.length()) {
                val t = ht.optLong(i) * 1000L
                if (t < start || t >= start + DAY_MS) continue
                vs += (if (i < a.length()) a.opt(i) else null).num() ?: return null
            }
            // A day of 23 or 25 hours (the clocks changing) is still a whole day.
            return vs.takeIf { it.size >= 23 }
        }
        fun derive(dailyKey: String, hourlyKey: String, f: (List<Double>) -> Double) {
            if (dailyKey !in bs) return
            val arr = out.getJSONArray(dailyKey)
            for (i in 0 until minOf(n, today + SHORT_DAYS)) dayValues(hourlyKey, starts[i])?.let { arr.put(i, f(it)) }
        }
        derive("temperature_2m_max", "temperature_2m") { it.max() }
        derive("temperature_2m_min", "temperature_2m") { it.min() }
        derive("precipitation_sum", "precipitation") { it.sum() }
        derive("precipitation_probability_max", "precipitation_probability") { it.max() }
        return out
    }
}
