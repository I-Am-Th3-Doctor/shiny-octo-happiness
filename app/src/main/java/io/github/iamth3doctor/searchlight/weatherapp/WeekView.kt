package io.github.iamth3doctor.searchlight.weatherapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.ConditionVisual
import io.github.iamth3doctor.searchlight.weather.DayLapse
import io.github.iamth3doctor.searchlight.weather.DayPoint
import io.github.iamth3doctor.searchlight.weather.SceneModel
import io.github.iamth3doctor.searchlight.weather.WeatherCodes
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherText
import io.github.iamth3doctor.searchlight.widgets.ConditionView
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.TextStyle
import kotlin.math.abs

/**
 * The 7-day view's content: a window on the scene, then a row per day (its condition, chance of
 * rain, low and high). Hold a day and the window plays that whole day as a time-lapse from its
 * real hourly forecast, midnight to midnight in [DayLapse.PLAY_MS]: the sun rises, arcs and sets,
 * the moon takes the night, clouds, rain and wind come and go at their hours, and the temperature,
 * condition and an hour readout ("2 PM · 44° · 10% rain") ride along over a thin bar showing where
 * in the day it is (and, in blue, when rain is due). Let go and it stops on that day, still.
 */
class WeekView(private val context: Context, private val light: Boolean) {

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** The window the held day plays in. */
    val preview = WeatherSceneView(context, mini = false).apply { paused = true }
    val caption = TextView(context)
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Over the window while a day plays: its temperature and condition, the hour, and where in the day. */
    val overlay = FrameLayout(context).apply { visibility = View.GONE }
    val lapseTemp = TextView(context)
    val lapseCondition = TextView(context)
    val readout = TextView(context)
    val lapseBar = LapseBarView(context)

    /** How far through the played day (0..1), while one plays (tests). */
    var progress = 0.0
        private set

    /** The day the window shows still (after a hold), or null: today, now. */
    var stillDay: Int? = null
        private set

    /** The clock the time-lapse plays by (tests can drive it). */
    var playClock: () -> Long = { android.os.SystemClock.uptimeMillis() }

    /** The day being played (index into the forecast), or null. */
    var previewDay: Int? = null
        private set

    /** Light haptics on hold. */
    var haptics = true

    var clock: () -> Long = System::currentTimeMillis

    private var data: WeatherData? = null
    private val main = Handler(Looper.getMainLooper())
    private var playStart = 0L

    private fun px(t: Int) = context.px(t)
    private val fg = if (light) 0xFF1C1C21.toInt() else context.color(R.color.on_surface)
    private val dim = if (light) 0xA61C1C21.toInt() else context.color(R.color.on_surface_dim)
    private val inset = if (light) 0x14000000 else context.color(R.color.surface_inset)

    init {
        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = context.pxF(R.dimen.radius_card); setColor(0xFF000000.toInt()) }
            clipToOutline = true
        }
        card.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildOverlay()
        card.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(R.dimen.size_weather_week_preview)))
        caption.type(Type.BODY)
        caption.setTextColor(dim)
        caption.gravity = Gravity.CENTER
        caption.text = context.getString(R.string.weather_week_hint)
        root.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(R.dimen.space_s) })
        list.background = GradientDrawable().apply { cornerRadius = context.pxF(R.dimen.radius_card) / 2; setColor(inset) }
        val p = px(R.dimen.space_s)
        list.setPadding(p, p / 2, p, p / 2)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(R.dimen.space_m) })
    }

    private fun buildOverlay() {
        val white = 0xFFFFFFFF.toInt()
        val shadow = context.pxF(R.dimen.glass_text_shadow)
        fun TextView.glass(style: Int, bold: Boolean) {
            type(style)
            if (bold) typeface = Typeface.create(typeface, 700, false)
            setTextColor(white)
            includeFontPadding = false
            setShadowLayer(shadow, 0f, 1f, 0x99000000.toInt())
        }
        val m = px(R.dimen.space_s) + px(R.dimen.space_xs)
        val top = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        lapseTemp.glass(Type.GLANCE, bold = true)
        lapseTemp.tag = "weather_lapse_temp"
        lapseCondition.glass(Type.BODY, bold = true)
        top.addView(lapseTemp)
        top.addView(lapseCondition)
        overlay.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
            .apply { setMargins(m, m, m, m) })
        val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        readout.glass(Type.BODY, bold = true)
        readout.tag = "weather_lapse_readout"
        bottom.addView(readout)
        lapseBar.tag = "weather_lapse_bar"
        bottom.addView(lapseBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(R.dimen.space_m))
            .apply { topMargin = px(R.dimen.space_xs) })
        overlay.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { setMargins(m, m, m, m / 2) })
    }

    private fun text(style: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        type(style)
        if (bold) typeface = Typeface.create(typeface, 700, false)
        setTextColor(color)
        includeFontPadding = false
    }

    private fun deg(c: Double) = "${WeatherText.f(c)}°"

    fun render(data: WeatherData?) {
        this.data = data
        list.removeAllViews()
        if (previewDay == null) still(stillDay)
        val d = data?.detail ?: return
        val zone = ZoneOffset.ofTotalSeconds(d.utcOffsetSec)
        val locale = context.resources.configuration.locales[0]
        val hv = px(R.dimen.size_weather_visual_hour) + px(R.dimen.space_xs)
        val col = px(R.dimen.size_cal_chevron)
        d.daily.take(7).forEachIndexed { i, day ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = col + px(R.dimen.space_s)
                val p = px(R.dimen.space_s)
                setPadding(p, 0, p, 0)
                tag = "weather_week_day"
                background = GradientDrawable().apply { cornerRadius = context.pxF(R.dimen.radius_card) / 3; setColor(0) }
            }
            val name = dayName(i, day, zone, locale)
            row.addView(text(Type.BODY, fg, bold = i == 0).apply { text = name }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ConditionView(context, glow = false).apply { visual = ConditionVisual.of(day.code, true) }, LinearLayout.LayoutParams(hv, hv))
            row.addView(text(Type.CAPTION, context.color(R.color.weather_drop)).apply {
                text = day.precipPct?.let { "$it%" } ?: ""
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(col, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(text(Type.BODY, dim).apply { text = deg(day.lowC); gravity = Gravity.END },
                LinearLayout.LayoutParams(col + px(R.dimen.space_s), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(text(Type.BODY, fg, bold = true).apply { text = deg(day.highC); gravity = Gravity.END },
                LinearLayout.LayoutParams(col + px(R.dimen.space_s), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.contentDescription = "$name, ${WeatherCodes.describe(day.code)}, high ${deg(day.highC)}, low ${deg(day.lowC)}" +
                (day.precipPct?.let { ", $it% chance of precipitation" } ?: "") + ". Hold to preview."
            holdToPreview(row, i)
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dayName(i: Int, day: DayPoint, zone: ZoneOffset, locale: java.util.Locale): String =
        if (i == 0) context.getString(R.string.weather_detail_today)
        else Instant.ofEpochMilli(day.date).atOffset(zone).dayOfWeek.getDisplayName(TextStyle.FULL, locale)

    @SuppressLint("ClickableViewAccessibility")
    private fun holdToPreview(row: View, i: Int) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        val start = Runnable {
            row.parent?.requestDisallowInterceptTouchEvent(true)
            startPreview(i)
        }
        row.isLongClickable = true
        row.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    v.postDelayed(start, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Moved off before the hold took: it's a scroll or a swipe, not a hold.
                    if (previewDay != i && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                        v.removeCallbacks(start)
                        return@setOnTouchListener false
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(start)
                    if (previewDay == i) stopPreview()
                    true
                }
                else -> false
            }
        }
    }

    /** Plays day [i] in the window, midnight to midnight, from the start, until [stopPreview]. */
    fun startPreview(i: Int) {
        val d = data?.detail ?: return
        val day = d.daily.getOrNull(i) ?: return
        previewDay = i
        list.getChildAt(i)?.let { (it.background as? GradientDrawable)?.setColor(if (light) 0x1A000000 else 0x26FFFFFF) }
        if (haptics) list.getChildAt(i)?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val zone = ZoneOffset.ofTotalSeconds(d.utcOffsetSec)
        val wet = DayLapse.wetWindows(data!!, day.date)
        val hf = WeatherFormat.hour(context)
        fun hour(ms: Long) = hf.format(Instant.ofEpochMilli(ms).atOffset(zone))
        caption.text = "${dayName(i, day, zone, context.resources.configuration.locales[0])} · " +
            "${WeatherCodes.describe(day.code)} · ${deg(day.highC)} / ${deg(day.lowC)}" +
            (wet.firstOrNull()?.let { (a, b) -> " · " + context.getString(R.string.weather_week_wet, hour(a), hour(b)) } ?: "")
        caption.setTextColor(fg)
        lapseBar.windows = wet.map { (a, b) -> ((a - day.date) / DAY_MS.toFloat()) to ((b - day.date) / DAY_MS.toFloat()) }
        playStart = playClock()
        progress = 0.0
        // An hour passes in a third of a second here: the scene keeps up close behind.
        preview.renderer.easeSec = 0.12
        frame(0.0)
        preview.jumpTo(SceneModel.at(data!!, playTime(day, 0.0), live = false))
        overlay.visibility = View.VISIBLE
        preview.paused = false
        main.removeCallbacks(play)
        main.postDelayed(play, STEP_MS)
    }

    /** Stops the time-lapse: the window holds still on the day that was playing. */
    fun stopPreview() {
        val i = previewDay ?: return
        previewDay = null
        main.removeCallbacks(play)
        list.getChildAt(i)?.let { (it.background as? GradientDrawable)?.setColor(0) }
        caption.text = context.getString(R.string.weather_week_hint)
        caption.setTextColor(dim)
        still(i)
    }

    /** The time [f] (0..1) through [day]: midnight to midnight there. */
    private fun playTime(day: DayPoint, f: Double): Long = DayLapse.time(day.date, f)

    /** The time the preview shows now (tests). */
    var previewTime: Long? = null
        private set

    /** Shows the moment [f] of the way through the playing day: scene, numbers, readout and bar. */
    private fun frame(f: Double) {
        val i = previewDay ?: return
        val d = data ?: return
        val detail = d.detail ?: return
        val day = detail.daily.getOrNull(i) ?: return
        val t = playTime(day, f)
        previewTime = t
        progress = f
        val c = SceneModel.at(d, t, live = false)
        preview.conditions = c
        val r = DayLapse.readout(d, t)
        val zone = ZoneOffset.ofTotalSeconds(detail.utcOffsetSec)
        val hour = WeatherFormat.hour(context).format(Instant.ofEpochMilli(r.hourMs).atOffset(zone))
        lapseTemp.text = "${WeatherText.f(c.tempC)}°"
        lapseCondition.text = WeatherCodes.describe(r.code)
        readout.text = context.getString(R.string.weather_week_readout, hour, deg(r.tempC), r.precipProb ?: 0)
        lapseBar.progress = f.toFloat()
    }

    private val play = object : Runnable {
        override fun run() {
            if (previewDay == null) return
            val f = DayLapse.progress(playClock() - playStart)
            frame(f)
            // At the end of the day it rests there (the scene still lives) until it's let go.
            if (f < 1.0) main.postDelayed(this, STEP_MS)
        }
    }

    /** Still: day [i] (today: now; another day: its early afternoon), or today now. */
    private fun still(i: Int? = null) {
        previewTime = null
        stillDay = i
        overlay.visibility = View.GONE
        preview.renderer.easeSec = 0.7
        val d = data
        preview.paused = true
        val day = i?.let { d?.detail?.daily?.getOrNull(it) }
        val t = if (day == null || i == 0) clock() else day.date + 13 * 3_600_000L
        if (d != null) preview.jumpTo(SceneModel.at(d, t, live = day == null || i == 0))
        preview.invalidate()
    }

    /** Stops everything (the screen is closing). */
    fun stop() {
        stopPreview()
        main.removeCallbacks(play)
    }

    private companion object {
        const val STEP_MS = 50L
        const val DAY_MS = 24 * 3_600_000L
    }
}

/** The time-lapse's thin bar: the whole day, when rain is due (blue), and how far it has played. */
class LapseBarView(context: Context) : View(context) {
    /** Wet spans as fractions of the day. */
    var windows: List<Pair<Float, Float>> = emptyList()
        set(value) { field = value; invalidate() }
    var progress = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val wet = context.color(R.color.weather_drop)

    override fun onDraw(canvas: android.graphics.Canvas) {
        val d = resources.displayMetrics.density
        val h = 4 * d
        val cy = height / 2f
        val r = h / 2
        val l = 6 * d
        val w = width - 2 * l
        paint.color = 0x40FFFFFF
        canvas.drawRoundRect(l, cy - r, l + w, cy + r, r, r, paint)
        paint.color = wet
        for ((a, b) in windows) canvas.drawRoundRect(l + w * a, cy - r, l + w * b, cy + r, r, r, paint)
        paint.color = 0xE6FFFFFF.toInt()
        canvas.drawRoundRect(l, cy - r / 2, l + w * progress, cy + r / 2, r, r, paint)
        paint.setShadowLayer(3 * d, 0f, 0f, 0x80000000.toInt())
        canvas.drawCircle(l + w * progress, cy, 5 * d, paint)
        paint.clearShadowLayer()
    }
}
