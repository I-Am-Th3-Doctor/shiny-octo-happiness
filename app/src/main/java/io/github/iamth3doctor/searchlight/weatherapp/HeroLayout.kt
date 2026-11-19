package io.github.iamth3doctor.searchlight.weatherapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.HeroReadings
import io.github.iamth3doctor.searchlight.weather.WeatherSkin
import io.github.iamth3doctor.searchlight.weather.WeatherText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A data callout on the hero: a small label over a line or three of numbers, on a pad of the
 * skin's color so it reads over any sky. A leader line ([LeaderLinesView]) runs from it to what it
 * describes in the scene.
 */
@SuppressLint("ViewConstructor")
class CalloutView(context: Context, val key: String) : LinearLayout(context) {

    val labelView = TextView(context)
    val lines = List(3) { TextView(context) }

    init {
        orientation = VERTICAL
        tag = "weather_callout_$key"
        val p = context.px(R.dimen.space_s)
        setPadding(p, p * 3 / 4, p, p * 3 / 4)
        labelView.type(Type.CAPTION)
        labelView.typeface = Typeface.create(labelView.typeface, 700, false)
        labelView.letterSpacing = 0.1f
        labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, labelView.textSize * 0.85f)
        labelView.isSingleLine = true
        addView(labelView)
        for (t in lines) {
            t.type(Type.BODY)
            t.typeface = Typeface.create(t.typeface, 700, false)
            t.isSingleLine = true
            t.ellipsize = TextUtils.TruncateAt.END
            t.includeFontPadding = false
            addView(t, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = p / 4 })
        }
    }

    /** How wide the label is, letter spacing and all. */
    val labelWidth: Int get() = kotlin.math.ceil(labelView.paint.measureText(labelView.text.toString())).toInt()

    // At least as wide as the label: short lines ("Rain None") mustn't leave it cut off ("CLOUDS & RAI").
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val need = labelWidth + labelView.paddingLeft + labelView.paddingRight + paddingLeft + paddingRight
        if (need <= measuredWidth) return
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) need
            else minOf(need, MeasureSpec.getSize(widthMeasureSpec))
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    fun show(c: HeroReadings.Callout) {
        labelView.text = c.label
        lines.forEachIndexed { i, t ->
            val v = c.lines.getOrNull(i)
            t.text = v ?: ""
            t.visibility = if (v == null) GONE else VISIBLE
        }
        contentDescription = c.label.lowercase().replaceFirstChar { it.uppercase() } + ": " + c.lines.joinToString(", ")
    }

    fun style(skin: WeatherSkin) {
        background = GradientDrawable().apply { cornerRadius = context.pxF(R.dimen.radius_card) / 2.5f; setColor(skin.calloutBg) }
        labelView.setTextColor(skin.accent)
        for (t in lines) t.setTextColor(skin.ink)
    }
}

/**
 * The short timeline under the condition: NOW (or the scrubbed hour), then the next hours, each
 * a mark on a line with its temperature over it and its hour under it.
 */
class HeroTimelineView(context: Context) : View(context) {

    var skin: WeatherSkin = Skins.current
        set(value) { field = value; invalidate() }

    /** The marks' labels and temperatures as drawn (tests). */
    var labels: List<String> = emptyList()
        private set
    var temps: List<String> = emptyList()
        private set
    var times: List<Long> = emptyList()
        private set

    private val density = resources.displayMetrics.density
    private val font = ResourcesCompat.getFont(context, R.font.lexend) ?: Typeface.DEFAULT
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(font, 600, false); textAlign = Paint.Align.CENTER }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(font, 700, false); textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scale get() = resources.configuration.fontScale.coerceIn(1f, 1.3f)

    fun show(marks: List<HeroReadings.Mark>, hourLabel: (Long) -> String, nowLabel: String) {
        times = marks.map { it.time }
        labels = marks.map { if (it.now) nowLabel else hourLabel(it.time) }
        temps = marks.map { m -> m.tempC?.let { "${WeatherText.f(it)}°" } ?: "" }
        contentDescription = labels.zip(temps).joinToString(", ") { (l, t) -> "$l $t".trim() }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (46 * density * scale).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val n = labels.size
        if (n == 0) return
        val pad = 18 * density
        val cy = height * 0.5f
        val shadow = !skin.light
        text.textSize = 11 * density * scale
        bold.textSize = 12 * density * scale
        line.color = SceneRenderer.withAlpha(skin.ink, 0.55)
        line.strokeWidth = 2 * density
        canvas.drawLine(pad, cy, width - pad, cy, line)
        for (i in 0 until n) {
            val x = if (n == 1) width / 2f else pad + (width - 2 * pad) * i / (n - 1f)
            val first = i == 0
            dot.color = if (first) skin.accent else skin.ink
            if (shadow) dot.setShadowLayer(2 * density, 0f, 1f, 0x80000000.toInt()) else dot.clearShadowLayer()
            canvas.drawCircle(x, cy, (if (first) 5f else 3f) * density, dot)
            for (p in listOf(text, bold)) if (shadow) p.setShadowLayer(3 * density, 0f, 1f, 0xB3000000.toInt()) else p.clearShadowLayer()
            bold.color = if (first) skin.accent else skin.ink
            canvas.drawText(temps[i], x, cy - 7 * density, bold)
            text.color = if (first) skin.accent else skin.inkDim
            canvas.drawText(labels[i], x, cy + 7 * density + text.textSize, text)
        }
    }
}

/**
 * The hero's thin leader lines, from each callout to what it describes in the scene (the wind's
 * swooshes, a cloud, the sun or moon, the big number, the horizon), with a dot at the far end.
 * Drawn over the scene and under the callouts; laid over the whole hero screen.
 */
class LeaderLinesView(context: Context, private val scene: WeatherSceneView) : View(context) {

    var skin: WeatherSkin = Skins.current
        set(value) { field = value; invalidate() }

    /** Each callout, and the view whose box the line starts from. */
    var callouts: Map<String, View> = emptyMap()
    /** The big number (the temperature callout points at it). */
    var number: View? = null
    /** The wind (mph, + blowing east), for the swooshes. */
    var windEast = 0.0
    /** The condition's pad: lines stay above it. */
    var floor: View? = null

    /** Each line as drawn: (from x, from y, to x, to y) in this view (tests). */
    val drawn = LinkedHashMap<String, FloatArray>()

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val box = RectF()
    private val here = IntArray(2)
    private val there = IntArray(2)
    private var t0 = android.os.SystemClock.uptimeMillis()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** [v]'s box in this view's coordinates. */
    private fun rectOf(v: View): RectF? {
        if (!v.isShown || v.width == 0) return null
        getLocationInWindow(here)
        v.getLocationInWindow(there)
        box.set((there[0] - here[0]).toFloat(), (there[1] - here[1]).toFloat(), (there[0] - here[0] + v.width).toFloat(), (there[1] - here[1] + v.height).toFloat())
        return RectF(box)
    }

    /** A point in the scene, in this view's coordinates. */
    private fun fromScene(p: FloatArray): FloatArray {
        getLocationInWindow(here)
        scene.getLocationInWindow(there)
        return floatArrayOf(p[0] + there[0] - here[0], p[1] + there[1] - here[1])
    }

    /**
     * Where the wind's swooshes go: in the open sky between the top two callouts (or, without room
     * there, just under the wind's).
     */
    fun windPoint(): FloatArray {
        val w = scene.width.toFloat()
        val wind = callouts[HeroReadings.WIND]?.let { rectOf(it) }?.let { toScene(it) } ?: return floatArrayOf(w * 0.5f, scene.height * 0.2f)
        val sun = callouts[HeroReadings.SUN]?.let { rectOf(it) }?.let { toScene(it) }
        if (sun != null) {
            val (l, r) = if (wind.centerX() < sun.centerX()) wind to sun else sun to wind
            if (r.left - l.right > 60 * density) return floatArrayOf((l.right + r.left) / 2, wind.centerY())
        }
        val x = if (wind.centerX() > w / 2) w * 0.6f else w * 0.4f
        return floatArrayOf(x, wind.bottom + 20 * density)
    }

    /** A box in this view, in the scene's coordinates. */
    private fun toScene(r: RectF): RectF {
        getLocationInWindow(here)
        scene.getLocationInWindow(there)
        return RectF(r.left + here[0] - there[0], r.top + here[1] - there[1], r.right + here[0] - there[0], r.bottom + here[1] - there[1])
    }

    /** What callout [key] points at, in scene coordinates. */
    fun targetOf(key: String): FloatArray? {
        val r = scene.renderer
        // Nothing it points at hidden behind its own callout.
        val own = callouts[key]?.let { rectOf(it) }?.let { toScene(it) }
        val floorY = floor?.let { rectOf(it) }?.let { toScene(it).top - 10 * density } ?: Float.MAX_VALUE
        return when (key) {
            HeroReadings.WIND -> windPoint()
            HeroReadings.SKY -> r.cloudPoint { x, y -> own?.contains(x, y) == true || y > floorY }
            HeroReadings.SUN -> r.bodyPoint()?.takeIf { own?.contains(it[0], it[1]) != true && it[1] < floorY }
                ?: floatArrayOf(scene.width * 0.5f, minOf(r.horizonY(), floorY))
            HeroReadings.UV -> floatArrayOf(scene.width * 0.72f, minOf(r.horizonY() - 4 * density, floorY))
            else -> null
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawn.clear()
        val ink = skin.ink
        val shadow = !skin.light
        paint.color = SceneRenderer.withAlpha(ink, 0.85)
        paint.strokeWidth = 1.5f * density
        if (shadow) paint.setShadowLayer(2 * density, 0f, 0f, 0x99000000.toInt()) else paint.clearShadowLayer()

        // The wind: a few swooshes in the sky, drifting the way it blows.
        val wp = fromScene(windPoint())
        val speed = abs(windEast)
        if (speed >= 1) {
            val dir = if (windEast >= 0) 1f else -1f
            val phase = ((android.os.SystemClock.uptimeMillis() - t0) / 1000.0 * (0.4 + speed / 30)).toFloat()
            for (k in 0 until 3) {
                val len = (26 + k * 8) * density
                val off = ((phase + k * 0.33f) % 1f) * 12 * density * dir
                val y = wp[1] + (k - 1) * 9 * density
                val x0 = wp[0] - len / 2 + off
                path.reset()
                path.moveTo(x0, y)
                path.quadTo(x0 + len * 0.5f, y - 5 * density * sin(phase * 2 + k).toFloat(), x0 + len, y)
                canvas.drawPath(path, paint)
            }
        }

        for ((key, v) in callouts) {
            val from = rectOf(v) ?: continue
            val to: FloatArray = if (key == HeroReadings.TEMP) {
                val n = number?.let { rectOf(it) } ?: continue
                floatArrayOf(n.centerX() - n.width() * 0.18f, n.top + n.height() * 0.62f)
            } else {
                targetOf(key)?.let { fromScene(it) } ?: continue
            }
            // From the callout's nearest edge (its middle), to the thing.
            val sx = when {
                to[0] < from.left -> from.left
                to[0] > from.right -> from.right
                else -> to[0].coerceIn(from.left + 8 * density, from.right - 8 * density)
            }
            val sy = when {
                to[1] < from.top -> from.top
                to[1] > from.bottom -> from.bottom
                else -> from.centerY()
            }
            if (abs(sx - to[0]) + abs(sy - to[1]) < 6 * density) continue
            canvas.drawLine(sx, sy, to[0], to[1], paint)
            dot.color = skin.accent
            if (shadow) dot.setShadowLayer(2 * density, 0f, 0f, 0x99000000.toInt()) else dot.clearShadowLayer()
            canvas.drawCircle(to[0], to[1], 3.5f * density, dot)
            drawn[key] = floatArrayOf(sx, sy, to[0], to[1])
        }
    }
}

/**
 * The hero, in the scene: the callouts round the edges, the big number in the middle with some of
 * the clouds drifting in front of it ([front]), and the condition in words with its short
 * timeline at the bottom. Lays them out so nothing overlaps: the number shrinks to the room left.
 */
class HeroLayout(context: Context, val number: HeroTempView, val front: View?) : ViewGroup(context) {

    val callouts: Map<String, CalloutView> = HeroReadings.KEYS.associateWith { CalloutView(context, it) }
    val condition = TextView(context)
    val timeline = HeroTimelineView(context)

    private val gap = context.px(R.dimen.space_s)

    init {
        clipChildren = false
        addView(number)
        front?.let { addView(it) }
        for (c in callouts.values) addView(c)
        condition.apply {
            type(Type.TILE_TITLE)
            typeface = Typeface.create(typeface, 800, false)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            tag = "weather_hero_condition"
        }
        addView(condition)
        timeline.tag = "weather_hero_timeline"
        addView(timeline)
    }

    private val pad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val padRect = RectF()

    fun style(skin: WeatherSkin) {
        pad.color = skin.calloutBg
        setWillNotDraw(false)
        number.skin = skin
        timeline.skin = skin
        for (c in callouts.values) c.style(skin)
        condition.setTextColor(skin.ink)
        if (skin.light) condition.setShadowLayer(0f, 0f, 0f, 0) else condition.setShadowLayer(context.pxF(R.dimen.glass_text_shadow) * 1.5f, 0f, 1f, 0xB3000000.toInt())
    }

    /** The sun and moon's callout on the left (when the sun or moon is on the right: never behind its own callout). */
    var sunOnLeft = false
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val rows get() = listOf(
        if (sunOnLeft) listOf(callouts.getValue(HeroReadings.SUN), callouts.getValue(HeroReadings.WIND))
        else listOf(callouts.getValue(HeroReadings.WIND), callouts.getValue(HeroReadings.SUN)),
        listOf(callouts.getValue(HeroReadings.TEMP), callouts.getValue(HeroReadings.SKY)),
        listOf(callouts.getValue(HeroReadings.UV)),
    )

    private var numberTop = 0
    private val rowTops = IntArray(3)
    private var conditionTop = 0
    private var timelineTop = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val half = MeasureSpec.makeMeasureSpec((w - gap) / 2, MeasureSpec.AT_MOST)
        val any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (c in callouts.values) c.measure(half, any)
        condition.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), any)
        timeline.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), any)
        val rowH = rows.map { r -> r.maxOf { it.measuredHeight } }
        val fixed = rowH.sum() + condition.measuredHeight + timeline.measuredHeight + gap * 5
        // The number takes what's left, shrinking (by width, which sizes it) to fit.
        number.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), any)
        val room = (h - fixed).coerceAtLeast(0)
        if (number.measuredHeight > room && number.measuredHeight > 0) {
            val f = room.toFloat() / number.measuredHeight
            val nw = max(1, (w * f).toInt())
            number.measure(MeasureSpec.makeMeasureSpec(nw, MeasureSpec.EXACTLY), any)
        }
        // Spare height: some above the number, the rest over the condition.
        val spare = (h - fixed - number.measuredHeight).coerceAtLeast(0)
        var y = 0
        rowTops[0] = y; y += rowH[0] + gap + spare / 3
        numberTop = y; y += number.measuredHeight + gap
        rowTops[1] = y; y += rowH[1] + gap
        rowTops[2] = y; y += rowH[2] + gap
        y += spare - spare / 3
        conditionTop = y; y += condition.measuredHeight + gap
        timelineTop = y
        front?.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        rows.forEachIndexed { i, row ->
            val top = rowTops[i]
            row.getOrNull(0)?.let { it.layout(0, top, it.measuredWidth, top + it.measuredHeight) }
            row.getOrNull(1)?.let { it.layout(w - it.measuredWidth, top, w, top + it.measuredHeight) }
        }
        val nx = (w - number.measuredWidth) / 2
        number.layout(nx, numberTop, nx + number.measuredWidth, numberTop + number.measuredHeight)
        condition.layout(0, conditionTop, w, conditionTop + condition.measuredHeight)
        timeline.layout(0, timelineTop, w, timelineTop + timeline.measuredHeight)
        front?.layout(0, 0, w, b - t)
    }

    /** Under the condition and its timeline: a pad of the skin's color, so they read over any sky. */
    override fun dispatchDraw(canvas: Canvas) {
        if (condition.visibility == VISIBLE && condition.height > 0) {
            val r = context.pxF(R.dimen.radius_card) / 2.5f
            padRect.set(0f, condition.top - gap / 2f, width.toFloat(), timeline.bottom.toFloat())
            canvas.drawRoundRect(padRect, r, r, pad)
        }
        super.dispatchDraw(canvas)
    }

    /** Whether any two of the callouts, the number, the condition and the timeline overlap (tests). */
    fun overlaps(): String? {
        val boxes = (callouts.values.filter { it.visibility == VISIBLE } + listOf(number, condition, timeline))
            .map { (it.tag ?: it.javaClass.simpleName).toString() to android.graphics.Rect(it.left, it.top, it.right, it.bottom) }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val (a, ra) = boxes[i]
            val (b, rb) = boxes[j]
            if (android.graphics.Rect.intersects(ra, rb)) return "$a $ra overlaps $b $rb"
        }
        return null
    }

    /** The front layer draws the scene's front clouds, lined up with the scene behind. */
    companion object {
        fun frontFor(context: Context, scene: WeatherSceneView): View {
            scene.renderer.layered = true
            return object : View(context) {
                private val a = IntArray(2)
                private val b = IntArray(2)
                init {
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                override fun onDraw(canvas: Canvas) {
                    getLocationInWindow(a)
                    scene.getLocationInWindow(b)
                    canvas.save()
                    canvas.translate((b[0] - a[0]).toFloat(), (b[1] - a[1]).toFloat())
                    scene.renderer.drawFront(canvas)
                    canvas.restore()
                }
            }.also { v ->
                // Redrawn with the scene's frames.
                scene.frontRedraw = { v.invalidate() }
            }
        }
    }
}

/** A skin's swatch in the menu: its sky, its sun (the accent) and a cloud, in a circle. */
class SkinSwatchView(context: Context, private val skin: WeatherSkin) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    init {
        tag = "weather_skin_swatch_${skin.key}"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        val d = resources.displayMetrics.density
        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        paint.shader = android.graphics.LinearGradient(0f, cy - r, 0f, cy + r, skin.scene.skyTop.last(), skin.scene.skyBottom.last(), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(cx - r, cy - r, cx + r, cy + r, paint)
        paint.shader = null
        paint.color = skin.outline
        canvas.drawCircle(cx + r * 0.25f, cy - r * 0.2f, r * 0.36f, paint)
        paint.color = skin.scene.sunCore.first
        canvas.drawCircle(cx + r * 0.25f, cy - r * 0.2f, r * 0.3f, paint)
        paint.color = skin.scene.cloudClear
        canvas.drawCircle(cx - r * 0.3f, cy + r * 0.35f, r * 0.3f, paint)
        canvas.drawCircle(cx + r * 0.05f, cy + r * 0.45f, r * 0.26f, paint)
        paint.color = skin.accent
        canvas.drawRect(cx - r, cy + r * 0.72f, cx + r, cy + r, paint)
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.color = 0x40808080
        canvas.drawCircle(cx, cy, r - d, paint)
        paint.style = Paint.Style.FILL
    }
}
