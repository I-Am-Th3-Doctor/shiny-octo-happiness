package io.github.iamth3doctor.searchlight.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.res.ResourcesCompat
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.color
import kotlin.math.max

/**
 * The weather screen's hero: the temperature as oversized, dimensional numbers in Lexend Bold:
 * a gold face over a rust extrusion stepping down and right, with a soft shadow, so it stands off
 * the scene behind it. "58°" with a small "F". Shrinks to fit its width; never clipped.
 *
 * It's also a fidget: drag it sideways to scrub the day, an hour per [stepPx] of drag
 * ([onScrubStart], then [onScrubSteps] with the hours moved so far); a tap is a click.
 */
class HeroTempView(context: Context) : View(context) {

    /** The number ("58", or "--"). */
    var number: String = "--"
        set(value) {
            if (field == value) return
            field = value
            contentDescription = "$value°F"
            requestLayout()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.lexend)?.let { Typeface.create(it, 700, false) } ?: Typeface.DEFAULT_BOLD
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }
    private val side = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }

    private val gold = context.color(R.color.gold)

    /** The skin: the face's colors, the extrusion's, and a bold outline so it reads over any sky. */
    var skin: io.github.iamth3doctor.searchlight.weather.WeatherSkin = Skins.current
        set(value) { field = value; invalidate() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    /** The face's color in the middle (the gold skin keeps its own gold). */
    private fun faceMid(): Int = SceneRenderer.mix(skin.numberTop, skin.numberBottom, 0.45)

    /** The largest the number is drawn (it shrinks to fit narrower screens). */
    private val maxSize = 168 * density
    /** The size it's drawn at (tests). */
    var textSize = maxSize
        private set

    private fun depth() = textSize * 0.07f
    private val degree = "°"

    /** Drag distance per hour. */
    var stepPx: () -> Float = { 24 * density }
    var onScrubStart: (() -> Unit)? = null
    /** Hours moved since the drag began (negative: back). */
    var onScrubSteps: ((Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var steps = 0

    /** Whether the number is being dragged (tests). */
    val scrubbing: Boolean get() = dragging

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; steps = 0 }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (!dragging && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(e.y - downY)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onScrubStart?.invoke()
                }
                if (dragging) {
                    // Whole hours, and sticky like the bar: a hand resting between two hours stays put.
                    val pos = dx / stepPx()
                    if (kotlin.math.abs(pos - steps) >= 0.5f + io.github.iamth3doctor.searchlight.weather.DayBar.SNAP_HYSTERESIS) {
                        steps = Math.round(pos)
                        onScrubSteps?.invoke(steps)
                    }
                }
            }
            MotionEvent.ACTION_UP -> { if (!dragging) performClick(); dragging = false }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "--°F"
    }

    /** The drawn width of the number, degree and unit at [size]. */
    private fun widthAt(size: Float): Float {
        face.textSize = size
        unitPaint.textSize = size * 0.28f
        return face.measureText(number + degree) + unitPaint.measureText("F") + size * 0.07f + 2 * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        var size = maxSize
        val room = w - paddingLeft - paddingRight
        if (room > 0) {
            val wAtMax = widthAt(maxSize)
            if (wAtMax > room) size = maxSize * room / wAtMax
        }
        textSize = size
        face.textSize = size
        val fm = face.fontMetrics
        // Digits sit between the cap height and the baseline: size the view to them, plus the extrusion.
        val h = (-fm.ascent * 0.78f + depth() + size * 0.06f + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    /** Whether the whole number fits inside the view (tests). */
    fun fits(): Boolean = widthAt(textSize) <= width - paddingLeft - paddingRight + 1

    override fun onDraw(canvas: Canvas) {
        val size = textSize
        face.textSize = size
        side.textSize = size
        unitPaint.textSize = size * 0.28f
        val text = number + degree
        val total = widthAt(size)
        val x = paddingLeft + (width - paddingLeft - paddingRight - total) / 2
        val fm = face.fontMetrics
        val baseline = paddingTop - fm.ascent * 0.78f
        val d = depth()

        // The extrusion: rust, darker as it goes back; the backmost layer casts a soft shadow on the scene.
        val steps = max(4, (d / max(1f, density * 0.75f)).toInt())
        for (i in steps downTo 1) {
            val f = i / steps.toFloat()
            side.color = SceneRenderer.mix(skin.accentDeep, 0xFF111114.toInt(), f * 0.55)
            if (i == steps) side.setShadowLayer(size * 0.08f, d * 0.4f, d * 0.8f, 0x80000000.toInt()) else side.clearShadowLayer()
            canvas.drawText(text, x + d * f, baseline + d * f, side)
        }
        side.clearShadowLayer()

        // A frosted white outline round the face, so it reads over any sky, then the face, lit from above.
        val top = baseline + fm.ascent * 0.78f
        stroke.textSize = size
        stroke.color = skin.outline
        stroke.strokeWidth = size * 0.035f
        canvas.drawText(text, x, baseline, stroke)
        face.shader = LinearGradient(0f, top, 0f, baseline, intArrayOf(skin.numberTop, faceMid(), skin.numberBottom),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawText(text, x, baseline, face)
        face.shader = null

        // The unit, small, up by the degree.
        val ux = x + face.measureText(text) + size * 0.02f
        val uBase = top + unitPaint.textSize * 0.95f
        unitPaint.color = SceneRenderer.mix(skin.accentDeep, 0xFF111114.toInt(), 0.3)
        canvas.drawText("F", ux + d * 0.35f, uBase + d * 0.35f, unitPaint)
        unitPaint.color = faceMid()
        canvas.drawText("F", ux, uBase, unitPaint)
    }
}
