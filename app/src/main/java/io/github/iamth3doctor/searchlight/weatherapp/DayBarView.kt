package io.github.iamth3doctor.searchlight.weatherapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.weather.DayBar
import io.github.iamth3doctor.searchlight.weather.HourPoint
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherText
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * "Play the weather": the day at a glance along the bottom of the weather screen. The temperature
 * across the next day as a line (its high and low marked), rain chances as blue bars under it,
 * a gold "now" marker. Tap or drag anywhere to preview that time ([onScrub] with it): the scene
 * plays to it like a timelapse, and the bar reads out that hour's temperature and chance of rain.
 * Tap "now" to go back to live ([onScrub] with null).
 */
class DayBarView(context: Context) : View(context) {

    /** A time to preview, or null for live. */
    var onScrub: ((Long?) -> Unit)? = null
    /** Light ticks as the scrub passes each hour. */
    var haptics = true

    var light = false
        set(value) { field = value; invalidate() }

    private var data: WeatherData? = null
    private var nowMs = 0L
    /** The previewed time, or null when live. */
    var scrubTime: Long? = null
        private set
    var hours: List<HourPoint> = emptyList()
        private set

    private val density = resources.displayMetrics.density
    private val lexend = ResourcesCompat.getFont(context, R.font.lexend) ?: Typeface.DEFAULT
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = lexend }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(lexend, Typeface.BOLD) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val area = Path()
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.weather_bar_desc)
    }

    fun update(data: WeatherData?, nowMs: Long, scrub: Long?) {
        this.data = data
        this.nowMs = nowMs
        this.scrubTime = scrub
        if (scrub == null) lastHour = Math.floorDiv(nowMs, 3_600_000L)
        hours = DayBar.hours(data, nowMs)
        invalidate()
    }

    private fun fg() = if (light) 0xFF1C1C21.toInt() else 0xFFFFFFFF.toInt()
    private fun dim() = if (light) 0x991C1C21.toInt() else 0xB3FFFFFF.toInt()
    private fun accent() = context.color(if (light) R.color.accent else R.color.gold)

    private val zone get() = ZoneOffset.ofTotalSeconds(data?.detail?.utcOffsetSec ?: 0)
    private fun hourLabel(t: Long) = WeatherFormat.hour(context).format(Instant.ofEpochMilli(t).atOffset(zone))

    /** x for [t] on the bar. */
    fun xOf(t: Long): Float = pad + (width - 2 * pad) * DayBar.fractionOf(t, nowMs).toFloat()
    private val pad get() = 12 * density

    // Rows, top to bottom: readout, temperature line, rain bars, hour labels.
    private val readoutH get() = 18 * density
    private val chartTop get() = readoutH + 4 * density
    private val chartBottom get() = height - 30 * density
    private val rainTop get() = height - 28 * density
    private val rainBottom get() = height - 16 * density

    override fun onDraw(canvas: Canvas) {
        val hs = hours
        val left = pad
        val right = width - pad
        if (hs.size >= 2) {
            val (hi, lo) = DayBar.highLow(hs)!!
            val span = (hi.tempC - lo.tempC).coerceAtLeast(1.0)
            fun y(c: Double) = (chartBottom - 6 * density - (c - lo.tempC) / span * (chartBottom - chartTop - 12 * density)).toFloat()
            path.reset()
            area.reset()
            hs.forEachIndexed { i, h ->
                val x = xOf(h.time)
                if (i == 0) { path.moveTo(x, y(h.tempC)); area.moveTo(x, chartBottom); area.lineTo(x, y(h.tempC)) } else {
                    val p = hs[i - 1]
                    val px = xOf(p.time)
                    val mx = (px + x) / 2
                    path.cubicTo(mx, y(p.tempC), mx, y(h.tempC), x, y(h.tempC))
                    area.cubicTo(mx, y(p.tempC), mx, y(h.tempC), x, y(h.tempC))
                }
            }
            area.lineTo(xOf(hs.last().time), chartBottom)
            area.close()
            fill.shader = LinearGradient(0f, chartTop, 0f, chartBottom, SceneRenderer.withAlpha(accent(), 0.35), accent() and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawPath(area, fill)
            fill.shader = null
            line.color = fg()
            line.strokeWidth = 2 * density
            canvas.drawPath(path, line)

            // The day's high and low on the line.
            bold.textSize = 11 * density * resources.configuration.fontScale.coerceAtMost(1.3f)
            for ((h, above) in listOf(hi to true, lo to false)) {
                val x = xOf(h.time)
                val yy = y(h.tempC)
                fill.color = fg()
                canvas.drawCircle(x, yy, 3 * density, fill)
                bold.color = fg()
                val label = "${WeatherText.f(h.tempC)}°"
                val tw = bold.measureText(label)
                val tx = (x - tw / 2).coerceIn(left, right - tw)
                canvas.drawText(label, tx, if (above) yy - 6 * density else yy + bold.textSize + 4 * density, bold)
            }

            // When rain is expected: a bar per hour, as tall as its chance.
            val step = (right - left) / DayBar.HOURS
            fill.color = context.color(R.color.weather_drop)
            for (h in hs) {
                val p = (h.precipProb ?: if (DayBar.wet(h)) 60 else 0) / 100f
                if (p <= 0.05f) continue
                val x = xOf(h.time)
                rect.set(x - step * 0.36f, rainBottom - (rainBottom - rainTop) * p, x + step * 0.36f, rainBottom)
                fill.alpha = (110 + 145 * p).toInt()
                canvas.drawRoundRect(rect, 2 * density, 2 * density, fill)
            }
            fill.alpha = 255

            // The time row: NOW (gold, at now), then every three hours: 12P, 3, 6, 9, 12A, ...
            text.textSize = 10 * density * resources.configuration.fontScale.coerceAtMost(1.3f)
            val nowLabel = context.getString(R.string.weather_bar_now).uppercase()
            val nowX = xOf(nowMs)
            val nowW = text.measureText(nowLabel)
            for (h in hs) {
                val local = Instant.ofEpochMilli(h.time).atOffset(zone).hour
                if (local % 3 != 0) continue
                val x = xOf(h.time)
                val label = compactHour(local)
                val tw = text.measureText(label)
                if (abs(x - nowX) < (nowW + tw) / 2 + 6 * density) continue
                text.color = dim()
                canvas.drawText(label, (x - tw / 2).coerceIn(left, right - tw), height - 4 * density, text)
            }
            text.color = accent()
            canvas.drawText(nowLabel, (nowX - nowW / 2).coerceIn(left, right - nowW), height - 4 * density, text)
        }

        // Now: a gold marker, labeled when live.
        val nx = xOf(nowMs)
        line.color = accent()
        line.strokeWidth = 2 * density
        canvas.drawLine(nx, chartTop - 2 * density, nx, rainBottom, line)
        fill.color = accent()
        canvas.drawCircle(nx, chartTop - 2 * density, 3.5f * density, fill)

        bold.textSize = 12 * density * resources.configuration.fontScale.coerceAtMost(1.3f)
        // Just back at now (on its own, after a while untouched): the readout says this hour, then fades.
        val left2 = nowReadoutUntil - android.os.SystemClock.uptimeMillis()
        if (scrubTime == null && left2 > 0 && data != null) {
            val label = readout(nowMs)
            val tw = bold.measureText(label)
            bold.color = accent()
            bold.alpha = (255 * (left2 / NOW_FADE_MS.toFloat()).coerceAtMost(1f)).toInt()
            canvas.drawText(label, (nx - tw / 2).coerceIn(left, right - tw), readoutH - 4 * density, bold)
            bold.alpha = 255
            postInvalidateOnAnimation()
        }
        val s = scrubTime
        if (s != null) {
            val sx = xOf(s)
            line.color = fg()
            line.strokeWidth = 1.5f * density
            canvas.drawLine(sx, chartTop, sx, rainBottom, line)
            fill.color = fg()
            canvas.drawCircle(sx, chartTop, 4 * density, fill)
            bold.color = fg()
            val label = readout(s)
            val tw = bold.measureText(label)
            canvas.drawText(label, (sx - tw / 2).coerceIn(left, right - tw), readoutH - 4 * density, bold)
        }
    }

    /** The time row's hours: "12P", "3", "6", "9", "12A" (or "15" on a 24-hour phone). */
    private fun compactHour(h: Int): String = when {
        android.text.format.DateFormat.is24HourFormat(context) -> h.toString().padStart(2, '0')
        h == 0 -> "12A"
        h == 12 -> "12P"
        else -> (h % 12).toString()
    }

    /** "3 PM · 54° · 40% rain" for a previewed time. */
    fun readout(t: Long): String {
        val d = data ?: return hourLabel(t)
        val (f, p) = DayBar.readout(d, t)
        return buildString {
            append(hourLabel(t)).append(" · ").append(f).append('°')
            if (p != null) append(" · ").append(context.getString(R.string.weather_bar_rain, p))
        }
    }

    // ------------------------------------------------------------------ touch

    private var downX = 0f
    private var dragging = false

    /** A finger is dragging along the bar. */
    val isDragging: Boolean get() = dragging
    private var lastHour = Long.MIN_VALUE
    /** The bar hour (0..24) the drag sits on, or null. */
    private var dragHour: Int? = null

    /** Haptic ticks given as the scrub passed an hour (tests). */
    var ticks = 0
        private set

    /** Where [x] falls along the bar, in hours from its start. */
    private fun hoursAtX(x: Float): Double = ((x - pad) / (width - 2 * pad)).toDouble().coerceIn(0.0, 1.0) * DayBar.HOURS

    /** The bar hour being shown (scrubbed), or null when live. */
    val scrubHour: Int? get() = scrubTime?.let { DayBar.hourIndex(it, nowMs) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSlide()
                downX = e.x
                dragging = false
                dragHour = scrubHour
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > 8 * density) {
                    dragging = true
                    if (dragHour == null) dragHour = DayBar.hourIndex(nowMs, nowMs)
                }
                if (dragging) {
                    // Snapped to whole hours, and sticky: only a clear move goes to the next hour.
                    val hour = DayBar.snap(hoursAtX(e.x), dragHour)
                    if (hour != dragHour || scrubTime == null) {
                        dragHour = hour
                        scrubToHour(hour)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) tap(e.x)
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; return true }
        }
        return super.onTouchEvent(e)
    }

    /** A tap at [x]: on the "now" marker, back to live; anywhere else, the nearest hour. */
    fun tap(x: Float) {
        if (abs(x - xOf(nowMs)) <= 22 * density) goLive() else scrubToHour(DayBar.snap(hoursAtX(x), null))
        performClick()
    }

    /** Shows the bar's [i]th hour (0..24). */
    fun scrubToHour(i: Int) = scrubTo(DayBar.hourTime(i, nowMs))

    fun goLive() {
        scrubTime = null
        lastHour = Math.floorDiv(nowMs, 3_600_000L)
        onScrub?.invoke(null)
        invalidate()
    }

    fun scrubTo(t: Long) {
        scrubTime = t
        val hour = Math.floorDiv(t, 3_600_000L)
        if (hour != lastHour) {
            if (lastHour != Long.MIN_VALUE && haptics) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                ticks++
            }
            lastHour = hour
        }
        onScrub?.invoke(t)
        invalidate()
    }

    override fun performClick(): Boolean = super.performClick()

    // ------------------------------------------------------------------ back to now on its own

    /** Sliding back to now (after the screen sat untouched). */
    var returning = false
        private set

    /** Until when (uptime) the bar reads out the hour at now, after sliding back to it. */
    private var nowReadoutUntil = 0L

    /** Whether the readout at now is showing (tests). */
    val nowReadoutShown: Boolean get() = scrubTime == null && android.os.SystemClock.uptimeMillis() < nowReadoutUntil

    /** The clock the slide runs on (tests can step it). */
    var slideClock: () -> Long = { android.os.SystemClock.uptimeMillis() }

    private val ease = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private var slide: Runnable? = null

    /**
     * Slides the scrubber from the previewed time back to now over [RETURN_MS], the scene and number
     * following it ([onScrub] each frame), then goes live with the hour at now read out for a moment.
     */
    fun slideToNow(onDone: () -> Unit = {}) {
        val from = scrubTime ?: return
        cancelSlide()
        returning = true
        val start = slideClock()
        val step = object : Runnable {
            override fun run() {
                val f = ((slideClock() - start).toFloat() / RETURN_MS).coerceIn(0f, 1f)
                if (f < 1f) {
                    val t = from + ((nowMs - from) * ease.getInterpolation(f).toDouble()).toLong()
                    scrubTime = t
                    lastHour = Math.floorDiv(t, 3_600_000L)
                    onScrub?.invoke(t)
                    invalidate()
                    postOnAnimation(this)
                } else {
                    slide = null
                    returning = false
                    nowReadoutUntil = android.os.SystemClock.uptimeMillis() + NOW_READOUT_MS
                    goLive()
                    onDone()
                }
            }
        }
        slide = step
        postOnAnimation(step)
    }

    /** Stops a slide back to now where it is (a finger came down). */
    fun cancelSlide() {
        slide?.let { removeCallbacks(it) }
        slide = null
        returning = false
    }

    companion object {
        const val RETURN_MS = 1_100L
        const val NOW_READOUT_MS = 4_000L
        const val NOW_FADE_MS = 600L
    }
}

/** Time formats for the weather screen, at the place's own time. */
object WeatherFormat {
    fun hour(context: Context): DateTimeFormatter = DateTimeFormatter.ofPattern(
        if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h a", context.resources.configuration.locales[0])

    fun time(context: Context): DateTimeFormatter = DateTimeFormatter.ofPattern(
        if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", context.resources.configuration.locales[0])
}
