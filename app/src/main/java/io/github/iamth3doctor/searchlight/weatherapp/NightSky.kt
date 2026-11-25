package io.github.iamth3doctor.searchlight.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The details page's night theme, from sunset to sunrise: moonlight print (pale silver and white)
 * on a deep blue night sky, and the frosted panels in their night form, dark glass the sky shows
 * faintly through. The day theme keeps its own colors; these are only for night.
 */
object Moonlight {
    /** The print: names, numbers, temperatures. */
    const val INK = 0xFFEEF2F8.toInt()
    /** The quieter print: feels-like, lows, hours, the updated time. */
    const val INK_DIM = 0xD1C9D3E2.toInt()
    /** The section labels and controls. */
    const val ACCENT = 0xFFDCE3EC.toInt()
    /** Print on a moonlit control (the picked DAY / WEEK chip). */
    const val ON_ACCENT = 0xFF0D1732.toInt()
    /** The frosted panels at night: dark navy glass. */
    const val GLASS = 0x940D1732.toInt()
    /** Their edge: a thin line of moonlight. */
    const val GLASS_EDGE = 0x2EDCE3EC
    /** The round frosted buttons at night. */
    const val CHIP = 0x8C1A2648.toInt()
    /** A soft dark edge under the print where it crosses the moon. */
    const val SHADOW = 0xB3050A18.toInt()
    /** The night sky, top to bottom. */
    const val SKY_TOP = 0xFF050A1C.toInt()
    const val SKY_MID = 0xFF0A1532.toInt()
    const val SKY_BOTTOM = 0xFF13244A.toInt()
    /** What night tones mix toward (the island's grass, rock and tree at night). */
    const val NIGHT_TONE = 0xFF0E1A36.toInt()
    /** A day color in its muted night tone. */
    fun muted(c: Int, amount: Double = 0.55): Int = SceneRenderer.mix(c, NIGHT_TONE, amount)
}

/**
 * The moon drawn as it is tonight: pale silver, never the sun's yellow. The lit part follows the
 * phase ([lit] 0 new to 1 full, [waxing] lit on the right), with gray maria in it; the dark part is
 * still faintly there (earthshine), so the disc reads even as a thin crescent; a soft halo round it.
 */
object MoonDisc {
    const val LIT = 0xFFE4E9F0.toInt()
    const val LIT_EDGE = 0xFFC6CFDB.toInt()
    const val DARK = 0xFF27304A.toInt()
    const val MARE = 0xFFB4BFCD.toInt()
    const val HALO = 0x55DCE3EC

    /** The maria: (dx, dy, r) as fractions of the radius. */
    private val MARIA = listOf(
        Triple(-0.28f, -0.30f, 0.26f), Triple(0.10f, -0.38f, 0.18f), Triple(0.30f, -0.05f, 0.22f),
        Triple(-0.05f, 0.12f, 0.2f), Triple(-0.38f, 0.22f, 0.15f), Triple(0.18f, 0.45f, 0.1f),
    )

    fun draw(canvas: Canvas, paint: Paint, path: Path, rect: RectF, cx: Float, cy: Float, r: Float,
             lit: Double, waxing: Boolean, halo: Float = 2.4f) {
        paint.style = Paint.Style.FILL
        if (halo > 1f) {
            paint.shader = RadialGradient(cx, cy, r * halo, intArrayOf(HALO, SceneRenderer.withAlpha(HALO, 0.12), 0x00DCE3EC),
                floatArrayOf(r / (r * halo), 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * halo, paint)
            paint.shader = null
        }
        paint.color = DARK
        canvas.drawCircle(cx, cy, r, paint)
        // The lit part, from the phase: the right half, then the terminator's ellipse back across.
        val l = lit.coerceIn(0.0, 1.0)
        path.reset()
        if (l >= 0.995) {
            path.addCircle(cx, cy, r, Path.Direction.CW)
        } else if (l > 0.005) {
            rect.set(cx - r, cy - r, cx + r, cy + r)
            path.arcTo(rect, -90f, 180f)
            val ew = (r * abs(1 - 2 * l)).toFloat()
            rect.set(cx - ew, cy - r, cx + ew, cy + r)
            if (l > 0.5) path.arcTo(rect, 90f, 180f) else path.arcTo(rect, 90f, -180f)
            path.close()
            if (!waxing) {
                val m = android.graphics.Matrix().apply { setScale(-1f, 1f, cx, cy) }
                path.transform(m)
            }
        }
        if (path.isEmpty) return
        canvas.save()
        canvas.clipPath(path)
        paint.shader = RadialGradient(cx - r * 0.25f, cy - r * 0.25f, r * 1.35f, LIT, LIT_EDGE, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
        paint.color = SceneRenderer.withAlpha(MARE, 0.7)
        for ((dx, dy, mr) in MARIA) canvas.drawCircle(cx + dx * r, cy + dy * r, mr * r, paint)
        canvas.restore()
    }
}

/**
 * The details page's sky at night, behind everything on it: deep night blue, stars scattered
 * across it, and the moon, large and pale, high on the right as tonight's phase. [level] fades it
 * in (1, night) and out (0, day). The weather's own scene still moves faintly beneath it.
 */
class NightSkyView(context: Context) : View(context) {

    /** 0 day (not drawn) to 1 night. */
    var level = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            alpha = field
            visibility = if (field > 0f) VISIBLE else GONE
        }

    /** Tonight's moon: lit fraction and side. */
    var moonLit = 1.0
        set(value) { field = value; invalidate() }
    var waxing = true
        set(value) { field = value; invalidate() }

    /**
     * Only the stars and the moon, no sky of its own: over a living scene (the 7 days), whose own
     * night sky, clouds and weather show between them.
     */
    var clearSky = false
        set(value) { field = value; invalidate() }

    /** Where the moon sits (tests): its centre and radius, in this view. */
    var moonX = 0f
        private set
    var moonY = 0f
        private set
    var moonR = 0f
        private set


    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val stars = FloatArray(STARS * 3)

    init {
        tag = "weather_night_sky"
        level = 0f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // The same stars every time: a fixed scatter.
        val rnd = java.util.Random(7)
        for (i in 0 until STARS) {
            stars[i * 3] = rnd.nextFloat()
            stars[i * 3 + 1] = rnd.nextFloat()
            stars[i * 3 + 2] = rnd.nextFloat()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place()
    }

    private fun place() {
        val w = width.toFloat()
        val h = height.toFloat()
        moonR = (minOf(w, h) * 0.12f).coerceIn(40 * d, 72 * d)
        moonX = w - 20 * d - moonR * 1.15f
        // Below the status bar, level with the place's name and the big number.
        val top = rootWindowInsets?.let {
            androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it, this)
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()).top
        } ?: 0
        moonY = top + 64 * d + moonR * 0.6f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        place()
        if (!clearSky) {
            paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(
                SceneRenderer.withAlpha(Moonlight.SKY_TOP, 0.94), SceneRenderer.withAlpha(Moonlight.SKY_MID, 0.92),
                SceneRenderer.withAlpha(Moonlight.SKY_BOTTOM, 0.9)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }
        // Stars, thinning toward the bottom, none over the moon.
        for (i in 0 until STARS) {
            val sx = stars[i * 3] * w
            val f = stars[i * 3 + 1]
            val sy = f * f * h
            val dx = sx - moonX
            val dy = sy - moonY
            if (sqrt(dx * dx + dy * dy) < moonR * 1.8f) continue
            val s = stars[i * 3 + 2]
            paint.color = SceneRenderer.withAlpha(0xFFEEF2FF.toInt(), 0.35 + 0.6 * s * (1 - f * 0.5))
            canvas.drawCircle(sx, sy, (0.5f + 1.1f * s) * d, paint)
        }
        MoonDisc.draw(canvas, paint, path, rect, moonX, moonY, moonR, moonLit, waxing)
    }

    private companion object {
        const val STARS = 110
    }
}
