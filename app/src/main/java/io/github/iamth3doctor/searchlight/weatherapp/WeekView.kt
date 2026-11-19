package io.github.iamth3doctor.searchlight.weatherapp

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
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
import io.github.iamth3doctor.searchlight.weather.WeatherCodes
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherText
import io.github.iamth3doctor.searchlight.widgets.ConditionView
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.TextStyle

/**
 * The 7-day view's content: a window on the scene, then a row per day (its condition, chance of
 * rain, low and high). Hold a day and the window plays that whole day as a time-lapse from its
 * real hourly forecast, midnight to midnight in [DayLapse.PLAY_MS]: the sun rises, arcs and sets,
 * the moon takes the night, clouds, rain and wind come and go at their hours, and the temperature,
 * condition and an hour readout ("2 PM · 44° · 10% rain") ride along over a thin bar showing where
 * in the day it is (and, in blue, when rain is due). Let go and it goes back to now. Otherwise the
 * window shows the weather now, alive, like the scene on the hero.
 */
class WeekView(private val context: Context, private val light: Boolean) {

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** The window the held day plays in. */
    val window = DayLapseWindow(context)
    val preview get() = window.preview
    val caption = TextView(context)
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Over the window while a day plays: its temperature and condition, the hour, and where in the day. */
    val overlay get() = window.overlay
    val lapseTemp get() = window.lapseTemp
    val lapseCondition get() = window.lapseCondition
    val readout get() = window.readout
    val lapseBar get() = window.lapseBar

    /** How far through the played day (0..1), while one plays (tests). */
    val progress get() = window.progress

    /** The clock the time-lapse plays by (tests can drive it). */
    var playClock: () -> Long
        get() = window.playClock
        set(value) { window.playClock = value }

    /** The day being played (index into the forecast), or null. */
    val previewDay: Int? get() = window.day

    /** The time the preview shows while playing (tests). */
    val previewTime: Long? get() = window.previewTime

    /** Light haptics on hold. */
    var haptics = true

    var clock: () -> Long = System::currentTimeMillis

    /** A hold ended (the window is back on now): the screen's idle timer starts over. */
    var onSettled: (() -> Unit)? = null

    /** Playing another day: away from now. */
    val awayFromNow: Boolean get() = previewDay != null

    private var data: WeatherData? = null

    private fun px(t: Int) = context.px(t)
    private val fg = if (light) 0xFF1C1C21.toInt() else context.color(R.color.on_surface)
    private val dim = if (light) 0xA61C1C21.toInt() else context.color(R.color.on_surface_dim)
    private val inset = if (light) 0x14000000 else context.color(R.color.surface_inset)

    init {
        root.addView(window.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(R.dimen.size_weather_week_preview)))
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
        live()
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
            DayLapseWindow.holdToPlay(row, isHeld = { previewDay == i }, onHold = { startPreview(i) }, onRelease = { stopPreview() })
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dayName(i: Int, day: DayPoint, zone: ZoneOffset, locale: java.util.Locale): String =
        if (i == 0) context.getString(R.string.weather_detail_today)
        else Instant.ofEpochMilli(day.date).atOffset(zone).dayOfWeek.getDisplayName(TextStyle.FULL, locale)

    /** Plays day [i] in the window, midnight to midnight, from the start, until [stopPreview]. */
    fun startPreview(i: Int) {
        val all = data ?: return
        val d = all.detail ?: return
        val day = d.daily.getOrNull(i) ?: return
        previewDay?.let { highlight(it, false) }
        if (!window.play(all, i)) return
        highlight(i, true)
        if (haptics) list.getChildAt(i)?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val zone = ZoneOffset.ofTotalSeconds(d.utcOffsetSec)
        val wet = window.wetWindows(all, i)
        val hf = WeatherFormat.hour(context)
        fun hour(ms: Long) = hf.format(Instant.ofEpochMilli(ms).atOffset(zone))
        caption.text = "${dayName(i, day, zone, context.resources.configuration.locales[0])} · " +
            "${WeatherCodes.describe(day.code)} · ${deg(day.highC)} / ${deg(day.lowC)}" +
            (wet.firstOrNull()?.let { (a, b) -> " · " + context.getString(R.string.weather_week_wet, hour(a), hour(b)) } ?: "")
        caption.setTextColor(fg)
    }

    private fun highlight(i: Int, on: Boolean) {
        list.getChildAt(i)?.let { (it.background as? GradientDrawable)?.setColor(if (on) (if (light) 0x1A000000 else 0x26FFFFFF) else 0) }
    }

    /** Stops the time-lapse: the window eases back to the weather now, alive. */
    fun stopPreview() {
        val i = previewDay ?: return
        window.stop(data, clock())
        highlight(i, false)
        caption.text = context.getString(R.string.weather_week_hint)
        caption.setTextColor(dim)
        onSettled?.invoke()
    }

    /** The weather now in the window (kept up as time passes); not while a day plays. */
    fun live() = window.showLive(data, clock())

    /** Stops everything (the screen is closing). */
    fun stop() = stopPreview()
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
