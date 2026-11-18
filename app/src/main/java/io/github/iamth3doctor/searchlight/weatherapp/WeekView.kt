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
 * rain, low and high). Hold a day and the window plays that day as a timelapse, dawn to night;
 * let go and it stops.
 */
class WeekView(private val context: Context, private val light: Boolean) {

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** The window the held day plays in. */
    val preview = WeatherSceneView(context, mini = false).apply { paused = true }
    val caption = TextView(context)
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

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
        if (previewDay == null) still()
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

    /** Plays day [i] in the window, from dawn to night, over and over, until [stopPreview]. */
    fun startPreview(i: Int) {
        val d = data?.detail ?: return
        val day = d.daily.getOrNull(i) ?: return
        previewDay = i
        list.getChildAt(i)?.let { (it.background as? GradientDrawable)?.setColor(if (light) 0x1A000000 else 0x26FFFFFF) }
        if (haptics) list.getChildAt(i)?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        caption.text = "${dayName(i, day, ZoneOffset.ofTotalSeconds(d.utcOffsetSec), context.resources.configuration.locales[0])} · " +
            "${WeatherCodes.describe(day.code)} · ${deg(day.highC)} / ${deg(day.lowC)}"
        caption.setTextColor(fg)
        playStart = android.os.SystemClock.uptimeMillis()
        preview.jumpTo(SceneModel.at(data!!, playTime(day, 0.0), live = false))
        preview.paused = false
        main.removeCallbacks(play)
        main.post(play)
    }

    /** Stops the preview: the window holds still on today, now. */
    fun stopPreview() {
        val i = previewDay ?: return
        previewDay = null
        main.removeCallbacks(play)
        list.getChildAt(i)?.let { (it.background as? GradientDrawable)?.setColor(0) }
        caption.text = context.getString(R.string.weather_week_hint)
        caption.setTextColor(dim)
        still()
    }

    /** The time [f] (0..1) through the played part of [day]: 5 AM to 11 PM there. */
    private fun playTime(day: DayPoint, f: Double): Long = day.date + ((5 + 18 * f) * 3_600_000L).toLong()

    /** The time the preview shows now (tests). */
    var previewTime: Long? = null
        private set

    private val play = object : Runnable {
        override fun run() {
            val i = previewDay ?: return
            val d = data ?: return
            val day = d.detail?.daily?.getOrNull(i) ?: return
            val f = ((android.os.SystemClock.uptimeMillis() - playStart) % PLAY_MS).toDouble() / PLAY_MS
            val t = playTime(day, f)
            previewTime = t
            preview.conditions = SceneModel.at(d, t, live = false)
            main.postDelayed(this, STEP_MS)
        }
    }

    private fun still() {
        previewTime = null
        val d = data
        preview.paused = true
        if (d != null) preview.jumpTo(SceneModel.at(d, clock(), live = true))
        preview.invalidate()
    }

    /** Stops everything (the screen is closing). */
    fun stop() {
        stopPreview()
        main.removeCallbacks(play)
    }

    private companion object {
        const val PLAY_MS = 6_000L
        const val STEP_MS = 120L
    }
}
