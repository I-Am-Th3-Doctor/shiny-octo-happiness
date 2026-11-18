package io.github.iamth3doctor.searchlight.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.SceneModel
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import io.github.iamth3doctor.searchlight.weather.WeatherText
import io.github.iamth3doctor.searchlight.weatherapp.WeatherSceneView

/**
 * The Weather widget: a window into the weather scene (the same sky, clouds, rain, sun or moon as
 * the weather screen, moving), with just the temperature, large, and today's high/low over it.
 * The scene animates while the home screen shows and holds still otherwise, and in battery saver
 * ([WeatherSceneView]). Offline it keeps the last good scene, with a small glyph in the corner.
 *
 * 2x1 stacks the temperature over high/low on the left of a slim window; 2x2 and 4x2 put them in
 * the corner of a bigger one. Tap: the weather screen ([onOpen]). Long press: the grid's edit mode.
 */
@SuppressLint("ViewConstructor")
class WeatherWidgetView(context: Context, private val repo: WeatherRepository) : FrameLayout(context) {

    enum class Size { COMPACT, STANDARD, WIDE }

    var onLongPress: (() -> Unit)? = null
    var onOpen: (() -> Unit)? = null
    /** The alert glyph was tapped: the weather screen, at the alert. */
    var onOpenAlert: (() -> Unit)? = null

    /** Now, in ms (tests may pin it). */
    var clock: () -> Long = System::currentTimeMillis

    var size: Size = Size.STANDARD
        set(value) {
            if (field == value) return
            field = value
            layoutText()
        }

    private fun px(token: Int) = context.px(token)
    private fun pxF(token: Int) = context.pxF(token)

    val scene = WeatherSceneView(context, mini = true)
    val tempView: TextView = WholeText(context)
    val hiLoView: TextView = WholeText(context)
    val offlineGlyph = ImageView(context)
    /** A small gold glyph while a severe-weather alert is in effect. */
    val alertGlyph = ImageView(context)
    private val textBox = LinearLayout(context)

    val offlineShown: Boolean get() = offlineGlyph.visibility == VISIBLE
    val alertShown: Boolean get() = alertGlyph.visibility == VISIBLE

    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            render() // the sun and moon move on
            postDelayed(this, TICK_MS)
        }
    }

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
        val r = pxF(R.dimen.radius_card)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
        }
        clipToOutline = true
        addView(scene, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // A hairline round the window, like the glass.
        foreground = GradientDrawable().apply {
            cornerRadius = r
            setStroke(pxF(R.dimen.stroke_glass).toInt().coerceAtLeast(1), context.color(R.color.glass_border))
        }

        for (t in listOf(tempView, hiLoView)) {
            t.setTextColor(context.color(R.color.on_surface))
            t.isSingleLine = true
            t.ellipsize = null
            t.includeFontPadding = false
            t.setShadowLayer(pxF(R.dimen.glass_text_shadow) * 1.5f, 0f, pxF(R.dimen.stroke_glass), context.color(R.color.scrim))
        }
        // Lexend Bold exactly (weight 700): "bold" on top of the bold glance style would be faked wider.
        tempView.type(Type.GLANCE)
        tempView.typeface = Typeface.create(tempView.typeface, 700, false)
        hiLoView.type(Type.BODY)
        hiLoView.typeface = Typeface.create(hiLoView.typeface, 700, false)
        hiLoView.ellipsize = TextUtils.TruncateAt.END
        addView(textBox, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

        offlineGlyph.setImageResource(R.drawable.ic_cloud_off)
        offlineGlyph.alpha = 0.75f
        offlineGlyph.contentDescription = context.getString(R.string.weather_offline_desc)
        offlineGlyph.visibility = GONE
        val g = px(R.dimen.space_m)
        addView(offlineGlyph, LayoutParams(g, g, Gravity.TOP or Gravity.END).apply {
            topMargin = px(R.dimen.space_s) + px(R.dimen.space_xs) / 2
            marginEnd = px(R.dimen.space_s) + px(R.dimen.space_xs) / 2
        })

        alertGlyph.setImageResource(R.drawable.ic_alert)
        alertGlyph.contentDescription = context.getString(R.string.weather_alert_glyph_desc)
        alertGlyph.visibility = GONE
        val ap = px(R.dimen.space_xs)
        alertGlyph.setPadding(ap, ap, ap, ap)
        alertGlyph.setOnClickListener { onOpenAlert?.invoke() }
        val a = px(R.dimen.space_m) + 2 * ap
        addView(alertGlyph, LayoutParams(a, a, Gravity.TOP or Gravity.START).apply {
            topMargin = px(R.dimen.space_s) - ap / 2
            marginStart = px(R.dimen.space_s) - ap / 2
        })

        setOnClickListener { onOpen?.invoke() }
        setOnLongClickListener {
            onLongPress?.invoke()
            true
        }
        layoutText()
    }

    private fun layoutText() {
        textBox.removeAllViews()
        val pad = px(R.dimen.space_s) + px(R.dimen.space_xs)
        val lp = textBox.layoutParams as? LayoutParams
        when (size) {
            Size.COMPACT -> {
                // One cell tall: temperature over high/low, centered on the left (side by side doesn't fit two cells).
                textBox.orientation = LinearLayout.VERTICAL
                textBox.gravity = Gravity.START
                lp?.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                textBox.setPadding(pad, 0, pad, 0)
                tempView.setTextSize(TypedValue.COMPLEX_UNIT_PX, pxF(R.dimen.text_weather_temp_compact))
                textBox.addView(tempView)
                textBox.addView(hiLoView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            Size.STANDARD, Size.WIDE -> {
                textBox.orientation = LinearLayout.VERTICAL
                textBox.gravity = Gravity.START
                lp?.gravity = Gravity.BOTTOM or Gravity.START
                textBox.setPadding(pad, pad, pad, px(R.dimen.space_s) + px(R.dimen.space_xs) / 2)
                tempView.setTextSize(TypedValue.COMPLEX_UNIT_PX, pxF(if (size == Size.WIDE) R.dimen.text_weather_temp_wide else R.dimen.text_weather_temp))
                textBox.addView(tempView)
                textBox.addView(hiLoView)
            }
        }
        textBox.layoutParams = lp ?: textBox.layoutParams
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        repo.addListener(listener)
        render()
        postDelayed(tick, TICK_MS)
    }

    override fun onDetachedFromWindow() {
        repo.removeListener(listener)
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /** "52°F", the F smaller (like the Weather tile). */
    private fun bigTemp(c: Double?): CharSequence {
        val n = c?.let { WeatherText.f(it).toString() } ?: "--"
        return SpannableString("$n°F").apply { setSpan(RelativeSizeSpan(0.45f), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    fun render() {
        val state = repo.state
        val data = state.data
        val now = clock()
        scene.conditions = SceneModel.at(data ?: placeholder(now), now, live = true)
        tempView.text = bigTemp(data?.temperatureC)
        hiLoView.text = if (data != null) context.getString(R.string.weather_widget_hi_lo, "${WeatherText.f(data.highC)}°", "${WeatherText.f(data.lowC)}°") else ""
        hiLoView.visibility = if (data != null) VISIBLE else GONE
        offlineGlyph.visibility = if (data != null && state.problem == WeatherRepository.Problem.NETWORK) VISIBLE else GONE
        alertGlyph.visibility = if (io.github.iamth3doctor.searchlight.weather.NwsAlerts.active(data?.alerts, now).isNotEmpty()) VISIBLE else GONE
        contentDescription = if (data != null) {
            context.getString(R.string.weather_widget_desc, "${WeatherText.f(data.temperatureC)}°F", data.condition, data.locationName)
        } else {
            context.getString(R.string.widget_weather)
        }
    }

    private fun placeholder(now: Long) = WeatherData("Tacoma", 47.25, -122.44, 12.0, 2, true, 14.0, 6.0, now)

    /** Whether the temperature and high/low show whole, inside the widget (tests). */
    fun contentFits(): Boolean = fitProblem() == null

    /** What doesn't fit, or null (tests). */
    fun fitProblem(): String? {
        for (t in listOf(tempView, hiLoView)) {
            if (t.visibility != VISIBLE) continue
            val name = "'${t.text}' (${t.width}x${t.height} at ${t.left},${t.top} in box ${textBox.left},${textBox.top} " +
                "${textBox.width}x${textBox.height}; widget ${width}x$height)"
            val l = t.layout ?: return "$name: no layout"
            if ((0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }) return "$name: cut short"
            val textW = l.getLineWidth(0) + t.totalPaddingLeft + t.totalPaddingRight
            if (textW > t.width + 1) return "$name: text $textW wider than its view"
            val left = textBox.left + t.left
            val top = textBox.top + t.top
            if (left < 0 || top < 0 || left + t.width > width + 1 || top + t.height > height + 1) return "$name: outside the widget"
        }
        return null
    }

    /**
     * A one-line text that's always as wide as the line it draws: at large sizes with a smaller
     * unit span, TextView's measured width can come out a few pixels short of its own layout,
     * clipping the last letter.
     */
    private class WholeText(context: Context) : TextView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val line = layout?.takeIf { it.lineCount > 0 }?.getLineWidth(0) ?: return
            val need = kotlin.math.ceil(line).toInt() + totalPaddingLeft + totalPaddingRight
            if (need > measuredWidth && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
                val max = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) MeasureSpec.getSize(widthMeasureSpec) else need
                setMeasuredDimension(minOf(need, max), measuredHeight)
            }
        }
    }

    companion object {
        private const val TICK_MS = 60_000L

        /** The layout for a widget [cols] × [rows] cells big. */
        fun sizeFor(cols: Int, rows: Int): Size = when {
            rows < 2 -> Size.COMPACT
            cols >= 4 -> Size.WIDE
            else -> Size.STANDARD
        }
    }
}
