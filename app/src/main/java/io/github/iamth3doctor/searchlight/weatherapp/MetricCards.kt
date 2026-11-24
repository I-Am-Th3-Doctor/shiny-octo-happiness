package io.github.iamth3doctor.searchlight.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.iamth3doctor.searchlight.weather.MetricArt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The details' metric cards (Sun, Moon, Humidity, Visibility, Pressure, UV, Air quality, Wind).
 * Collapsed, a card is its label, value and line under it, as before. Tapped, it opens in place
 * into an animated picture of that number (the sun on its arc, the moon's phase and when it's up,
 * mist as thick as the humidity, ...) with a plain line on what it means; tapped again, or another
 * card tapped, it closes. Opening and closing ease the card's height, so the cards under it slide
 * down and back rather than jump.
 */

/** What a card's picture shows: the card's own numbers. */
data class ArtInfo(
    val kind: String,
    // sun: where it is along its arc (0 sunrise, 1 sunset), and the times
    val sunF: Double? = null,
    val riseText: String? = null,
    val setText: String? = null,
    // moon
    val illumination: Double = 0.0,
    val waxing: Boolean = true,
    val moonDay: MetricArt.MoonDay? = null,
    /** Now, as a share of the day since local midnight. */
    val nowF: Double = 0.0,
    val ticks: List<String> = emptyList(),
    /** The same hours, short ("12a"), for when the full ones don't fit. */
    val shortTicks: List<String> = emptyList(),
    // humidity (0..1)
    val humidity: Double = 0.0,
    // visibility (miles)
    val visMiles: Double = 0.0,
    // pressure: where the needle sits (0..1), and the trend
    val pressureF: Double = 0.5,
    val trend: MetricArt.Trend? = null,
    // uv
    val uv: Double = 0.0,
    // air quality
    val aqi: Int = 0,
    // wind: the way it blows to (0 north, 90 east) and how hard
    val windTowardDeg: Double = 0.0,
    val windMph: Double = 0.0,
)

/** The animated picture inside an open card. */
class MetricArtView(context: Context, private val light: Boolean) : View(context) {

    var info: ArtInfo? = null
        set(value) { field = value; invalidate() }

    /** Playing (the card is open): from the start of its opening flourish. */
    var active = false
        set(value) {
            if (value && !field) startMs = SystemClock.uptimeMillis()
            field = value
            invalidate()
        }

    /** Frames drawn while open (tests). */
    var frames = 0L
        private set

    private var startMs = 0L
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * resources.displayMetrics.scaledDensity }
    private val path = Path()
    private val rect = RectF()
    private val fg = if (light) 0xFF1C1C21.toInt() else 0xFFF2F2F5.toInt()
    private val dim = if (light) 0x991C1C21.toInt() else 0x99F2F2F5.toInt()
    private val faint = if (light) 0x261C1C21 else 0x26F2F2F5
    private val sunColor = 0xFFFFC94D.toInt()
    private val drop = 0xFF5AA9F0.toInt()

    private fun powerSave() = WeatherSceneView.powerSaveOverride
        ?: (context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true)

    private val running get() = active && isAttachedToWindow && isShown && !powerSave()

    /** Seconds since it opened (in battery saver: the settled picture, still). */
    private fun seconds(): Double = if (powerSave()) 30.0 else (SystemClock.uptimeMillis() - startMs) / 1000.0

    /** The opening flourish, 0 to 1 over the first second. */
    private fun entry(t: Double): Double {
        val x = (t / 1.0).coerceIn(0.0, 1.0)
        return 1 - (1 - x) * (1 - x) * (1 - x)
    }

    /** Where the last frame put its words (for the tests: none may overlap another). */
    val drawnLabels = ArrayList<Pair<String, RectF>>()

    override fun onDraw(canvas: Canvas) {
        drawnLabels.clear()
        val i = info ?: return
        val t = seconds()
        when (i.kind) {
            "sun" -> sun(canvas, i, t)
            "moon" -> moon(canvas, i, t)
            "humidity" -> humidity(canvas, i, t)
            "visibility" -> visibility(canvas, i, t)
            "pressure" -> pressure(canvas, i, t)
            "uv" -> uv(canvas, i, t)
            "aqi" -> aqi(canvas, i, t)
            "wind" -> wind(canvas, i, t)
        }
        if (running) {
            frames++
            postInvalidateDelayed(FRAME_MS)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate()
    }

    private fun label(canvas: Canvas, s: String, x: Float, y: Float, align: Paint.Align, color: Int = dim) {
        text.color = color
        text.textAlign = align
        canvas.drawText(s, x, y, text)
        val w = text.measureText(s)
        val left = when (align) { Paint.Align.LEFT -> x; Paint.Align.CENTER -> x - w / 2; else -> x - w }
        drawnLabels += s to RectF(left, y + text.ascent(), left + w, y + text.descent())
    }

    // ------------------------------------------------------------------ sun

    private fun sun(c: Canvas, i: ArtInfo, t: Double) {
        val pad = 16 * d
        val base = height - 24 * d
        val peak = 14 * d
        val x0 = pad
        val x1 = width - pad
        fun x(f: Double) = (x0 + (x1 - x0) * f).toFloat()
        fun y(f: Double) = (base - (base - peak) * sin(PI * f)).toFloat()
        // The horizon, and the sun's whole path for today, dashed.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.color = faint
        c.drawLine(0f, base, width.toFloat(), base, paint)
        path.reset()
        for (k in 0..48) { val f = k / 48.0; if (k == 0) path.moveTo(x(f), y(f)) else path.lineTo(x(f), y(f)) }
        paint.color = dim
        paint.pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
        c.drawPath(path, paint)
        paint.pathEffect = null
        val f = i.sunF ?: return
        val up = f in 0.0..1.0
        // The part of the day already lit, in gold, as far as the sun has come.
        val shown = if (up) f * entry(t) else if (f > 1) entry(t) else 0.0
        if (shown > 0) {
            path.reset()
            val n = (48 * shown).toInt().coerceAtLeast(1)
            for (k in 0..n) { val g = shown * k / n; if (k == 0) path.moveTo(x(g), y(g)) else path.lineTo(x(g), y(g)) }
            paint.color = sunColor
            paint.strokeWidth = 3 * d
            c.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        // Sunrise and sunset, marked.
        paint.color = dim
        c.drawCircle(x0, base, 3 * d, paint)
        c.drawCircle(x1, base, 3 * d, paint)
        // Sunrise under the left end of the arc, sunset under the right: one line each, never
        // meeting in the middle (both shrink together if the card is too narrow for them).
        val riseLabel = i.riseText?.let { "↑${WeatherFormat.NBSP}$it" }
        val setLabel = i.setText?.let { "↓${WeatherFormat.NBSP}$it" }
        val size = text.textSize
        val need = (riseLabel?.let(text::measureText) ?: 0f) + (setLabel?.let(text::measureText) ?: 0f) + LABEL_GAP_DP * d
        if (need > x1 - x0) text.textSize = size * (x1 - x0) / need
        riseLabel?.let { label(c, it, x0, base + 16 * d, Paint.Align.LEFT) }
        setLabel?.let { label(c, it, x1, base + 16 * d, Paint.Align.RIGHT) }
        text.textSize = size
        if (up) {
            val sx = x(shown)
            val sy = y(shown)
            val pulse = (1 + 0.12 * sin(t * 2.4)).toFloat()
            paint.color = 0x40FFC94D
            c.drawCircle(sx, sy, 16 * d * pulse, paint)
            paint.color = sunColor
            paint.strokeWidth = 2 * d
            for (k in 0 until 8) {
                val a = k * PI / 4 + t * 0.6
                val r0 = 12 * d
                val r1 = 17 * d * pulse
                c.drawLine(sx + (r0 * cos(a)).toFloat(), sy + (r0 * sin(a)).toFloat(), sx + (r1 * cos(a)).toFloat(), sy + (r1 * sin(a)).toFloat(), paint)
            }
            c.drawCircle(sx, sy, 9 * d, paint)
        } else {
            // Below the horizon: just its glow, at the end it set from or will rise at.
            val sx = if (f > 1) x1 else x0
            paint.color = 0x55FFC94D
            c.save()
            c.clipRect(0f, 0f, width.toFloat(), base)
            c.drawCircle(sx, base + 4 * d, 9 * d, paint)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ moon

    private fun moon(c: Canvas, i: ArtInfo, t: Double) {
        val cx = width / 2f
        val r = min(30 * d, (height - 64 * d) / 2)
        val cy = 10 * d + r
        // A few stars about it, twinkling.
        for (k in 0 until 14) {
            val sx = ((k * 73 + 17) % 100) / 100f * width
            val sy = ((k * 41 + 7) % 100) / 100f * (cy + r)
            if (abs(sx - cx) < r * 1.3f && abs(sy - cy) < r * 1.3f) continue
            paint.color = fg
            paint.alpha = (90 + 80 * sin(t * (1.3 + k % 4 * 0.4) + k)).toInt().coerceIn(20, 255)
            c.drawCircle(sx, sy, (1 + k % 2) * d * 0.8f, paint)
        }
        paint.alpha = 255
        // The phase as it is: the lit part filling in as the card opens.
        val lit = i.illumination * entry(t)
        paint.color = 0x33F2EFE4
        c.drawCircle(cx, cy, r * 1.25f, paint)
        paint.color = if (light) 0xFF3A3F4B.toInt() else 0xFF2B2F3A.toInt()
        c.drawCircle(cx, cy, r, paint)
        c.save()
        if (!i.waxing) c.scale(-1f, 1f, cx, cy)
        path.reset()
        rect.set(cx - r, cy - r, cx + r, cy + r)
        path.arcTo(rect, -90f, 180f) // the lit (right) half, top to bottom
        val ew = (r * abs(1 - 2 * lit)).toFloat()
        rect.set(cx - ew, cy - r, cx + ew, cy + r)
        // Gibbous: the terminator bulges into the dark side; crescent: into the lit side.
        if (lit > 0.5) path.arcTo(rect, 90f, 180f) else path.arcTo(rect, 90f, -180f)
        path.close()
        paint.color = 0xFFF2EFE4.toInt()
        c.drawPath(path, paint)
        c.restore()
        // The day: night blue, day pale; the moon up across it, bright where it's up in a dark sky.
        val day = i.moonDay ?: return
        val pad = 12 * d
        val top = height - 34 * d
        val bh = 10 * d
        val w = width - 2 * pad
        val n = day.steps
        for (k in 0 until n) {
            val x = pad + w * k / n
            val xe = pad + w * (k + 1) / n + 0.5f
            paint.color = if (day.dark[k]) 0xFF1E2A4A.toInt() else 0xFFB9D5F0.toInt()
            c.drawRect(x, top, xe, top + bh, paint)
            if (day.up[k]) {
                paint.color = if (day.visible[k]) 0xFFF2EFE4.toInt() else 0x80F2EFE4.toInt()
                c.drawRect(x, top + bh * 0.3f, xe, top + bh * 0.7f, paint)
            }
        }
        // Now.
        val nx = pad + w * i.nowF.toFloat()
        paint.color = if (light) 0xFFB8860B.toInt() else 0xFFFFD27A.toInt()
        c.drawRect(nx - 1 * d, top - 4 * d, nx + 1 * d, top + bh + 4 * d, paint)
        // The hours under it: full ("12 AM"), else short ("12a"), else every other one, whichever
        // first leaves clear space between each label. Never two on top of each other.
        val n0 = i.ticks.size
        val choice = sequenceOf(i.ticks to 1, i.shortTicks to 1, i.shortTicks to 2)
            .filter { it.first.size == n0 && n0 > 0 }
            .firstOrNull { (labels, every) -> ticksFit(labels, every, pad, w) }
            ?: ((i.shortTicks.takeIf { it.size == n0 } ?: i.ticks) to n0.coerceAtLeast(1))
        val (labels, every) = choice
        labels.forEachIndexed { k, s ->
            if (k % every != 0) return@forEachIndexed
            label(c, s, pad + w * k / n0, top + bh + 14 * d, if (k == 0) Paint.Align.LEFT else Paint.Align.CENTER)
        }
    }

    /** Whether every [every]th of [labels], spread across [w] from [pad] as the moon's hours are, leaves a clear gap. */
    private fun ticksFit(labels: List<String>, every: Int, pad: Float, w: Float): Boolean {
        var lastRight = Float.NEGATIVE_INFINITY
        labels.forEachIndexed { k, s ->
            if (k % every != 0) return@forEachIndexed
            val x = pad + w * k / labels.size
            val tw = text.measureText(s)
            val left = if (k == 0) x else x - tw / 2
            val right = left + tw
            if (left - lastRight < LABEL_GAP_DP * d || right > width) return false
            lastRight = right
        }
        return true
    }

    // ------------------------------------------------------------------ humidity

    private fun humidity(c: Canvas, i: ArtInfo, t: Double) {
        val hum = i.humidity
        // Mist: drifting bands, thicker with the humidity.
        for (k in 0 until 3) {
            val y = height * (0.25f + 0.25f * k)
            val x = ((t * (8 + k * 5) * d + k * 90 * d) % (width + 160 * d)).toFloat() - 80 * d
            paint.color = drop
            paint.alpha = (hum * 70).toInt()
            rect.set(x - 70 * d, y - 9 * d, x + 70 * d, y + 9 * d)
            c.drawOval(rect, paint)
        }
        // Droplets, as many as the air holds, drifting down.
        val n = (6 + hum * 60).toInt()
        for (k in 0 until n) {
            val sx = ((k * 53 + 11) % 97) / 97f * width
            val speed = (6 + k % 5 * 3) * d
            val sy = (((k * 31 % 89) / 89f * height + t * speed) % height).toFloat()
            paint.color = drop
            paint.alpha = (60 + (k % 3) * 40)
            c.drawCircle(sx, sy, (1.2f + (k % 3) * 0.8f) * d, paint)
        }
        paint.alpha = 255
        // A drop filled to the humidity, its surface rippling.
        val cx = width / 2f
        val r = min(26 * d, height / 4f)
        val cy = height / 2f + r * 0.3f
        path.reset()
        path.moveTo(cx, cy - r * 2.1f)
        path.cubicTo(cx + r * 0.4f, cy - r * 1.2f, cx + r, cy - r * 0.6f, cx + r, cy)
        path.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 0f, 180f)
        path.cubicTo(cx - r, cy - r * 0.6f, cx - r * 0.4f, cy - r * 1.2f, cx, cy - r * 2.1f)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = if (light) 0xFFFFFFFF.toInt() else 0xFF20242C.toInt()
        c.drawPath(path, paint)
        c.save()
        c.clipPath(path)
        val level = (hum * entry(t)).toFloat()
        val topY = cy + r - (r * 3.1f) * level
        val wave = Path()
        wave.moveTo(cx - r * 1.2f, height.toFloat())
        var x = cx - r * 1.2f
        while (x <= cx + r * 1.2f) {
            wave.lineTo(x, topY + (2.5 * d * sin(x / (8 * d) + t * 3)).toFloat())
            x += 2 * d
        }
        wave.lineTo(cx + r * 1.2f, height.toFloat())
        wave.close()
        paint.color = drop
        c.drawPath(wave, paint)
        c.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * d
        paint.color = drop
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    // ------------------------------------------------------------------ visibility

    private val marks = listOf(1.0, 3.0, 6.0, 10.0)

    private fun visibility(c: Canvas, i: ArtInfo, t: Double) {
        val pad = 14 * d
        val ground = height - 26 * d
        val w = width - 2 * pad
        fun x(mi: Double) = pad + w * (mi / 10).toFloat()
        paint.color = if (light) 0xFFD9E6CF.toInt() else 0xFF26301F.toInt()
        c.drawRect(0f, ground, width.toFloat(), height.toFloat(), paint)
        val vis = i.visMiles * entry(t) + 0.0001
        for ((k, mi) in marks.withIndex()) {
            val cx = x(mi)
            val base = ground - (mi / 10 * 6 * d).toFloat()
            val s = (1.15 - mi / 12).toFloat() // nearer, bigger
            paint.color = fg
            paint.alpha = (255 * max(0.06, MetricArt.clarity(mi, vis))).toInt()
            when (k) {
                0 -> { // a tree
                    c.drawRect(cx - 2 * d * s, base - 14 * d * s, cx + 2 * d * s, base, paint)
                    c.drawCircle(cx, base - 24 * d * s, 12 * d * s, paint)
                }
                1 -> { // a house
                    c.drawRect(cx - 12 * d * s, base - 16 * d * s, cx + 12 * d * s, base, paint)
                    path.reset()
                    path.moveTo(cx - 15 * d * s, base - 16 * d * s); path.lineTo(cx, base - 28 * d * s); path.lineTo(cx + 15 * d * s, base - 16 * d * s); path.close()
                    c.drawPath(path, paint)
                }
                2 -> { // a tower
                    c.drawRect(cx - 4 * d * s, base - 40 * d * s, cx + 4 * d * s, base, paint)
                    c.drawRect(cx - 9 * d * s, base - 44 * d * s, cx + 9 * d * s, base - 38 * d * s, paint)
                }
                else -> { // mountains
                    path.reset()
                    path.moveTo(cx - 34 * d * s, base); path.lineTo(cx - 10 * d * s, base - 46 * d * s); path.lineTo(cx + 4 * d * s, base - 30 * d * s)
                    path.lineTo(cx + 14 * d * s, base - 40 * d * s); path.lineTo(cx + 30 * d * s, base); path.close()
                    c.drawPath(path, paint)
                }
            }
            paint.alpha = 255
            label(c, if (k == marks.size - 1) "${mi.toInt()} mi" else "${mi.toInt()}", cx, height - 10 * d, Paint.Align.CENTER)
        }
        // Haze drifting across, thicker the less you can see.
        val haze = (1 - MetricArt.visibilityFraction(i.visMiles * 1609.344)).toFloat()
        for (k in 0 until 2) {
            val hx = ((t * (6 + k * 4) * d + k * 120 * d) % (width + 200 * d)).toFloat() - 100 * d
            paint.color = if (light) 0xFFFFFFFF.toInt() else 0xFF9AA3AE.toInt()
            paint.alpha = (haze * 90).toInt()
            rect.set(hx - 110 * d, ground - 60 * d, hx + 110 * d, ground)
            c.drawOval(rect, paint)
        }
        paint.alpha = 255
        // How far you can see, marked.
        val mx = x(min(vis, 10.0))
        paint.color = if (light) 0xFFB8860B.toInt() else 0xFFFFD27A.toInt()
        paint.strokeWidth = 1.5f * d
        paint.pathEffect = DashPathEffect(floatArrayOf(3 * d, 3 * d), 0f)
        c.drawLine(mx, 6 * d, mx, ground, paint)
        paint.pathEffect = null
        val v = i.visMiles
        val s = if (v >= 10) "${v.toInt()} mi ›" else if (v < 1) String.format(java.util.Locale.US, "%.1f mi", v) else "${v.toInt()} mi"
        label(c, s, min(mx + 4 * d, width - 4 * d), 16 * d, if (mx > width * 0.7f) Paint.Align.RIGHT else Paint.Align.LEFT, paint.color)
    }

    // ------------------------------------------------------------------ pressure

    private fun pressure(c: Canvas, i: ArtInfo, t: Double) {
        val cx = width / 2f
        val r = min(width / 2f - 18 * d, height - 46 * d)
        val cy = 10 * d + r
        rect.set(cx - r, cy - r, cx + r, cy + r)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8 * d
        paint.strokeCap = Paint.Cap.ROUND
        // Low (stormy) to high (fair).
        val bands = intArrayOf(0xFF6F7FA8.toInt(), 0xFF8FB0C9.toInt(), 0xFFB9D29A.toInt(), 0xFFF2C46B.toInt())
        for (k in bands.indices) {
            paint.color = bands[k]
            c.drawArc(rect, 180f + 180f * k / bands.size + 2, 180f / bands.size - 4, false, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = 1.5f * d
        paint.color = dim
        val span = MetricArt.GAUGE_HIGH_INHG - MetricArt.GAUGE_LOW_INHG
        for (v in listOf(29.0, 29.5, 30.0, 30.5)) {
            val a = PI + PI * (v - MetricArt.GAUGE_LOW_INHG) / span
            c.drawLine(cx + ((r - 12 * d) * cos(a)).toFloat(), cy + ((r - 12 * d) * sin(a)).toFloat(),
                cx + ((r - 18 * d) * cos(a)).toFloat(), cy + ((r - 18 * d) * sin(a)).toFloat(), paint)
            if (v % 1.0 == 0.0) label(c, v.toInt().toString(), cx + ((r - 28 * d) * cos(a)).toFloat(), cy + ((r - 28 * d) * sin(a)).toFloat() + 4 * d, Paint.Align.CENTER)
        }
        paint.style = Paint.Style.FILL
        label(c, "Low", cx - r, cy + 16 * d, Paint.Align.LEFT)
        label(c, "High", cx + r, cy + 16 * d, Paint.Align.RIGHT)
        // The needle swings over to the reading and settles (a little overshoot), then trembles.
        val e = (t / 1.2).coerceIn(0.0, 1.0)
        val settle = 1 - cos(e * PI * 2.5) * (1 - e) * (1 - e) * 0.9 - (1 - e) * 0.1 * (1 - e)
        val f = 0.5 + (i.pressureF - 0.5) * settle + 0.002 * sin(t * 7)
        val a = PI + PI * f
        paint.color = fg
        paint.strokeWidth = 3 * d
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx, cy, cx + ((r - 10 * d) * cos(a)).toFloat(), cy + ((r - 10 * d) * sin(a)).toFloat(), paint)
        paint.strokeCap = Paint.Cap.BUTT
        c.drawCircle(cx, cy, 5 * d, paint)
        // Which way it's going.
        i.trend?.let { tr ->
            val bob = (3 * d * sin(t * 3)).toFloat()
            val dy = when (tr) { MetricArt.Trend.RISING -> -bob; MetricArt.Trend.FALLING -> bob; else -> 0f }
            val dx = if (tr == MetricArt.Trend.STEADY) bob else 0f
            text.textSize = 22 * resources.displayMetrics.scaledDensity
            label(c, tr.arrow, cx + dx, cy - r * 0.38f + dy, Paint.Align.CENTER,
                if (tr == MetricArt.Trend.FALLING) 0xFF6F9FD8.toInt() else if (tr == MetricArt.Trend.RISING) 0xFFE8B04A.toInt() else dim)
            text.textSize = 11 * resources.displayMetrics.scaledDensity
        }
    }

    // ------------------------------------------------------------------ UV

    private val uvColors = intArrayOf(0xFF6CC24A.toInt(), 0xFFF4D03F.toInt(), 0xFFF39C3C.toInt(), 0xFFE5533D.toInt(), 0xFF9B59B6.toInt())
    private val uvStops = doubleArrayOf(0.0, 3.0, 6.0, 8.0, 11.0, 12.0)

    private fun uv(c: Canvas, i: ArtInfo, t: Double) {
        val k = MetricArt.uvIntensity(i.uv) * entry(t)
        val cx = width / 2f
        val cy = (height - 30 * d) / 2f
        val core = 15 * d
        paint.color = sunColor
        paint.alpha = (40 + 60 * k).toInt()
        c.drawCircle(cx, cy, core * (1.4f + k.toFloat()), paint)
        paint.alpha = 255
        paint.strokeCap = Paint.Cap.ROUND
        for (n in 0 until 16) {
            val a = n * PI / 8 + t * 0.25
            val wob = 1 + 0.15 * sin(t * 3 + n)
            val len = (5 + 28 * k * wob) * d
            paint.strokeWidth = ((1.5 + 2 * k) * d).toFloat()
            paint.alpha = (60 + 195 * k).toInt().coerceIn(0, 255)
            val r0 = core + 4 * d
            c.drawLine(cx + (r0 * cos(a)).toFloat(), cy + (r0 * sin(a)).toFloat(),
                cx + ((r0 + len) * cos(a)).toFloat(), cy + ((r0 + len) * sin(a)).toFloat(), paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.alpha = 255
        c.drawCircle(cx, cy, core, paint)
        scale(c, uvColors, uvStops, i.uv * entry(t), listOf("0", "3", "6", "8", "11+"))
    }

    /** A coloured scale along the bottom with a marker at [value]. */
    private fun scale(c: Canvas, colors: IntArray, stops: DoubleArray, value: Double, ticks: List<String>, even: Boolean = false) {
        val pad = 12 * d
        val top = height - 26 * d
        val bh = 6 * d
        val w = width - 2 * pad
        val max = stops.last()
        fun pos(v: Double): Float = if (even) {
            val b = (1 until stops.size).firstOrNull { v <= stops[it] }?.minus(1) ?: (stops.size - 2)
            ((b + ((v - stops[b]) / (stops[b + 1] - stops[b])).coerceIn(0.0, 1.0)) / (stops.size - 1)).toFloat()
        } else (v / max).coerceIn(0.0, 1.0).toFloat()
        for (k in colors.indices) {
            paint.color = colors[k]
            c.drawRect(pad + w * pos(stops[k]), top, pad + w * pos(stops[k + 1]), top + bh, paint)
        }
        val mx = pad + w * pos(value)
        paint.color = fg
        path.reset()
        path.moveTo(mx, top - 1 * d); path.lineTo(mx - 6 * d, top - 9 * d); path.lineTo(mx + 6 * d, top - 9 * d); path.close()
        c.drawPath(path, paint)
        ticks.forEachIndexed { k, s -> label(c, s, pad + w * pos(stops[k]), top + bh + 13 * d, if (k == 0) Paint.Align.LEFT else Paint.Align.CENTER) }
    }

    // ------------------------------------------------------------------ air quality

    private val aqiColors = intArrayOf(0xFF6CC24A.toInt(), 0xFFF4D03F.toInt(), 0xFFF39C3C.toInt(), 0xFFE5533D.toInt(), 0xFF9B59B6.toInt(), 0xFF7E2A3C.toInt())
    private val aqiStops = doubleArrayOf(0.0, 50.0, 100.0, 150.0, 200.0, 300.0, 500.0)

    private fun aqi(c: Canvas, i: ArtInfo, t: Double) {
        val n = (MetricArt.aqiParticles(i.aqi) * entry(t)).toInt()
        val area = height - 40 * d
        val band = aqiStops.indexOfFirst { i.aqi <= it }.coerceIn(1, aqiColors.size) - 1
        for (k in 0 until n) {
            val speed = (10 + k % 7 * 4) * d
            val x = (((k * 67 % 101) / 101f * width + t * speed) % (width + 8 * d)).toFloat() - 4 * d
            val y = ((k * 37 % 97) / 97f * area + 6 * d * sin(t * (0.8 + k % 5 * 0.2) + k)).toFloat()
            paint.color = if (k % 3 == 0) aqiColors[band] else dim
            paint.alpha = 120 + (k % 4) * 30
            c.drawCircle(x, y, (1.2f + (k % 3) * 0.9f) * d, paint)
        }
        paint.alpha = 255
        scale(c, aqiColors, aqiStops, i.aqi * entry(t), listOf("0", "50", "100", "150", "200", "300"), even = true)
    }

    // ------------------------------------------------------------------ wind

    private fun wind(c: Canvas, i: ArtInfo, t: Double) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 16 * d
        // Streamlines blowing the way the wind goes, as fast as it goes.
        val a = Math.toRadians(i.windTowardDeg)
        val ux = sin(a).toFloat()
        val uy = -cos(a).toFloat()
        val span = (width + height).toFloat()
        val speed = (MetricArt.streamSpeedDp(i.windMph) * d).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.strokeCap = Paint.Cap.ROUND
        for (k in 0 until 9) {
            val off = (k - 4) * span / 9f * 0.55f
            val along = ((t * speed + k * 97 * d) % span).toFloat() - span / 2
            val bx = cx + ux * along - uy * off
            val by = cy + uy * along + ux * off
            val len = 34 * d
            path.reset()
            for (s in 0..10) {
                val p = s / 10f
                val wv = (3 * d * sin(p * 6 + t * 4 + k)).toFloat()
                val px = bx - ux * len * p - uy * wv
                val py = by - uy * len * p + ux * wv
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            paint.color = drop
            paint.alpha = 140
            c.drawPath(path, paint)
        }
        paint.alpha = 255
        // The compass.
        paint.color = if (light) 0xF2FFFFFF.toInt() else 0xF21B1E25.toInt()
        paint.style = Paint.Style.FILL
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.color = dim
        c.drawCircle(cx, cy, r, paint)
        for (k in 0 until 16) {
            val b = k * PI / 8
            val r0 = if (k % 4 == 0) r - 7 * d else r - 4 * d
            c.drawLine(cx + (r0 * sin(b)).toFloat(), cy - (r0 * cos(b)).toFloat(), cx + (r * sin(b)).toFloat(), cy - (r * cos(b)).toFloat(), paint)
        }
        paint.style = Paint.Style.FILL
        for ((k, s) in listOf("N", "E", "S", "W").withIndex()) {
            val b = k * PI / 2
            label(c, s, cx + ((r - 16 * d) * sin(b)).toFloat(), cy - ((r - 16 * d) * cos(b)).toFloat() + 4 * d, Paint.Align.CENTER, if (k == 0) fg else dim)
        }
        // The arrow swings round from north to the wind, then sways with it.
        val sway = Math.toRadians(min(8.0, i.windMph * 0.6) * sin(t * 1.7))
        val b = a * entry(t) + sway
        val bx = sin(b).toFloat()
        val by = -cos(b).toFloat()
        val tip = r - 22 * d
        paint.color = if (light) 0xFFB8860B.toInt() else 0xFFFFD27A.toInt()
        paint.strokeWidth = 3 * d
        paint.style = Paint.Style.STROKE
        c.drawLine(cx - bx * tip * 0.8f, cy - by * tip * 0.8f, cx + bx * (tip - 8 * d), cy + by * (tip - 8 * d), paint)
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(cx + bx * tip, cy + by * tip)
        path.lineTo(cx + bx * (tip - 12 * d) - by * 7 * d, cy + by * (tip - 12 * d) + bx * 7 * d)
        path.lineTo(cx + bx * (tip - 12 * d) + by * 7 * d, cy + by * (tip - 12 * d) - bx * 7 * d)
        path.close()
        c.drawPath(path, paint)
        c.drawCircle(cx - bx * tip * 0.8f, cy - by * tip * 0.8f, 4 * d, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private companion object {
        const val FRAME_MS = 33L

        /** The least clear space between two labels side by side, in dp. */
        const val LABEL_GAP_DP = 10f
    }
}

/**
 * The open part of a card: as tall as its content times [open] (0 shut, 1 open), clipping the
 * rest, so the card grows and shrinks smoothly.
 */
class Expander(context: Context) : FrameLayout(context) {
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }

    var open = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            // Shut, what's inside isn't there for a screen reader either.
            importantForAccessibility = if (field == 0f) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        var natural = 0
        for (k in 0 until childCount) {
            val ch = getChildAt(k)
            ch.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            natural = max(natural, ch.measuredHeight)
        }
        setMeasuredDimension(w, (natural * open).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (k in 0 until childCount) {
            val ch = getChildAt(k)
            ch.layout(0, 0, ch.measuredWidth, ch.measuredHeight)
        }
    }
}

/** A metric card: its label, value and line, then (opened) its picture and what it means. */
class MetricTile(context: Context, val key: String, light: Boolean) : LinearLayout(context) {
    val art = MetricArtView(context, light)
    val note = TextView(context)
    val expander = Expander(context)
    private val header = ArrayList<View>()

    init {
        orientation = VERTICAL
        tag = "weather_section_$key"
        isClickable = true
        isFocusable = true
    }

    /** Adds a line of the collapsed card. */
    fun addHeader(v: View, lp: LayoutParams) {
        header += v
        addView(v, lp)
    }

    /** After the header: the space that keeps a row's cards level, then the picture. */
    fun finish(artHeight: Int, noteTop: Int) {
        addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val inner = LinearLayout(context).apply { orientation = VERTICAL }
        inner.addView(art, LayoutParams(LayoutParams.MATCH_PARENT, artHeight).apply { topMargin = noteTop })
        inner.addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = noteTop })
        expander.addView(inner, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        expander.tag = "weather_metric_open_$key"
        addView(expander, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    val isOpen get() = expander.open > 0f
}

/**
 * Two cards side by side. Shut, both are as tall as the taller one (as before); one opening grows
 * by just its picture, its neighbour staying its shut size, and the row (and all below) follows.
 */
class MetricRow(context: Context, private val gap: Int) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val n = childCount
        if (n == 0) { setMeasuredDimension(w, 0); return }
        val col = (w - gap * (n - 1)) / n
        var compact = 0
        for (k in 0 until n) {
            val ch = getChildAt(k)
            ch.measure(MeasureSpec.makeMeasureSpec(col, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val extra = (ch as? MetricTile)?.expander?.measuredHeight ?: 0
            compact = max(compact, ch.measuredHeight - extra)
        }
        var tallest = 0
        for (k in 0 until n) {
            val ch = getChildAt(k)
            val extra = (ch as? MetricTile)?.expander?.measuredHeight ?: 0
            val h = compact + extra
            ch.measure(MeasureSpec.makeMeasureSpec(col, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            tallest = max(tallest, h)
        }
        setMeasuredDimension(w, tallest)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (k in 0 until childCount) {
            val ch = getChildAt(if (rtl) childCount - 1 - k else k)
            ch.layout(x, 0, x + ch.measuredWidth, ch.measuredHeight)
            x += ch.measuredWidth + gap
        }
    }
}
