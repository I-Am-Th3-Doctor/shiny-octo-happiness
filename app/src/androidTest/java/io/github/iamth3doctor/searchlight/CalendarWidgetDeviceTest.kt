package io.github.iamth3doctor.searchlight

import android.Manifest
import android.app.Activity
import android.app.UiAutomation
import android.content.ContentValues
import android.content.Intent
import android.provider.CalendarContract
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.calendar.CalendarModel
import io.github.iamth3doctor.searchlight.calendar.DayEvent
import io.github.iamth3doctor.searchlight.ui.Actions
import io.github.iamth3doctor.searchlight.ui.Posture
import io.github.iamth3doctor.searchlight.widgets.BuiltInWidget
import io.github.iamth3doctor.searchlight.widgets.CalendarWidgetView
import io.github.iamth3doctor.searchlight.widgets.GridSpec
import io.github.iamth3doctor.searchlight.widgets.WidgetController
import io.github.iamth3doctor.searchlight.widgets.WidgetGridView
import io.github.iamth3doctor.searchlight.widgets.WidgetPickerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The built-in Calendar widget (month grid | day agenda), as the person uses it: picked from the
 * widget picker, placed at 4 × 3, resized (stacked below three cells wide); months moved with
 * the chevrons and the month name; a tapped day's agenda with live labels; opening an event or
 * the day; the refresh icon; no calendar access; fold/unfold; and Focus. Runs folded and
 * unfolded (the workflow runs the suite in both).
 */
@RunWith(AndroidJUnit4::class)
class CalendarWidgetDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val zone = ZoneId.systemDefault()

    private var savedFolded = emptyList<GridSpec>()
    private var savedUnfolded = emptyList<GridSpec>()

    private fun shell(cmd: String): String =
        automation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> on(home: ActivityScenario<MainActivity>, read: (MainActivity) -> T): T {
        var v: T? = null
        home.onActivity { v = read(it) }
        @Suppress("UNCHECKED_CAST") return v as T
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out: $what")
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(800)
        shell("screencap -p $dir/${prefix}_font1.0_$name.png")
    }

    @Before fun setUp() {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        shell("pm grant $pkg ${Manifest.permission.READ_CALENDAR}")
        main {
            savedFolded = app.prefs.widgetGrid
            savedUnfolded = app.prefs.widgetGridUnfolded
            app.prefs.widgetGrid = emptyList()
            app.prefs.widgetGridUnfolded = emptyList()
            app.prefs.focusMode = false
        }
    }

    @After fun restore() {
        Actions.startCalendar = true
        app.calendarEvents.permissionOverride = null
        app.calendarEvents.eventsOverride = null
        main {
            app.prefs.focusMode = false
            app.prefs.widgetGrid = savedFolded
            app.prefs.widgetGridUnfolded = savedUnfolded
        }
        shell("input keyevent KEYCODE_HOME")
    }

    // ---- helpers ----

    private val today get() = LocalDate.now(zone)
    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    /** Today at 2:05 PM: a fixed "now" for the live labels. */
    private val now get() = at(today, 14, 5)

    /**
     * Today: a meeting running now, two later (in 35 min, in 2 hr), one this morning (ended), an
     * all-day one; tomorrow one; in four days one; and one other day with five (three dots).
     */
    private fun fakeEvents(): List<DayEvent> {
        val t = today
        return listOf(
            DayEvent(101, "Standup", at(t, 14, 0), at(t, 14, 30), false, "Work", 0xFF4285F4.toInt(), "Room 4"),
            DayEvent(102, "Design review", at(t, 14, 40), at(t, 15, 10), false, "Work", 0xFF4285F4.toInt()),
            DayEvent(103, "Dinner", at(t, 16, 50), at(t, 18, 0), false, "Home", 0xFF33B679.toInt(), "Luigi's"),
            DayEvent(104, "Gym", at(t, 7, 0), at(t, 8, 0), false, "Home", 0xFF33B679.toInt()),
            DayEvent(105, "Holiday", utc(t), utc(t.plusDays(1)), true, "Holidays", 0xFFE67C73.toInt()),
            DayEvent(201, "Flight", at(t.plusDays(1), 9, 0), at(t.plusDays(1), 11, 0), false, "Travel", 0xFFF6BF26.toInt(), "UA 123"),
            DayEvent(301, "Dentist", at(t.plusDays(4), 10, 0), at(t.plusDays(4), 11, 0), false, "Home", 0xFF33B679.toInt()),
        ) + (0 until 5).map { i -> DayEvent(400L + i, "Busy $i", at(busyDay(), 9 + i), at(busyDay(), 9 + i, 30), false, "Home", 0xFF33B679.toInt()) }
    }

    private fun utc(d: LocalDate) = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    /** A day this month (not today, tomorrow or four days on) for the five-event day. */
    private fun busyDay(): LocalDate {
        val m = YearMonth.from(today)
        return (1..m.lengthOfMonth()).map(m::atDay).first { it != today && it != today.plusDays(1) && it != today.plusDays(4) }
    }

    /** A day this month with no events. */
    private fun emptyDay(): LocalDate {
        val m = YearMonth.from(today)
        val taken = setOf(today, today.plusDays(1), today.plusDays(4), busyDay())
        return (1..m.lengthOfMonth()).map(m::atDay).first { it !in taken }
    }

    private fun home(block: (ActivityScenario<MainActivity>) -> Unit) {
        val s = ActivityScenario.launch(MainActivity::class.java)
        try {
            Thread.sleep(1500)
            block(s)
        } finally {
            runCatching { s.close() }
        }
    }

    /** What the picker hands back for "Calendar", delivered the way the picker delivers it. */
    private fun addCalendar(h: ActivityScenario<MainActivity>, fixedNow: Boolean = true): CalendarWidgetView {
        h.onActivity {
            it.widgetController.onActivityResult(WidgetController.REQUEST_PICK, Activity.RESULT_OK,
                Intent().putExtra(WidgetPickerActivity.EXTRA_BUILTIN, BuiltInWidget.CALENDAR.key))
        }
        waitFor("calendar widget on the grid") { on(h) { it.widgetController.calendarViews().any { v -> v.isAttachedToWindow && v.width > 0 } } }
        val v = on(h) { it.widgetController.calendarViews().single() }
        if (fixedNow) h.onActivity { v.clock = { now }; v.goToToday() }
        return v
    }

    private fun layout(h: ActivityScenario<MainActivity>): List<GridSpec> = on(h) {
        if (it.findViewById<WidgetGridView>(R.id.widgetGrid).unfolded) app.prefs.widgetGridUnfolded else app.prefs.widgetGrid
    }

    private fun loaded(h: ActivityScenario<MainActivity>, v: CalendarWidgetView) =
        waitFor("events loaded") { on(h) { v.loadedMonth == v.month } }

    /** The middle of [v] as drawn (getLocationOnScreen of a flipped view is its flipped corner). */
    private fun center(v: View): Pair<Int, Int> {
        val r = android.graphics.Rect()
        var out = 0 to 0
        main { v.getGlobalVisibleRect(r); out = r.centerX() to r.centerY() }
        return out
    }

    private fun tap(v: View) {
        val (x, y) = center(v)
        shell("input tap $x $y")
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
    }

    private fun dayView(h: ActivityScenario<MainActivity>, v: CalendarWidgetView, d: LocalDate) =
        on(h) { v.dayViews.first { it.day == d } }

    /** Taps [d] in the grid, moving on a month first if this one doesn't show it. */
    private fun select(h: ActivityScenario<MainActivity>, v: CalendarWidgetView, d: LocalDate) {
        if (on(h) { v.dayViews.none { it.day == d } }) tap(v.nextButton)
        tap(dayView(h, v, d))
        assertEquals(d, on(h) { v.selected })
    }

    /** The shown texts under [root] (call on the main thread). */
    private fun collectTexts(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(x: View) {
            if (x is TextView && x.isShown) out += x
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun texts(root: View): List<TextView> {
        var out = emptyList<TextView>()
        main { out = collectTexts(root) }
        return out
    }

    private fun strings(root: View) = texts(root).map { it.text.toString() }

    /** The agenda's rows: each row's texts, in order. */
    private fun rows(h: ActivityScenario<MainActivity>, v: CalendarWidgetView): List<List<String>> = on(h) {
        (0 until v.eventList.childCount).map { i -> collectTexts(v.eventList.getChildAt(i)).map { t -> t.text.toString() } }
    }

    private fun row(h: ActivityScenario<MainActivity>, v: CalendarWidgetView, title: String): List<String> =
        rows(h, v).firstOrNull { it.firstOrNull() == title } ?: throw AssertionError("no row $title in ${rows(h, v)}")

    private fun time(h: ActivityScenario<MainActivity>, ms: Long): String = on(h) { android.text.format.DateFormat.getTimeFormat(it).format(ms) }

    // ---- 1. Picker, size, resize, split / stacked ----

    @Test fun pickerOffersCalendarWhichAddsAtFourByThreeAndStacksWhenNarrow() {
        val picker = ActivityScenario.launchActivityForResult(WidgetPickerActivity::class.java)
        var row: View? = null
        waitFor("Calendar in the picker") {
            picker.onActivity { a ->
                fun find(x: View): RecyclerView? = x as? RecyclerView ?: (x as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
                val list = find(a.window.decorView)
                row = list?.let { l -> (0 until l.childCount).map(l::getChildAt).firstOrNull { r -> collectTexts(r).any { it.text.toString() == a.getString(R.string.widget_calendar) } } }
            }
            row != null
        }
        shot("60-calendar-picker")
        picker.onActivity { row!!.performClick() }
        val result = picker.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertEquals(BuiltInWidget.CALENDAR.key, result.resultData.getStringExtra(WidgetPickerActivity.EXTRA_BUILTIN))

        app.calendarEvents.permissionOverride = true
        app.calendarEvents.eventsOverride = { _, _ -> fakeEvents() }
        home { h ->
            val v = addCalendar(h)
            val spec = layout(h).single()
            assertTrue("built-in id: $spec", BuiltInWidget.isBuiltIn(spec.id))
            assertEquals("4 × 3", 4 to 3, spec.colSpan to spec.rowSpan)
            loaded(h, v)
            assertFalse("split at 4 wide", on(h) { v.stacked })
            assertEquals(LinearLayout.HORIZONTAL, on(h) { v.orientation })
            shot("61-calendar-widget")

            val grid = on(h) { it.findViewById<WidgetGridView>(R.id.widgetGrid) }
            assertEquals(true to true, on(h) { it.widgetController.resizable(spec.id) })
            val (minW, minH) = on(h) { it.widgetController.minSpan(spec.id) }
            assertTrue("min span $minW × $minH", minW in 1..2 && minH in 1..3)
            val gap = 2 * app.resources.getDimension(R.dimen.space_s)
            val density = app.resources.displayMetrics.density
            for (size in listOf(3 to 3, 2 to 3, minW to minH, 4 to 4, 4 to 3)) {
                h.onActivity { it.widgetController.onCommit(spec.copy(colSpan = size.first, rowSpan = size.second)); it.widgetController.render() }
                waitFor("resized to $size") {
                    on(h) {
                        val wantW = grid.cellWidthDp * density * size.first - gap
                        val wantH = grid.cellHeightDp * density * size.second - gap
                        kotlin.math.abs(v.width - wantW) <= 2 && kotlin.math.abs(v.height - wantH) <= 2
                    }
                }
                Thread.sleep(400)
                assertEquals(size, layout(h).single().let { it.colSpan to it.rowSpan })
                assertSame("same widget, not a new one", v, on(h) { it.widgetController.calendarViews().single() })
                // Below three cells wide it stacks (month over agenda); from three it splits.
                val narrow = size.first < 3
                assertEquals("$size stacked", narrow, on(h) { v.stacked })
                assertEquals(if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL, on(h) { v.orientation })
                // Every day still shown and every text legible: nothing below the caption size.
                assertTrue("month grid shown at $size", on(h) { v.dayViews.filter { it.day != null }.all { d -> d.width > 0 && d.height > 0 } })
                assertTrue("agenda shown at $size", on(h) { v.dayTitle.isShown && v.dayTitle.width > 0 })
                val minPx = app.resources.getDimension(R.dimen.text_caption) - 0.5f
                for (t in texts(v)) assertTrue("${t.text} at ${t.textSize}px", t.textSize >= minPx)
                if (size == 2 to 3) shot("62-calendar-stacked")
            }
        }
    }

    // ---- 2. Month navigation and days ----

    @Test fun monthChevronsTheMonthNameAndTappingADay() {
        app.calendarEvents.permissionOverride = true
        app.calendarEvents.eventsOverride = { _, _ -> fakeEvents() }
        home { h ->
            val v = addCalendar(h)
            loaded(h, v)
            val thisMonth = YearMonth.from(today)
            val locale = app.resources.configuration.locales[0]
            assertEquals(CalendarModel.monthLabel(thisMonth, locale), on(h) { v.monthTitle.text.toString() })
            assertEquals(CalendarModel.dayLabel(today, locale), on(h) { v.dayTitle.text.toString() })

            // Today: gold disc; selected (today, at first): the ring; dots where there are events.
            assertTrue(dayView(h, v, today).isToday)
            assertEquals(1, on(h) { v.dayViews.count { it.isToday } })
            assertTrue(dayView(h, v, today).isSelectedDay)
            assertEquals(3, dayView(h, v, today).dots) // five events today: capped at three
            assertEquals(3, dayView(h, v, busyDay()).dots)
            assertEquals(0, dayView(h, v, emptyDay()).dots)
            // Leading / trailing days of other months are dimmed, this month's aren't.
            val dims = on(h) { v.dayViews.filter { it.day != null }.map { d -> CalendarModel.dimmed(d.day!!, thisMonth) to d.dimmed } }
            for ((want, got) in dims) assertEquals(want, got)

            // A tapped day: selected (ring moves), the agenda shows it.
            tap(dayView(h, v, busyDay()))
            assertEquals(busyDay(), on(h) { v.selected })
            assertTrue(dayView(h, v, busyDay()).isSelectedDay)
            assertFalse(dayView(h, v, today).isSelectedDay)
            assertEquals(CalendarModel.dayLabel(busyDay(), locale), on(h) { v.dayTitle.text.toString() })
            assertEquals((0 until 5).map { "Busy $it" }, rows(h, v).map { it.first() })

            // Chevrons: on a month, back two; the title follows.
            tap(v.nextButton)
            assertEquals(thisMonth.plusMonths(1), on(h) { v.month })
            assertEquals(CalendarModel.monthLabel(thisMonth.plusMonths(1), locale), on(h) { v.monthTitle.text.toString() })
            tap(v.prevButton)
            tap(v.prevButton)
            assertEquals(thisMonth.minusMonths(1), on(h) { v.month })
            assertEquals(thisMonth.minusMonths(1).atDay(1), on(h) { v.selected })
            assertFalse(on(h) { it.findViewById<WidgetGridView>(R.id.widgetGrid).editMode })

            // The month name: straight back to this month, today selected.
            tap(v.monthTitle)
            assertEquals(thisMonth, on(h) { v.month })
            assertEquals(today, on(h) { v.selected })
            assertTrue(dayView(h, v, today).isSelectedDay)
        }
    }

    // ---- 3. Live labels ----

    @Test fun liveLabelsCountDownAndEndedEventsDim() {
        app.calendarEvents.permissionOverride = true
        app.calendarEvents.eventsOverride = { _, _ -> fakeEvents() }
        home { h ->
            val v = addCalendar(h)
            loaded(h, v)
            // All-day first, then by start: Holiday, Gym, Standup, Design review, Dinner.
            assertEquals(listOf("Holiday", "Gym", "Standup", "Design review", "Dinner"), rows(h, v).map { it.first() })
            shot("63-calendar-labels")

            val standup = row(h, v, "Standup")
            assertTrue("time range: $standup", "${time(h, at(today, 14, 0))} – ${time(h, at(today, 14, 30))}" in standup)
            assertTrue("detail line: $standup", "Room 4" in standup)
            assertTrue("now: $standup", "now" in standup && "25 min left" in standup)
            assertTrue("In 35 min.: ${row(h, v, "Design review")}", "In 35 min." in row(h, v, "Design review"))
            assertTrue("In 2 hr.: ${row(h, v, "Dinner")}", "In 2 hr." in row(h, v, "Dinner") && "Luigi's" in row(h, v, "Dinner"))
            assertTrue("All day: ${row(h, v, "Holiday")}", "All day" in row(h, v, "Holiday") && "Today" in row(h, v, "Holiday"))
            assertTrue("Ended: ${row(h, v, "Gym")}", "Ended" in row(h, v, "Gym"))
            // Ended is dimmed; the rest aren't.
            val alphas = on(h) { (0 until v.eventList.childCount).map { i -> v.eventList.getChildAt(i).let { r -> collectTexts(r).first().text.toString() to r.alpha } } }.toMap()
            assertTrue("Gym dimmed: $alphas", alphas.getValue("Gym") < 1f)
            assertEquals(1f, alphas.getValue("Standup"))

            // A minute ticks by (here: ten): the labels move on without a reload.
            h.onActivity { v.clock = { now + 10 * 60_000L }; it.widgetController.tickCalendars() }
            assertTrue("15 min left: ${row(h, v, "Standup")}", "15 min left" in row(h, v, "Standup"))
            assertTrue("In 25 min.: ${row(h, v, "Design review")}", "In 25 min." in row(h, v, "Design review"))
            h.onActivity { v.clock = { at(today, 14, 31) }; it.widgetController.tickCalendars() }
            assertTrue("Standup ended: ${row(h, v, "Standup")}", "Ended" in row(h, v, "Standup"))
            h.onActivity { v.clock = { now } ; v.renderAgenda() }

            // Tomorrow, and four days on.
            select(h, v, today.plusDays(1))
            assertTrue("Tomorrow: ${row(h, v, "Flight")}", "Tomorrow" in row(h, v, "Flight") && "UA 123" in row(h, v, "Flight"))
            shot("64-calendar-tomorrow")
            tap(v.monthTitle)
            select(h, v, today.plusDays(4))
            assertTrue("In 4 days: ${row(h, v, "Dentist")}", "In 4 days" in row(h, v, "Dentist"))
        }
    }

    // ---- 4. Opening, empty day, refresh ----

    @Test fun openingEventsEmptyDaysAndTheRefreshIcon() {
        app.calendarEvents.permissionOverride = true
        val extra = ArrayList<DayEvent>()
        app.calendarEvents.eventsOverride = { _, _ -> fakeEvents() + extra }
        // What would open is recorded, not started: leaving home mid-test and coming back can
        // hand the test a new activity. (The provider test below opens the real Calendar app.)
        Actions.startCalendar = false
        home { h ->
            val v = addCalendar(h)
            loaded(h, v)

            // An event opens in the calendar app.
            Actions.lastCalendarUri = null
            on(h) { (0 until v.eventList.childCount).map(v.eventList::getChildAt).first { r -> collectTexts(r).first().text == "Standup" }.performClick() }
            assertEquals(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 101L), Actions.lastCalendarUri)

            // Empty agenda space (below the rows) opens the calendar on the day.
            Actions.lastCalendarUri = null
            on(h) { v.eventList.performClick() }
            assertEquals(today, Actions.lastCalendarUri?.lastPathSegment?.toLong()?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() })

            // A day with nothing: "No events"; tapping it opens the calendar there.
            tap(dayView(h, v, emptyDay()))
            assertEquals(emptyDay(), on(h) { v.selected })
            assertTrue(on(h) { v.emptyView.isShown && v.eventList.childCount == 0 })
            assertEquals(app.getString(R.string.cal_no_events), on(h) { v.emptyView.text.toString() })
            shot("65-calendar-empty-day")
            Actions.lastCalendarUri = null
            tap(v.emptyView)
            assertEquals(emptyDay(), Actions.lastCalendarUri?.lastPathSegment?.toLong()?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() })

            // The refresh icon re-reads: an event added since shows up.
            extra += DayEvent(900, "Added later", at(emptyDay(), 12), at(emptyDay(), 13), false, "Work", 0xFF4285F4.toInt())
            assertEquals(0, on(h) { v.eventList.childCount })
            tap(v.refreshButton)
            waitFor("refreshed") { on(h) { v.eventList.childCount == 1 } }
            assertEquals("Added later", rows(h, v).single().first())
            assertEquals(1, dayView(h, v, emptyDay()).dots)

            // The gear opens the calendar app (on the selected day).
            Actions.lastCalendarUri = null
            tap(v.gearButton)
            assertNotNull(Actions.lastCalendarUri)
        }
    }

    /** A real event in a real (local) calendar: read from the provider, opened in the calendar app. */
    @Test fun realEventsFromTheCalendarProvider() {
        val title = "Searchlight widget kiwi"
        val calUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "searchlight-widget-test")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        var calId = -1L
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        try {
            calId = app.contentResolver.insert(calUri, ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, "searchlight-widget-test")
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, "Widget test")
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Widget test")
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFFE67C73.toInt())
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, "searchlight-widget-test")
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            })!!.lastPathSegment!!.toLong()
        } finally {
            automation.dropShellPermissionIdentity()
        }
        try {
            home { h ->
                val v = addCalendar(h, fixedNow = false)
                loaded(h, v)
                val before = on(h) { v.byDay[today].orEmpty().size }
                // Added while the widget is showing: calendar data changed, the widget follows.
                automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                try {
                    app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calId)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.EVENT_LOCATION, "Gate B12")
                        put(CalendarContract.Events.DTSTART, at(today, 23, 0))
                        put(CalendarContract.Events.DTEND, at(today, 23, 30))
                        put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                    })
                } finally {
                    automation.dropShellPermissionIdentity()
                }
                waitFor("new event on today", 15_000) { on(h) { v.byDay[today].orEmpty().any { it.title == title } } }
                assertEquals(before + 1, on(h) { v.byDay[today].orEmpty().size })
                if (on(h) { v.selected } != today) tap(v.monthTitle)
                val r = row(h, v, title)
                assertTrue("detail from the location: $r", "Gate B12" in r)
                shot("66-calendar-real-event")
                val rowView = on(h) { (0 until v.eventList.childCount).map(v.eventList::getChildAt).first { x -> collectTexts(x).first().text == title } }
                tap(rowView)
                waitFor("calendar app opened", 20_000) { "calendar" in shell("dumpsys activity activities").lineSequence().firstOrNull { "ResumedActivity" in it }.orEmpty().lowercase() }
            }
        } finally {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CALENDAR)
            runCatching { app.contentResolver.delete(calUri, "${CalendarContract.Calendars._ID}=?", arrayOf(calId.toString())) }
            automation.dropShellPermissionIdentity()
        }
    }

    // ---- 5. Calendar access ----

    @Test fun withoutAccessTheGrantRowShowsAndGrantingLoadsEvents() {
        // Revoking for real would kill the app (and this test with it): stand in for "denied".
        app.calendarEvents.permissionOverride = false
        app.calendarEvents.eventsOverride = { _, _ -> fakeEvents() }
        main { app.prefs.calendarPermissionAsked = false }
        home { h ->
            val v = addCalendar(h)
            assertTrue("grant row", on(h) { v.permissionRow.isShown && v.allowButton.isShown })
            assertFalse(on(h) { v.emptyView.isShown })
            assertEquals(0, on(h) { v.eventList.childCount })
            assertTrue("no dots without access", on(h) { v.dayViews.all { d -> d.dots == 0 } })
            assertTrue("the month still works", on(h) { v.dayViews.any { it.isToday } })
            assertTrue(app.getString(R.string.cal_permission) in strings(v.permissionRow))
            shot("67-calendar-permission")

            val asked = app.calendarEvents.permissionRequests
            tap(v.allowButton)
            waitFor("permission requested") { app.calendarEvents.permissionRequests == asked + 1 }
            // Granted underneath, so Android answers without a dialog (no Back press: a lingering
            // record of its permission screen once made one close home instead).
            waitFor("home again") { on(h) { it.hasWindowFocus() } }
            assertTrue(on(h) { v.permissionRow.isShown })
            val id = layout(h).single().id

            // Granted: the same widget loads its events, nothing re-added.
            app.calendarEvents.permissionOverride = null
            h.onActivity { it.onRequestPermissionsResult(WidgetController.REQUEST_CALENDAR_PERMISSION,
                arrayOf(Manifest.permission.READ_CALENDAR), intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED)) }
            loaded(h, v)
            assertFalse(on(h) { v.permissionRow.isShown })
            assertEquals(3, dayView(h, v, today).dots)
            assertEquals(5, on(h) { v.eventList.childCount })
            assertSame(v, on(h) { it.widgetController.calendarViews().single() })
            assertEquals(id, layout(h).single().id)
        }
    }

    // ---- 6. Fold / unfold, Focus ----

    @Test fun foldingKeepsItPlacedAndFocusHidesIt() {
        app.calendarEvents.permissionOverride = true
        app.calendarEvents.eventsOverride = { _, _ -> fakeEvents() }
        home { h ->
            val v = addCalendar(h)
            val grid = on(h) { it.findViewById<WidgetGridView>(R.id.widgetGrid) }
            val startedUnfolded = on(h) { grid.unfolded }
            val here = layout(h).single()
            val other = if (startedUnfolded) Posture.FOLDED else Posture.UNFOLDED
            val back = if (startedUnfolded) Posture.UNFOLDED else Posture.FOLDED

            h.onActivity { it.widgetController.posture = other }
            waitFor("laid out in the other posture") { on(h) { grid.measuredUnfolded == !startedUnfolded && v.isAttachedToWindow && v.width > 0 } }
            Thread.sleep(500)
            val there = on(h) { if (grid.unfolded) app.prefs.widgetGridUnfolded else app.prefs.widgetGrid }.single()
            assertEquals(here.id, there.id)
            assertTrue("fits the grid: $there", on(h) { there.inBounds(grid.cols, grid.rows) })
            assertSame(v, on(h) { it.widgetController.calendarViews().single() })
            assertEquals("split or stacked by its width there", there.colSpan < 3, on(h) { v.stacked })
            assertTrue("still shows its month", on(h) { v.dayViews.count { it.day != null } >= 28 })

            h.onActivity { it.widgetController.posture = back }
            waitFor("back") { on(h) { grid.measuredUnfolded == startedUnfolded && v.isAttachedToWindow } }
            Thread.sleep(500)
            assertEquals("its place here untouched", here, layout(h).single())
            assertFalse(on(h) { v.stacked })

            main { app.prefs.focusMode = true }
            waitFor("hidden in Focus") { on(h) { v.visibility != View.VISIBLE } }
            main { app.prefs.focusMode = false }
            waitFor("back after Focus") { on(h) { v.visibility == View.VISIBLE } }
            assertEquals(here, layout(h).single())
        }
    }
}
