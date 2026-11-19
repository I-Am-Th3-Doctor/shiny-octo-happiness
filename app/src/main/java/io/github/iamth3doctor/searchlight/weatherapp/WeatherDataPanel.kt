package io.github.iamth3doctor.searchlight.weatherapp

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.ConditionVisual
import io.github.iamth3doctor.searchlight.weather.Moon
import io.github.iamth3doctor.searchlight.weather.WeatherCodes
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import io.github.iamth3doctor.searchlight.weather.WeatherText
import io.github.iamth3doctor.searchlight.widgets.ConditionView
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Date
import io.github.iamth3doctor.searchlight.weather.MetricArt
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The numbers behind the scene, shown when it's tapped: now (temp, condition, feels-like,
 * high/low, last updated), the day in a sentence, the next 24 hours, 7 days, then sun, moon,
 * humidity and dew point, visibility, pressure, UV, air quality, wind. All °F, the place's own
 * times. Light or dark to match the system ([light]).
 */
class WeatherDataPanel(private val context: Context, private val light: Boolean) {

    /** The content: rebuilt by [render]. */
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Each section by key ("now", "summary", "hourly", "daily", "sun", "moon", ...): read by tests. */
    val sections = LinkedHashMap<String, View>()

    /** Now, in ms. */
    var clock: () -> Long = System::currentTimeMillis

    /** Holding a day in the 7 DAYS list: play it ([onHoldDay] with its index) until let go ([onReleaseDay]). */
    var onHoldDay: ((Int) -> Unit)? = null
    var onReleaseDay: (() -> Unit)? = null
    /** The day being held, or null. */
    var heldDay: () -> Int? = { null }

    /** The 7 DAYS rows (tests). */
    val dayRows = ArrayList<View>()

    /** The precip card (kept across renders, so its graph and island keep their state). */
    val precip = PrecipCard(context, light)

    private fun px(token: Int) = context.px(token)
    private fun pxF(token: Int) = context.pxF(token)

    private val fg = if (light) 0xFF1C1C21.toInt() else context.color(R.color.on_surface)
    private val dim = if (light) 0xA61C1C21.toInt() else context.color(R.color.on_surface_dim)
    private val accent = context.color(if (light) R.color.accent else R.color.gold)
    private val insetColor = if (light) 0x14000000 else context.color(R.color.surface_inset)

    private fun text(style: Int, dimmed: Boolean = false, bold: Boolean = false) = TextView(context).apply {
        type(style)
        if (bold) typeface = Typeface.create(typeface, 700, false) // Lexend Bold itself, never a faked heavier one
        setTextColor(if (dimmed) dim else fg)
        includeFontPadding = false
    }

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    private fun label(res: Int) = text(Type.CAPTION, bold = true).apply {
        text = context.getString(res).uppercase()
        setTextColor(accent)
        letterSpacing = 0.08f
    }

    private fun section(key: String, view: View, top: Int = px(R.dimen.space_m)) {
        view.tag = "weather_section_$key"
        sections[key] = view
        body.addView(view, lp(top))
    }

    private fun inset() = GradientDrawable().apply {
        cornerRadius = pxF(R.dimen.radius_card) / 2
        setColor(insetColor)
    }

    private fun deg(c: Double) = "${WeatherText.f(c)}°"

    /** Marks day [i]'s row as the one playing (or not). */
    fun highlightDay(i: Int, on: Boolean) {
        (dayRows.getOrNull(i)?.background as? GradientDrawable)?.setColor(if (on) (if (light) 0x1A000000 else 0x26FFFFFF) else 0)
    }

    fun render(state: WeatherRepository.State) {
        body.removeAllViews()
        sections.clear()
        val data = state.data
        if (data == null) {
            section("now", text(Type.BODY, dimmed = true).apply {
                text = context.getString(
                    when (state.problem) {
                        WeatherRepository.Problem.NEEDS_LOCATION -> R.string.weather_needs_location
                        WeatherRepository.Problem.LOCATION_UNAVAILABLE -> R.string.weather_no_fix
                        WeatherRepository.Problem.NETWORK -> R.string.weather_failed
                        null -> R.string.weather_loading
                    },
                )
            }, top = 0)
            return
        }
        now(data)
        summary(data)
        (precip.root.parent as? ViewGroup)?.removeView(precip.root)
        precip.root.background = inset()
        val p = px(R.dimen.space_s) + px(R.dimen.space_xs)
        precip.root.setPadding(p, p, p, p)
        section("precip", precip.root)
        hourly(data)
        daily(data)
        tiles(data)
    }

    private fun now(data: WeatherData) {
        val d = data.detail
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(Type.TILE_TITLE, bold = true).apply { text = data.locationName })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(Type.GLANCE, bold = true).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, pxF(R.dimen.text_weather_detail_temp))
            text = "${WeatherText.f(data.temperatureC)}°F"
        })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(Type.BODY, bold = true).apply { text = data.condition })
        d?.feelsLikeC?.let { f -> col.addView(text(Type.BODY, dimmed = true).apply { text = context.getString(R.string.weather_widget_feels, deg(f)) }) }
        col.addView(text(Type.BODY, dimmed = true).apply { text = context.getString(R.string.weather_widget_hi_lo, deg(data.highC), deg(data.lowC)) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(R.dimen.space_m) })
        box.addView(row, lp(px(R.dimen.space_xs)))
        box.addView(text(Type.CAPTION, dimmed = true).apply {
            text = context.getString(R.string.weather_widget_updated, DateFormat.getTimeFormat(context).format(Date(data.fetchedAt)))
            tag = "weather_updated"
        }, lp(px(R.dimen.space_xs)))
        section("now", box, top = 0)
    }

    private fun summary(data: WeatherData) {
        val d = data.detail ?: return
        val (dayCode, nightCode) = WeatherText.dayAndNight(d.hourly, data.weatherCode, clock(), d.utcOffsetSec)
        section("summary", text(Type.BODY).apply {
            text = WeatherText.daySummary(d.daily.firstOrNull()?.highC ?: data.highC, dayCode, nightCode)
            setLineSpacing(0f, 1.2f)
        }, top = px(R.dimen.space_s))
    }

    private fun hourly(data: WeatherData) {
        val d = data.detail ?: return
        val now = clock()
        val hours = d.hourly.filter { it.time > now - 3_600_000L }.take(24)
        if (hours.isEmpty()) return
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(R.string.weather_detail_hourly))
        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val zone = ZoneOffset.ofTotalSeconds(d.utcOffsetSec)
        val fmt = WeatherFormat.hour(context)
        val hv = px(R.dimen.size_weather_visual_hour) + px(R.dimen.space_xs)
        hours.forEachIndexed { i, h ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val p = px(R.dimen.space_s)
                setPadding(p, p, p, p)
                tag = "weather_hour"
            }
            cell.addView(text(Type.CAPTION, dimmed = true).apply {
                text = if (i == 0 && h.time <= now) context.getString(R.string.weather_detail_now_hour) else fmt.format(Instant.ofEpochMilli(h.time).atOffset(zone))
            })
            cell.addView(ConditionView(context, glow = false).apply { visual = ConditionVisual.of(h.code, h.isDay) },
                LinearLayout.LayoutParams(hv, hv).apply { topMargin = px(R.dimen.space_xs); bottomMargin = px(R.dimen.space_xs) })
            cell.addView(text(Type.BODY, bold = true).apply { text = deg(h.tempC) })
            h.precipProb?.takeIf { it >= 10 }?.let { p ->
                cell.addView(text(Type.CAPTION).apply { text = "$p%"; setTextColor(context.color(R.color.weather_drop)) })
            }
            strip.addView(cell)
        }
        box.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
            background = inset()
        }, lp(px(R.dimen.space_xs)))
        section("hourly", box)
    }

    private fun daily(data: WeatherData) {
        val d = data.detail ?: return
        if (d.daily.isEmpty()) return
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(R.string.weather_detail_daily))
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = inset()
            val p = px(R.dimen.space_s)
            setPadding(p + px(R.dimen.space_xs), p / 2, p + px(R.dimen.space_xs), p / 2)
        }
        val zone = ZoneOffset.ofTotalSeconds(d.utcOffsetSec)
        val locale = context.resources.configuration.locales[0]
        dayRows.clear()
        val hv = px(R.dimen.size_weather_visual_hour)
        val col = px(R.dimen.size_cal_chevron)
        d.daily.take(7).forEachIndexed { i, day ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = col
                tag = "weather_day"
            }
            val name = if (i == 0) context.getString(R.string.weather_detail_today)
                else Instant.ofEpochMilli(day.date).atOffset(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            row.addView(text(Type.BODY, bold = i == 0).apply { text = name }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ConditionView(context, glow = false).apply { visual = ConditionVisual.of(day.code, true) }, LinearLayout.LayoutParams(hv, hv))
            row.addView(text(Type.CAPTION).apply {
                text = day.precipPct?.let { "$it%" } ?: ""
                setTextColor(context.color(R.color.weather_drop))
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(col, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(text(Type.BODY, dimmed = true).apply { text = deg(day.lowC); gravity = Gravity.END },
                LinearLayout.LayoutParams(col + px(R.dimen.space_s), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(text(Type.BODY, bold = true).apply { text = deg(day.highC); gravity = Gravity.END },
                LinearLayout.LayoutParams(col + px(R.dimen.space_s), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.contentDescription = "$name, ${WeatherCodes.describe(day.code)}, high ${deg(day.highC)}, low ${deg(day.lowC)}" +
                (day.precipPct?.let { ", $it% chance of precipitation" } ?: "") + ". Hold to preview."
            // Hold a day: it plays as a time-lapse at the top of the screen until let go.
            row.background = GradientDrawable().apply { cornerRadius = pxF(R.dimen.radius_card) / 3; setColor(0) }
            DayLapseWindow.holdToPlay(row, isHeld = { heldDay() == i },
                onHold = { onHoldDay?.invoke(i) },
                onRelease = { onReleaseDay?.invoke() })
            dayRows += row
            list.addView(row, lp())
        }
        box.addView(list, lp(px(R.dimen.space_xs)))
        section("daily", box)
    }

    /** The open metric card ("sun", "moon", ...), or null. Stays open when the details rebuild. */
    var expanded: String? = null
        private set

    /** The metric cards by key (tests). */
    val cards = LinkedHashMap<String, MetricTile>()

    /** Tests: whether opening a card animates (null: as the system's animation setting says). */
    var animateOverride: Boolean? = null

    /** The clock the open/close easing runs on (tests step it a frame at a time). */
    var expandClock: () -> Long = { android.os.SystemClock.uptimeMillis() }

    private val moving = HashMap<String, Runnable>()
    private val ease = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private fun tile(key: String, labelRes: Int, value: String, sub: String?, art: ArtInfo, note: String): MetricTile {
        val t = MetricTile(context, key, light).apply {
            background = inset()
            val p = px(R.dimen.space_s) + px(R.dimen.space_xs)
            setPadding(p, p, p, p)
        }
        t.addHeader(label(labelRes), lp())
        t.addHeader(text(Type.TILE_TITLE, bold = true).apply {
            text = value
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(px(R.dimen.space_xs)))
        if (!sub.isNullOrBlank()) t.addHeader(text(Type.BODY, dimmed = true).apply { text = sub }, lp(px(R.dimen.space_xs) / 2))
        t.art.info = art
        t.note.apply {
            type(Type.BODY)
            setTextColor(fg)
            text = note
            setLineSpacing(0f, 1.15f)
            tag = "weather_metric_note"
        }
        t.finish(px(R.dimen.size_weather_metric_art), px(R.dimen.space_s))
        t.setOnClickListener { toggle(key) }
        androidx.core.view.ViewCompat.setStateDescription(t, context.getString(if (expanded == key) R.string.weather_metric_open else R.string.weather_metric_hint))
        if (expanded == key) {
            t.expander.open = 1f
            t.art.active = true
        }
        sections[key] = t
        cards[key] = t
        return t
    }

    /** Opens card [key] (closing any other), or closes it if it's the one open. */
    fun toggle(key: String) {
        val old = expanded
        expanded = if (old == key) null else key
        old?.let { k -> cards[k]?.let { slide(it, open = false) } }
        expanded?.let { k -> cards[k]?.let { slide(it, open = true) } }
    }

    /** Eases [t]'s picture open or shut: its height grows or shrinks, the cards below follow. */
    private fun slide(t: MetricTile, open: Boolean) {
        moving.remove(t.key)?.let { t.removeCallbacks(it) }
        val from = t.expander.open
        val to = if (open) 1f else 0f
        if (open) t.art.active = true
        androidx.core.view.ViewCompat.setStateDescription(t, context.getString(if (open) R.string.weather_metric_open else R.string.weather_metric_hint))
        fun done() {
            moving.remove(t.key)
            if (open) t.requestRectangleOnScreen(android.graphics.Rect(0, 0, t.width, t.height)) else t.art.active = false
        }
        if (!(animateOverride ?: android.animation.ValueAnimator.areAnimatorsEnabled())) {
            t.expander.open = to
            done()
            return
        }
        val start = expandClock()
        val ms = (EXPAND_MS * abs(to - from)).toLong().coerceAtLeast(1)
        val step = object : Runnable {
            override fun run() {
                val f = ((expandClock() - start).toFloat() / ms).coerceIn(0f, 1f)
                t.expander.open = from + (to - from) * ease.getInterpolation(f)
                if (f < 1f) t.postOnAnimation(this) else done()
            }
        }
        moving[t.key] = step
        t.postOnAnimation(step)
    }

    private fun tiles(data: WeatherData) {
        val d = data.detail ?: return
        moving.clear()
        cards.clear()
        val zone = ZoneOffset.ofTotalSeconds(d.utcOffsetSec)
        val tf = WeatherFormat.time(context)
        fun at(ms: Long?) = ms?.let { tf.format(Instant.ofEpochMilli(it).atOffset(zone)) }
        val none = context.getString(R.string.weather_detail_none)
        val today = d.daily.firstOrNull()
        val now = clock()
        val dayStart = Moon.dayStart(now, d.utcOffsetSec)
        val nowF = ((now - dayStart).toDouble() / DAY_MS).coerceIn(0.0, 1.0)
        val moon = Moon.times(dayStart, data.latitude, data.longitude)

        // Sun: its arc today, where it is on it, how long the day is.
        val rise = today?.sunrise
        val set = today?.sunset
        val sunNote = if (rise != null && set != null) {
            context.getString(R.string.weather_metric_daylight, MetricArt.duration(set - rise)) + " · " + when {
                now < rise -> context.getString(R.string.weather_metric_sun_before, MetricArt.duration(rise - now))
                now < set -> context.getString(R.string.weather_metric_sun_up, MetricArt.duration(set - now))
                else -> context.getString(R.string.weather_metric_sun_down)
            }
        } else none
        val sunArt = ArtInfo("sun", sunF = if (rise != null && set != null) MetricArt.sunFraction(rise, set, now) else null,
            riseText = at(rise), setText = at(set))

        // Moon: its phase as it is, and when it's up (and up in a dark sky) today.
        val moonDay = MetricArt.moonDay(dayStart, data.latitude, data.longitude)
        val hf = WeatherFormat.hour(context)
        val ticks = (0 until 4).map { hf.format(Instant.ofEpochMilli(dayStart + it * 6 * 3_600_000L).atOffset(zone)) }
        val lit = (Moon.illumination(now) * 100).roundToInt()
        val firstDark = moonDay.visible.indexOfFirst { it }
        val moonNote = Moon.phase(now).label + " · " + context.getString(R.string.weather_detail_lit, lit) + "\n" + when {
            !moonDay.upAtAll -> context.getString(R.string.weather_metric_moon_none)
            firstDark < 0 -> context.getString(R.string.weather_metric_moon_day)
            else -> {
                var end = firstDark
                while (end + 1 < moonDay.steps && moonDay.visible[end + 1]) end++
                fun step(k: Int) = dayStart + k * MetricArt.STEP_MIN * 60_000L
                context.getString(R.string.weather_metric_moon_dark, "${at(step(firstDark))}–${at(step(end + 1))}")
            }
        }
        val moonArt = ArtInfo("moon", illumination = Moon.illumination(now), waxing = MetricArt.waxing(now), moonDay = moonDay,
            nowF = nowF, ticks = ticks)

        val humNote = d.dewPointC?.let { dew ->
            context.getString(R.string.weather_metric_dew, deg(dew), MetricArt.humidityNote(data.temperatureC, dew))
        } ?: none
        val visNote = d.visibilityM?.let { context.getString(R.string.weather_metric_vis, WeatherText.visibility(it), WeatherText.visibilityRating(it)) } ?: none
        val trend = MetricArt.pressureTrend(d.hourly, now)
        val presNote = d.pressureHpa?.let { p ->
            (trend?.let { "${WeatherText.pressure(p)} ${it.arrow} ${it.note}\n" } ?: "") + MetricArt.pressureLevel(p)
        } ?: none
        val uvNote = d.uvIndex?.let { context.getString(R.string.weather_metric_uv, it.roundToInt().toString(), WeatherText.uvRating(it), MetricArt.uvAdvice(it)) } ?: none
        val aqiNote = d.aqi?.let { context.getString(R.string.weather_metric_aqi, it, WeatherText.aqiRating(it), MetricArt.aqiAdvice(it)) } ?: none
        val windNote = d.windMph?.let { mph ->
            if (mph.roundToInt() < 1) context.getString(R.string.weather_metric_calm)
            else context.getString(R.string.weather_metric_wind, mph.roundToInt().toString(),
                WeatherText.compass(d.windFromDeg ?: 0.0), MetricArt.windWord(mph))
        } ?: none

        val list = listOf(
            tile("sun", R.string.weather_detail_sun, "↑ ${at(today?.sunrise) ?: none}", "↓ ${at(today?.sunset) ?: none}", sunArt, sunNote),
            tile("moon", R.string.weather_detail_moon, Moon.phase(now).label,
                "↑ ${at(moon.rise) ?: context.getString(R.string.weather_detail_no_rise)}   ↓ ${at(moon.set) ?: context.getString(R.string.weather_detail_no_set)}" +
                    "\n" + context.getString(R.string.weather_detail_lit, lit), moonArt, moonNote),
            tile("humidity", R.string.weather_detail_humidity, d.humidity?.let { "$it%" } ?: none,
                d.dewPointC?.let { context.getString(R.string.weather_detail_dew, deg(it)) },
                ArtInfo("humidity", humidity = (d.humidity ?: 0) / 100.0), humNote),
            tile("visibility", R.string.weather_detail_visibility, d.visibilityM?.let { WeatherText.visibility(it) } ?: none,
                d.visibilityM?.let { WeatherText.visibilityRating(it) },
                ArtInfo("visibility", visMiles = d.visibilityM?.let { WeatherText.miles(it) } ?: 0.0), visNote),
            tile("pressure", R.string.weather_detail_pressure, d.pressureHpa?.let { WeatherText.pressure(it) } ?: none, null,
                ArtInfo("pressure", pressureF = d.pressureHpa?.let { MetricArt.pressureFraction(it) } ?: 0.5, trend = trend), presNote),
            tile("uv", R.string.weather_detail_uv, d.uvIndex?.let { it.roundToInt().toString() } ?: none, d.uvIndex?.let { WeatherText.uvRating(it) },
                ArtInfo("uv", uv = d.uvIndex ?: 0.0), uvNote),
            tile("aqi", R.string.weather_detail_aqi, d.aqi?.toString() ?: none, d.aqi?.let { WeatherText.aqiRating(it) },
                ArtInfo("aqi", aqi = d.aqi ?: 0), aqiNote),
            tile("wind", R.string.weather_detail_wind, d.windMph?.let { WeatherText.wind(it, d.windFromDeg) } ?: none, null,
                ArtInfo("wind", windTowardDeg = ((d.windFromDeg ?: 0.0) + 180) % 360, windMph = d.windMph ?: 0.0), windNote),
        )
        val gap = px(R.dimen.space_s)
        list.chunked(2).forEachIndexed { i, pair ->
            val row = MetricRow(context, gap).apply { tag = "weather_metric_row" }
            pair.forEach { row.addView(it) }
            body.addView(row, lp(if (i == 0) px(R.dimen.space_m) else gap))
        }
    }

    private companion object {
        const val EXPAND_MS = 320L
        const val DAY_MS = 24 * 3_600_000L
    }
}
