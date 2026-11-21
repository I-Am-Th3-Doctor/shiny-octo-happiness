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

    /** The 7 DAYS rows (tests). Tap one and the box goes into that day ([openDay]). */
    val dayRows = ArrayList<View>()

    /** The day open in the 7 DAYS box, or null (the list). Stays open when the details rebuild. */
    var openDay: Int? = null
        private set

    /** The open day's view in the 7 DAYS box (tests), or null. */
    var dayView: View? = null
        private set

    /** A day opened or closed (the screen counts it as a touch). */
    var onDayChanged: ((Int?) -> Unit)? = null

    /** The 7 DAYS box's inside: the list, or the open day in its place. */
    private var dayStage: android.widget.FrameLayout? = null
    private var dayList: View? = null
    private var lastData: WeatherData? = null

    /** The big number and condition at the top (tests read them; a played day takes them over). */
    var nowTemp: TextView? = null
        private set
    var nowCondition: TextView? = null
        private set
    private var liveTemp: CharSequence = ""
    private var liveCondition: CharSequence = ""

    /** A played moment's temperature and condition in the big number's place; null: now's again. */
    fun showMoment(tempC: Double?, condition: String?) {
        if (tempC == null) {
            nowTemp?.text = liveTemp
            nowCondition?.text = liveCondition
        } else {
            nowTemp?.text = "${WeatherText.f(tempC)}°F"
            nowCondition?.text = condition
        }
    }

    /** The precip card (kept across renders, so its graph and island keep their state). */
    val precip = PrecipCard(context, light)

    private fun px(token: Int) = context.px(token)
    private fun pxF(token: Int) = context.pxF(token)

    private val fg = if (light) 0xFF1C1C21.toInt() else context.color(R.color.on_surface)
    private val dim = if (light) 0xA61C1C21.toInt() else context.color(R.color.on_surface_dim)
    // No theme color: the chrome's marks are the text's own dark gray.
    private val accent = if (light) 0xFF2A2A30.toInt() else context.color(R.color.on_surface)
    private val insetColor = if (light) 0x99FFFFFF.toInt() else context.color(R.color.surface_inset)

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

    fun render(state: WeatherRepository.State) {
        body.removeAllViews()
        sections.clear()
        nowTemp = null
        nowCondition = null
        dayStage = null
        dayList = null
        dayView = null
        val data = state.data
        lastData = data
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
        val temp = text(Type.GLANCE, bold = true).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, pxF(R.dimen.text_weather_detail_temp))
            text = "${WeatherText.f(data.temperatureC)}°F"
            tag = "weather_now_temp"
        }
        row.addView(temp)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val cond = text(Type.BODY, bold = true).apply { text = data.condition; tag = "weather_now_condition" }
        col.addView(cond)
        nowTemp = temp
        nowCondition = cond
        liveTemp = temp.text
        liveCondition = cond.text
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
            val name = if (i == 0 && h.time <= now) context.getString(R.string.weather_detail_now_hour) else fmt.format(Instant.ofEpochMilli(h.time).atOffset(zone))
            strip.addView(hourCell(h, name, hv, "weather_hour"))
        }
        box.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
            background = inset()
        }, lp(px(R.dimen.space_xs)))
        section("hourly", box)
    }

    /** One hour in a strip: its time, the condition drawn, the temperature, the chance of rain (10% or more). */
    private fun hourCell(h: io.github.iamth3doctor.searchlight.weather.HourPoint, name: String, size: Int, tag: String) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = px(R.dimen.space_s)
            setPadding(p, p, p, p)
            this.tag = tag
            addView(text(Type.CAPTION, dimmed = true).apply { text = name })
            addView(ConditionView(context, glow = false).apply { visual = ConditionVisual.of(h.code, h.isDay) },
                LinearLayout.LayoutParams(size, size).apply { topMargin = px(R.dimen.space_xs); bottomMargin = px(R.dimen.space_xs) })
            addView(text(Type.BODY, bold = true).apply { text = deg(h.tempC) })
            h.precipProb?.takeIf { it >= 10 }?.let { pct ->
                addView(text(Type.CAPTION).apply { text = "$pct%"; setTextColor(context.color(R.color.weather_drop)) })
            }
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
                (day.precipPct?.let { ", $it% chance of precipitation" } ?: "")
            // Tap a day: the box goes into it.
            row.isClickable = true
            row.isFocusable = true
            row.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(if (light) 0x1F000000 else 0x33FFFFFF),
                null, GradientDrawable().apply { cornerRadius = pxF(R.dimen.radius_card) / 3; setColor(0xFF000000.toInt()) })
            row.setOnClickListener { openDay(i) }
            dayRows += row
            list.addView(row, lp())
        }
        val stage = android.widget.FrameLayout(context).apply { tag = "weather_day_stage" }
        stage.addView(list, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dayStage = stage
        dayList = list
        box.addView(stage, lp(px(R.dimen.space_xs)))
        section("daily", box)
        // A day left open stays open through a rebuild (a refresh, a new reading).
        val open = openDay
        if (open != null && (open >= d.daily.size.coerceAtMost(7) || !showDay(open, animate = false))) openDay = null
    }

    /** Tap day [i]: the 7 DAYS box goes into it, the list sliding away and the day sliding in. */
    fun openDay(i: Int, animate: Boolean = true) {
        if (openDay == i && dayView != null) return
        if (showDay(i, animate)) onDayChanged?.invoke(i)
    }

    /** Back out of the open day to the list. False when no day was open. */
    fun closeDay(animate: Boolean = true): Boolean {
        if (openDay == null) return false
        val stage = dayStage
        val v = dayView
        openDay = null
        dayView = null
        if (stage != null) {
            if (animate) beginDayTransition(forward = false, entering = dayList, leaving = v)
            v?.let { stage.removeView(it) }
            dayList?.visibility = View.VISIBLE
        }
        onDayChanged?.invoke(null)
        return true
    }

    private fun showDay(i: Int, animate: Boolean): Boolean {
        val data = lastData ?: return false
        val d = data.detail ?: return false
        val day = d.daily.getOrNull(i) ?: return false
        val stage = dayStage ?: return false
        val v = buildDay(data, i, day)
        val old = dayView
        if (animate) beginDayTransition(forward = true, entering = v, leaving = old ?: dayList)
        old?.let { stage.removeView(it) }
        stage.addView(v, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dayList?.visibility = View.GONE
        openDay = i
        dayView = v
        return true
    }

    /** Tests: whether going into a day and back animates (null: as the system's animation setting says). */
    var dayAnimateOverride: Boolean? = null

    /** Into a day ([forward]) the day comes in from the end and the list goes out the start; back, the other way. */
    private fun beginDayTransition(forward: Boolean, entering: View?, leaving: View?) {
        val on = dayAnimateOverride ?: android.animation.ValueAnimator.areAnimatorsEnabled()
        if (!on) return
        val inEdge = if (forward) Gravity.END else Gravity.START
        val outEdge = if (forward) Gravity.START else Gravity.END
        val set = android.transition.TransitionSet().apply {
            ordering = android.transition.TransitionSet.ORDERING_TOGETHER
            entering?.let { addTransition(android.transition.Slide(inEdge).addTarget(it)); addTransition(android.transition.Fade().addTarget(it)) }
            leaving?.let { addTransition(android.transition.Slide(outEdge).addTarget(it)); addTransition(android.transition.Fade().addTarget(it)) }
            // The box and everything under it follow the new height.
            addTransition(android.transition.ChangeBounds())
            duration = OPEN_DAY_MS
            interpolator = ease
        }
        android.transition.TransitionManager.beginDelayedTransition(body, set)
    }

    /**
     * Day [i] in full, in the 7 DAYS box: back to the list, the day's name and date, the condition
     * drawn large with its words and high and low, its hours, then the chance of rain and how much,
     * the UV, the wind, and sunrise and sunset.
     */
    private fun buildDay(data: WeatherData, i: Int, day: io.github.iamth3doctor.searchlight.weather.DayPoint): View {
        val d = data.detail!!
        val zone = ZoneOffset.ofTotalSeconds(d.utcOffsetSec)
        val locale = context.resources.configuration.locales[0]
        val date = Instant.ofEpochMilli(day.date).atOffset(zone)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = inset()
            val p = px(R.dimen.space_s) + px(R.dimen.space_xs)
            setPadding(p, px(R.dimen.space_s), p, p)
            tag = "weather_day_view"
        }
        // Back to the 7 days.
        val back = text(Type.CAPTION, bold = true).apply {
            text = "‹  " + context.getString(R.string.weather_day_back).uppercase(locale)
            setTextColor(accent)
            letterSpacing = 0.08f
            tag = "weather_day_back"
            contentDescription = context.getString(R.string.weather_day_back_desc)
            val v = px(R.dimen.space_s)
            setPadding(0, v, v * 2, v)
            setOnClickListener { closeDay() }
        }
        inner.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val name = if (i == 0) context.getString(R.string.weather_detail_today) else date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        inner.addView(text(Type.TILE_TITLE, bold = true).apply { text = name; tag = "weather_day_name" }, lp(px(R.dimen.space_xs)))
        inner.addView(text(Type.CAPTION, dimmed = true).apply {
            text = java.time.format.DateTimeFormatter.ofPattern("MMMM d", locale).format(date)
        })
        // The condition drawn large, its words, the high and low.
        val big = px(R.dimen.size_weather_visual_hour) * 2
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ConditionView(context, glow = false).apply { visual = ConditionVisual.of(day.code, true) }, LinearLayout.LayoutParams(big, big))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(Type.BODY, bold = true).apply { text = WeatherCodes.describe(day.code); tag = "weather_day_condition" })
        col.addView(text(Type.GLANCE, bold = true).apply {
            text = context.getString(R.string.weather_widget_hi_lo, deg(day.highC), deg(day.lowC))
            tag = "weather_day_hi_lo"
        }, lp(px(R.dimen.space_xs)))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(R.dimen.space_m) })
        inner.addView(row, lp(px(R.dimen.space_s)))
        // Its hours.
        val hours = d.hourly.filter { it.time >= day.date && it.time < day.date + DAY_MS }
        if (hours.isNotEmpty()) {
            val fmt = WeatherFormat.hour(context)
            val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val hv = px(R.dimen.size_weather_visual_hour) + px(R.dimen.space_xs)
            for (h in hours) strip.addView(hourCell(h, fmt.format(Instant.ofEpochMilli(h.time).atOffset(zone)), hv, "weather_day_hour"))
            inner.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            }, lp(px(R.dimen.space_s)))
        }
        // The numbers.
        val facts = ArrayList<Pair<String, String>>()
        day.precipPct?.let { facts += context.getString(R.string.weather_day_rain_chance) to "$it%" }
        day.precipMm?.let { facts += context.getString(R.string.weather_day_precip) to String.format(java.util.Locale.US, "%.2f in", it / 25.4) }
        day.uvMax?.let { facts += context.getString(R.string.weather_day_uv) to "${it.roundToInt()} ${WeatherText.uvRating(it)}" }
        hours.mapNotNull { it.windMph }.maxOrNull()?.let { facts += context.getString(R.string.weather_day_wind) to "${it.roundToInt()} mph" }
        val tf = WeatherFormat.time(context)
        day.sunrise?.let { facts += context.getString(R.string.weather_day_sunrise) to tf.format(Instant.ofEpochMilli(it).atOffset(zone)) }
        day.sunset?.let { facts += context.getString(R.string.weather_day_sunset) to tf.format(Instant.ofEpochMilli(it).atOffset(zone)) }
        for (pair in facts.chunked(2)) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((k, v) in pair) {
                val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "weather_day_fact" }
                cell.addView(text(Type.CAPTION, dimmed = true).apply { text = k })
                cell.addView(text(Type.BODY, bold = true).apply { text = v })
                line.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            if (pair.size == 1) line.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            inner.addView(line, lp(px(R.dimen.space_s)))
        }
        return inner
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
        /** Into a day and back out. */
        const val OPEN_DAY_MS = 300L
    }
}
