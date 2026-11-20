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
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.DayBar
import io.github.iamth3doctor.searchlight.weather.Precip
import io.github.iamth3doctor.searchlight.weather.WeatherData
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Random
import kotlin.math.abs
import kotlin.math.sin

/**
 * The precip card in the weather numbers: a little floating island that rain (or snow) falls on
 * when it's expected (dry and sunny when it isn't), the hour's RATE, CHANCE and TYPE, when rain
 * is next expected (from the hourly forecast, never pretending to the minute), and a graph of the
 * day's (or week's) precipitation with a scrubber. In DAY its scrubber is the main bar's: one
 * position drives both, and the rows follow it.
 */
class PrecipCard(private val context: Context, private val light: Boolean) {

    /** The graph was scrubbed (DAY): move the whole screen there; null: back to now. */
    var onScrub: ((Long?) -> Unit)? = null

    private fun px(token: Int) = context.px(token)
    private val fg = if (light) 0xFF1C1C21.toInt() else context.color(R.color.on_surface)
    private val dim = if (light) 0xA61C1C21.toInt() else context.color(R.color.on_surface_dim)
    // No theme color: the controls are dark gray (the weather itself keeps its colors: blue rain, the sun).
    private val accent = if (light) 0xFF2A2A30.toInt() else context.color(R.color.on_surface)

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val island = IslandView(context)
    val rateLabel = TextView(context)
    val rateValue = TextView(context)
    val chanceValue = TextView(context)
    val typeValue = TextView(context)
    val expected = TextView(context)
    val graph = PrecipGraphView(context, light)
    val dayChip = TextView(context)
    val weekChip = TextView(context)

    private var data: WeatherData? = null
    private var nowMs = 0L
    private var scrub: Long? = null

    init {
        root.addView(TextView(context).apply {
            type(Type.CAPTION)
            typeface = Typeface.create(typeface, 700, false)
            text = context.getString(R.string.weather_precip).uppercase()
            setTextColor(accent)
            letterSpacing = 0.08f
        })
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(island, LinearLayout.LayoutParams(0, px(R.dimen.size_weather_island), 1.1f))
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun row(label: TextView, value: TextView, labelRes: Int) {
            val r = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            label.apply {
                type(Type.CAPTION)
                typeface = Typeface.create(typeface, 700, false)
                setTextColor(dim)
                letterSpacing = 0.1f
                text = context.getString(labelRes)
            }
            value.apply {
                type(Type.TILE_TITLE)
                typeface = Typeface.create(typeface, 700, false)
                setTextColor(fg)
                isSingleLine = true
            }
            r.addView(label)
            r.addView(value)
            rows.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(R.dimen.space_xs) })
        }
        row(rateLabel, rateValue, R.string.weather_precip_rate)
        row(TextView(context), chanceValue, R.string.weather_precip_chance)
        row(TextView(context), typeValue, R.string.weather_precip_type)
        top.addView(rows, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(R.dimen.space_m) })
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(R.dimen.space_xs) })

        expected.apply {
            type(Type.BODY)
            setTextColor(fg)
        }
        root.addView(expected, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(R.dimen.space_s) })

        // DAY / WEEK
        val toggle = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((chip, res, mode) in listOf(Triple(dayChip, R.string.weather_precip_day, PrecipGraphView.Mode.DAY),
            Triple(weekChip, R.string.weather_precip_week, PrecipGraphView.Mode.WEEK))) {
            chip.apply {
                type(Type.CAPTION)
                typeface = Typeface.create(typeface, 700, false)
                letterSpacing = 0.1f
                text = context.getString(res)
                gravity = Gravity.CENTER
                val p = px(R.dimen.space_s)
                setPadding(p + p / 2, p / 2 + 2, p + p / 2, p / 2 + 2)
                setOnClickListener { setMode(mode) }
            }
            toggle.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = px(R.dimen.space_xs) })
        }
        root.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(R.dimen.space_s) })
        root.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(R.dimen.size_weather_precip_graph))
            .apply { topMargin = px(R.dimen.space_xs) })

        graph.onScrub = { t -> onScrub?.invoke(t) }
        graph.onPickDay = { refreshRows(); onPicked?.invoke() }
        styleChips()
    }

    /** WEEK: a day was picked (the screen's idle timer starts over). */
    var onPicked: (() -> Unit)? = null

    /** WEEK, on a day after today: away from now. */
    val awayFromToday: Boolean get() = graph.mode == PrecipGraphView.Mode.WEEK && graph.weekIndex != 0

    /** WEEK: back to today. */
    fun backToToday() {
        graph.pickDay(0)
        refreshRows()
    }

    fun setMode(mode: PrecipGraphView.Mode) {
        graph.mode = mode
        styleChips()
        refreshRows()
    }

    private fun styleChips() {
        for ((chip, on) in listOf(dayChip to (graph.mode == PrecipGraphView.Mode.DAY), weekChip to (graph.mode == PrecipGraphView.Mode.WEEK))) {
            chip.background = GradientDrawable().apply {
                cornerRadius = 999f
                if (on) setColor(accent) else setStroke(context.resources.displayMetrics.density.toInt().coerceAtLeast(1), dim)
            }
            chip.setTextColor(if (on) (if (light) 0xFFFFFFFF.toInt() else context.color(R.color.on_gold)) else dim)
            chip.isSelected = on
        }
    }

    /** New data, a new now, or the main bar moved: everything follows. */
    fun update(data: WeatherData?, nowMs: Long, scrub: Long?) {
        this.data = data
        this.nowMs = nowMs
        this.scrub = scrub
        graph.update(data, nowMs, scrub)
        refreshRows()
    }

    private val zone get() = ZoneOffset.ofTotalSeconds(data?.detail?.utcOffsetSec ?: 0)

    private fun refreshRows() {
        val d = data ?: return
        val week = graph.mode == PrecipGraphView.Mode.WEEK
        val reading = if (week) Precip.day(d, graph.weekDayTime() ?: nowMs) else Precip.at(d, scrub ?: nowMs)
        rateLabel.text = context.getString(if (week) R.string.weather_precip_total else R.string.weather_precip_rate)
        rateValue.text = Precip.format(reading.rateIn)
        chanceValue.text = reading.chance?.let { "$it%" } ?: context.getString(R.string.weather_detail_none)
        typeValue.text = context.getString(when (reading.type) {
            Precip.Type.RAIN -> R.string.weather_precip_rain
            Precip.Type.SNOW -> R.string.weather_precip_snow
            Precip.Type.NONE -> R.string.weather_precip_none
        })
        island.set(reading.type, if (week) reading.rateIn / 24 else reading.rateIn)

        val next = Precip.nextWet(d, nowMs)
        expected.text = when {
            next == null -> context.getString(R.string.weather_precip_dry)
            next.time <= nowMs -> context.getString(if (io.github.iamth3doctor.searchlight.weather.SceneModel.isSnow(next.code)) R.string.weather_precip_snow_now else R.string.weather_precip_rain_now)
            else -> context.getString(
                if (io.github.iamth3doctor.searchlight.weather.SceneModel.isSnow(next.code)) R.string.weather_precip_snow_around else R.string.weather_precip_rain_around,
                WeatherFormat.hour(context).format(Instant.ofEpochMilli(next.time).atOffset(zone)),
            )
        }
    }
}

/**
 * A small floating island, drawn in layers for depth: grass on top, rock tapering beneath, a
 * tree, its shadow below. When it rains, a cloud over it lets drops fall onto it (as many and as
 * fast as the rate); snow drifts down instead; dry, the sun glints. Animates only while shown,
 * never in battery saver.
 */
class IslandView(context: Context) : View(context) {

    var type = Precip.Type.NONE
        private set
    /** Inches per hour. */
    var rate = 0.0
        private set
    /** Drops (or flakes) falling now (tests). */
    val particles: Int get() = if (type == Precip.Type.NONE) 0 else count

    private val density = resources.displayMetrics.density
    private val rnd = Random()
    private var count = 0
    private val px = FloatArray(80)
    private val py = FloatArray(80)
    private var t = 0.0
    private var lastNs = 0L
    private var running = false
    private val choreographer = Choreographer.getInstance()

    fun set(type: Precip.Type, rateIn: Double) {
        this.type = type
        this.rate = rateIn
        val want = when (type) {
            Precip.Type.NONE -> 0
            // 0.01 in/h: a few drops; 0.1: a steady fall; 0.3+: a downpour.
            else -> (6 + rateIn * 200).toInt().coerceIn(6, px.size)
        }
        while (count < want) {
            px[count] = rnd.nextFloat()
            py[count] = rnd.nextFloat()
            count++
        }
        count = want
        contentDescription = context.getString(R.string.weather_island_desc, type.name.lowercase())
        update()
        invalidate()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            val dt = if (lastNs == 0L) 0.0 else ((now - lastNs) / 1e9).coerceAtMost(0.1)
            lastNs = now
            t += dt
            val speed = if (type == Precip.Type.SNOW) 0.25 else 0.9 + rate * 4
            for (i in 0 until count) {
                py[i] += (speed * dt).toFloat()
                if (type == Precip.Type.SNOW) px[i] += (sin(t * 2 + i) * 0.02 * dt).toFloat()
                if (py[i] > 1f) { py[i] -= 1f; px[i] = rnd.nextFloat() }
            }
            invalidate()
            choreographer.postFrameCallbackDelayed(this, 33)
        }
    }

    private fun update() {
        val power = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        val should = isAttachedToWindow && windowVisibility == VISIBLE && isShown && !power &&
            WeatherSceneView.powerSaveOverride != true
        if (should == running) return
        running = should
        if (should) { lastNs = 0L; choreographer.postFrameCallback(frame) } else choreographer.removeFrameCallback(frame)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); update() }
    override fun onDetachedFromWindow() { running = false; choreographer.removeFrameCallback(frame); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); update() }
    override fun onVisibilityChanged(changedView: View, v: Int) { super.onVisibilityChanged(changedView, v); update() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val bob = (sin(t * 1.2) * 2 * density).toFloat()
        val cx = w / 2
        val iw = minOf(w * 0.78f, h * 1.2f)
        val topY = h * 0.6f + bob
        val wet = type != Precip.Type.NONE

        // Shadow far below: it floats.
        paint.shader = null
        paint.color = 0x33000000
        oval.set(cx - iw * 0.3f, h * 0.93f, cx + iw * 0.3f, h * 0.99f)
        canvas.drawOval(oval, paint)

        // Rock: tapering layers, lighter at the top.
        path.reset()
        path.moveTo(cx - iw / 2, topY)
        path.quadTo(cx - iw * 0.32f, topY + h * 0.2f, cx - iw * 0.08f, topY + h * 0.3f)
        path.lineTo(cx, topY + h * 0.34f)
        path.lineTo(cx + iw * 0.1f, topY + h * 0.26f)
        path.quadTo(cx + iw * 0.36f, topY + h * 0.16f, cx + iw / 2, topY)
        path.close()
        paint.shader = LinearGradient(0f, topY, 0f, topY + h * 0.34f, 0xFF8A6544.toInt(), 0xFF3E2A1C.toInt(), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        // A darker right face for depth.
        path.reset()
        path.moveTo(cx + iw * 0.06f, topY + h * 0.02f)
        path.lineTo(cx + iw / 2, topY)
        path.quadTo(cx + iw * 0.36f, topY + h * 0.16f, cx + iw * 0.1f, topY + h * 0.26f)
        path.lineTo(cx, topY + h * 0.34f)
        path.close()
        paint.shader = null
        paint.color = 0x40000000
        canvas.drawPath(path, paint)

        // Grass top: an ellipse with a lip, greener when wet.
        val grass = if (wet) 0xFF4E8F57.toInt() else 0xFF7BAA4E.toInt()
        oval.set(cx - iw / 2, topY - h * 0.07f, cx + iw / 2, topY + h * 0.07f)
        paint.color = SceneRenderer.mix(grass, 0xFF000000.toInt(), 0.25)
        canvas.drawOval(oval, paint)
        oval.offset(0f, -h * 0.02f)
        paint.color = grass
        canvas.drawOval(oval, paint)

        // A tree.
        paint.color = 0xFF5A3D27.toInt()
        val tx = cx - iw * 0.18f
        canvas.drawRect(tx - 2 * density, topY - h * 0.2f, tx + 2 * density, topY - h * 0.02f, paint)
        paint.color = SceneRenderer.mix(grass, 0xFF0F3D1C.toInt(), 0.45)
        canvas.drawCircle(tx, topY - h * 0.24f, h * 0.09f, paint)
        canvas.drawCircle(tx + h * 0.06f, topY - h * 0.19f, h * 0.07f, paint)

        if (!wet) {
            // Dry: a sun.
            paint.color = context.color(R.color.weather_sun)
            canvas.drawCircle(cx + iw * 0.28f, h * 0.2f + bob / 2, h * 0.08f, paint)
            return
        }
        // A cloud over it, then the fall onto the grass.
        paint.color = if (type == Precip.Type.SNOW) 0xFFE6EBF0.toInt() else 0xFF8C96A3.toInt()
        val cy = h * 0.16f
        for ((dx, r) in listOf(-0.22f to 0.09f, -0.05f to 0.12f, 0.14f to 0.1f, 0.28f to 0.07f)) {
            canvas.drawCircle(cx + iw * dx, cy, h * r, paint)
        }
        oval.set(cx - iw * 0.32f, cy, cx + iw * 0.36f, cy + h * 0.08f)
        canvas.drawRoundRect(oval, h * 0.04f, h * 0.04f, paint)
        val fallTop = cy + h * 0.08f
        val fallBottom = topY - h * 0.04f
        paint.color = if (type == Precip.Type.SNOW) 0xFFFFFFFF.toInt() else context.color(R.color.weather_drop)
        paint.strokeWidth = 1.6f * density
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until count) {
            val x = cx - iw * 0.3f + px[i] * iw * 0.62f
            val y = fallTop + py[i] * (fallBottom - fallTop)
            if (type == Precip.Type.SNOW) canvas.drawCircle(x, y, 1.8f * density, paint)
            else canvas.drawLine(x, y, x - 1.2f * density, y + 6 * density, paint)
        }
    }
}

/**
 * The precipitation graph: an area of amounts (blue) with the chance as a line. DAY: the main
 * bar's 24 hours, with its scrubber; WEEK: the 7 days, one picked at a time.
 */
class PrecipGraphView(context: Context, private val light: Boolean) : View(context) {

    enum class Mode { DAY, WEEK }

    var mode = Mode.DAY
        set(value) { field = value; invalidate() }
    var onScrub: ((Long?) -> Unit)? = null
    var onPickDay: (() -> Unit)? = null

    private var data: WeatherData? = null
    private var nowMs = 0L
    /** DAY: the scrubbed time (null: now). */
    var scrubTime: Long? = null
        private set
    /** WEEK: the picked day (0 today). */
    var weekIndex = 0
        private set

    /** Points on the graph now (tests). */
    val pointCount: Int get() = if (mode == Mode.DAY) DayBar.hours(data, nowMs).size else days().size

    private val density = resources.displayMetrics.density
    private val lexend = ResourcesCompat.getFont(context, R.font.lexend) ?: Typeface.DEFAULT
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = lexend; textSize = 10 * resources.displayMetrics.density }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val fg = if (light) 0xFF1C1C21.toInt() else 0xFFFFFFFF.toInt()
    private val dim = if (light) 0x991C1C21.toInt() else 0x99FFFFFF.toInt()
    // No theme color: the controls are dark gray (the weather itself keeps its colors: blue rain, the sun).
    private val accent = if (light) 0xFF2A2A30.toInt() else context.color(R.color.on_surface)
    private val drop = context.color(R.color.weather_drop)

    init {
        isClickable = true
        contentDescription = context.getString(R.string.weather_precip_graph_desc)
    }

    fun update(data: WeatherData?, nowMs: Long, scrub: Long?) {
        this.data = data
        this.nowMs = nowMs
        this.scrubTime = scrub
        invalidate()
    }

    private fun days() = data?.detail?.daily.orEmpty().take(7)

    fun pickDay(i: Int) {
        weekIndex = i
        invalidate()
    }

    /** WEEK: the picked day's time (noon-ish), for its numbers. */
    fun weekDayTime(): Long? = days().getOrNull(weekIndex)?.let { it.date + 12 * 3_600_000L }

    private val pad get() = 8 * density
    private val bottom get() = height - 16 * density
    private val top get() = 6 * density

    /** DAY: where [t] is; WEEK: day [i]. */
    fun xOf(t: Long): Float = pad + (width - 2 * pad) * DayBar.fractionOf(t, nowMs).toFloat()
    fun xOfDay(i: Int): Float = pad + (width - 2 * pad) * (if (days().size <= 1) 0.5f else i / (days().size - 1f))

    override fun onDraw(canvas: Canvas) {
        val zone = ZoneOffset.ofTotalSeconds(data?.detail?.utcOffsetSec ?: 0)
        // Baseline.
        line.color = dim
        line.strokeWidth = 1 * density
        canvas.drawLine(pad, bottom, width - pad, bottom, line)
        if (mode == Mode.DAY) {
            val hs = DayBar.hours(data, nowMs)
            if (hs.isEmpty()) return
            val max = maxOf(1.0, hs.maxOf { it.precipMm ?: 0.0 })
            fun y(mm: Double) = (bottom - (mm / max) * (bottom - top)).toFloat()
            chance(canvas, hs.map { xOf(it.time) to (it.precipProb ?: 0) })
            area(canvas, hs.map { xOf(it.time) to y(it.precipMm ?: 0.0) })
            // Labels every 6 hours.
            text.color = dim
            for (h in hs) {
                val local = Instant.ofEpochMilli(h.time).atOffset(zone).hour
                if (local % 6 != 0) continue
                val l = WeatherFormat.hour(context).format(Instant.ofEpochMilli(h.time).atOffset(zone))
                val tw = text.measureText(l)
                canvas.drawText(l, (xOf(h.time) - tw / 2).coerceIn(0f, width - tw), height - 3 * density, text)
            }
            // Now, and the shared scrubber.
            marker(canvas, xOf(nowMs), accent, 1.5f)
            scrubTime?.let { marker(canvas, xOf(it), fg, 2f) }
        } else {
            val ds = days()
            if (ds.isEmpty()) return
            val max = maxOf(2.0, ds.maxOf { it.precipMm ?: 0.0 })
            fun y(mm: Double) = (bottom - (mm / max) * (bottom - top)).toFloat()
            chance(canvas, ds.mapIndexed { i, d -> xOfDay(i) to (d.precipPct ?: 0) })
            area(canvas, ds.mapIndexed { i, d -> xOfDay(i) to y(d.precipMm ?: 0.0) })
            text.color = dim
            val locale = resources.configuration.locales[0]
            ds.forEachIndexed { i, d ->
                val l = if (i == 0) context.getString(R.string.weather_detail_today)
                    else Instant.ofEpochMilli(d.date).atOffset(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                val tw = text.measureText(l)
                canvas.drawText(l, (xOfDay(i) - tw / 2).coerceIn(0f, width - tw), height - 3 * density, text)
            }
            marker(canvas, xOfDay(weekIndex), fg, 2f)
        }
    }

    /** The amount (mm), in front: a solid, deeper fill. */
    private fun area(canvas: Canvas, points: List<Pair<Float, Float>>) {
        if (points.size < 2 || points.all { it.second >= bottom - 0.5f }) return
        smooth(points)
        path.lineTo(points.last().first, bottom)
        path.lineTo(points.first().first, bottom)
        path.close()
        fill.shader = LinearGradient(0f, top, 0f, bottom, SceneRenderer.withAlpha(drop, 0.95), SceneRenderer.withAlpha(drop, 0.55), Shader.TileMode.CLAMP)
        canvas.drawPath(path, fill)
        fill.shader = null
    }

    /** The chance (%), behind: a filled area chart with a solid edge along its top. */
    private fun chance(canvas: Canvas, points: List<Pair<Float, Int>>) {
        if (points.size < 2) return
        val pts = points.map { (x, p) -> x to (bottom - p.coerceIn(0, 100) / 100f * (bottom - top)) }
        smooth(pts)
        line.color = SceneRenderer.withAlpha(drop, 0.9)
        line.strokeWidth = 1.6f * density
        line.pathEffect = null
        canvas.drawPath(path, line)
        path.lineTo(pts.last().first, bottom)
        path.lineTo(pts.first().first, bottom)
        path.close()
        fill.shader = LinearGradient(0f, top, 0f, bottom, SceneRenderer.withAlpha(drop, 0.45), SceneRenderer.withAlpha(drop, 0.08), Shader.TileMode.CLAMP)
        canvas.drawPath(path, fill)
        fill.shader = null
    }

    /** [path] through [points], curved (never overshooting between neighbours). */
    private fun smooth(points: List<Pair<Float, Float>>) {
        path.reset()
        points.forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x, y) else {
                val (px, py) = points[i - 1]
                val mx = (px + x) / 2
                path.cubicTo(mx, py, mx, y, x, y)
            }
        }
    }

    private fun marker(canvas: Canvas, x: Float, color: Int, w: Float) {
        line.color = color
        line.strokeWidth = w * density
        canvas.drawLine(x, top - 2 * density, x, bottom, line)
        fill.color = color
        canvas.drawCircle(x, top - 2 * density, 3 * density, fill)
    }

    // ---- touch: scrub (DAY) or pick a day (WEEK)

    private var downX = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; dragging = false; parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > 6 * density) dragging = true
                if (dragging) touchAt(e.x, tap = false)
                return true
            }
            MotionEvent.ACTION_UP -> { if (!dragging) { touchAt(e.x, tap = true); performClick() }; dragging = false; return true }
            MotionEvent.ACTION_CANCEL -> { dragging = false; return true }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    /** A touch at [x]: DAY scrubs there (a tap on now goes live); WEEK picks the nearest day. */
    fun touchAt(x: Float, tap: Boolean) {
        if (mode == Mode.DAY) {
            if (tap && abs(x - xOf(nowMs)) <= 18 * density) {
                scrubTime = null
                onScrub?.invoke(null)
            } else {
                val t = DayBar.timeAt(((x - pad) / (width - 2 * pad)).toDouble(), nowMs)
                scrubTime = t
                onScrub?.invoke(t)
            }
        } else {
            val n = days().size
            if (n == 0) return
            weekIndex = (0 until n).minBy { abs(xOfDay(it) - x) }
            onPickDay?.invoke()
        }
        invalidate()
    }
}
