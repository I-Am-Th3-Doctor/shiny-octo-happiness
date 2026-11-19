package io.github.iamth3doctor.searchlight.weather

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Picks the best Open-Meteo model for each stretch of the forecast, from one request that asks for
 * several (`models=best_match,gfs_hrrr,ecmwf_ifs`; Open-Meteo then names each variable after its
 * model, e.g. `temperature_2m_gfs_hrrr`):
 *
 *  - the next 48 hours (now, the hourly strip, the scrub bar, the precip card, the time-lapse hours)
 *    come from HRRR, NOAA's 3 km model, updated every hour: the best at short-range rain timing in
 *    the US;
 *  - days 3 to 7 come from ECMWF IFS (9 km), the best global model at medium range;
 *  - across [BLEND_FROM_H]..[BLEND_TO_H] hours ahead the two are cross-faded hour by hour, so the
 *    temperature and rain chance don't jump where one model hands over to the other;
 *  - a value one model doesn't give (HRRR outside the US, or past its run; rain chance, UV or
 *    visibility, which some models don't report) is taken from Open-Meteo's `best_match`, that
 *    value only.
 *
 * The result is shaped like a single-model answer (plain variable names), so the parsers read it as
 * before. An answer with no model names in it (a single-model request) passes through unchanged.
 * Pure; JVM-tested.
 */
object ModelBlend {

    const val BEST = "best_match"
    const val SHORT = "gfs_hrrr"
    const val LONG = "ecmwf_ifs"
    val MODELS = listOf(BEST, SHORT, LONG)

    /** HRRR alone up to here (hours ahead of now)... */
    const val BLEND_FROM_H = 40.0
    /** ...ECMWF alone from here; in between, a cross-fade. */
    const val BLEND_TO_H = 48.0

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    /** How much of the moment [t] comes from the short-range model (1: all HRRR; 0: all ECMWF). */
    fun shortWeight(t: Long, nowMs: Long): Double {
        val h = (t - nowMs) / HOUR_MS.toDouble()
        return when {
            h <= BLEND_FROM_H -> 1.0
            h >= BLEND_TO_H -> 0.0
            else -> (BLEND_TO_H - h) / (BLEND_TO_H - BLEND_FROM_H)
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
        current?.let { out.put("current", blendCurrent(it, hourly, nowMs)) }
        val hours = hourly?.let { blendHourly(it, nowMs) }
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

    /**
     * [base] at [i], [w] of it from HRRR and the rest from ECMWF, each falling back to best_match
     * where it has no value.
     */
    private fun JSONObject.mix(base: String, i: Int?, w: Double): Any? {
        if (base in TIMES) return raw(base, BEST, i) ?: raw(base, SHORT, i) ?: raw(base, LONG, i)
        if (base in CATEGORICAL) {
            val s = raw(base, SHORT, i) ?: raw(base, BEST, i)
            val l = raw(base, LONG, i) ?: raw(base, BEST, i)
            return if (w >= 0.5) s ?: l else l ?: s
        }
        val best = num(base, BEST, i)
        val s = num(base, SHORT, i) ?: best
        val l = num(base, LONG, i) ?: best
        return when {
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
    }

    private fun bases(o: JSONObject): List<String> =
        o.keys().asSequence().map { baseOf(it) }.filter { it != "time" && it != "interval" }.distinct().toList()

    private fun JSONObject.length(): Int =
        keys().asSequence().mapNotNull { optJSONArray(it)?.length() }.maxOrNull() ?: 0

    // ------------------------------------------------------------------ sections

    /**
     * Now: HRRR's reading (best_match where it has none). Open-Meteo answers `current` once, not per
     * model (it's best_match's); then each value HRRR gives hour by hour is HRRR's, read between the
     * hours either side of now, and the rest stay best_match's.
     */
    private fun blendCurrent(c: JSONObject, hourly: JSONObject?, nowMs: Long): JSONObject {
        val out = JSONObject()
        c.opt("time")?.let { out.put("time", it) }
        c.opt("interval")?.let { out.put("interval", it) }
        val split = hasModelKeys(c)
        for (b in bases(c)) {
            val v = if (split) c.mix(b, null, 1.0) else hourly?.let { shortAt(it, b, nowMs) } ?: c.opt(b)
            out.put(b, v ?: JSONObject.NULL)
        }
        return out
    }

    /** HRRR's [base] at [nowMs], between the hours either side (a code or flag: the nearer hour's); null if it has none. */
    private fun shortAt(h: JSONObject, base: String, nowMs: Long): Any? {
        if (base == "is_day") return null
        val time = h.optJSONArray("time") ?: return null
        val a = h.optJSONArray("${base}_$SHORT") ?: return null
        var i = -1
        for (k in 0 until time.length()) if (time.optLong(k) * 1000L <= nowMs) i = k else break
        if (i < 0) return null
        val t0 = time.optLong(i) * 1000L
        val f = ((nowMs - t0) / HOUR_MS.toDouble()).coerceIn(0.0, 1.0)
        val v0 = a.opt(i).num()
        val v1 = if (i + 1 < a.length()) a.opt(i + 1).num() else null
        if (base in CATEGORICAL) return (if (f < 0.5 || v1 == null) v0 ?: v1 else v1)?.toInt()
        if (v0 == null || v1 == null) return if (f < 0.5) v0 else v1
        if (base == DIRECTION) {
            val r = Math.toRadians(v0)
            val q = Math.toRadians(v1)
            return (Math.toDegrees(atan2((1 - f) * sin(r) + f * sin(q), (1 - f) * cos(r) + f * cos(q))) + 360.0) % 360.0
        }
        return v0 + (v1 - v0) * f
    }

    private fun blendHourly(h: JSONObject, nowMs: Long): JSONObject {
        val time = h.optJSONArray("time") ?: return h
        val out = JSONObject().put("time", time)
        val weights = (0 until time.length()).map { shortWeight(time.optLong(it) * 1000L, nowMs) }
        for (b in bases(h)) {
            out.put(b, JSONArray().apply { weights.forEachIndexed { i, w -> put(h.mix(b, i, w) ?: JSONObject.NULL) } })
        }
        return out
    }

    /**
     * Each day takes its models in the share its hours do. A day the blended hours cover whole gets
     * its high, low, rain chance and rain total from those hours, so the 7-day list agrees with the
     * hourly strip and the time-lapse hour for hour, across the hand-over too.
     */
    private fun blendDaily(d: JSONObject, hours: JSONObject?, nowMs: Long): JSONObject {
        val time = d.optJSONArray("time") ?: return d
        val out = JSONObject().put("time", time)
        val n = time.length()
        val starts = (0 until n).map { time.optLong(it) * 1000L }
        val weights = starts.map { s -> (0 until 24).sumOf { k -> shortWeight(s + k * HOUR_MS + HOUR_MS / 2, nowMs) } / 24.0 }
        val bs = bases(d)
        for (b in bs) out.put(b, JSONArray().apply { weights.forEachIndexed { i, w -> put(d.mix(b, i, w) ?: JSONObject.NULL) } })

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
            starts.forEachIndexed { i, s -> dayValues(hourlyKey, s)?.let { arr.put(i, f(it)) } }
        }
        derive("temperature_2m_max", "temperature_2m") { it.max() }
        derive("temperature_2m_min", "temperature_2m") { it.min() }
        derive("precipitation_sum", "precipitation") { it.sum() }
        derive("precipitation_probability_max", "precipitation_probability") { it.max() }
        return out
    }
}
