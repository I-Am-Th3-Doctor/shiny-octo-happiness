package io.github.iamth3doctor.searchlight.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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
import io.github.iamth3doctor.searchlight.weather.ConditionVisual
import io.github.iamth3doctor.searchlight.weather.WeatherCodes
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherSkin
import io.github.iamth3doctor.searchlight.weatherapp.Skins
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import io.github.iamth3doctor.searchlight.weather.WeatherText
import io.github.iamth3doctor.searchlight.weather.WidgetForecast
import io.github.iamth3doctor.searchlight.weatherapp.WeatherFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.TextStyle

/**
 * The Weather widget's other two faces, from the same forecast as the live one:
 *
 *  - [Style.THREE_DAYS] (2×2): Today and the next two days side by side, each its day, its
 *    condition drawn, its high in bold and its low muted, on a light card;
 *  - [Style.DAY] (4×2): the temperature now, large, the place and the condition in words on the
 *    left, the condition drawn large on the right, and under them a bar the width of the widget with
 *    the next hours' temperature curve, its high and low marked, and the hours (NOW, then every 3).
 *
 * Offline it keeps the last good forecast with a small glyph in the corner. Tap: the weather screen
 * ([onOpen]). Long press: the grid's edit mode.
 */
@SuppressLint("ViewConstructor")
class WeatherForecastWidgetView(
    context: Context,
    private val repo: WeatherRepository,
    val style: Style,
) : FrameLayout(context) {

    enum class Style { THREE_DAYS, DAY }

    var onLongPress: (() -> Unit)? = null
    var onOpen: (() -> Unit)? = null

    /** Now, in ms (tests may pin it). */
    var clock: () -> Long = System::currentTimeMillis

    private fun px(t: Int) = context.px(t)
    private fun pxF(t: Int) = context.pxF(t)

    /** The skin in use ([Skins]): the cards' colors and the text's. */
    var skin: WeatherSkin = Skins.current
        private set
    /** A light card (dark text): the 3-day widget on the default skin, and every card on a light skin. */
    private val light get() = skin.cardLight(style == Style.THREE_DAYS)
    private val ink get() = skin.cardInk(style == Style.THREE_DAYS)
    private val muted get() = skin.cardMuted(style == Style.THREE_DAYS)
    private val skinListener: () -> Unit = { applySkin(); render() }

    val offlineGlyph = ImageView(context)
    val offlineShown: Boolean get() = offlineGlyph.visibility == VISIBLE

    // The three days: a column each (label, condition, high, low). Read by tests.
    val dayLabels = List(3) { TextView(context) }
    val dayVisuals = List(3) { ConditionView(context, glow = false) }
    val dayHighs = List(3) { TextView(context) }
    val dayLows = List(3) { TextView(context) }
    private val dayColumns = List(3) { LinearLayout(context) }

    // The day: now on the left, the condition on the right, the bar under them. Read by tests.
    val tempView = TextView(context)
    val placeView = TextView(context)
    val conditionView = TextView(context)
    val visual = ConditionView(context, glow = true)
    val bar = CurveBar(context)

    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            render()
            postDelayed(this, TICK_MS)
        }
    }

    init {
        // No color: the widget (its scene and icons too) is drawn in grays.
        io.github.iamth3doctor.searchlight.weatherapp.Monochrome.apply(this)
        isClickable = true
        isLongClickable = true
        isFocusable = true
        val r = pxF(R.dimen.radius_card)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
        }
        clipToOutline = true
        when (style) {
            Style.THREE_DAYS -> buildDays()
            Style.DAY -> buildDay()
        }

        offlineGlyph.setImageResource(R.drawable.ic_cloud_off)
        offlineGlyph.alpha = 0.75f
        offlineGlyph.contentDescription = context.getString(R.string.weather_offline_desc)
        offlineGlyph.visibility = GONE
        val g = px(R.dimen.space_m)
        addView(offlineGlyph, LayoutParams(g, g, Gravity.TOP or Gravity.END).apply {
            topMargin = px(R.dimen.space_s)
            marginEnd = px(R.dimen.space_s)
        })

        setOnClickListener { onOpen?.invoke() }
        setOnLongClickListener {
            onLongPress?.invoke()
            true
        }
        applySkin()
    }

    /** The skin's colors: the card, its text, the condition's accent, the curve's. */
    fun applySkin() {
        skin = Skins.current
        val r = pxF(R.dimen.radius_card)
        val (top, bottom) = skin.cardColors(style == Style.THREE_DAYS)
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply { cornerRadius = r }
        foreground = GradientDrawable().apply {
            cornerRadius = r
            setStroke(pxF(R.dimen.stroke_glass).toInt().coerceAtLeast(1), if (light) 0x1F000000 else context.color(R.color.glass_border))
        }
        if (light) offlineGlyph.setColorFilter(ink) else offlineGlyph.clearColorFilter()
        for (t in dayLabels + dayLows) t.setTextColor(muted)
        for (t in dayHighs) t.setTextColor(ink)
        tempView.setTextColor(ink)
        placeView.setTextColor(ink)
        conditionView.setTextColor(skin.accent)
        bar.style(skin.accent, ink, muted, light)
    }

    private fun TextView.style(style: Int, weight: Int, color: Int) {
        type(style)
        typeface = Typeface.create(typeface, weight, false)
        setTextColor(color)
        includeFontPadding = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun buildDays() {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val pad = px(R.dimen.space_s) + px(R.dimen.space_xs)
        row.setPadding(px(R.dimen.space_xs), pad, px(R.dimen.space_xs), pad)
        for (i in 0 until 3) {
            val col = dayColumns[i].apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            dayLabels[i].style(Type.CAPTION, 700, muted)
            dayLabels[i].gravity = Gravity.CENTER
            dayLabels[i].tag = "weather_3day_label_$i"
            dayHighs[i].style(Type.TILE_TITLE, 700, ink)
            dayHighs[i].gravity = Gravity.CENTER
            dayLows[i].style(Type.BODY, 500, muted)
            dayLows[i].gravity = Gravity.CENTER
            col.addView(dayLabels[i], LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            col.addView(dayVisuals[i], LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { topMargin = px(R.dimen.space_xs); bottomMargin = px(R.dimen.space_xs) })
            col.addView(dayHighs[i], LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            col.addView(dayLows[i], LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(R.dimen.space_xs) / 2 })
            row.addView(col, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun buildDay() {
        val pad = px(R.dimen.space_s) + px(R.dimen.space_xs)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        tempView.style(Type.GLANCE, 700, ink)
        tempView.setTextSize(TypedValue.COMPLEX_UNIT_PX, pxF(R.dimen.text_weather_temp_wide))
        tempView.ellipsize = null
        placeView.style(Type.BODY, 700, ink)
        conditionView.style(Type.CAPTION, 700, skin.accent)
        conditionView.letterSpacing = 0.08f
        left.addView(tempView)
        left.addView(placeView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(R.dimen.space_xs) })
        left.addView(conditionView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(R.dimen.space_xs) / 2 })
        top.addView(left, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(visual, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 0.6f))
        col.addView(top, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        bar.tag = "weather_day_bar"
        col.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (pxF(R.dimen.space_m) * 3.2f).toInt())
            .apply { topMargin = px(R.dimen.space_xs) })
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        repo.addListener(listener)
        Skins.addListener(skinListener)
        if (skin != Skins.current) applySkin()
        render()
        postDelayed(tick, TICK_MS)
    }

    override fun onDetachedFromWindow() {
        repo.removeListener(listener)
        Skins.removeListener(skinListener)
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun deg(c: Double) = "${WeatherText.f(c)}°"

    fun render() {
        val state = repo.state
        val data = state.data
        val now = clock()
        offlineGlyph.visibility = if (data != null && state.problem == WeatherRepository.Problem.NETWORK) VISIBLE else GONE
        when (style) {
            Style.THREE_DAYS -> renderDays(data, now)
            Style.DAY -> renderDay(data, now)
        }
    }

    private fun renderDays(data: WeatherData?, now: Long) {
        val days = data?.let { WidgetForecast.threeDays(it, now) }.orEmpty()
        val zone = ZoneOffset.ofTotalSeconds(data?.detail?.utcOffsetSec ?: 0)
        val locale = context.resources.configuration.locales[0]
        for (i in 0 until 3) {
            val d = days.getOrNull(i)
            dayColumns[i].visibility = if (d != null || days.isEmpty()) VISIBLE else INVISIBLE
            if (d == null) {
                dayLabels[i].text = if (i == 0) context.getString(R.string.weather_detail_today).uppercase() else ""
                dayHighs[i].text = if (i == 0) "--" else ""
                dayLows[i].text = ""
                continue
            }
            dayLabels[i].text = (if (d.today) context.getString(R.string.weather_detail_today)
                else Instant.ofEpochMilli(d.date + 12 * 3_600_000L).atOffset(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)).uppercase(locale)
            dayVisuals[i].visual = ConditionVisual.of(d.code, true)
            dayHighs[i].text = deg(d.highC)
            dayLows[i].text = deg(d.lowC)
        }
        contentDescription = if (days.isNotEmpty()) days.joinToString("; ") { "${dayLabelText(it, zone)}: ${WeatherCodes.describe(it.code)}, high ${WeatherText.f(it.highC)}°F, low ${WeatherText.f(it.lowC)}°F" }
            else context.getString(R.string.widget_weather)
    }

    private fun dayLabelText(d: WidgetForecast.Day, zone: ZoneOffset) = if (d.today) context.getString(R.string.weather_detail_today)
        else Instant.ofEpochMilli(d.date + 12 * 3_600_000L).atOffset(zone).dayOfWeek.getDisplayName(TextStyle.FULL, context.resources.configuration.locales[0])

    private fun renderDay(data: WeatherData?, now: Long) {
        tempView.text = SpannableString("${data?.let { WeatherText.f(it.temperatureC) } ?: "--"}°F").apply {
            setSpan(RelativeSizeSpan(0.45f), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        placeView.text = data?.locationName ?: ""
        conditionView.text = data?.condition?.uppercase(context.resources.configuration.locales[0]) ?: ""
        visual.visual = data?.let { ConditionVisual.of(it.weatherCode, it.isDay) } ?: ConditionVisual.CLOUDY
        bar.curve = data?.let { WidgetForecast.curve(it, now) }
        bar.zone = ZoneOffset.ofTotalSeconds(data?.detail?.utcOffsetSec ?: 0)
        contentDescription = if (data != null) context.getString(R.string.weather_widget_desc, "${WeatherText.f(data.temperatureC)}°F", data.condition, data.locationName)
            else context.getString(R.string.widget_weather)
    }

    /**
     * The next hours' temperature as a curve along a glass bar: the line in gold over a soft fill,
     * the high and low marked with a dot and their degrees, the hours (NOW, then every 3) under it.
     */
    class CurveBar(context: Context) : View(context) {
        var curve: WidgetForecast.Curve? = null
            set(value) { field = value; invalidate() }
        var zone: ZoneOffset = ZoneOffset.UTC
            set(value) { field = value; invalidate() }

        private val density = resources.displayMetrics.density
        private var gold = context.color(R.color.gold)
        private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = gold
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF; strokeWidth = density }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.color(R.color.on_surface_dim)
            textSize = 10f * resources.displayMetrics.scaledDensityCompat()
            typeface = Typeface.create(context.resources.getFont(R.font.lexend), 600, false)
        }
        private val value = Paint(label).apply { color = context.color(R.color.on_surface) ; typeface = Typeface.create(context.resources.getFont(R.font.lexend), 700, false) }

        /** The skin's colors: the curve and dots in [accent], numbers in [ink], hours in [muted]. */
        fun style(accent: Int, ink: Int, muted: Int, light: Boolean) {
            gold = accent
            line.color = accent
            dot.color = accent
            value.color = ink
            label.color = muted
            glass.color = if (light) 0x14000000 else 0x1FFFFFFF
            tick.color = if (light) 0x33000000 else 0x40FFFFFF
            invalidate()
        }

        /** Where each hour mark's label went, and the high's and low's (tests). */
        val markLabels = ArrayList<String>()
        var highLabel = ""
            private set
        var lowLabel = ""
            private set

        private fun android.util.DisplayMetrics.scaledDensityCompat(): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, this)

        /** The y of [tempC] on the curve (tests). */
        fun yOf(tempC: Double): Float {
            val c = curve ?: return 0f
            val (top, bottom) = plotTop() to plotBottom()
            val hi = c.points[c.high].second
            val lo = c.points[c.low].second
            val f = if (hi - lo < 0.01) 0.5 else (tempC - lo) / (hi - lo)
            return (bottom - (bottom - top) * f).toFloat()
        }

        private fun plotTop() = label.textSize + 4 * density
        private fun plotBottom() = height - label.textSize - 6 * density
        private fun inset() = 8 * density
        fun xOf(t: Long): Float {
            val c = curve ?: return 0f
            return inset() + (width - 2 * inset()) * c.x(t)
        }

        override fun onDraw(canvas: Canvas) {
            val r = 10 * density
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, glass)
            val c = curve ?: return
            markLabels.clear()
            val fmt = WeatherFormat.hour(context)
            val baseline = height - 4 * density
            for ((i, t) in c.marks.withIndex()) {
                val x = xOf(t)
                val text = if (i == 0) context.getString(R.string.weather_widget_now) else fmt.format(Instant.ofEpochMilli(t).atOffset(zone))
                markLabels += text
                val w = label.measureText(text)
                canvas.drawLine(x, plotBottom() + 2 * density, x, plotBottom() + 4 * density, tick)
                canvas.drawText(text, (x - w / 2).coerceIn(inset() / 2, width - w - inset() / 2), baseline, label)
            }
            val path = Path()
            c.points.forEachIndexed { i, (t, temp) ->
                val x = xOf(t)
                val y = yOf(temp)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val area = Path(path).apply {
                lineTo(xOf(c.points.last().first), plotBottom())
                lineTo(xOf(c.points.first().first), plotBottom())
                close()
            }
            fill.shader = LinearGradient(0f, plotTop(), 0f, plotBottom(), (gold and 0x00FFFFFF) or 0x59000000, gold and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawPath(area, fill)
            canvas.drawPath(path, line)
            fun mark(i: Int, above: Boolean): String {
                val (t, temp) = c.points[i]
                val x = xOf(t)
                val y = yOf(temp)
                canvas.drawCircle(x, y, 3.5f * density, dot)
                val text = "${if (above) "H" else "L"} ${WeatherText.f(temp)}°"
                val w = value.measureText(text)
                val ty = if (above) (y - 6 * density).coerceAtLeast(value.textSize) else (y + value.textSize + 4 * density).coerceAtMost(plotBottom())
                canvas.drawText(text, (x - w / 2).coerceIn(inset() / 2, width - w - inset() / 2), ty, value)
                return text
            }
            highLabel = mark(c.high, above = true)
            lowLabel = mark(c.low, above = false)
        }
    }

    companion object {
        private const val TICK_MS = 60_000L
    }
}
