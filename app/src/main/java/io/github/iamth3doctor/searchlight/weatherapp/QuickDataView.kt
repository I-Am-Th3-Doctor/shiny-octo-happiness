package io.github.iamth3doctor.searchlight.weatherapp

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.Moon
import io.github.iamth3doctor.searchlight.weather.SceneConditions
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherText
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToInt

// Not used by Searchlight: kept for the second launcher's hero screen (the full-screen scene,
// its giant number, callouts, day bar, quick numbers, 7-day view and sound).
/**
 * The quick view: the numbers drawn straight over the dimmed scene when it's tapped. Cloud cover,
 * chance of precipitation, wind (with an arrow), temperature, feels-like, humidity, dew point,
 * the day's max UV, visibility, sunrise and sunset, the moon. The hour-by-hour ones follow the
 * scrubbed hour. The precipitation cell (and the row under the grid) opens the full details.
 */
class QuickDataView(private val context: Context) {

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Opens the full details (precip card, hourly, 7 days). */
    var onMore: (() -> Unit)? = null

    /** Each value by key ("cloud", "precip", "wind", "temp", ...): read by tests. */
    val values = LinkedHashMap<String, TextView>()
    val windArrow = WindArrowView(context).apply { color = 0xFFFFFFFF.toInt() }
    lateinit var precipCell: View
        private set
    lateinit var moreRow: TextView
        private set

    private fun px(t: Int) = context.px(t)
    private val white = 0xFFFFFFFF.toInt()
    private val dim = 0xCCFFFFFF.toInt()

    private val keys = listOf(
        "temp" to R.string.weather_quick_temp,
        "feels" to R.string.weather_quick_feels,
        "cloud" to R.string.weather_quick_cloud,
        "precip" to R.string.weather_quick_precip,
        "wind" to R.string.weather_quick_wind,
        "humidity" to R.string.weather_quick_humidity,
        "dew" to R.string.weather_quick_dew,
        "uv" to R.string.weather_quick_uv,
        "visibility" to R.string.weather_quick_visibility,
        "sun" to R.string.weather_quick_sun,
        "moon" to R.string.weather_quick_moon,
    )

    init {
        val columns = if (context.resources.configuration.screenWidthDp >= 600) 3 else 2
        val gap = px(R.dimen.space_s)
        keys.chunked(columns).forEachIndexed { r, chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0 until columns) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (j > 0) marginStart = gap }
                val entry = chunk.getOrNull(j)
                if (entry == null) { row.addView(View(context), lp); continue }
                row.addView(cell(entry.first, entry.second), lp)
            }
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (r > 0) topMargin = gap })
        }
        moreRow = TextView(context).apply {
            type(Type.BODY)
            typeface = Typeface.create(typeface, 600, false)
            setTextColor(white)
            text = context.getString(R.string.weather_quick_more) + "  ›"
            gravity = Gravity.CENTER
            val p = px(R.dimen.space_s)
            setPadding(p * 2, p, p * 2, p)
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(0x33FFFFFF); setStroke(1, 0x66FFFFFF) }
            setOnClickListener { onMore?.invoke() }
        }
        root.addView(moreRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(R.dimen.space_m); gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun cell(key: String, labelRes: Int): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = px(R.dimen.space_s)
            setPadding(p, p, p, p)
            tag = "weather_quick_$key"
        }
        box.addView(TextView(context).apply {
            type(Type.CAPTION)
            typeface = Typeface.create(typeface, 700, false)
            letterSpacing = 0.08f
            setTextColor(dim)
            text = context.getString(labelRes).uppercase() + if (key == "precip") "  ›" else ""
        })
        val value = TextView(context).apply {
            type(Type.TILE_TITLE)
            typeface = Typeface.create(typeface, 700, false)
            setTextColor(white)
            setShadowLayer(context.pxF(R.dimen.glass_text_shadow), 0f, 1f, 0x99000000.toInt())
        }
        values[key] = value
        if (key == "wind") {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val a = px(R.dimen.space_m) + px(R.dimen.space_s)
            line.addView(windArrow, LinearLayout.LayoutParams(a, a).apply { marginEnd = px(R.dimen.space_xs) })
            line.addView(value)
            box.addView(line)
        } else {
            box.addView(value)
        }
        if (key == "precip") {
            // The way in to the full details.
            box.background = GradientDrawable().apply { cornerRadius = context.pxF(R.dimen.radius_card) / 3; setColor(0x26FFFFFF); setStroke(1, 0x55FFFFFF) }
            box.setOnClickListener { onMore?.invoke() }
            precipCell = box
        }
        return box
    }

    private fun deg(c: Double) = "${WeatherText.f(c)}°F"

    /** Fills the numbers for [c] (the scene showing: now, or the scrubbed hour). */
    fun render(data: WeatherData?, c: SceneConditions) {
        val d = data?.detail
        val none = context.getString(R.string.weather_detail_none)
        values["temp"]?.text = deg(c.tempC)
        values["feels"]?.text = if (c.live) d?.feelsLikeC?.let(::deg) ?: approxFeels(c) else approxFeels(c)
        values["cloud"]?.text = "${(c.cloudCover * 100).roundToInt()}%"
        values["precip"]?.text = c.precipProb?.let { "$it%" } ?: none
        values["wind"]?.text = "${c.windMph.roundToInt()} mph ${WeatherText.compass(c.windFromDeg)}"
        windArrow.set(c.windTowardDeg, c.windMph)
        values["humidity"]?.text = d?.humidity?.let { "$it%" } ?: none
        values["dew"]?.text = d?.dewPointC?.let(::deg) ?: none
        val zone = ZoneOffset.ofTotalSeconds(d?.utcOffsetSec ?: 0)
        val day = d?.daily?.lastOrNull { it.date <= c.timeMs } ?: d?.daily?.firstOrNull()
        values["uv"]?.text = (day?.uvMax ?: d?.uvIndex)?.let { "${it.roundToInt()} · ${WeatherText.uvRating(it)}" } ?: none
        values["visibility"]?.text = d?.visibilityM?.let { WeatherText.visibility(it) } ?: none
        val tf = WeatherFormat.time(context)
        fun at(ms: Long?) = ms?.let { tf.format(Instant.ofEpochMilli(it).atOffset(zone)) } ?: none
        values["sun"]?.text = "↑ ${at(day?.sunrise)}  ↓ ${at(day?.sunset)}"
        values["moon"]?.text = "${Moon.phase(c.timeMs).label} · " +
            context.getString(R.string.weather_detail_lit, (Moon.illumination(c.timeMs) * 100).roundToInt())
    }

    /** A scrubbed hour has no feels-like of its own: wind chill below 50°F, else the temperature. */
    private fun approxFeels(c: SceneConditions): String {
        val f = c.tempC * 9 / 5 + 32
        val v = c.windMph
        val feels = if (f <= 50 && v > 3) 35.74 + 0.6215 * f - 35.75 * Math.pow(v, 0.16) + 0.4275 * f * Math.pow(v, 0.16) else f
        return "${feels.roundToInt()}°F"
    }
}
