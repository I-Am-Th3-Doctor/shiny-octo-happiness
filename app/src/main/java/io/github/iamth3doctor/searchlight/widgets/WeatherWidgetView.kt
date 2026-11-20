package io.github.iamth3doctor.searchlight.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.FrostedDrawable
import io.github.iamth3doctor.searchlight.ui.GlassTint
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.ConditionVisual
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import io.github.iamth3doctor.searchlight.weather.WeatherText

/**
 * The Weather widget: plain and quiet, on the same frosted glass as the Calendar widget (so the
 * Glass transparency setting moves both together). The place, small; the temperature, large, with
 * a small condition icon; the condition in words; today's high and low in gold. Nothing moves.
 * Offline it keeps the last good reading, with only a small glyph in the corner.
 *
 * Tap: your weather app, else the conditions screen, as the Weather tile ([onOpen]). Long press: the grid's edit mode.
 */
@SuppressLint("ViewConstructor")
class WeatherWidgetView(context: Context, private val repo: WeatherRepository) : LinearLayout(context) {

    var onLongPress: (() -> Unit)? = null
    var onOpen: (() -> Unit)? = null

    private fun px(token: Int) = context.px(token)
    private fun pxF(token: Int) = context.pxF(token)

    /** "Tacoma", small at the top. */
    val placeView = TextView(context)
    val offlineGlyph = ImageView(context)
    val tempView: TextView = WholeText(context)
    val icon = ConditionView(context, glow = false)
    /** "Overcast". */
    val conditionView = TextView(context)
    /** "H 58° · L 44°". */
    val hiLoView = TextView(context)

    val offlineShown: Boolean get() = offlineGlyph.visibility == VISIBLE

    private val listener: () -> Unit = { render() }

    init {
        orientation = VERTICAL
        isClickable = true
        isLongClickable = true
        isFocusable = true
        val s = px(R.dimen.space_s)
        val pad = s + px(R.dimen.space_xs)
        setPadding(pad, s, pad, s)
        // The Calendar widget's glass, exactly.
        background = FrostedDrawable(
            this, pxF(R.dimen.radius_card), context.color(R.color.glass_card), context.color(R.color.glass_card_fallback),
            context.color(R.color.glass_border), pxF(R.dimen.stroke_glass), adaptive = GlassTint.Layer.CARD,
        )
        clipToOutline = true

        // The place, and the offline glyph across from it.
        val top = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        placeView.apply {
            type(Type.CAPTION)
            typeface = Typeface.create(typeface, 600, false)
            setTextColor(context.color(R.color.on_surface_dim))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        top.addView(placeView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        offlineGlyph.setImageResource(R.drawable.ic_cloud_off)
        offlineGlyph.alpha = 0.75f
        offlineGlyph.contentDescription = context.getString(R.string.weather_offline_desc)
        offlineGlyph.visibility = GONE
        val g = px(R.dimen.space_m)
        top.addView(offlineGlyph, LayoutParams(g, g).apply { marginStart = px(R.dimen.space_xs) })
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // The temperature, large, and the condition icon beside it.
        val now = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tempView.apply {
            type(Type.GLANCE)
            // Lexend Bold exactly (weight 700): "bold" on top of the bold glance style would be faked wider.
            typeface = Typeface.create(typeface, 700, false)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, pxF(R.dimen.text_weather_temp))
            setTextColor(context.color(R.color.on_surface))
            isSingleLine = true
            ellipsize = null
            includeFontPadding = false
        }
        now.addView(tempView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        now.addView(View(context), LayoutParams(0, 1, 1f))
        val iv = px(R.dimen.size_weather_visual_compact)
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        now.addView(icon, LayoutParams(iv, iv))
        addView(now, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        conditionView.apply {
            type(Type.BODY)
            setTextColor(context.color(R.color.on_surface))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        addView(conditionView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(R.dimen.space_xs) })
        hiLoView.apply {
            type(Type.CAPTION)
            typeface = Typeface.create(typeface, 600, false)
            setTextColor(context.color(R.color.gold))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        addView(hiLoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(R.dimen.space_xs) / 2 })

        setOnClickListener { onOpen?.invoke() }
        setOnLongClickListener {
            onLongPress?.invoke()
            true
        }
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        repo.addListener(listener)
        render()
    }

    override fun onDetachedFromWindow() {
        repo.removeListener(listener)
        super.onDetachedFromWindow()
    }

    /** "52°F", the F smaller (like the Weather tile). */
    private fun bigTemp(c: Double?): CharSequence {
        val n = c?.let { WeatherText.f(it).toString() } ?: "--"
        return SpannableString("$n°F").apply { setSpan(RelativeSizeSpan(0.45f), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    /** The reading as the weather app shows it: the same temperature, words, and high/low. */
    fun render() {
        val state = repo.state
        val data = state.data
        placeView.text = data?.locationName.orEmpty()
        tempView.text = bigTemp(data?.temperatureC)
        conditionView.text = data?.condition ?: context.getString(R.string.weather_widget_no_data)
        hiLoView.text = if (data != null) context.getString(R.string.weather_widget_hi_lo, "${WeatherText.f(data.highC)}°", "${WeatherText.f(data.lowC)}°") else ""
        hiLoView.visibility = if (data != null) VISIBLE else INVISIBLE
        icon.visibility = if (data != null) VISIBLE else INVISIBLE
        if (data != null) icon.visual = ConditionVisual.of(data.weatherCode, data.isDay)
        offlineGlyph.visibility = if (data != null && state.problem == WeatherRepository.Problem.NETWORK) VISIBLE else GONE
        contentDescription = if (data != null) {
            context.getString(R.string.weather_widget_desc, "${WeatherText.f(data.temperatureC)}°F", data.condition, data.locationName)
        } else {
            context.getString(R.string.widget_weather)
        }
    }

    /** What doesn't show whole inside the widget, or null (tests). */
    fun fitProblem(): String? {
        for (t in listOf(placeView, tempView, conditionView, hiLoView)) {
            if (t.visibility != VISIBLE) continue
            val name = "'${t.text}' (${t.width}x${t.height}; widget ${width}x$height)"
            val l = t.layout ?: return "$name: no layout"
            if ((0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }) return "$name: cut short"
            val r = android.graphics.Rect()
            if (!t.getGlobalVisibleRect(r) || r.width() < t.width - 1 || r.height() < t.height - 1) return "$name: outside the widget"
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
            val t = text
            if (t.isNotEmpty()) {
                val b = android.text.StaticLayout.Builder.obtain(t, 0, t.length, paint, 1_000_000)
                    .setIncludePad(includeFontPadding).setMaxLines(1)
                // Android 15 can size a line by its glyphs' ink rather than their advances: measure it the way it's laid out.
                if (android.os.Build.VERSION.SDK_INT >= 35) b.setUseBoundsForWidth(useBoundsForWidth)
                val need = kotlin.math.ceil(b.build().getLineWidth(0)).toInt() + compoundPaddingLeft + compoundPaddingRight + 2
                if (need != minWidth) minWidth = need
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
