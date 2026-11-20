package io.github.iamth3doctor.searchlight.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import io.github.iamth3doctor.searchlight.weather.HourPoint
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import io.github.iamth3doctor.searchlight.weather.WeatherText
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Searchlight's own Weather widget for the home grid: frosted glass like the home card, with a
 * drawn condition visual ([ConditionView]) whose look and tint follow the conditions (the same
 * WMO code the Weather tile shows, from the same [WeatherRepository], so the two never disagree).
 * Always °F.
 *
 * Each size has its own fixed layout ([Size]): only the visual and the tint change with the
 * weather. Content never scrolls; if a size is a little short for its layout (small screens,
 * big fonts), it's scaled down to fit rather than clipped.
 *
 * Tap: the detail view ([onOpen]). Long press: the grid's edit mode, like any widget.
 */
@SuppressLint("ViewConstructor")
class WeatherWidgetView(context: Context, private val repo: WeatherRepository) : FrameLayout(context) {

    /** 2x1: visual, temp, high/low. 2x2: + feels-like, place, updated. 4x2: + the next 6 hours. */
    enum class Size { COMPACT, STANDARD, WIDE }

    var onLongPress: (() -> Unit)? = null
    var onOpen: (() -> Unit)? = null
    /** Content was rebuilt (new texts to shade on clear glass). */
    var onContentChanged: ((View) -> Unit)? = null

    /** Now, in ms (tests may set a fixed one). */
    var clock: () -> Long = System::currentTimeMillis

    var size: Size = Size.STANDARD
        set(value) {
            if (field == value) return
            field = value
            build()
        }

    // Read by tests.
    var visual: ConditionVisual = ConditionVisual.CLEAR_DAY
        private set
    val tint: Int get() = ConditionView.tintOf(context, visual)
    var offlineShown = false
        private set

    private fun px(token: Int) = context.px(token)
    private fun pxF(token: Int) = context.pxF(token)
    private fun color(token: Int) = context.color(token)

    private val wash = View(context)
    private val fit = FitFrame(context)

    lateinit var conditionView: ConditionView
        private set
    lateinit var tempView: TextView
        private set
    var feelsView: TextView? = null
        private set
    lateinit var hiLoView: TextView
        private set
    var placeView: TextView? = null
        private set
    var updatedView: TextView? = null
        private set
    /** The hourly strip's cells (4x2 only): time, visual, temperature. */
    val hourCells = ArrayList<Triple<TextView, ConditionView, TextView>>()

    private val listener: () -> Unit = { render() }

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
        val r = pxF(R.dimen.radius_card)
        background = FrostedDrawable(
            this, r, color(R.color.glass_card), color(R.color.glass_card_fallback),
            color(R.color.glass_border), pxF(R.dimen.stroke_glass), adaptive = GlassTint.Layer.CARD,
        )
        clipToOutline = true
        addView(wash, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val pad = px(R.dimen.space_s) + px(R.dimen.space_xs)
        fit.setPadding(pad, px(R.dimen.space_s), pad, px(R.dimen.space_s))
        addView(fit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnClickListener { onOpen?.invoke() }
        setOnLongClickListener {
            onLongPress?.invoke()
            true
        }
        build()
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

    // ------------------------------------------------------------------ layouts

    private fun text(style: Int, dim: Boolean = false, bold: Boolean = false) = TextView(context).apply {
        type(style)
        if (bold) typeface = Typeface.create(typeface, Typeface.BOLD)
        setTextColor(color(if (dim) R.color.on_surface_dim else R.color.on_surface))
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun tempText(sizeToken: Int) = text(Type.TILE_TITLE, bold = true).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, pxF(sizeToken))
        ellipsize = null
    }

    private fun wrap() = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    private fun fill() = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    private fun build() {
        fit.removeAllViews()
        hourCells.clear()
        feelsView = null
        placeView = null
        updatedView = null
        val s = px(R.dimen.space_s)
        val xs = px(R.dimen.space_xs)
        conditionView = ConditionView(context)
        when (size) {
            Size.COMPACT -> {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val v = px(R.dimen.size_weather_visual_compact)
                row.addView(conditionView, LinearLayout.LayoutParams(v, v))
                // Temperature over high/low (side by side doesn't fit two cells across).
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val tempRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
                tempView = tempText(R.dimen.text_weather_temp_compact)
                tempRow.addView(tempView, wrap())
                updatedView = text(Type.CAPTION).apply { setTextColor(color(R.color.gold)) }
                tempRow.addView(updatedView, wrap().apply { marginStart = xs; bottomMargin = xs / 2 })
                col.addView(tempRow, fill())
                hiLoView = text(Type.BODY)
                col.addView(hiLoView, fill())
                row.addView(col, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = s })
                fit.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            }
            Size.STANDARD -> {
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val v = px(R.dimen.size_weather_visual)
                top.addView(conditionView, LinearLayout.LayoutParams(v, v))
                tempView = tempText(R.dimen.text_weather_temp).apply { gravity = Gravity.END }
                top.addView(tempView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = s })
                col.addView(top, fill())
                feelsView = text(Type.BODY, dim = true).also { col.addView(it, fill().apply { topMargin = xs }) }
                hiLoView = text(Type.BODY).also { col.addView(it, fill().apply { topMargin = xs / 2 }) }
                placeView = text(Type.BODY, bold = true).also { col.addView(it, fill().apply { topMargin = xs }) }
                updatedView = text(Type.CAPTION, dim = true).apply { isSingleLine = false; maxLines = 2 }
                    .also { col.addView(it, fill().apply { topMargin = xs / 2 }) }
                fit.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            }
            Size.WIDE -> {
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val v = px(R.dimen.size_weather_visual)
                top.addView(conditionView, LinearLayout.LayoutParams(v, v))
                tempView = tempText(R.dimen.text_weather_temp)
                top.addView(tempView, wrap().apply { marginStart = s })
                val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                placeView = text(Type.BODY, bold = true).also { info.addView(it, fill()) }
                feelsView = text(Type.BODY, dim = true).also { info.addView(it, fill()) }
                hiLoView = text(Type.BODY).also { info.addView(it, fill()) }
                updatedView = text(Type.CAPTION, dim = true).apply { isSingleLine = false; maxLines = 2 }.also { info.addView(it, fill()) }
                top.addView(info, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = s + xs })
                col.addView(top, fill())
                val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val hv = px(R.dimen.size_weather_visual_hour)
                repeat(HOURS) {
                    val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                    val time = text(Type.CAPTION, dim = true).apply { gravity = Gravity.CENTER }
                    val cv = ConditionView(context, glow = false)
                    val t = text(Type.BODY, bold = true).apply { gravity = Gravity.CENTER; ellipsize = null }
                    cell.addView(time, fill())
                    cell.addView(cv, LinearLayout.LayoutParams(hv, hv).apply { topMargin = xs / 2; bottomMargin = xs / 2 })
                    cell.addView(t, fill())
                    strip.addView(cell, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                    hourCells += Triple(time, cv, t)
                }
                col.addView(strip, fill().apply { topMargin = s })
                fit.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            }
        }
        render()
    }

    // ------------------------------------------------------------------ content

    private fun deg(c: Double) = "${WeatherText.f(c)}°"

    /** "52°F", the F smaller (like the Weather tile): never ambiguous. */
    private fun bigTemp(c: Double?): CharSequence {
        val n = c?.let { WeatherText.f(it).toString() } ?: "--"
        return SpannableString("$n°F").apply { setSpan(RelativeSizeSpan(0.45f), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    fun render() {
        val state = repo.state
        val data = state.data
        offlineShown = data != null && state.problem == WeatherRepository.Problem.NETWORK
        visual = if (data != null) ConditionVisual.of(data.weatherCode, data.isDay) else ConditionVisual.CLOUDY
        conditionView.visual = visual
        wash.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(ConditionView.withAlpha(tint, WASH_ALPHA), ConditionView.withAlpha(tint, 0)),
        ).apply { cornerRadius = pxF(R.dimen.radius_card) }

        tempView.text = bigTemp(data?.temperatureC)
        hiLoView.text = if (data != null) context.getString(R.string.weather_widget_hi_lo, deg(data.highC), deg(data.lowC)) else ""
        feelsView?.let { v ->
            val feels = data?.detail?.feelsLikeC
            v.text = if (feels != null) context.getString(R.string.weather_widget_feels, deg(feels)) else data?.condition.orEmpty()
        }
        placeView?.text = data?.locationName ?: context.getString(R.string.weather_widget_no_data)
        updatedView?.let { v ->
            v.setTextColor(color(if (offlineShown) R.color.gold else R.color.on_surface_dim))
            v.text = when {
                offlineShown -> context.getString(if (size == Size.COMPACT) R.string.weather_widget_offline_short else R.string.weather_widget_offline)
                data == null -> context.getString(
                    when (state.problem) {
                        WeatherRepository.Problem.NEEDS_LOCATION -> R.string.weather_needs_location
                        WeatherRepository.Problem.LOCATION_UNAVAILABLE -> R.string.weather_no_fix
                        WeatherRepository.Problem.NETWORK -> R.string.weather_failed
                        null -> R.string.weather_loading
                    },
                )
                size == Size.COMPACT -> ""
                else -> context.getString(R.string.weather_widget_updated, DateFormat.getTimeFormat(context).format(Date(data.fetchedAt)))
            }
            v.visibility = if (v.text.isEmpty()) GONE else VISIBLE
        }
        renderHours(data)
        contentDescription = if (data != null) {
            context.getString(R.string.weather_widget_desc, "${WeatherText.f(data.temperatureC)}°F", data.condition, data.locationName)
        } else {
            context.getString(R.string.widget_weather)
        }
        onContentChanged?.invoke(this)
    }

    /** How much the content is scaled down to fit (1: not at all). Tests. */
    val fitScale: Float get() = fit.scale

    /** Whether everything shows: the content inside the widget, and no text cut short (tests). */
    fun contentFits(): Boolean {
        val child = fit.getChildAt(0) ?: return false
        if (child.top + child.height * fit.scale > fit.height + 1 || child.left + child.width * fit.scale > fit.width + 1) return false
        fun whole(v: View): Boolean = when {
            v.visibility != VISIBLE -> true
            v is TextView -> v.layout?.let { l -> (0 until l.lineCount).all { l.getEllipsisCount(it) == 0 } } ?: true
            v is ViewGroup -> (0 until v.childCount).all { whole(v.getChildAt(it)) }
            else -> true
        }
        return whole(child)
    }

    /** The next hours (from the coming one), at the place's own time. */
    fun nextHours(data: WeatherData?): List<HourPoint> {
        val now = clock()
        return data?.detail?.hourly.orEmpty().filter { it.time > now }.take(HOURS)
    }

    private fun renderHours(data: WeatherData?) {
        if (hourCells.isEmpty()) return
        val hours = nextHours(data)
        val zone = ZoneOffset.ofTotalSeconds(data?.detail?.utcOffsetSec ?: 0)
        val fmt = hourFormat(context)
        hourCells.forEachIndexed { i, (time, cv, temp) ->
            val h = hours.getOrNull(i)
            val vis = if (h != null) VISIBLE else INVISIBLE
            time.visibility = vis
            cv.visibility = vis
            temp.visibility = vis
            if (h != null) {
                time.text = fmt.format(Instant.ofEpochMilli(h.time).atOffset(zone))
                cv.visual = ConditionVisual.of(h.code, h.isDay)
                temp.text = deg(h.tempC)
            }
        }
    }

    /**
     * Holds the content at its natural size, scaled down (never up) to fit when it's taller or
     * wider than the space: a size a little short for its layout shrinks instead of clipping.
     */
    private class FitFrame(context: Context) : FrameLayout(context) {
        var scale = 1f
            private set

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val availW = (w - paddingLeft - paddingRight).coerceAtLeast(0)
            val availH = (h - paddingTop - paddingBottom).coerceAtLeast(0)
            val child = getChildAt(0) ?: return
            child.measure(
                MeasureSpec.makeMeasureSpec(availW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            scale = if (child.measuredHeight > availH && child.measuredHeight > 0) availH.toFloat() / child.measuredHeight else 1f
            if (scale < 1f) {
                // Narrower by the same factor, laid out at full width: text gets the room it needs.
                child.measure(
                    MeasureSpec.makeMeasureSpec((availW / scale).toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                scale = (availH.toFloat() / child.measuredHeight).coerceAtMost(1f)
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val child = getChildAt(0) ?: return
            val availH = height - paddingTop - paddingBottom
            val availW = width - paddingLeft - paddingRight
            val ch = child.measuredHeight
            val cw = child.measuredWidth
            // Centered vertically (and horizontally when scaled).
            val top = paddingTop + ((availH - ch * scale) / 2).toInt().coerceAtLeast(0)
            val left = paddingLeft + ((availW - cw * scale) / 2).toInt().coerceAtLeast(0)
            child.layout(left, top, left + cw, top + ch)
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = scale
            child.scaleY = scale
        }
    }

    companion object {
        const val HOURS = 6
        private const val WASH_ALPHA = 0x4D // 30%

        /** The layout for a widget [cols] × [rows] cells big. */
        fun sizeFor(cols: Int, rows: Int): Size = when {
            rows < 2 -> Size.COMPACT
            cols >= 4 -> Size.WIDE
            else -> Size.STANDARD
        }

        /** "3 PM" (or "15:00" on a 24-hour phone). */
        fun hourFormat(context: Context): DateTimeFormatter =
            DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h a", context.resources.configuration.locales[0])
    }
}
