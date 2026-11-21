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
import io.github.iamth3doctor.searchlight.weatherapp.Skins
import io.github.iamth3doctor.searchlight.weatherapp.WeatherSceneView

/**
 * The Weather widget: a window into the weather scene (the same sky, clouds, rain, sun or moon as
 * the weather screen, moving), with just the temperature, large, and today's high/low over it.
 * The scene animates while the home screen shows and holds still otherwise, and in battery saver
 * ([WeatherSceneView]). Offline it keeps the last good scene, with a small glyph in the corner.
 *
 * 2x1 stacks the temperature over high/low on the left of a slim window; 4x2 puts them in the
 * corner of a bigger one. 2x2 is just the place, small at the top, and the temperature, large: the
 * scene is the condition, and nothing else is on it. Tap: the weather screen ([onOpen]). Long press: the grid's edit mode.
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
            render()
        }

    private fun px(token: Int) = context.px(token)
    private fun pxF(token: Int) = context.pxF(token)

    val scene = WeatherSceneView(context, mini = true)
    val tempView: TextView = WholeText(context)
    val hiLoView: TextView = WholeText(context)
    /** The place, small at the top (2x2 only). */
    val placeView = TextView(context)
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
        placeView.type(Type.CAPTION)
        placeView.typeface = Typeface.create(placeView.typeface, 700, false)
        placeView.setTextColor(context.color(R.color.on_surface))
        placeView.isSingleLine = true
        placeView.ellipsize = TextUtils.TruncateAt.END
        placeView.includeFontPadding = false
        placeView.setShadowLayer(pxF(R.dimen.glass_text_shadow) * 1.5f, 0f, pxF(R.dimen.stroke_glass), context.color(R.color.scrim))
        placeView.visibility = GONE
        val pp = px(R.dimen.space_s)
        placeView.setPadding(px(R.dimen.space_xs), px(R.dimen.space_xs) / 2, px(R.dimen.space_xs), px(R.dimen.space_xs) / 2)
        addView(placeView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = pp
            marginStart = pp
            marginEnd = pp + px(R.dimen.space_m)
        })
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
        applySkin()
    }

    private val skinListener: () -> Unit = { applySkin() }

    /**
     * The skin in use ([Skins]): the scene follows it on its own. The dark text sits on small frosted
     * pads, so it reads over a night sky as well as a bright one.
     */
    fun applySkin() {
        val skin = Skins.current
        val shadow = pxF(R.dimen.glass_text_shadow) * 1.5f
        fun pad(radius: Float) = if (skin.light) GradientDrawable().apply { cornerRadius = radius; setColor(skin.calloutBg) } else null
        textBox.background = pad(pxF(R.dimen.radius_card) * 0.6f)
        placeView.background = pad(999f)
        for (t in listOf(tempView, hiLoView, placeView)) {
            if (skin.light) {
                t.setTextColor(skin.ink)
                t.setShadowLayer(0f, 0f, 0f, 0)
            } else {
                t.setTextColor(skin.ink)
                t.setShadowLayer(shadow, 0f, pxF(R.dimen.stroke_glass), context.color(R.color.scrim))
            }
        }
        if (skin.light) offlineGlyph.setColorFilter(skin.ink) else offlineGlyph.clearColorFilter()
    }

    private fun layoutText() {
        textBox.removeAllViews()
        val pad = px(R.dimen.space_s) + px(R.dimen.space_xs)
        // The frosted pad's own margin from the widget's edge.
        val edge = px(R.dimen.space_xs)
        val lp = textBox.layoutParams as? LayoutParams
        when (size) {
            Size.COMPACT -> {
                // One cell tall: temperature over high/low, centered on the left (side by side doesn't fit two cells).
                textBox.orientation = LinearLayout.VERTICAL
                textBox.gravity = Gravity.START
                lp?.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                lp?.setMargins(edge, 0, edge, 0)
                textBox.setPadding(pad - edge, edge, pad - edge, edge)
                tempView.setTextSize(TypedValue.COMPLEX_UNIT_PX, pxF(R.dimen.text_weather_temp_compact))
                textBox.addView(tempView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                textBox.addView(hiLoView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            Size.STANDARD, Size.WIDE -> {
                textBox.orientation = LinearLayout.VERTICAL
                textBox.gravity = Gravity.START
                lp?.gravity = Gravity.BOTTOM or Gravity.START
                lp?.setMargins(edge, 0, edge, edge)
                textBox.setPadding(pad - edge, pad - edge, pad - edge, px(R.dimen.space_s) + px(R.dimen.space_xs) / 2 - edge)
                tempView.setTextSize(TypedValue.COMPLEX_UNIT_PX, pxF(if (size == Size.WIDE) R.dimen.text_weather_temp_wide else R.dimen.text_weather_temp))
                // Each as wide as its own text (a vertical LinearLayout would stretch them to the box, clipping the wider one).
                textBox.addView(tempView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                textBox.addView(hiLoView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        textBox.layoutParams = lp ?: textBox.layoutParams
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        repo.addListener(listener)
        Skins.addListener(skinListener)
        applySkin()
        render()
        postDelayed(tick, TICK_MS)
    }

    override fun onDetachedFromWindow() {
        repo.removeListener(listener)
        Skins.removeListener(skinListener)
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
        val standard = size == Size.STANDARD
        hiLoView.visibility = if (data != null && !standard) VISIBLE else GONE
        placeView.text = data?.locationName ?: ""
        placeView.visibility = if (data != null && standard) VISIBLE else GONE
        offlineGlyph.visibility = if (data != null && state.problem == WeatherRepository.Problem.NETWORK) VISIBLE else GONE
        alertGlyph.visibility = if (io.github.iamth3doctor.searchlight.weather.NwsAlerts.active(data?.alerts, now).isNotEmpty()) VISIBLE else GONE
        // The place moves over for the alert glyph when there is one.
        (placeView.layoutParams as? LayoutParams)?.let { lp ->
            val want = px(R.dimen.space_s) + if (alertGlyph.visibility == VISIBLE) px(R.dimen.space_m) + px(R.dimen.space_s) else 0
            if (lp.marginStart != want) { lp.marginStart = want; placeView.layoutParams = lp }
        }
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
            if (textW > t.width + 1) return "$name: text $textW wider than its view (min ${t.minWidth}, size ${t.textSize}, " +
                "bounds ${if (android.os.Build.VERSION.SDK_INT >= 35) t.useBoundsForWidth else false}, ${l.javaClass.simpleName} ${l.width})"
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
            // Ask for the width the drawn line really takes (measured the same way the layout lays it out).
            val t = text
            if (t.isNotEmpty()) {
                val b = android.text.StaticLayout.Builder.obtain(t, 0, t.length, paint, 1_000_000)
                    .setIncludePad(includeFontPadding).setMaxLines(1)
                // Android 15 can size a line by its glyphs' ink rather than their advances: measure it the way it's laid out.
                if (android.os.Build.VERSION.SDK_INT >= 35) b.setUseBoundsForWidth(useBoundsForWidth)
                val l = b.build()
                val need = kotlin.math.ceil(l.getLineWidth(0)).toInt() + compoundPaddingLeft + compoundPaddingRight + 2
                // Never below what the last layout showed this same text needs.
                val want = if ("$t@$textSize" == drawnText) maxOf(need, drawnWidth) else need
                if (want != minWidth) minWidth = want
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            // The line as finally laid out can come out wider than measured (a large size with a smaller
            // unit span): if so, ask for exactly that width and lay out again, so nothing is clipped.
            val l = layout?.takeIf { it.lineCount > 0 } ?: return
            val drawn = kotlin.math.ceil(l.getLineWidth(0)).toInt() + totalPaddingLeft + totalPaddingRight + 1
            if (drawn > width && drawn > minWidth) {
                drawnText = "$text@$textSize"
                drawnWidth = drawn
                minWidth = drawn
                post { requestLayout() }
            }
        }

        private var drawnText: String? = null
        private var drawnWidth = 0
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
