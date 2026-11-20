package io.github.iamth3doctor.searchlight.widgets

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.FrostedDrawable
import io.github.iamth3doctor.searchlight.ui.GlassTint
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.padForSystemBars
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.ConditionVisual
import io.github.iamth3doctor.searchlight.weather.Moon
import io.github.iamth3doctor.searchlight.weather.WeatherCodes
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import io.github.iamth3doctor.searchlight.weather.WeatherText
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import kotlin.math.roundToInt

/**
 * The Weather widget's detail view: a frosted panel over the home screen with everything the
 * forecast has. Now, a sentence about the day, the next 24 hours (scrolls sideways), 7 days,
 * then sun, moon, humidity, visibility, pressure, UV, air quality and wind. All °F; times are
 * the place's own. Refreshes when it opens (and follows the refresh); offline it shows the last
 * good data, saying so. A tap outside the panel, ×, or Back closes it.
 */
class WeatherDetailSheet(
    private val activity: Activity,
    private val repo: WeatherRepository,
    /** Shades texts for the current glass level, like the home screen's (see MainActivity). */
    private val decorate: (View) -> Unit = {},
) {
    lateinit var dialog: Dialog
        private set
    /** Each section by key ("now", "summary", "hourly", "daily", "sun", "moon", ...): read by tests. */
    val sections = LinkedHashMap<String, View>()
    lateinit var panel: LinearLayout
        private set
    private lateinit var body: LinearLayout
    private lateinit var title: TextView
    private val listener: () -> Unit = { render() }

    /** Called once it has closed (any way). */
    var onDismissed: (() -> Unit)? = null

    /** Now, in ms (tests may set a fixed one). */
    var clock: () -> Long = System::currentTimeMillis

    private fun px(token: Int) = activity.px(token)
    private fun pxF(token: Int) = activity.pxF(token)
    private fun color(token: Int) = activity.color(token)

    fun show(): WeatherDetailSheet {
        dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(activity).apply {
            setBackgroundColor(color(R.color.scrim))
            setOnClickListener { dialog.dismiss() } // outside the panel
            contentDescription = activity.getString(R.string.weather_detail_close)
        }
        val m = px(R.dimen.space_m)
        root.setPadding(m, m, m, m)
        panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // a tap inside never closes it
            val s = px(R.dimen.space_s)
            setPadding(m, s, s, m)
            background = FrostedDrawable(
                this, pxF(R.dimen.radius_card), color(R.color.glass_card), color(R.color.glass_card_fallback),
                color(R.color.glass_border), pxF(R.dimen.stroke_glass), adaptive = GlassTint.Layer.CARD,
            )
            clipToOutline = true
        }
        // Header: the place, and ×.
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = text(Type.TILE_TITLE, bold = true)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(color(R.color.on_surface_dim))
            val p = px(R.dimen.space_s)
            setPadding(p, p, p, p)
            contentDescription = activity.getString(R.string.weather_detail_close)
            setOnClickListener { dialog.dismiss() }
            background = TypedValue().let { tv ->
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                activity.getDrawable(tv.resourceId)
            }
        }
        header.addView(close, LinearLayout.LayoutParams(px(R.dimen.size_cal_chevron) + px(R.dimen.space_xs), px(R.dimen.size_cal_chevron) + px(R.dimen.space_xs)))
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, px(R.dimen.space_s), 0) }
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(body) }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val maxW = px(R.dimen.size_weather_detail_max)
        val screenW = activity.resources.displayMetrics.widthPixels - 2 * m
        root.addView(panel, FrameLayout.LayoutParams(minOf(maxW, screenW), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.padForSystemBars()

        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawable(ColorDrawable(0))
            WindowCompat.setDecorFitsSystemWindows(w, false)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            repo.removeListener(listener)
            onDismissed?.invoke()
        }
        repo.addListener(listener)
        render()
        dialog.show()
        // Opening it is a refresh (the last good data shows meanwhile, and stays if it fails).
        repo.refresh()
        return this
    }

    fun dismiss() {
        if (::dialog.isInitialized) dialog.dismiss()
    }

    // ------------------------------------------------------------------ building blocks

    private fun text(style: Int, dim: Boolean = false, bold: Boolean = false) = TextView(activity).apply {
        type(style)
        if (bold) typeface = Typeface.create(typeface, Typeface.BOLD)
        setTextColor(color(if (dim) R.color.on_surface_dim else R.color.on_surface))
        includeFontPadding = false
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    /** A gold section label ("HOURLY"). */
    private fun label(res: Int) = text(Type.CAPTION, bold = true).apply {
        text = activity.getString(res).uppercase()
        setTextColor(color(R.color.gold))
        letterSpacing = 0.08f
    }

    private fun section(key: String, view: View, top: Int = px(R.dimen.space_m)) {
        view.tag = "weather_section_$key"
        sections[key] = view
        body.addView(view, lp(top = top))
    }

    private fun inset() = GradientDrawable().apply {
        cornerRadius = pxF(R.dimen.radius_card) / 2
        setColor(color(R.color.surface_inset))
    }

    private fun deg(c: Double) = "${WeatherText.f(c)}°"

    private fun timeFmt(): DateTimeFormatter = DateTimeFormatter.ofPattern(
        if (DateFormat.is24HourFormat(activity)) "HH:mm" else "h:mm a", activity.resources.configuration.locales[0],
    )

    // ------------------------------------------------------------------ content

    fun render() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        sections.clear()
        val state = repo.state
        val data = state.data
        title.text = data?.locationName ?: activity.getString(R.string.widget_weather)
        if (data == null) {
            section("now", text(Type.BODY, dim = true).apply {
                text = activity.getString(
                    when (state.problem) {
                        WeatherRepository.Problem.NEEDS_LOCATION -> R.string.weather_needs_location
                        WeatherRepository.Problem.LOCATION_UNAVAILABLE -> R.string.weather_no_fix
                        WeatherRepository.Problem.NETWORK -> R.string.weather_failed
                        null -> R.string.weather_loading
                    },
                )
            }, top = px(R.dimen.space_s))
            decorate(panel)
            return
        }
        val offline = state.problem == WeatherRepository.Problem.NETWORK
        now(data, offline)
        summary(data)
        hourly(data)
        daily(data)
        tiles(data)
        decorate(panel)
    }

    private fun now(data: WeatherData, offline: Boolean) {
        val d = data.detail
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val v = px(R.dimen.size_weather_visual) + px(R.dimen.space_m)
        row.addView(ConditionView(activity).apply { visual = ConditionVisual.of(data.weatherCode, data.isDay) }, LinearLayout.LayoutParams(v, v))
        row.addView(text(Type.GLANCE, bold = true).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, pxF(R.dimen.text_weather_detail_temp))
            text = "${WeatherText.f(data.temperatureC)}°F"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(R.dimen.space_s) })
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(Type.BODY, bold = true).apply { text = data.condition })
        d?.feelsLikeC?.let { f -> col.addView(text(Type.BODY, dim = true).apply { text = activity.getString(R.string.weather_widget_feels, deg(f)) }) }
        col.addView(text(Type.BODY, dim = true).apply { text = activity.getString(R.string.weather_widget_hi_lo, deg(data.highC), deg(data.lowC)) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(R.dimen.space_m) })
        box.addView(row, lp())
        box.addView(text(Type.CAPTION, dim = !offline).apply {
            text = if (offline) {
                activity.getString(R.string.weather_widget_offline)
            } else {
                activity.getString(R.string.weather_widget_updated, DateFormat.getTimeFormat(activity).format(Date(data.fetchedAt)))
            }
            if (offline) setTextColor(color(R.color.gold))
        }, lp(top = px(R.dimen.space_xs)))
        section("now", box, top = px(R.dimen.space_xs))
    }

    private fun summary(data: WeatherData) {
        val d = data.detail ?: return
        val today = d.daily.firstOrNull()
        val (dayCode, nightCode) = WeatherText.dayAndNight(d.hourly, data.weatherCode, clock(), d.utcOffsetSec)
        section("summary", text(Type.BODY).apply {
            text = WeatherText.daySummary(today?.highC ?: data.highC, dayCode, nightCode)
            setLineSpacing(0f, 1.2f)
        }, top = px(R.dimen.space_s))
    }

    private fun hourly(data: WeatherData) {
        val d = data.detail ?: return
        val now = clock()
        val hours = d.hourly.filter { it.time > now - 3_600_000L }.take(24)
        if (hours.isEmpty()) return
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(R.string.weather_detail_hourly))
        val strip = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val zone = ZoneOffset.ofTotalSeconds(d.utcOffsetSec)
        val fmt = WeatherWidgetView.hourFormat(activity)
        val hv = px(R.dimen.size_weather_visual_hour) + px(R.dimen.space_xs)
        hours.forEachIndexed { i, h ->
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val p = px(R.dimen.space_s)
                setPadding(p, p, p, p)
                tag = "weather_hour"
            }
            cell.addView(text(Type.CAPTION, dim = true).apply {
                text = if (i == 0 && h.time <= now) activity.getString(R.string.weather_detail_now_hour) else fmt.format(Instant.ofEpochMilli(h.time).atOffset(zone))
            })
            cell.addView(ConditionView(activity, glow = false).apply { visual = ConditionVisual.of(h.code, h.isDay) },
                LinearLayout.LayoutParams(hv, hv).apply { topMargin = px(R.dimen.space_xs); bottomMargin = px(R.dimen.space_xs) })
            cell.addView(text(Type.BODY, bold = true).apply { text = deg(h.tempC) })
            strip.addView(cell)
        }
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
            background = inset()
        }
        box.addView(scroller, lp(top = px(R.dimen.space_xs)))
        section("hourly", box)
    }

    private fun daily(data: WeatherData) {
        val d = data.detail ?: return
        if (d.daily.isEmpty()) return
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(R.string.weather_detail_daily))
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = inset()
            val p = px(R.dimen.space_s)
            setPadding(p + px(R.dimen.space_xs), p / 2, p + px(R.dimen.space_xs), p / 2)
        }
        val zone = ZoneOffset.ofTotalSeconds(d.utcOffsetSec)
        val locale = activity.resources.configuration.locales[0]
        val hv = px(R.dimen.size_weather_visual_hour)
        d.daily.take(7).forEachIndexed { i, day ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(R.dimen.size_cal_chevron)
                tag = "weather_day"
            }
            val name = if (i == 0) activity.getString(R.string.weather_detail_today)
                else Instant.ofEpochMilli(day.date).atOffset(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            row.addView(text(Type.BODY, bold = i == 0).apply { text = name }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ConditionView(activity, glow = false).apply { visual = ConditionVisual.of(day.code, true) }, LinearLayout.LayoutParams(hv, hv))
            row.addView(text(Type.CAPTION).apply {
                text = day.precipPct?.let { "$it%" } ?: ""
                setTextColor(color(R.color.weather_drop))
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(px(R.dimen.size_cal_chevron), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(text(Type.BODY, dim = true).apply { text = deg(day.lowC); gravity = Gravity.END },
                LinearLayout.LayoutParams(px(R.dimen.size_cal_chevron) + px(R.dimen.space_s), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(text(Type.BODY, bold = true).apply { text = deg(day.highC); gravity = Gravity.END },
                LinearLayout.LayoutParams(px(R.dimen.size_cal_chevron) + px(R.dimen.space_s), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.contentDescription = "$name, ${WeatherCodes.describe(day.code)}, high ${deg(day.highC)}, low ${deg(day.lowC)}" +
                (day.precipPct?.let { ", $it% chance of precipitation" } ?: "")
            list.addView(row, lp())
        }
        box.addView(list, lp(top = px(R.dimen.space_xs)))
        section("daily", box)
    }

    /** One tile: a gold label, a value, and a dim line under it. */
    private fun tile(key: String, labelRes: Int, value: String, sub: String?): View {
        val t = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = inset()
            val p = px(R.dimen.space_s) + px(R.dimen.space_xs)
            setPadding(p, p, p, p)
            tag = "weather_section_$key"
        }
        t.addView(label(labelRes))
        t.addView(text(Type.TILE_TITLE, bold = true).apply {
            text = value
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(top = px(R.dimen.space_xs)))
        if (!sub.isNullOrBlank()) t.addView(text(Type.BODY, dim = true).apply { text = sub }, lp(top = px(R.dimen.space_xs) / 2))
        sections[key] = t
        return t
    }

    private fun tiles(data: WeatherData) {
        val d = data.detail ?: return
        val zone = ZoneOffset.ofTotalSeconds(d.utcOffsetSec)
        val tf = timeFmt()
        fun at(ms: Long?) = ms?.let { tf.format(Instant.ofEpochMilli(it).atOffset(zone)) }
        val none = activity.getString(R.string.weather_detail_none)
        val today = d.daily.firstOrNull()
        val now = clock()
        val moonTimes = Moon.times(Moon.dayStart(now, d.utcOffsetSec), data.latitude, data.longitude)
        val list = listOf(
            tile("sun", R.string.weather_detail_sun,
                "↑ ${at(today?.sunrise) ?: none}",
                "↓ ${at(today?.sunset) ?: none}"),
            tile("moon", R.string.weather_detail_moon,
                Moon.phase(now).label,
                "↑ ${at(moonTimes.rise) ?: activity.getString(R.string.weather_detail_no_rise)}   ↓ ${at(moonTimes.set) ?: activity.getString(R.string.weather_detail_no_set)}"),
            tile("humidity", R.string.weather_detail_humidity,
                d.humidity?.let { "$it%" } ?: none,
                d.dewPointC?.let { activity.getString(R.string.weather_detail_dew, deg(it)) }),
            tile("visibility", R.string.weather_detail_visibility,
                d.visibilityM?.let { WeatherText.visibility(it) } ?: none,
                d.visibilityM?.let { WeatherText.visibilityRating(it) }),
            tile("pressure", R.string.weather_detail_pressure,
                d.pressureHpa?.let { WeatherText.pressure(it) } ?: none, null),
            tile("uv", R.string.weather_detail_uv,
                d.uvIndex?.let { it.roundToInt().toString() } ?: none,
                d.uvIndex?.let { WeatherText.uvRating(it) }),
            tile("aqi", R.string.weather_detail_aqi,
                d.aqi?.toString() ?: none,
                d.aqi?.let { WeatherText.aqiRating(it) }),
            tile("wind", R.string.weather_detail_wind,
                d.windMph?.let { WeatherText.wind(it, d.windFromDeg) } ?: none, null),
        )
        val gap = px(R.dimen.space_s)
        list.chunked(2).forEachIndexed { i, pair ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { j, t ->
                row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (j > 0) marginStart = gap })
            }
            body.addView(row, lp(top = if (i == 0) px(R.dimen.space_m) else gap))
        }
    }
}
