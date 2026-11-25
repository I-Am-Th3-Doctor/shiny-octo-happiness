package io.github.iamth3doctor.searchlight.weather

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.Locale

/** One National Weather Service alert (a warning, watch, advisory or statement); times in epoch ms. */
data class WeatherAlert(
    val id: String,
    val event: String,
    val headline: String,
    val severity: String,
    val effective: Long?,
    val expires: Long?,
    val description: String,
    val instruction: String,
    val sender: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("event", event).put("headline", headline).put("severity", severity)
        .putOpt("eff", effective).putOpt("exp", expires)
        .put("desc", description).put("inst", instruction).put("from", sender)

    companion object {
        fun fromJson(o: JSONObject) = WeatherAlert(
            o.getString("id"), o.getString("event"), o.optString("headline"), o.optString("severity"),
            if (o.has("eff")) o.getLong("eff") else null, if (o.has("exp")) o.getLong("exp") else null,
            o.optString("desc"), o.optString("inst"), o.optString("from"),
        )
    }
}

/** What an alert looks like in the scene. */
enum class AlertKind { THUNDER, RAIN, WIND, SNOW, FOG, HEAT, OTHER }

/**
 * Severe weather alerts from the National Weather Service (api.weather.gov: free, no key; US only).
 * Asked for by point; only alerts in effect count. Pure; JVM-tested.
 */
object NwsAlerts {

    /** The active alerts at a point (NWS takes at most four decimals). */
    fun url(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "https://api.weather.gov/alerts/active?point=%.4f,%.4f", latitude, longitude)

    private fun time(s: String?): Long? =
        s?.takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /** The alerts in a GeoJSON response that are real and still in effect at [nowMs], most severe first. */
    fun parse(json: String, nowMs: Long): List<WeatherAlert> {
        val features: JSONArray = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<WeatherAlert>()
        for (i in 0 until features.length()) {
            val p = features.getJSONObject(i).optJSONObject("properties") ?: continue
            if (p.optString("status") != "Actual") continue // no drills or tests
            if (p.optString("messageType") == "Cancel") continue
            val expires = time(p.optString("ends").takeIf { it.isNotBlank() && it != "null" } ?: p.optString("expires"))
            if (expires != null && expires <= nowMs) continue
            fun str(k: String) = if (p.isNull(k)) "" else p.optString(k).trim()
            out += WeatherAlert(
                id = str("id").ifBlank { str("@id") },
                event = str("event"),
                headline = str("headline"),
                severity = str("severity"),
                effective = time(p.optString("onset").takeIf { it.isNotBlank() && it != "null" } ?: p.optString("effective")),
                expires = expires,
                description = str("description"),
                instruction = str("instruction"),
                sender = str("senderName"),
            )
        }
        return out.distinctBy { it.id }.sortedBy { rank(it.severity) }
    }

    private fun rank(severity: String) = when (severity) {
        "Extreme" -> 0
        "Severe" -> 1
        "Moderate" -> 2
        "Minor" -> 3
        else -> 4
    }

    /** The alerts in effect at [t] (started, not yet expired). */
    fun active(alerts: List<WeatherAlert>?, t: Long): List<WeatherAlert> =
        alerts.orEmpty().filter { (it.effective == null || it.effective <= t) && (it.expires == null || t < it.expires) }

    fun kind(event: String): AlertKind {
        val e = event.lowercase()
        return when {
            "thunder" in e || "tornado" in e -> AlertKind.THUNDER
            "winter" in e || "snow" in e || "blizzard" in e || "ice" in e || "freez" in e || "sleet" in e -> AlertKind.SNOW
            "flood" in e || "rain" in e || "hydrologic" in e -> AlertKind.RAIN
            "wind" in e || "hurricane" in e || "tropical" in e || "gale" in e || "storm" in e -> AlertKind.WIND
            "fog" in e -> AlertKind.FOG
            "heat" in e || "fire" in e || "red flag" in e || "smoke" in e || "air quality" in e || "dust" in e -> AlertKind.HEAT
            else -> AlertKind.OTHER
        }
    }

    /** The pill's words: "! THUNDERSTORM !" for a Severe Thunderstorm Warning, "! FLOOD WATCH !", ... */
    fun pill(a: WeatherAlert): String {
        var words = a.event.uppercase(Locale.US).trim()
        if (words.endsWith(" WARNING")) words = words.removeSuffix(" WARNING")
        if (words.startsWith("SEVERE ")) words = words.removePrefix("SEVERE ")
        return "! $words !"
    }
}

/** Precipitation for the precip card, from the hourly forecast only (never minute by minute). Pure; JVM-tested. */
object Precip {

    enum class Type { RAIN, SNOW, NONE }

    /** A precipitation reading: rate (in/h; a day's total in WEEK), chance, type, and the sky to draw. */
    data class Reading(val rateIn: Double, val chance: Int?, val type: Type, val sky: SceneModel.Sky)

    private const val HOUR = 3_600_000L

    fun inches(mm: Double) = mm / 25.4

    /** "0.09 IN" */
    fun format(inches: Double): String = String.format(Locale.US, "%.2f IN", inches)

    private fun typeOf(sky: SceneModel.Sky): Type = when (sky) {
        SceneModel.Sky.SNOW -> Type.SNOW
        SceneModel.Sky.RAIN -> Type.RAIN
        else -> Type.NONE
    }

    private fun reading(rateMm: Double?, chance: Int?, sky: SceneModel.Sky) =
        Reading(inches(rateMm ?: 0.0), chance, typeOf(sky), sky)

    private fun hourAt(data: WeatherData, t: Long): HourPoint? =
        data.detail?.hourly?.lastOrNull { it.time <= t && t < it.time + HOUR }

    /**
     * Now, as the page shows it: the displayed condition with the current hour's rate and chance,
     * through the same rule as the scene ([SceneModel.sky]), so the TYPE, the island and the scene
     * can't disagree with the condition above them.
     */
    fun now(data: WeatherData, nowMs: Long): Reading {
        val h = hourAt(data, nowMs)
        // The same rate and chance the scene uses for now.
        val rate = data.detail?.precipMm ?: h?.precipMm
        val sky = SceneModel.sky(data.weatherCode, rate, h?.precipProb)
        // TYPE: what's falling now, else what the strip shows coming in the next hours.
        val coming = data.detail?.hourly.orEmpty()
            .filter { it.time + HOUR > nowMs && it.time < nowMs + TYPE_AHEAD_MS }
        val type = when {
            sky == SceneModel.Sky.SNOW -> Type.SNOW
            sky == SceneModel.Sky.RAIN -> Type.RAIN
            coming.any { SceneModel.isSnow(it.code) } && coming.none { SceneModel.isWet(it.code) } -> Type.SNOW
            coming.any { SceneModel.isWet(it.code) || SceneModel.isSnow(it.code) || (it.precipMm ?: 0.0) > 0.0 } -> Type.RAIN
            else -> Type.NONE
        }
        return Reading(inches(rate ?: 0.0), h?.precipProb, type, sky)
    }

    /** TYPE now looks this far ahead: rain the strip shows within it isn't NONE. */
    const val TYPE_AHEAD_MS = 6 * HOUR

    /** The hour holding [t]: its rate (in/h), chance and type. */
    fun at(data: WeatherData, t: Long): Reading {
        val h = hourAt(data, t)
            ?: return reading(data.detail?.precipMm, null, SceneModel.sky(data.weatherCode, data.detail?.precipMm, null))
        return reading(h.precipMm, h.precipProb, SceneModel.sky(h.code, h.precipMm, h.precipProb))
    }

    /** The day holding [t]: its total (in), highest chance and type (from its condition and chance). */
    fun day(data: WeatherData, t: Long): Reading {
        val d = data.detail?.daily?.lastOrNull { it.date <= t } ?: return Reading(0.0, null, Type.NONE, SceneModel.Sky.CLOUD)
        return reading(d.precipMm, d.precipPct, SceneModel.sky(d.code, null, d.precipPct))
    }

    /**
     * Whether [h] is an hour of rain or snow: a rain, shower or snow condition (the hourly strip's
     * icon), any amount (the graph's area), or a 50%+ chance (the graph's line). The soonest such
     * hour is when rain is "expected around", so the card, the strip and the graph agree.
     */
    fun wet(h: HourPoint) = SceneModel.isWet(h.code) || SceneModel.isSnow(h.code) ||
        (h.precipProb ?: 0) >= 50 || (h.precipMm ?: 0.0) > 0.0

    /** When rain is next expected, as the hour (epoch ms), within [withinMs]; the current hour if it's wet now; null if dry. */
    fun nextWet(data: WeatherData, nowMs: Long, withinMs: Long = 24 * HOUR): HourPoint? =
        data.detail?.hourly.orEmpty().firstOrNull { it.time + HOUR > nowMs && it.time < nowMs + withinMs && wet(it) }
}
