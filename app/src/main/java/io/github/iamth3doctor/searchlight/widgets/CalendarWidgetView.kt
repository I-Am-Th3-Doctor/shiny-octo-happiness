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
import androidx.core.content.res.ResourcesCompat
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.calendar.CalendarEvents
import io.github.iamth3doctor.searchlight.calendar.CalendarModel
import io.github.iamth3doctor.searchlight.calendar.CalendarModel.Rel
import io.github.iamth3doctor.searchlight.calendar.DayEvent
import io.github.iamth3doctor.searchlight.ui.Actions
import io.github.iamth3doctor.searchlight.ui.FrostedDrawable
import io.github.iamth3doctor.searchlight.ui.GlassTint
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Searchlight's own Calendar widget for the home grid (not an app widget: it's drawn here, so it
 * can be frosted glass like the home card). Split in two on one glass surface: the month grid
 * on the left, the selected day's agenda on the right, each event with a live label ("now ·
 * 25 min left", "In 35 min.", "Tomorrow", "Ended"). Narrower than three cells ([stacked]), the
 * month sits above the agenda instead. Events come from [events]; without calendar access the
 * agenda is a single "Allow" row.
 */
@SuppressLint("ViewConstructor")
class CalendarWidgetView(context: Context, private val events: CalendarEvents) : LinearLayout(context) {

    /** A long press anywhere on the widget: the grid's edit mode, like any other widget. */
    var onLongPress: (() -> Unit)? = null
    /** The "Allow" button: ask Android for calendar access. */
    var onRequestPermission: (() -> Unit)? = null
    /** Content was rebuilt (new texts to shade on clear glass). */
    var onContentChanged: ((View) -> Unit)? = null

    /** Now, in ms (tests set a fixed one). */
    var clock: () -> Long = System::currentTimeMillis

    /**
     * What tapping and long-pressing events, "+" and a long-pressed day do (the home screen
     * supplies it). Null: "+" is hidden and an event opens in the calendar app.
     */
    var actions: EventActions? = null
        set(value) {
            field = value
            addButton.visibility = if (value != null) VISIBLE else GONE
        }

    interface EventActions {
        /** "+" or "Add event": the add sheet, for [date]. */
        fun add(date: LocalDate)
        /** A tapped event: its detail view (Edit, Delete, Open in Calendar). */
        fun details(e: DayEvent)
        fun edit(e: DayEvent)
        fun delete(e: DayEvent)
        /** A long-pressed day's "Arrange widgets". */
        fun arrange()
    }

    // Design tokens (this is a View, not a Context).
    private fun px(token: Int) = context.px(token)
    private fun pxF(token: Int) = context.pxF(token)
    private fun color(token: Int) = context.color(token)

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val locale: Locale get() = resources.configuration.locales[0]
    private fun todayNow(): LocalDate = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()

    var today: LocalDate = todayNow()
        private set
    var month: YearMonth = YearMonth.from(today)
        private set
    var selected: LocalDate = today
        private set
    /** The month the shown events were loaded for (null: none yet). */
    var loadedMonth: YearMonth? = null
        private set
    /** Events by day for the grid of [loadedMonth]. */
    var byDay: Map<LocalDate, List<DayEvent>> = emptyMap()
        private set
    /** Still on "this month, today" (not navigated away): follows the date past midnight. */
    private var followingToday = true
    private var generation = 0
    private var firstDay: DayOfWeek = firstDayOfWeek()

    /** Month above agenda (narrower than three cells) instead of side by side. */
    var stacked = false
        set(value) {
            if (field == value) return
            field = value
            applyArrangement()
        }

    private val lexend: Typeface = ResourcesCompat.getFont(context, R.font.lexend) ?: Typeface.DEFAULT

    // ---- month side
    private val monthPane = LinearLayout(context)
    val prevButton = iconButton(R.drawable.ic_chevron_slim, R.string.cal_prev_month, flip = true) { shiftMonth(-1) }
    val nextButton = iconButton(R.drawable.ic_chevron_slim, R.string.cal_next_month, flip = false) { shiftMonth(+1) }
    val monthTitle = TextView(context)
    private val weekdays = LinearLayout(context)
    private val weeks = LinearLayout(context)
    /** Six rows of seven; rows a month doesn't need are hidden. */
    val dayViews: List<DayView> = List(42) { DayView(context) }

    // ---- divider
    private val divider = View(context)

    // ---- agenda side
    private val agendaPane = LinearLayout(context)
    val dayTitle = TextView(context)
    val refreshButton = iconButton(R.drawable.ic_refresh, R.string.cal_refresh, flip = false) { userRefresh() }
    val gearButton = iconButton(R.drawable.ic_settings, R.string.cal_open_app, flip = false) { openDay(selected) }
    val addButton = iconButton(R.drawable.ic_add, R.string.cal_add_event, flip = false) { actions?.add(selected) }
    private val agendaBody = FrameLayout(context)
    private val scroll = ScrollView(context)
    val eventList = LinearLayout(context)
    val emptyView = TextView(context)
    val permissionRow = LinearLayout(context)
    val allowButton = TextView(context)

    init {
        isClickable = true // a press on the widget is never a press on empty space
        val s = px(R.dimen.space_s)
        val xs = px(R.dimen.space_xs)
        setPadding(s, xs, s, s)
        background = FrostedDrawable(
            this, pxF(R.dimen.radius_card), color(R.color.glass_card), color(R.color.glass_card_fallback),
            color(R.color.glass_border), pxF(R.dimen.stroke_glass), adaptive = GlassTint.Layer.CARD,
        )
        clipToOutline = true

        // ---- month: ‹ June 2026 ›, weekdays, the grid
        monthPane.orientation = VERTICAL
        val monthHeader = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        monthTitle.apply {
            type(Type.BODY)
            typeface = Typeface.create(typeface, Typeface.BOLD)
            setTextColor(color(R.color.on_surface))
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            background = ripple(borderless = false)
            setOnClickListener { goToToday() }
        }
        monthHeader.addView(prevButton, LayoutParams(px(R.dimen.size_cal_chevron), LayoutParams.MATCH_PARENT))
        monthHeader.addView(monthTitle, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        monthHeader.addView(nextButton, LayoutParams(px(R.dimen.size_cal_chevron), LayoutParams.MATCH_PARENT))
        monthPane.addView(monthHeader, LayoutParams(LayoutParams.MATCH_PARENT, px(R.dimen.size_cal_header)))

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
        monthPane.addView(weekdays, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        weeks.orientation = VERTICAL
        for (r in 0 until 6) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (c in 0 until 7) {
                val v = dayViews[r * 7 + c]
                v.setOnClickListener { v.day?.let(::tapDay) }
                row.addView(v, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
            weeks.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        monthPane.addView(weeks, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        divider.setBackgroundColor(color(R.color.glass_border))

        // ---- agenda: Fri, Jun 5  ⟳ ⚙, then the events
        agendaPane.orientation = VERTICAL
        val dayHeader = DayHeader(context)
        dayTitle.apply {
            type(Type.BODY)
            typeface = Typeface.create(typeface, Typeface.BOLD)
            setTextColor(color(R.color.on_surface))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(xs, 0, 0, 0)
        }
        dayHeader.addView(dayTitle, LayoutParams(0, px(R.dimen.size_cal_header), 1f))
        dayTitle.gravity = Gravity.CENTER_VERTICAL
        val icons = dayHeader.icons
        addButton.visibility = GONE
        icons.addView(addButton, LayoutParams(px(R.dimen.size_cal_chevron), px(R.dimen.size_cal_header)))
        icons.addView(refreshButton, LayoutParams(px(R.dimen.size_cal_chevron), px(R.dimen.size_cal_header)))
        icons.addView(gearButton, LayoutParams(px(R.dimen.size_cal_chevron), px(R.dimen.size_cal_header)))
        dayHeader.addView(icons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        agendaPane.addView(dayHeader, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        eventList.orientation = VERTICAL
        // Empty agenda space (below the events) opens the calendar on the day.
        eventList.isClickable = true
        eventList.setOnClickListener { openDay(selected) }
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.isFillViewport = true
        scroll.addView(eventList, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        agendaBody.addView(scroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        emptyView.apply {
            type(Type.BODY)
            setTextColor(color(R.color.on_surface_dim))
            setText(R.string.cal_no_events)
            contentDescription = context.getString(R.string.cal_no_events_desc)
            gravity = Gravity.CENTER
            background = ripple(borderless = false)
            setOnClickListener { openDay(selected) }
        }
        agendaBody.addView(emptyView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        permissionRow.apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(xs, 0, xs, 0)
            addView(TextView(context).apply {
                type(Type.BODY)
                setTextColor(color(R.color.on_surface))
                setText(R.string.cal_permission)
                gravity = Gravity.CENTER
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
            addView(allowButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = s })
        }
        agendaBody.addView(permissionRow, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        agendaPane.addView(agendaBody, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(monthPane)
        addView(divider)
        addView(agendaPane)
        applyArrangement()
        render()
    }

    /** Side by side (month | agenda), or month over agenda. */
    private fun applyArrangement() {
        val line = px(R.dimen.stroke_glass)
        val s = px(R.dimen.space_s)
        if (stacked) {
            orientation = VERTICAL
            monthPane.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 3f)
            divider.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, line).apply { topMargin = s; bottomMargin = s / 2 }
            agendaPane.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 2f)
        } else {
            orientation = HORIZONTAL
            monthPane.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            divider.layoutParams = LayoutParams(line, LayoutParams.MATCH_PARENT).apply { marginStart = s; marginEnd = s; topMargin = s; bottomMargin = s }
            agendaPane.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        requestLayout()
    }

    // ------------------------------------------------------------------ state

    /** The week the system calendar starts on (the locale's, or the user's own choice on Android 14+). */
    private fun firstDayOfWeek(): DayOfWeek =
        CalendarModel.dayOfWeek(android.icu.util.Calendar.getInstance(android.icu.util.ULocale.getDefault()).firstDayOfWeek)

    /** Returning home, another day or month, the refresh icon, or changed calendar data: reload. */
    fun refresh() {
        catchUpWithToday()
        firstDay = firstDayOfWeek()
        if (!events.hasPermission()) {
            generation++
            byDay = emptyMap()
            loadedMonth = null
            render()
            return
        }
        val m = month
        val grid = CalendarModel.monthGrid(m, firstDay)
        val gen = ++generation
        val (from, to) = CalendarModel.queryRange(grid.first(), grid.last(), zone)
        events.load(from, to) { list ->
            if (gen != generation) return@load // a newer month or refresh is on its way
            byDay = CalendarModel.byDay(list, grid, zone)
            loadedMonth = m
            render()
        }
        render()
    }

    /** The minute changed: the live labels move on (and past midnight, the day does too). */
    fun tick() {
        if (todayNow() != today) refresh() else renderAgenda()
    }

    private fun catchUpWithToday() {
        val now = todayNow()
        if (now == today) return
        today = now
        if (followingToday) {
            selected = now
            month = YearMonth.from(now)
        }
    }

    private fun userRefresh() {
        refreshButton.animate().rotationBy(360f).setDuration(400).start()
        refresh()
    }

    /** One month back (-1) or on (+1): today, if that month holds it, else its 1st, is selected. */
    fun shiftMonth(by: Int) {
        month = month.plusMonths(by.toLong())
        selected = if (YearMonth.from(today) == month) today else month.atDay(1)
        followingToday = selected == today
        refresh()
    }

    fun goToToday() {
        catchUpWithToday()
        followingToday = true
        selected = today
        month = YearMonth.from(today)
        refresh()
    }

    /** Selects [day] (a dimmed one from a neighbouring month too): the agenda shows it. */
    fun tapDay(day: LocalDate) {
        selected = day
        followingToday = day == today
        refresh()
    }

    private fun openDay(day: LocalDate) {
        Actions.openCalendarAt(context, day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
    }

    /** A tapped event: its detail view (never a dead end); without actions, the calendar app. */
    private fun open(e: DayEvent) {
        val a = actions
        if (a != null) a.details(e) else Actions.openEvent(context, e.eventId, e.begin, e.end)
    }

    // ------------------------------------------------------------------ drawing

    private fun render() {
        renderMonth()
        renderAgenda()
    }

    private fun renderMonth() {
        val grid = CalendarModel.monthGrid(month, firstDay)
        val allowed = events.hasPermission()
        val loaded = loadedMonth == month
        monthTitle.text = CalendarModel.monthLabel(month, locale)
        monthTitle.contentDescription = context.getString(R.string.cal_month_desc, monthTitle.text)
        for ((i, d) in CalendarModel.weekdays(firstDay).withIndex()) {
            (weekdays.getChildAt(i) as TextView).text = d.getDisplayName(TextStyle.NARROW_STANDALONE, locale)
        }
        for (r in 0 until 6) weeks.getChildAt(r).visibility = if (r * 7 < grid.size) VISIBLE else GONE
        for ((i, v) in dayViews.withIndex()) {
            val d = grid.getOrNull(i)
            if (d == null) {
                v.clear()
                continue
            }
            val count = if (allowed && loaded) byDay[d]?.size ?: 0 else 0
            v.bind(d, today = d == today, selected = d == selected, dimmed = CalendarModel.dimmed(d, month), events = count)
        }
    }

    /** The selected day's events, their live labels worked out now. */
    fun renderAgenda() {
        val allowed = events.hasPermission()
        val loaded = loadedMonth == month
        dayTitle.text = CalendarModel.dayLabel(selected, locale)
        permissionRow.visibility = if (allowed) GONE else VISIBLE
        refreshButton.visibility = if (allowed) VISIBLE else INVISIBLE
        val list = if (allowed && loaded) byDay[selected].orEmpty() else emptyList()
        emptyView.visibility = if (allowed && loaded && list.isEmpty()) VISIBLE else GONE
        scroll.visibility = if (list.isEmpty()) GONE else VISIBLE
        val now = clock()
        eventList.removeAllViews()
        for (e in list) eventList.addView(eventRow(e, now))
        onContentChanged?.invoke(this)
    }

    /** What an event's right-hand label says: one or two lines. */
    fun labelLines(e: DayEvent, nowMs: Long): Pair<String, String?> {
        fun day(r: Rel): String = when (r) {
            Rel.Today -> context.getString(R.string.cal_rel_today)
            Rel.Tomorrow -> context.getString(R.string.cal_rel_tomorrow)
            is Rel.InDays -> resources.getQuantityString(R.plurals.cal_rel_in_days, r.days.toInt(), r.days.toInt())
            else -> ""
        }
        return when (val r = CalendarModel.relative(e, nowMs, zone)) {
            is Rel.Now -> context.getString(R.string.cal_rel_now) to when {
                r.minutesLeft < 60 -> context.getString(R.string.cal_rel_min_left, r.minutesLeft.toInt())
                r.minutesLeft % 60 == 0L -> context.getString(R.string.cal_rel_hr_left, (r.minutesLeft / 60).toInt())
                else -> context.getString(R.string.cal_rel_hr_min_left, (r.minutesLeft / 60).toInt(), (r.minutesLeft % 60).toInt())
            }
            is Rel.InMinutes -> context.getString(R.string.cal_rel_in_min, r.minutes.toInt()) to null
            is Rel.InHours -> context.getString(R.string.cal_rel_in_hr, r.hours.toInt()) to null
            Rel.Ended -> context.getString(R.string.cal_rel_ended) to null
            is Rel.AllDay -> context.getString(R.string.cal_all_day) to day(r.day)
            else -> day(r) to null
        }
    }

    private fun eventRow(e: DayEvent, now: Long): View {
        val rel = CalendarModel.relative(e, now, zone)
        val ended = rel == Rel.Ended
        val (label, sub) = labelLines(e, now)
        val name = e.title.ifBlank { context.getString(R.string.cal_no_title) }
        val range = CalendarModel.timeRange(e) { DateFormat.getTimeFormat(context).format(it) }
        val xs = px(R.dimen.space_xs)
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            setPadding(xs, xs, xs, xs)
            background = ripple(borderless = false)
            alpha = if (ended) ENDED_ALPHA else 1f
            tag = rel
            setTag(R.id.calendar_event, e)
            contentDescription = listOfNotNull(name, range, e.detail.ifBlank { null }, label, sub).joinToString(", ")
            setOnClickListener { open(e) }
            val dot = px(R.dimen.size_cal_color)
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (e.color != 0) (e.color or 0xFF000000.toInt()) else color(R.color.gold))
                }
            }, LayoutParams(dot, dot).apply { topMargin = px(R.dimen.space_s) - dot / 4 })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(TextView(context).apply {
                    type(Type.BODY)
                    setTextColor(color(R.color.on_surface))
                    text = name
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (range != null) addView(caption(range, R.color.on_surface_dim))
                if (e.detail.isNotBlank()) addView(caption(e.detail, R.color.on_surface_dim))
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(R.dimen.space_s) })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.END
                addView(TextView(context).apply {
                    if (rel is Rel.Now) {
                        type(Type.BODY)
                        typeface = Typeface.create(typeface, Typeface.BOLD)
                        setTextColor(color(R.color.gold))
                    } else {
                        type(Type.CAPTION)
                        setTextColor(color(if (ended) R.color.on_surface_faint else R.color.on_surface))
                    }
                    text = label
                    isSingleLine = true
                    gravity = Gravity.END
                })
                if (sub != null) addView(caption(sub, R.color.on_surface_dim).apply { gravity = Gravity.END })
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = px(R.dimen.space_s) })
        }
    }

    private fun caption(text: String, colorToken: Int) = TextView(context).apply {
        type(Type.CAPTION)
        setTextColor(color(colorToken))
        this.text = text
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun iconButton(icon: Int, desc: Int, flip: Boolean, click: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        if (flip) scaleX = -1f
        imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.on_surface))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = (px(R.dimen.size_cal_chevron) - px(R.dimen.size_cal_icon)) / 2
        setPadding(pad, pad, pad, pad)
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
    private var downRawX = 0f
    private var downRawY = 0f
    private var longPressed = false
    private val longPressCheck = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val a = actions
        val event = if (a != null) eventRowAt(downRawX, downRawY) else null
        val day = if (a != null && event == null) dayAt(downRawX, downRawY) else null
        when {
            event != null -> eventMenu(event)
            day != null -> dayMenu(day)
            else -> onLongPress?.invoke()
        }
    }

    private fun under(v: View, rawX: Float, rawY: Float): Boolean {
        val r = android.graphics.Rect()
        return v.isShown && v.getGlobalVisibleRect(r) && r.contains(rawX.toInt(), rawY.toInt())
    }

    /** The grid's day under a screen point, if any. */
    private fun dayAt(rawX: Float, rawY: Float): DayView? = dayViews.firstOrNull { it.day != null && under(it, rawX, rawY) }

    /** The agenda row under a screen point, if any. */
    private fun eventRowAt(rawX: Float, rawY: Float): View? =
        (0 until eventList.childCount).map(eventList::getChildAt).firstOrNull { it.getTag(R.id.calendar_event) != null && under(it, rawX, rawY) }

    /** The menu a long press opened last (tests). */
    var lastMenu: android.widget.PopupMenu? = null
        private set

    /** A long-pressed day: "Add event" (on that day), or the usual widget arranging. */
    private fun dayMenu(v: DayView) {
        val d = v.day ?: return
        lastMenu = android.widget.PopupMenu(context, v).apply {
            menu.add(0, MENU_ADD, 0, R.string.cal_add_event)
            menu.add(0, MENU_ARRANGE, 1, R.string.cal_arrange_widgets)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ADD -> { tapDay(d); actions?.add(d) }
                    else -> actions?.arrange()
                }
                true
            }
            show()
        }
    }

    /** A long-pressed event: its details, Edit, Delete. */
    private fun eventMenu(row: View) {
        val e = row.getTag(R.id.calendar_event) as? DayEvent ?: return
        lastMenu = android.widget.PopupMenu(context, row).apply {
            menu.add(0, MENU_DETAILS, 0, R.string.cal_event_details)
            menu.add(0, MENU_EDIT, 1, R.string.cal_event_edit)
            menu.add(0, MENU_DELETE, 2, R.string.cal_event_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_DETAILS -> actions?.details(e)
                    MENU_EDIT -> actions?.edit(e)
                    MENU_DELETE -> actions?.delete(e)
                }
                true
            }
            show()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x
                downY = ev.y
                downRawX = ev.rawX
                downRawY = ev.rawY
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

    // ------------------------------------------------------------------ the agenda header

    /**
     * The day's title and its icons (+, refresh, gear) in one row; when the agenda half is too
     * narrow for both (a three-cell widget on the unfolded screen), the title gets its own line
     * and the icons sit under it, at the end.
     */
    inner class DayHeader(context: Context) : LinearLayout(context) {
        val icons = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val minTitle = px(R.dimen.size_cal_title_min)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val iconsWidth = (0 until icons.childCount).map(icons::getChildAt).filter { it.visibility != GONE }
                .sumOf { (it.layoutParams as LayoutParams).width }
            val narrow = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && width < minTitle + iconsWidth
            val want = if (narrow) VERTICAL else HORIZONTAL
            if (orientation != want) {
                orientation = want
                getChildAt(0).layoutParams = if (narrow) LayoutParams(LayoutParams.MATCH_PARENT, px(R.dimen.size_cal_header))
                else LayoutParams(0, px(R.dimen.size_cal_header), 1f)
                icons.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = if (narrow) Gravity.END else Gravity.CENTER_VERTICAL
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    // ------------------------------------------------------------------ a day in the grid

    /**
     * One day: its number in a circle that fits the cell (gold disc for today, gold ring when
     * selected), dimmed outside the month, and up to three gold dots for its events.
     */
    inner class DayView(context: Context) : View(context) {
        var day: LocalDate? = null
            private set
        var isToday = false
            private set
        var isSelectedDay = false
            private set
        var dimmed = false
            private set
        var dots = 0
            private set

        private val maxCircle = pxF(R.dimen.size_cal_day)
        private val dotR = pxF(R.dimen.size_cal_dot) / 2
        private val ring = pxF(R.dimen.stroke_hairline)
        private val bodySize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = lexend }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.color(R.color.gold) }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = ring; color = context.color(R.color.gold)
        }

        init {
            isClickable = true
            isFocusable = true
            background = ripple(borderless = true)
        }

        fun bind(d: LocalDate, today: Boolean, selected: Boolean, dimmed: Boolean, events: Int) {
            day = d
            isToday = today
            isSelectedDay = selected
            this.dimmed = dimmed
            dots = CalendarModel.dots(events)
            isSelected = selected
            visibility = VISIBLE
            val date = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
            val label = if (today) context.getString(R.string.cal_today, date) else date
            contentDescription = if (events > 0) resources.getQuantityString(R.plurals.cal_day_events, events, label, events) else label
            invalidate()
        }

        fun clear() {
            day = null
            visibility = INVISIBLE
        }

        override fun onDraw(canvas: Canvas) {
            val d = day ?: return
            val dotsH = dotR * 2 + dotR * 1.5f
            val circle = min(maxCircle, min(width.toFloat(), height - dotsH) - ring * 2).coerceAtLeast(0f)
            val cx = width / 2f
            val cy = (height - dotsH) / 2f
            val r = circle / 2
            if (isToday) canvas.drawCircle(cx, cy, r, fill)
            if (isSelectedDay) canvas.drawCircle(cx, cy, if (isToday) r + ring * 1.5f else r - ring / 2, outline)
            textPaint.textSize = min(bodySize, circle * 0.5f)
            textPaint.typeface = if (isToday || isSelectedDay) Typeface.create(lexend, Typeface.BOLD) else lexend
            textPaint.color = context.color(when {
                isToday -> R.color.on_gold
                dimmed -> R.color.on_surface_faint
                else -> R.color.on_surface
            })
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(d.dayOfMonth.toString(), cx, baseline, textPaint)
            if (dots > 0) {
                val gap = dotR * 1.5f
                val total = dots * 2 * dotR + (dots - 1) * gap
                var x = cx - total / 2 + dotR
                val y = cy + r + dotR + dotR * 1.5f
                repeat(dots) {
                    canvas.drawCircle(x, y.coerceAtMost(height - dotR), dotR, fill)
                    x += 2 * dotR + gap
                }
            }
        }
    }

    private companion object {
        const val ENDED_ALPHA = 0.45f
        const val MENU_ADD = 1
        const val MENU_ARRANGE = 2
        const val MENU_DETAILS = 3
        const val MENU_EDIT = 4
        const val MENU_DELETE = 5
    }
}
