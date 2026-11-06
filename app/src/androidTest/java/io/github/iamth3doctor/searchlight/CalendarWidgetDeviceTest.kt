package io.github.iamth3doctor.searchlight

import android.Manifest
import android.app.Activity
import android.app.UiAutomation
import android.content.ContentValues
import android.content.Intent
import android.provider.CalendarContract
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.calendar.DayEvent
import io.github.iamth3doctor.searchlight.calendar.WeekModel
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
import java.time.ZoneId

/**
 * The built-in Calendar widget, as the person uses it: picked from the widget picker, placed at
 * 4 × 2, resized; the week moved with chevrons, a swipe and the header; today and event dots;
 * the agenda for a tapped day, opening an event or the day; no calendar access; fold/unfold; and
 * Focus. Runs folded and unfolded (the workflow runs the suite in both).
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

    /** This week's days: today, a busy day (five events: three dots), and a day with none. */
    private class Week(val today: LocalDate, val busy: LocalDate, val empty: LocalDate, val events: List<DayEvent>)

    private fun fakeWeek(): Week {
        val days = WeekModel.days(WeekModel.weekStart(today, WeekModel.dayOfWeek(android.icu.util.Calendar.getInstance().firstDayOfWeek)))
        val others = days.filter { it != today }
        val busy = others[0]
        val empty = others[1]
        val events = listOf(DayEvent(101, "Design review", at(today, 10), at(today, 11), false, "Work", 0xFF4285F4.toInt())) +
            (0 until 5).map { i -> DayEvent(200L + i, "Busy $i", at(busy, 9 + i), at(busy, 9 + i, 30), false, "Home", 0xFF33B679.toInt()) }
        return Week(today, busy, empty, events)
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
    private fun addCalendar(h: ActivityScenario<MainActivity>): CalendarWidgetView {
        h.onActivity {
            it.widgetController.onActivityResult(WidgetController.REQUEST_PICK, Activity.RESULT_OK,
                Intent().putExtra(WidgetPickerActivity.EXTRA_BUILTIN, BuiltInWidget.CALENDAR.key))
        }
        waitFor("calendar widget on the grid") { on(h) { it.widgetController.calendarViews().any { v -> v.isAttachedToWindow && v.width > 0 } } }
        return on(h) { it.widgetController.calendarViews().single() }
    }

    private fun layout(h: ActivityScenario<MainActivity>): List<GridSpec> = on(h) {
        if (it.findViewById<WidgetGridView>(R.id.widgetGrid).unfolded) app.prefs.widgetGridUnfolded else app.prefs.widgetGrid
    }

    private fun loaded(h: ActivityScenario<MainActivity>, v: CalendarWidgetView) =
        waitFor("events loaded") { on(h) { v.loadedWeek == v.weekStart } }

    /** The middle of [v] as drawn (getLocationOnScreen of a rotated view is its rotated corner). */
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

    private fun cell(v: CalendarWidgetView, day: LocalDate) = v.dayCells.first { it.day == day }

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

    // ---- 1. Picker, size, resize ----

    @Test fun pickerOffersCalendarWhichAddsAtFourByTwoAndResizes() {
        // The picker lists Searchlight's Calendar among the apps' widgets; choosing it returns it.
        val picker = ActivityScenario.launchActivityForResult(WidgetPickerActivity::class.java)
        var row: View? = null
        waitFor("Calendar in the picker") {
            picker.onActivity { a ->
                val list = (a.window.decorView as ViewGroup).let { root ->
                    fun find(x: View): RecyclerView? = x as? RecyclerView ?: (x as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
                    find(root)
                }
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
        val week = fakeWeek()
        app.calendarEvents.eventsOverride = { _, _ -> week.events }
        home { h ->
            val v = addCalendar(h)
            val spec = layout(h).single()
            assertTrue("built-in id: $spec", BuiltInWidget.isBuiltIn(spec.id))
            assertEquals("4 × 2", 4 to 2, spec.colSpan to spec.rowSpan)
            loaded(h, v)
            shot("61-calendar-widget")

            // Resizable both ways, down to its minimum, which still shows the week and agenda.
            val grid = on(h) { it.findViewById<WidgetGridView>(R.id.widgetGrid) }
            assertEquals(true to true, on(h) { it.widgetController.resizable(spec.id) })
            val (minW, minH) = on(h) { it.widgetController.minSpan(spec.id) }
            assertTrue("min span $minW × $minH", minW in 1..4 && minH in 1..2)
            for (size in listOf(minW to minH, 4 to 3, 4 to 2)) {
                h.onActivity { it.widgetController.onCommit(spec.copy(colSpan = size.first, rowSpan = size.second)); it.widgetController.render() }
                // Its cells, less the grid's gap on each side.
                val gap = 2 * app.resources.getDimension(R.dimen.space_s)
                val density = app.resources.displayMetrics.density
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
                // Every text legible: nothing below the caption size.
                val minPx = app.resources.getDimension(R.dimen.text_caption) - 0.5f
                for (t in texts(v)) assertTrue("${t.text} at ${t.textSize}px", t.textSize >= minPx)
                assertTrue("week row shown", on(h) { v.weekRow.isShown && v.weekRow.height > 0 })
                if (size == minW to minH) shot("62-calendar-min-size")
            }
        }
    }

    // ---- 2. Week navigation ----

    @Test fun chevronsSwipeAndHeaderMoveTheWeek() {
        app.calendarEvents.permissionOverride = true
        app.calendarEvents.eventsOverride = { _, _ -> emptyList() }
        home { h ->
            val v = addCalendar(h)
            val thisWeek = on(h) { v.weekStart }
            assertTrue(today in WeekModel.days(thisWeek))
            assertEquals(WeekModel.rangeLabel(thisWeek, app.resources.configuration.locales[0]), on(h) { v.title.text.toString() })

            tap(v.nextButton)
            assertEquals(thisWeek.plusWeeks(1), on(h) { v.weekStart })
            assertEquals(WeekModel.rangeLabel(thisWeek.plusWeeks(1), app.resources.configuration.locales[0]), on(h) { v.title.text.toString() })
            tap(v.prevButton)
            tap(v.prevButton)
            assertEquals(thisWeek.minusWeeks(1), on(h) { v.weekStart })

            // A swipe left across the days: one week on; right: one back.
            fun swipe(leftward: Boolean) {
                val loc = IntArray(2)
                var w = 0
                var y = 0
                main { v.weekRow.getLocationOnScreen(loc); w = v.weekRow.width; y = loc[1] + v.weekRow.height / 2 }
                val (a, b) = (loc[0] + w * 8 / 10) to (loc[0] + w * 2 / 10)
                if (leftward) shell("input swipe $a $y $b $y 200") else shell("input swipe $b $y $a $y 200")
                Thread.sleep(600)
            }
            swipe(leftward = true)
            assertEquals(thisWeek, on(h) { v.weekStart })
            swipe(leftward = true)
            assertEquals(thisWeek.plusWeeks(1), on(h) { v.weekStart })
            swipe(leftward = false)
            swipe(leftward = false)
            assertEquals(thisWeek.minusWeeks(1), on(h) { v.weekStart })
            // A swipe on the widget is the widget's: no home gesture, no edit mode.
            assertFalse(on(h) { it.findViewById<WidgetGridView>(R.id.widgetGrid).editMode })

            // Days outside the week's starting month are dimmed (if this week crosses one).
            val dims = on(h) { v.dayCells.map { c -> WeekModel.dimmed(c.day!!, v.weekStart) to c.dimmed } }
            for ((want, got) in dims) assertEquals(want, got)

            // The header: straight back to this week, today selected.
            tap(v.nextButton)
            tap(v.nextButton)
            tap(v.title)
            assertEquals(thisWeek, on(h) { v.weekStart })
            assertEquals(today, on(h) { v.selected })
        }
    }

    // ---- 3. Today, dots, agenda ----

    @Test fun todayDotsAndTheAgendaFollowTheTappedDay() {
        app.calendarEvents.permissionOverride = true
        val week = fakeWeek()
        app.calendarEvents.eventsOverride = { _, _ -> week.events }
        home { h ->
            val v = addCalendar(h)
            loaded(h, v)
            assertTrue("today highlighted", on(h) { cell(v, today).isToday })
            assertEquals(1, on(h) { v.dayCells.count { c -> c.isToday } })
            // Dots only where there are events: one today, three (the cap) for five.
            val dots = on(h) { v.dayCells.associate { c -> c.day!! to c.dots } }
            assertEquals(1, dots[today])
            assertEquals(3, dots[week.busy])
            for ((d, n) in dots) if (d != today && d != week.busy) assertEquals("$d", 0, n)
            // Today is selected at first: its event in the agenda.
            assertEquals(today, on(h) { v.selected })
            assertEquals(1, on(h) { v.eventList.childCount })
            assertTrue(strings(v.eventList).toString(), "Design review" in strings(v.eventList))
            assertTrue("Work" in strings(v.eventList))

            tap(cell(v, week.busy))
            assertEquals(week.busy, on(h) { v.selected })
            assertEquals(5, on(h) { v.eventList.childCount })
            assertEquals(listOf("Busy 0", "Busy 1", "Busy 2", "Busy 3", "Busy 4"),
                strings(v.eventList).filter { it.startsWith("Busy") })
            shot("63-calendar-busy-day")

            // An event opens in the calendar app.
            Actions.lastCalendarUri = null
            on(h) { v.eventList.getChildAt(0).performClick() }
            assertEquals(
                android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 200L),
                Actions.lastCalendarUri,
            )
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(1500)

            // A day with nothing: "No events"; tapping it (again) opens the calendar on that day.
            tap(cell(v, week.empty))
            assertEquals(week.empty, on(h) { v.selected })
            assertTrue(on(h) { v.emptyView.isShown && v.eventList.childCount == 0 })
            assertEquals(app.getString(R.string.cal_no_events), on(h) { v.emptyView.text.toString() })
            shot("64-calendar-empty-day")
            Actions.lastCalendarUri = null
            tap(cell(v, week.empty))
            val uri = Actions.lastCalendarUri
            assertNotNull("calendar opened on the day", uri)
            val ms = uri!!.lastPathSegment!!.toLong()
            assertEquals(week.empty, java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())
            shell("input keyevent KEYCODE_HOME")
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
            val cr = app.contentResolver
            calId = cr.insert(calUri, ContentValues().apply {
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
                val v = addCalendar(h)
                loaded(h, v)
                val before = on(h) { v.byDay[today].orEmpty().size }
                // Added while the widget is showing: calendar data changed, the widget follows.
                automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                try {
                    app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calId)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.DTSTART, at(today, 23, 0))
                        put(CalendarContract.Events.DTEND, at(today, 23, 30))
                        put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                    })
                } finally {
                    automation.dropShellPermissionIdentity()
                }
                val seen = app.calendarEvents.changesSeen
                try {
                    waitFor("new event on today", 15_000) { on(h) { v.byDay[today].orEmpty().any { it.title == title } } }
                } catch (e: AssertionError) {
                    val (from, to) = WeekModel.queryRange(on(h) { v.weekStart }, zone)
                    val direct = runCatching { app.calendarEvents.queryNow(from, to).map { it.title } }
                    throw AssertionError("not shown: watching=${app.calendarEvents.watching} " +
                        "changes ${seen}->${app.calendarEvents.changesSeen}, widget today=${on(h) { v.byDay[today].orEmpty().map { it.title } }}, " +
                        "direct query=$direct, loaded=${on(h) { v.loadedWeek }} week=${on(h) { v.weekStart }}")
                }
                assertEquals(before + 1, on(h) { v.byDay[today].orEmpty().size })
                if (on(h) { v.selected } != today) tap(v.title)
                val row = on(h) { (0 until v.eventList.childCount).map(v.eventList::getChildAt).first { r -> r.contentDescription?.contains(title) == true } }
                assertTrue(strings(row).toString(), "Widget test" in strings(row))
                shot("65-calendar-real-event")
                tap(row)
                waitFor("calendar app opened", 20_000) { "calendar" in shell("dumpsys activity activities").lineSequence().firstOrNull { "ResumedActivity" in it }.orEmpty().lowercase() }
            }
        } finally {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CALENDAR)
            runCatching { app.contentResolver.delete(calUri, "${CalendarContract.Calendars._ID}=?", arrayOf(calId.toString())) }
            automation.dropShellPermissionIdentity()
        }
    }

    // ---- 4. Calendar access ----

    @Test fun withoutAccessTheGrantRowShowsAndGrantingLoadsEvents() {
        // Revoking for real would kill the app (and this test with it): stand in for "denied".
        app.calendarEvents.permissionOverride = false
        val week = fakeWeek()
        app.calendarEvents.eventsOverride = { _, _ -> week.events }
        main { app.prefs.calendarPermissionAsked = false }
        home { h ->
            val v = addCalendar(h)
            assertTrue("grant row", on(h) { v.permissionRow.isShown && v.allowButton.isShown })
            assertFalse(on(h) { v.emptyView.isShown })
            assertEquals(0, on(h) { v.eventList.childCount })
            assertTrue("no dots without access", on(h) { v.dayCells.all { c -> c.dots == 0 } })
            assertTrue("the week still works", on(h) { v.weekRow.isShown })
            assertTrue(app.getString(R.string.cal_permission) in strings(v.permissionRow))
            shot("66-calendar-permission")

            // "Allow" asks Android; still not granted (here: the stand-in), the row stays and nothing breaks.
            val asked = app.calendarEvents.permissionRequests
            tap(v.allowButton)
            waitFor("permission requested") { app.calendarEvents.permissionRequests == asked + 1 }
            Thread.sleep(1500) // the request's result comes back (granted underneath, so no dialog)
            if ("GrantPermissions" in shell("dumpsys activity activities")) shell("input keyevent KEYCODE_BACK")
            waitFor("home again") { on(h) { it.hasWindowFocus() } }
            assertTrue(on(h) { v.permissionRow.isShown })
            val id = layout(h).single().id

            // Granted: the same widget loads its events, nothing re-added.
            app.calendarEvents.permissionOverride = null
            h.onActivity { it.onRequestPermissionsResult(WidgetController.REQUEST_CALENDAR_PERMISSION,
                arrayOf(Manifest.permission.READ_CALENDAR), intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED)) }
            loaded(h, v)
            assertFalse(on(h) { v.permissionRow.isShown })
            assertEquals(1, on(h) { cell(v, today).dots })
            assertEquals(1, on(h) { v.eventList.childCount })
            assertSame(v, on(h) { it.widgetController.calendarViews().single() })
            assertEquals(id, layout(h).single().id)
        }
    }

    // ---- 5. Fold / unfold, Focus ----

    @Test fun foldingKeepsItPlacedAndFocusHidesIt() {
        app.calendarEvents.permissionOverride = true
        val week = fakeWeek()
        app.calendarEvents.eventsOverride = { _, _ -> week.events }
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
            assertTrue("still shows its week", on(h) { v.weekRow.height > 0 && v.dayCells.all { c -> c.day != null } })

            h.onActivity { it.widgetController.posture = back }
            waitFor("back") { on(h) { grid.measuredUnfolded == startedUnfolded && v.isAttachedToWindow } }
            Thread.sleep(500)
            assertEquals("its place here untouched", here, layout(h).single())

            // Focus hides it like every widget; off, it's back.
            main { app.prefs.focusMode = true }
            waitFor("hidden in Focus") { on(h) { v.visibility != View.VISIBLE } }
            main { app.prefs.focusMode = false }
            waitFor("back after Focus") { on(h) { v.visibility == View.VISIBLE } }
            assertEquals(here, layout(h).single())
        }
    }
}
