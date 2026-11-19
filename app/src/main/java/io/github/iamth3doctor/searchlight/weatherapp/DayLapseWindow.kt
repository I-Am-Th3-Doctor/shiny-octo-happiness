package io.github.iamth3doctor.searchlight.weatherapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.DayLapse
import io.github.iamth3doctor.searchlight.weather.SceneConditions
import io.github.iamth3doctor.searchlight.weather.SceneModel
import io.github.iamth3doctor.searchlight.weather.WeatherCodes
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherText
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * A window that plays one day as a time-lapse from its real hourly forecast, midnight to midnight in
 * [DayLapse.PLAY_MS]: the sun rises, arcs and sets, the moon takes the night, clouds, rain and wind
 * come and go at their hours, and the temperature, condition and an hour readout ("2 PM · 44° ·
 * 10% rain") ride along over a thin bar showing where in the day it is (and, in blue, when rain is
 * due). At rest it shows the weather now, alive.
 *
 * As a [card] (the 7-day view) it's a rounded window with the numbers over it; otherwise (the data
 * screen) the caller lays out [preview] behind its content, with no bar or readout over it, and
 * follows the played (or scrubbed, [showAt]) moment with [onFrame].
 */
class DayLapseWindow(private val context: Context, private val card: Boolean = true) {

    /** The card: the scene, and over it while playing, the numbers. (Not a card: unused.) */
    val root = FrameLayout(context).apply {
        if (card) {
            background = GradientDrawable().apply { cornerRadius = context.pxF(R.dimen.radius_card); setColor(0xFF000000.toInt()) }
            clipToOutline = true
        }
    }
    val preview = WeatherSceneView(context, mini = false)
    val overlay = FrameLayout(context).apply { visibility = View.GONE }
    val lapseTemp = TextView(context)
    val lapseCondition = TextView(context)
    val readout = TextView(context)
    val lapseBar = LapseBarView(context)

    /** How far through the played day (0..1), while one plays. */
    var progress = 0.0
        private set

    /** The clock the time-lapse plays by (tests can drive it). */
    var playClock: () -> Long = { android.os.SystemClock.uptimeMillis() }

    /** The day playing (index into the forecast), or null. */
    var day: Int? = null
        private set

    /** The time the window shows while playing (tests). */
    var previewTime: Long? = null
        private set

    /** Each played moment: the scene's conditions and the hour's readout (the details' own numbers follow it). */
    var onFrame: ((SceneConditions, DayLapse.Readout) -> Unit)? = null

    private var data: WeatherData? = null
    private val main = Handler(Looper.getMainLooper())
    private var playStart = 0L

    init {
        buildOverlay()
        if (card) {
            root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun px(t: Int) = context.px(t)

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
        // Full screen, the details' own big number and condition play along instead.
        if (card) overlay.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
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

    private fun deg(c: Double) = "${WeatherText.f(c)}°"

    /** When rain is due on day [i]: (start, end) times. */
    fun wetWindows(data: WeatherData, i: Int): List<Pair<Long, Long>> =
        data.detail?.daily?.getOrNull(i)?.let { DayLapse.wetWindows(data, it.date) }.orEmpty()

    /** Plays day [i] of [data] from midnight, until [stop]. False if there's no such day. */
    fun play(data: WeatherData, i: Int): Boolean {
        val d = data.detail ?: return false
        val dayPoint = d.daily.getOrNull(i) ?: return false
        this.data = data
        day = i
        lapseBar.windows = wetWindows(data, i).map { (a, b) -> ((a - dayPoint.date) / DAY_MS.toFloat()) to ((b - dayPoint.date) / DAY_MS.toFloat()) }
        playStart = playClock()
        progress = 0.0
        // An hour passes in a third of a second here: the scene keeps up close behind.
        preview.renderer.easeSec = 0.12
        frame(0.0)
        preview.jumpTo(SceneModel.at(data, DayLapse.time(dayPoint.date, 0.0), live = false))
        overlay.visibility = View.VISIBLE
        preview.paused = false
        main.removeCallbacks(tick)
        main.postDelayed(tick, STEP_MS)
        return true
    }

    /** Stops playing: the numbers go, and the scene eases back to the weather now ([data] at [now]). */
    fun stop(data: WeatherData?, now: Long) {
        day = null
        previewTime = null
        main.removeCallbacks(tick)
        overlay.visibility = View.GONE
        showLive(data, now)
    }

    /**
     * At rest: the weather now, alive (clouds drifting, rain if it's raining, the wind), easing there
     * from wherever the scene was. Called again as time passes, it keeps up. Not while a day plays.
     */
    fun showLive(data: WeatherData?, now: Long) {
        if (day != null) return
        previewTime = null
        preview.renderer.easeSec = 0.7
        preview.paused = false
        if (data != null) preview.conditions = SceneModel.at(data, now, live = true)
    }

    /** Shows the hour [t] (dragged to on the scene), easing there; [showLive] goes back to now. Not while a day plays. */
    fun showAt(data: WeatherData, t: Long) {
        if (day != null) return
        previewTime = t
        preview.renderer.easeSec = 0.25
        preview.paused = false
        val c = SceneModel.at(data, t, live = false)
        preview.conditions = c
        onFrame?.invoke(c, DayLapse.readout(data, t))
    }

    /** Shows the moment [f] of the way through the playing day: scene, numbers, readout and bar. */
    private fun frame(f: Double) {
        val i = day ?: return
        val d = data ?: return
        val detail = d.detail ?: return
        val dayPoint = detail.daily.getOrNull(i) ?: return
        val t = DayLapse.time(dayPoint.date, f)
        previewTime = t
        progress = f
        val c = SceneModel.at(d, t, live = false)
        preview.conditions = c
        val r = DayLapse.readout(d, t)
        val zone = ZoneOffset.ofTotalSeconds(detail.utcOffsetSec)
        val hour = WeatherFormat.hour(context).format(Instant.ofEpochMilli(r.hourMs).atOffset(zone))
        lapseTemp.text = deg(c.tempC)
        lapseCondition.text = WeatherCodes.describe(r.code)
        readout.text = context.getString(R.string.weather_week_readout, hour, deg(r.tempC), r.precipProb ?: 0)
        lapseBar.progress = f.toFloat()
        onFrame?.invoke(c, r)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (day == null) return
            val f = DayLapse.progress(playClock() - playStart)
            frame(f)
            // At the end of the day it rests there (the scene still lives) until it's let go.
            if (f < 1.0) main.postDelayed(this, STEP_MS)
        }
    }

    companion object {
        const val STEP_MS = 50L
        const val DAY_MS = 24 * 3_600_000L

        /**
         * Hold [row] to play: [onHold] after a long-press timeout without moving; [onRelease] when the
         * finger lifts (or the touch is taken away) after it took. A move first is a scroll, not a hold.
         */
        @SuppressLint("ClickableViewAccessibility")
        fun holdToPlay(row: View, isHeld: () -> Boolean, onHold: () -> Unit, onRelease: () -> Unit) {
            val slop = ViewConfiguration.get(row.context).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            val start = Runnable {
                row.parent?.requestDisallowInterceptTouchEvent(true)
                onHold()
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
                        if (!isHeld() && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                            v.removeCallbacks(start)
                            return@setOnTouchListener false
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(start)
                        if (isHeld()) onRelease()
                        true
                    }
                    else -> false
                }
            }
        }
    }
}
