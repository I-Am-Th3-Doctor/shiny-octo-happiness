package io.github.iamth3doctor.searchlight.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.calendar.CalendarEvents
import io.github.iamth3doctor.searchlight.calendar.DayEvent
import io.github.iamth3doctor.searchlight.calendar.WeekModel
import io.github.iamth3doctor.searchlight.ui.Actions
import io.github.iamth3doctor.searchlight.ui.FrostedDrawable
import io.github.iamth3doctor.searchlight.ui.GlassTint
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Searchlight's own Calendar widget for the home grid (not an app widget: it's drawn here, so it
 * can be frosted glass like the home card and take swipes). A week row with prev/next chevrons
 * (a horizontal swipe on the row also moves a week; tapping the range goes back to this week),
 * and under it the selected day's agenda. Events come from [events]; without calendar access
 * the agenda is a single "Allow" row instead.
 */
@SuppressLint("ViewConstructor")
class CalendarWidgetView(context: Context, private val events: CalendarEvents) : LinearLayout(context) {

    /** A long press anywhere on the widget: the grid's edit mode, like any other widget. */
    var onLongPress: (() -> Unit)? = null
    /** The "Allow" button: ask Android for calendar access. */
    var onRequestPermission: (() -> Unit)? = null
    /** Content was rebuilt (new texts to shade on clear glass). */
    var onContentChanged: ((View) -> Unit)? = null

    // Design tokens (this is a View, not a Context).
    private fun px(token: Int) = context.px(token)
    private fun pxF(token: Int) = context.pxF(token)
    private fun color(token: Int) = context.color(token)

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val locale: Locale get() = resources.configuration.locales[0]

    var today: LocalDate = LocalDate.now()
        private set
    var weekStart: LocalDate = WeekModel.weekStart(today, firstDayOfWeek())
        private set
    var selected: LocalDate = today
        private set
    /** The week the shown events were loaded for (null: none yet). */
    var loadedWeek: LocalDate? = null
        private set
    /** Events by day for [loadedWeek]. */
    var byDay: Map<LocalDate, List<DayEvent>> = emptyMap()
        private set
    /** Still on "this week, today" (not navigated away): follows the date past midnight. */
    private var followingToday = true
    private var generation = 0

    private val header = LinearLayout(context)
    val prevButton = chevron(R.string.cal_prev_week, flip = true) { shiftWeek(-1) }
    val nextButton = chevron(R.string.cal_next_week, flip = false) { shiftWeek(+1) }
    val title = TextView(context)
    private val weekdays = LinearLayout(context)
    val weekRow = WeekRow(context)
    val dayCells: List<DayCell> = List(7) { DayCell(context) }
    private val agenda = FrameLayout(context)
    private val scroll = ScrollView(context)
    val eventList = LinearLayout(context)
    val emptyView = TextView(context)
    val permissionRow = LinearLayout(context)
    val allowButton = TextView(context)

    init {
        orientation = VERTICAL
        isClickable = true // a press on the widget is never a press on empty space
        val s = px(R.dimen.space_s)
        val xs = px(R.dimen.space_xs)
        setPadding(s, xs, s, s)
        background = FrostedDrawable(
            this, pxF(R.dimen.radius_card), color(R.color.glass_card), color(R.color.glass_card_fallback),
            color(R.color.glass_border), pxF(R.dimen.stroke_glass), adaptive = GlassTint.Layer.CARD,
        )
        clipToOutline = true

        // Header: ‹  Nov 2 – 8, 2026  ›
        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        title.apply {
            type(Type.BODY)
            typeface = Typeface.create(typeface, Typeface.BOLD)
            setTextColor(color(R.color.on_surface))
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            background = ripple(borderless = false)
            setOnClickListener { goToToday() }
        }
        header.addView(prevButton, LayoutParams(px(R.dimen.size_cal_chevron), LayoutParams.MATCH_PARENT))
        header.addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        header.addView(nextButton, LayoutParams(px(R.dimen.size_cal_chevron), LayoutParams.MATCH_PARENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, px(R.dimen.size_cal_header)))

        weekdays.orientation = HORIZONTAL
        repeat(7) {
            weekdays.addView(TextView(context).apply {
                type(Type.CAPTION)
                setTextColor(color(R.color.on_surface_dim))
                gravity = Gravity.CENTER
                isSingleLine = true
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(weekdays, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        weekRow.orientation = HORIZONTAL
        dayCells.forEach { cell ->
            cell.setOnClickListener { cell.day?.let(::tapDay) }
            weekRow.addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(weekRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = xs })

        // Agenda: the selected day's events, "No events", or the permission row.
        eventList.orientation = VERTICAL
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(eventList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        agenda.addView(scroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        emptyView.apply {
            type(Type.BODY)
            setTextColor(color(R.color.on_surface_dim))
            setText(R.string.cal_no_events)
            contentDescription = context.getString(R.string.cal_no_events_desc)
            gravity = Gravity.CENTER
            background = ripple(borderless = false)
            setOnClickListener { openDay(selected) }
        }
        agenda.addView(emptyView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        permissionRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(xs, 0, 0, 0)
            addView(TextView(context).apply {
                type(Type.BODY)
                setTextColor(color(R.color.on_surface))
                setText(R.string.cal_permission)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            allowButton.apply {
                type(Type.BODY)
                typeface = Typeface.create(typeface, Typeface.BOLD)
                setText(R.string.cal_permission_allow)
                setTextColor(color(R.color.on_gold))
                gravity = Gravity.CENTER
                minHeight = px(R.dimen.size_cal_day)
                setPadding(px(R.dimen.space_m), 0, px(R.dimen.space_m), 0)
                background = GradientDrawable().apply {
                    setColor(color(R.color.gold))
                    cornerRadius = pxF(R.dimen.radius_pill)
                }
                foreground = ripple(borderless = false)
                setOnClickListener { onRequestPermission?.invoke() }
            }
            addView(allowButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = px(R.dimen.space_s) })
        }
        agenda.addView(permissionRow, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(agenda, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = xs })

        render()
    }

    // ------------------------------------------------------------------ state

    /** The week the system calendar starts on (the locale's, or the user's own choice on Android 14+). */
    private fun firstDayOfWeek(): DayOfWeek =
        WeekModel.dayOfWeek(android.icu.util.Calendar.getInstance(android.icu.util.ULocale.getDefault()).firstDayOfWeek)

    /** Returning home, a new week, or changed calendar data: reload this week's events. */
    fun refresh() {
        val now = LocalDate.now(zone)
        val first = firstDayOfWeek()
        if (now != today || WeekModel.weekStart(weekStart, first) != weekStart) {
            today = now
            if (followingToday) selected = now
            weekStart = WeekModel.weekStart(selected, first)
        }
        if (!events.hasPermission()) {
            generation++
            byDay = emptyMap()
            loadedWeek = null
            render()
            return
        }
        val week = weekStart
        val gen = ++generation
        val (from, to) = WeekModel.queryRange(week, zone)
        events.load(from, to) { list ->
            if (gen != generation) return@load // a newer week or refresh is on its way
            byDay = WeekModel.byDay(list, WeekModel.days(week), zone)
            loadedWeek = week
            render()
        }
        render()
    }

    /** One week back (-1) or on (+1): today, if that week holds it, else its first day, is selected. */
    fun shiftWeek(by: Int) {
        weekStart = weekStart.plusWeeks(by.toLong())
        val days = WeekModel.days(weekStart)
        selected = if (today in days) today else weekStart
        followingToday = selected == today
        refresh()
    }

    fun goToToday() {
        followingToday = true
        selected = today
        weekStart = WeekModel.weekStart(today, firstDayOfWeek())
        refresh()
    }

    /** Selects [day]; tapping the already-selected day with no events opens the calendar there. */
    fun tapDay(day: LocalDate) {
        if (day == selected && loadedWeek == weekStart && events.hasPermission() && byDay[day].isNullOrEmpty()) {
            openDay(day)
            return
        }
        selected = day
        followingToday = day == today
        render()
    }

    private fun openDay(day: LocalDate) {
        Actions.openCalendarAt(context, day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
    }

    private fun open(e: DayEvent) {
        Actions.openEvent(context, e.eventId, e.begin, e.end)
    }

    // ------------------------------------------------------------------ drawing

    private fun render() {
        val days = WeekModel.days(weekStart)
        val allowed = events.hasPermission()
        val loaded = loadedWeek == weekStart
        title.text = WeekModel.rangeLabel(weekStart, locale)
        title.contentDescription = context.getString(R.string.cal_header_desc, title.text)
        for ((i, d) in days.withIndex()) {
            (weekdays.getChildAt(i) as TextView).text = d.dayOfWeek.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
            val count = if (allowed && loaded) byDay[d]?.size ?: 0 else 0
            dayCells[i].bind(d, today = d == today, selected = d == selected, dimmed = WeekModel.dimmed(d, weekStart), dots = WeekModel.dots(count), events = count)
        }

        permissionRow.visibility = if (allowed) GONE else VISIBLE
        val list = if (allowed && loaded) byDay[selected].orEmpty() else emptyList()
        emptyView.visibility = if (allowed && loaded && list.isEmpty()) VISIBLE else GONE
        scroll.visibility = if (list.isEmpty()) GONE else VISIBLE
        eventList.removeAllViews()
        for (e in list) eventList.addView(eventRow(e, selected))
        scroll.scrollTo(0, 0)
        onContentChanged?.invoke(this)
    }

    private fun eventRow(e: DayEvent, day: LocalDate): View {
        val time = when (val w = WeekModel.whenOn(e, day, zone)) {
            WeekModel.When.AllDay -> context.getString(R.string.cal_all_day)
            is WeekModel.When.At -> DateFormat.getTimeFormat(context).format(w.ms)
            is WeekModel.When.Until -> context.getString(R.string.cal_until, DateFormat.getTimeFormat(context).format(w.ms))
        }
        val name = e.title.ifBlank { context.getString(R.string.cal_no_title) }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(R.dimen.size_cal_event_row)
            setPadding(px(R.dimen.space_xs), 0, px(R.dimen.space_xs), 0)
            background = ripple(borderless = false)
            contentDescription = listOf(time, name, e.calendarName).filter { it.isNotBlank() }.joinToString(", ")
            setOnClickListener { open(e) }
            val dot = px(R.dimen.size_cal_color)
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (e.color != 0) (e.color or 0xFF000000.toInt()) else color(R.color.gold))
                }
            }, LayoutParams(dot, dot))
            addView(TextView(context).apply {
                type(Type.CAPTION)
                setTextColor(color(R.color.on_surface_dim))
                text = time
                isSingleLine = true
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = px(R.dimen.space_s) })
            addView(TextView(context).apply {
                type(Type.BODY)
                setTextColor(color(R.color.on_surface))
                text = name
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(R.dimen.space_s) })
            if (e.calendarName.isNotBlank()) addView(TextView(context).apply {
                type(Type.CAPTION)
                setTextColor(color(R.color.on_surface_dim))
                text = e.calendarName
                maxWidth = px(R.dimen.size_cal_name_max)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = px(R.dimen.space_s) })
        }
    }

    /** Short widgets drop the weekday labels first, so the days and agenda keep legible text. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val compact = h < px(R.dimen.size_cal_compact)
        val v = if (compact) GONE else VISIBLE
        if (weekdays.visibility != v) post { weekdays.visibility = v }
    }

    private fun chevron(desc: Int, flip: Boolean, click: () -> Unit) = ImageView(context).apply {
        setImageResource(R.drawable.ic_chevron_slim)
        if (flip) rotation = 180f
        imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.on_surface))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(desc)
        background = ripple(borderless = true)
        isClickable = true
        setOnClickListener { click() }
    }

    private fun ripple(borderless: Boolean): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) context.getDrawable(tv.resourceId) else null
    }

    // ------------------------------------------------------------------ long press (edit mode)

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val longPressCheck = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x
                downY = ev.y
                postDelayed(longPressCheck, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPressCheck)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> removeCallbacks(longPressCheck)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** After a long press the rest of that touch is the grid's, never a tap on a day or event. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = longPressed || super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = longPressed || super.onTouchEvent(event)

    // ------------------------------------------------------------------ the week row

    /** The seven days: a horizontal swipe across them moves one week (left: on, right: back). */
    inner class WeekRow(context: Context) : LinearLayout(context) {
        private val swipeDistance = context.pxF(R.dimen.size_swipe) / 2
        private var x0 = 0f
        private var y0 = 0f
        private var swiping = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = ev.x; y0 = ev.y; swiping = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - x0
                    if (!swiping && abs(dx) > slop && abs(dx) > abs(ev.y - y0)) {
                        swiping = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            return swiping
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = ev.x; y0 = ev.y; swiping = false }
                MotionEvent.ACTION_MOVE -> if (!swiping && abs(ev.x - x0) > slop && abs(ev.x - x0) > abs(ev.y - y0)) swiping = true
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - x0
                    if (swiping && abs(dx) >= swipeDistance) shiftWeek(if (dx < 0) +1 else -1)
                    swiping = false
                }
                MotionEvent.ACTION_CANCEL -> swiping = false
            }
            return true
        }
    }

    /** One day: its number (gold disc when it's today, a ring when selected) and up to three dots. */
    inner class DayCell(context: Context) : LinearLayout(context) {
        var day: LocalDate? = null
            private set
        var isToday = false
            private set
        var dots = 0
            private set
        var dimmed = false
            private set
        val number = TextView(context)
        private val dotRow = Dots(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            background = ripple(borderless = true)
            number.apply {
                type(Type.BODY)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            val d = px(R.dimen.size_cal_day)
            addView(number, LayoutParams(d, d))
            addView(dotRow, LayoutParams(LayoutParams.MATCH_PARENT, px(R.dimen.size_cal_dot) * 2))
        }

        fun bind(d: LocalDate, today: Boolean, selected: Boolean, dimmed: Boolean, dots: Int, events: Int) {
            day = d
            isToday = today
            this.dots = dots
            this.dimmed = dimmed
            isSelected = selected
            number.text = d.dayOfMonth.toString()
            number.typeface = Typeface.create(number.typeface, if (today || selected) Typeface.BOLD else Typeface.NORMAL)
            number.setTextColor(color(when {
                today -> R.color.on_gold
                dimmed -> R.color.on_surface_faint
                else -> R.color.on_surface
            }))
            number.background = when {
                today || selected -> GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (today) color(R.color.gold) else color(R.color.surface_inset))
                    if (selected) setStroke(px(R.dimen.stroke_glass) * 2, color(if (today) R.color.on_surface else R.color.gold))
                }
                else -> null
            }
            dotRow.count = dots
            val date = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
            val label = if (today) context.getString(R.string.cal_today, date) else date
            contentDescription = if (events > 0) resources.getQuantityString(R.plurals.cal_day_events, events, label, events) else label
        }
    }

    private inner class Dots(context: Context) : View(context) {
        var count = 0
            set(value) {
                if (field != value) { field = value; invalidate() }
            }
        private val r = context.pxF(R.dimen.size_cal_dot) / 2
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.color(R.color.gold) }

        override fun onDraw(canvas: Canvas) {
            if (count <= 0) return
            val gap = r * 1.5f
            val total = count * 2 * r + (count - 1) * gap
            var x = (width - total) / 2 + r
            val y = height / 2f
            repeat(count) {
                canvas.drawCircle(x, y, r, paint)
                x += 2 * r + gap
            }
        }
    }
}
