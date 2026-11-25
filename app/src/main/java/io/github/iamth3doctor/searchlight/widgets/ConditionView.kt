package io.github.iamth3doctor.searchlight.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.weather.ConditionVisual
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Weather widget's condition, drawn: flat filled glyphs in the launcher's own style (sun,
 * moon and stars, clouds, drops, a bolt, flakes, fog lines) over a soft glow of the condition's
 * [tint]. Drawn rather than an icon set, so it scales to any size crisply and costs no assets.
 * Square: it draws in the largest centered square that fits.
 */
class ConditionView(context: Context, private val glow: Boolean = true) : View(context) {

    var visual: ConditionVisual = ConditionVisual.CLEAR_DAY
        set(value) {
            if (field == value) return
            field = value
            shader = null
            invalidate()
        }

    /** The condition's tint (also the widget's wash): read by tests. */
    val tint: Int get() = tintOf(context, visual)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Shader? = null
    private var shaderSize = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.translate(paddingLeft + (width - paddingLeft - paddingRight - s) / 2, paddingTop + (height - paddingTop - paddingBottom - s) / 2)
        if (glow) {
            if (shader == null || shaderSize != s) {
                val t = tint
                shader = RadialGradient(s / 2, s / 2, s / 2, intArrayOf(withAlpha(t, 0x8C), withAlpha(t, 0x40), withAlpha(t, 0)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                shaderSize = s
            }
            glowPaint.shader = shader
            canvas.drawCircle(s / 2, s / 2, s / 2, glowPaint)
        }
        val c = context
        when (visual) {
            ConditionVisual.CLEAR_DAY -> sun(canvas, s, 0.5f, 0.5f, 0.2f)
            ConditionVisual.CLEAR_NIGHT -> {
                moon(canvas, s, 0.46f, 0.52f, 0.22f)
                stars(canvas, s, listOf(Triple(0.74f, 0.28f, 0.05f), Triple(0.8f, 0.58f, 0.035f), Triple(0.28f, 0.22f, 0.03f)))
            }
            ConditionVisual.PARTLY_DAY -> {
                sun(canvas, s, 0.38f, 0.38f, 0.15f)
                cloud(canvas, s, 0.57f, 0.58f, 0.58f, c.color(R.color.weather_cloud))
            }
            ConditionVisual.PARTLY_NIGHT -> {
                moon(canvas, s, 0.38f, 0.36f, 0.16f)
                stars(canvas, s, listOf(Triple(0.74f, 0.22f, 0.035f)))
                cloud(canvas, s, 0.57f, 0.6f, 0.58f, c.color(R.color.weather_cloud))
            }
            ConditionVisual.CLOUDY -> {
                cloud(canvas, s, 0.62f, 0.4f, 0.48f, c.color(R.color.weather_cloud_dark))
                cloud(canvas, s, 0.46f, 0.56f, 0.66f, c.color(R.color.weather_cloud))
            }
            ConditionVisual.RAIN -> {
                cloud(canvas, s, 0.5f, 0.4f, 0.7f, c.color(R.color.weather_cloud_dark))
                lines(canvas, s, c.color(R.color.weather_drop), 0.055f,
                    listOf(0.36f, 0.52f, 0.68f).map { x -> floatArrayOf(x, 0.68f, x - 0.05f, 0.82f) })
            }
            ConditionVisual.THUNDER -> {
                cloud(canvas, s, 0.5f, 0.38f, 0.7f, c.color(R.color.weather_cloud_dark))
                bolt(canvas, s)
                lines(canvas, s, c.color(R.color.weather_drop), 0.045f,
                    listOf(floatArrayOf(0.32f, 0.68f, 0.28f, 0.78f), floatArrayOf(0.7f, 0.68f, 0.66f, 0.78f)))
            }
            ConditionVisual.SNOW -> {
                cloud(canvas, s, 0.5f, 0.4f, 0.7f, c.color(R.color.weather_cloud))
                listOf(0.33f to 0.75f, 0.5f to 0.84f, 0.67f to 0.75f).forEach { (x, y) -> flake(canvas, s, x, y, 0.06f) }
            }
            ConditionVisual.FOG -> {
                cloud(canvas, s, 0.5f, 0.38f, 0.66f, c.color(R.color.weather_cloud))
                lines(canvas, s, c.color(R.color.weather_fog), 0.055f, listOf(
                    floatArrayOf(0.2f, 0.66f, 0.8f, 0.66f), floatArrayOf(0.28f, 0.76f, 0.72f, 0.76f), floatArrayOf(0.22f, 0.86f, 0.6f, 0.86f),
                ))
            }
        }
        canvas.restore()
    }

    private fun sun(canvas: Canvas, s: Float, cx: Float, cy: Float, r: Float) {
        val color = context.color(R.color.weather_sun)
        fill.color = color
        canvas.drawCircle(cx * s, cy * s, r * s, fill)
        stroke.color = color
        stroke.strokeWidth = s * r * 0.32f
        for (i in 0 until 8) {
            val a = Math.PI / 4 * i
            val r1 = r * 1.45f
            val r2 = r * 1.85f
            canvas.drawLine(
                (cx + r1 * cos(a).toFloat()) * s, (cy + r1 * sin(a).toFloat()) * s,
                (cx + r2 * cos(a).toFloat()) * s, (cy + r2 * sin(a).toFloat()) * s, stroke,
            )
        }
    }

    private fun moon(canvas: Canvas, s: Float, cx: Float, cy: Float, r: Float) {
        val disc = Path().apply { addCircle(cx * s, cy * s, r * s, Path.Direction.CW) }
        val bite = Path().apply { addCircle((cx + r * 0.62f) * s, (cy - r * 0.42f) * s, r * 0.86f * s, Path.Direction.CW) }
        disc.op(bite, Path.Op.DIFFERENCE)
        fill.color = context.color(R.color.weather_moon)
        canvas.drawPath(disc, fill)
        // A crater or two on the lit crescent: a moon at a glance, never the sun.
        fill.color = context.color(R.color.weather_moon_crater)
        canvas.drawCircle((cx - r * 0.45f) * s, (cy + r * 0.2f) * s, r * 0.14f * s, fill)
        canvas.drawCircle((cx - r * 0.12f) * s, (cy + r * 0.6f) * s, r * 0.1f * s, fill)
    }

    private fun stars(canvas: Canvas, s: Float, at: List<Triple<Float, Float, Float>>) {
        fill.color = context.color(R.color.weather_moon)
        for ((x, y, r) in at) {
            val p = Path()
            val k = r * 0.28f
            p.moveTo(x * s, (y - r) * s)
            p.lineTo((x + k) * s, (y - k) * s)
            p.lineTo((x + r) * s, y * s)
            p.lineTo((x + k) * s, (y + k) * s)
            p.lineTo(x * s, (y + r) * s)
            p.lineTo((x - k) * s, (y + k) * s)
            p.lineTo((x - r) * s, y * s)
            p.lineTo((x - k) * s, (y - k) * s)
            p.close()
            canvas.drawPath(p, fill)
        }
    }

    /** A cloud [w] wide (fractions of the square), its flat base just below [cy]. */
    private fun cloud(canvas: Canvas, s: Float, cx: Float, cy: Float, w: Float, color: Int) {
        val h = w * 0.3f
        val base = Path().apply {
            addRoundRect(RectF((cx - w / 2) * s, cy * s, (cx + w / 2) * s, (cy + h) * s), h / 2 * s, h / 2 * s, Path.Direction.CW)
        }
        val bumps = listOf(
            Triple(cx - w * 0.2f, cy + h * 0.25f, w * 0.2f),
            Triple(cx + w * 0.04f, cy - h * 0.12f, w * 0.27f),
            Triple(cx + w * 0.27f, cy + h * 0.35f, w * 0.17f),
        )
        for ((x, y, r) in bumps) base.op(Path().apply { addCircle(x * s, y * s, r * s, Path.Direction.CW) }, Path.Op.UNION)
        // A faint shadow line under it keeps a light cloud apart from light glass.
        fill.color = withAlpha(Color.BLACK, 0x33)
        canvas.save()
        canvas.translate(0f, s * 0.02f)
        canvas.drawPath(base, fill)
        canvas.restore()
        fill.color = color
        canvas.drawPath(base, fill)
    }

    private fun lines(canvas: Canvas, s: Float, color: Int, width: Float, segs: List<FloatArray>) {
        stroke.color = color
        stroke.strokeWidth = width * s
        for (l in segs) canvas.drawLine(l[0] * s, l[1] * s, l[2] * s, l[3] * s, stroke)
    }

    private fun bolt(canvas: Canvas, s: Float) {
        val p = Path().apply {
            moveTo(0.54f * s, 0.56f * s)
            lineTo(0.4f * s, 0.76f * s)
            lineTo(0.5f * s, 0.76f * s)
            lineTo(0.44f * s, 0.94f * s)
            lineTo(0.62f * s, 0.7f * s)
            lineTo(0.52f * s, 0.7f * s)
            lineTo(0.6f * s, 0.56f * s)
            close()
        }
        fill.color = context.color(R.color.weather_bolt)
        canvas.drawPath(p, fill)
    }

    private fun flake(canvas: Canvas, s: Float, x: Float, y: Float, r: Float) {
        stroke.color = context.color(R.color.weather_snow)
        stroke.strokeWidth = s * r * 0.36f
        for (i in 0 until 3) {
            val a = Math.PI / 3 * i + Math.PI / 2
            val dx = (r * cos(a)).toFloat()
            val dy = (r * sin(a)).toFloat()
            canvas.drawLine((x - dx) * s, (y - dy) * s, (x + dx) * s, (y + dy) * s, stroke)
        }
    }

    companion object {
        fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

        fun tintOf(context: Context, v: ConditionVisual): Int = context.color(
            when (v) {
                ConditionVisual.CLEAR_DAY -> R.color.weather_tint_clear_day
                ConditionVisual.CLEAR_NIGHT -> R.color.weather_tint_clear_night
                ConditionVisual.PARTLY_DAY -> R.color.weather_tint_partly_day
                ConditionVisual.PARTLY_NIGHT -> R.color.weather_tint_partly_night
                ConditionVisual.CLOUDY -> R.color.weather_tint_cloudy
                ConditionVisual.RAIN -> R.color.weather_tint_rain
                ConditionVisual.THUNDER -> R.color.weather_tint_thunder
                ConditionVisual.SNOW -> R.color.weather_tint_snow
                ConditionVisual.FOG -> R.color.weather_tint_fog
            },
        )
    }
}
