package io.github.iamth3doctor.searchlight

import android.Manifest
import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.CalendarContract
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.calendar.DayEvent
import io.github.iamth3doctor.searchlight.calendar.StickyNote
import io.github.iamth3doctor.searchlight.calendar.StickyNote.Ink
import io.github.iamth3doctor.searchlight.ui.Actions
import io.github.iamth3doctor.searchlight.widgets.BuiltInWidget
import io.github.iamth3doctor.searchlight.widgets.GridSpec
import io.github.iamth3doctor.searchlight.widgets.StickyNoteWidgetView
import io.github.iamth3doctor.searchlight.widgets.WidgetController
import io.github.iamth3doctor.searchlight.widgets.WidgetGridView
import io.github.iamth3doctor.searchlight.widgets.WidgetPickerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The Sticky note widget as the person uses it: picked from the widget picker beside the
 * Calendar, placed at a fixed 2 × 2, frosted glass with no color; today's events in time order, the next one bold and
 * underlined, finished ones checked off, "+N more" past three, "Nothing on today" with its sun on
 * a clear day; a tap on a line opens that event and anywhere else today; the paper colour from
 * Settings; and the roll-over at midnight. Runs folded and unfolded.
 */
@RunWith(AndroidJUnit4::class)
class StickyNoteDeviceTest {

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
        app.calendarEvents.permissionOverride = true
        app.calendarEvents.eventsOverride = { _, _ -> fakeEvents() }
    }

    @After fun restore() {
        Actions.startCalendar = true
        app.calendarEvents.permissionOverride = null
        app.calendarEvents.eventsOverride = null
        main {
            app.prefs.widgetGrid = savedFolded
            app.prefs.widgetGridUnfolded = savedUnfolded
        }
        shell("input keyevent KEYCODE_HOME")
    }

    // ---- helpers ----

    private val today get() = LocalDate.now(zone)
    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun utc(d: LocalDate) = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Today at 2:05 PM. */
    private val now get() = at(today, 14, 5)

    /** Today: gym and standup done, a review at 2:40 (next), dinner later; one tomorrow, one yesterday. */
    private fun fakeEvents(): List<DayEvent> {
        val t = today
        return listOf(
            DayEvent(103, "Dinner with Sam", at(t, 18, 30), at(t, 20, 0), false, "Home", 0),
            DayEvent(101, "Team standup", at(t, 9, 0), at(t, 9, 15), false, "Work", 0),
            DayEvent(102, "Design review", at(t, 14, 40), at(t, 15, 10), false, "Work", 0),
            DayEvent(201, "Flight", at(t.plusDays(1), 7, 0), at(t.plusDays(1), 9, 0), false, "Travel", 0),
            DayEvent(99, "Yesterday's", at(t.minusDays(1), 9, 0), at(t.minusDays(1), 10, 0), false, "Work", 0),
        )
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

    /** What the picker hands back for "Sticky note", delivered the way the picker delivers it. */
    private fun addNote(h: ActivityScenario<MainActivity>, clock: Long = now): StickyNoteWidgetView {
        h.onActivity {
            it.widgetController.onActivityResult(WidgetController.REQUEST_PICK, Activity.RESULT_OK,
                Intent().putExtra(WidgetPickerActivity.EXTRA_BUILTIN, BuiltInWidget.STICKY_NOTE.key))
        }
        waitFor("sticky note on the grid") { on(h) { it.widgetController.stickyNoteViews().any { v -> v.isAttachedToWindow && v.width > 0 } } }
        val v = on(h) { it.widgetController.stickyNoteViews().single() }
        h.onActivity { v.clock = { clock }; v.refresh() }
        loaded(h, v)
        return v
    }

    private fun loaded(h: ActivityScenario<MainActivity>, v: StickyNoteWidgetView) {
        val day = on(h) { java.time.Instant.ofEpochMilli(v.clock()).atZone(zone).toLocalDate() }
        waitFor("today's events loaded") { on(h) { v.loadedDay == day } }
        // Drawn once now, so where each line is (for the taps) is current.
        pixels(v).recycle()
    }

    private fun layout(h: ActivityScenario<MainActivity>): List<GridSpec> = on(h) {
        if (it.findViewById<WidgetGridView>(R.id.widgetGrid).unfolded) app.prefs.widgetGridUnfolded else app.prefs.widgetGrid
    }

    private fun time(h: ActivityScenario<MainActivity>, ms: Long): String = on(h) { android.text.format.DateFormat.getTimeFormat(it).format(ms) }

    /** The note as drawn. */
    private fun pixels(v: View, grayscale: Boolean = false): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        main {
            val c = Canvas(b)
            if (grayscale) c.saveLayer(null, io.github.iamth3doctor.searchlight.weatherapp.Monochrome.paint())
            v.draw(c)
            if (grayscale) c.restore()
        }
        return b
    }

    /** Taps the screen at ([x], [y]) in [v]. */
    private fun tapIn(v: View, x: Float, y: Float) {
        val loc = IntArray(2)
        main { v.getLocationOnScreen(loc) }
        shell("input tap ${(loc[0] + x).toInt()} ${(loc[1] + y).toInt()}")
        Thread.sleep(600)
        instrumentation.waitForIdleSync()
    }

    // ---- 1. In the picker, beside the Calendar; placed at a fixed 2 × 2 ----

    @Test fun pickerOffersTheStickyNoteWhichAddsAtAFixedTwoByTwo() {
        val picker = ActivityScenario.launchActivityForResult(WidgetPickerActivity::class.java)
        var row: View? = null
        var calendarRow: View? = null
        waitFor("Sticky note in the picker") {
            picker.onActivity { a ->
                fun find(x: View): RecyclerView? = x as? RecyclerView ?: (x as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
                fun texts(x: View): List<String> = if (x is TextView) listOf(x.text.toString()) else (x as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { texts(g.getChildAt(it)) } }.orEmpty()
                val list = find(a.window.decorView)
                val rows = list?.let { l -> (0 until l.childCount).map(l::getChildAt) }.orEmpty()
                row = rows.firstOrNull { r -> a.getString(R.string.widget_sticky_note) in texts(r) }
                calendarRow = calendarRow ?: rows.firstOrNull { r -> a.getString(R.string.widget_calendar) in texts(r) }
                if (row == null) list?.let { it.scrollBy(0, it.height / 3) } // below the fold: scroll on
            }
            row != null
        }
        assertTrue("the Calendar widget is still offered", calendarRow != null)
        shot("130-sticky-picker")
        picker.onActivity { row!!.performClick() }
        val result = picker.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertEquals(BuiltInWidget.STICKY_NOTE.key, result.resultData.getStringExtra(WidgetPickerActivity.EXTRA_BUILTIN))

        home { h ->
            addNote(h)
            val spec = layout(h).single()
            assertTrue("built-in id: $spec", BuiltInWidget.isBuiltIn(spec.id))
            assertEquals("2 × 2", 2 to 2, spec.colSpan to spec.rowSpan)
            assertEquals("no resize handles", false to false, on(h) { it.widgetController.resizable(spec.id) })
            assertEquals("its minimum is its size", 2 to 2, on(h) { it.widgetController.minSpan(spec.id) })
        }
    }

    // ---- 2. Today, in handwriting: time order, next highlighted, done checked off, tilted with shadow and tape ----

    @Test fun todayInTimeOrderNextHighlightedDoneCheckedOff() = home { h ->
        val v = addNote(h)
        val n = on(h) { v.note }
        assertEquals(StickyNote.dateLabel(today, Locale.getDefault()), n.dateLabel)
        assertEquals("today's only, in time order", listOf("Team standup", "Design review", "Dinner with Sam"), n.lines.map { it.event.title })
        assertEquals("standup done, review next, dinner later", listOf(Ink.DONE, Ink.NEXT, Ink.PLAIN), n.lines.map { it.ink })
        assertEquals(0, n.more)
        assertEquals("${time(h, at(today, 14, 40))} — Design review", on(h) { v.lineText(n.lines[1]) })
        assertTrue("read aloud: ${on(h) { v.contentDescription }}", on(h) { v.contentDescription.toString() }.contains("Design review"))

        // As drawn: tilted, so the view's corners are bare (shadow and room for the tilt); the glass
        // translucent white, gray and light; dark ink on the next line; no color anywhere on it.
        val px = pixels(v)
        assertEquals("a bare corner", 0, android.graphics.Color.alpha(px.getPixel(1, 1)))
        val (mx, my) = on(h) { v.pagePoint(0.04f, 0.62f) }
        val paper = px.getPixel(mx.toInt(), my.toInt())
        assertTrue("frosted glass: ${Integer.toHexString(paper)}", android.graphics.Color.alpha(paper) in 0xC0..0xF8 &&
            android.graphics.Color.red(paper) > 220 && gray(paper))
        val (rx, ry) = on(h) { v.lineCenter(1)!! }
        val inked = (-60..60).count { dx ->
            val c = px.getPixel((rx + dx * px.width / 400f).toInt().coerceIn(0, px.width - 1), ry.toInt())
            android.graphics.Color.alpha(c) > 200 && android.graphics.Color.red(c) < 90
        }
        assertTrue("the next line in dark ink ($inked)", inked > 0)
        // As the screen shows it: through the note's grayscale layer. Nearly clear edge pixels are left
        // out, where un-premultiplying a 1-4 alpha rounds a gray to a tint.
        val shown = pixels(v, grayscale = true)
        var colored = 0
        for (y in 0 until shown.height step 3) for (x in 0 until shown.width step 3) {
            val c = shown.getPixel(x, y)
            if (android.graphics.Color.alpha(c) >= 32 && !gray(c)) colored++
        }
        shown.recycle()
        assertEquals("no color on the note", 0, colored)
        assertTrue("on screen, drawn through its grayscale layer", on(h) { v.layerType == android.view.View.LAYER_TYPE_HARDWARE })
        assertTrue("tilted", on(h) { v.tilt } != 0f && kotlin.math.abs(on(h) { v.tilt }) <= 2f)
        px.recycle()
        shot("131-sticky-note")
    }

    // ---- 3. A clear day ----

    @Test fun aClearDayReadsNothingOnToday() {
        app.calendarEvents.eventsOverride = { _, _ -> fakeEvents().filter { it.begin >= at(today.plusDays(1), 0) || it.end <= at(today, 0) } }
        home { h ->
            val v = addNote(h)
            assertTrue(on(h) { v.note.empty })
            assertEquals("${on(h) { v.note.dateLabel }}. ${app.getString(R.string.sticky_nothing)}", on(h) { v.contentDescription.toString() })
            shot("132-sticky-empty")
        }
    }

    // ---- 4. More than three ----

    @Test fun moreThanThreeShowsThreeAndHowManyMore() {
        val t = today
        app.calendarEvents.eventsOverride = { _, _ ->
            fakeEvents() + listOf(
                DayEvent(104, "Coffee with Ana", at(t, 16, 0), at(t, 16, 30), false, "Home", 0),
                DayEvent(105, "Holiday", utc(t), utc(t.plusDays(1)), true, "Holidays", 0),
            )
        }
        home { h ->
            val v = addNote(h)
            val n = on(h) { v.note }
            assertEquals("all-day first, then by time", listOf("Holiday", "Team standup", "Design review"), n.lines.map { it.event.title })
            assertEquals(2, n.more)
            assertTrue(on(h) { v.contentDescription.toString() }.endsWith(app.getString(R.string.sticky_more, 2)))
            shot("133-sticky-more")
        }
    }

    // ---- 5. Taps ----

    @Test fun aTapOnALineOpensThatEventElsewhereToday() = home { h ->
        val v = addNote(h)
        Actions.startCalendar = false
        Actions.lastCalendarUri = null
        val (x, y) = on(h) { v.lineCenter(1)!! }
        tapIn(v, x, y)
        assertEquals("the review's detail", android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 102L), Actions.lastCalendarUri)

        Actions.lastCalendarUri = null
        val (dx, dy) = on(h) { v.pagePoint(0.5f, 0.2f) }
        assertNull("the date is no event line", on(h) { v.eventAt(dx, dy) })
        tapIn(v, dx, dy)
        val uri = Actions.lastCalendarUri
        assertEquals("the calendar at a time", "time", uri?.pathSegments?.getOrNull(0))
        assertEquals("…today", today, uri?.lastPathSegment?.toLong()?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() })

        // In edit mode a tap opens nothing.
        Actions.lastCalendarUri = null
        h.onActivity { it.findViewById<WidgetGridView>(R.id.widgetGrid).setEditMode(true) }
        h.onActivity { v.tapAt(x, y) }
        assertNull(Actions.lastCalendarUri)
        h.onActivity { it.widgetController.exitEditMode() }
    }

    // ---- 6. Midnight ----

    @Test fun atMidnightTheNoteRollsToTheNewDay() = home { h ->
        val t = today
        val v = addNote(h, clock = at(t, 23, 59))
        assertEquals(StickyNote.dateLabel(t, Locale.getDefault()), on(h) { v.note.dateLabel })
        assertEquals(listOf(Ink.DONE, Ink.DONE, Ink.DONE), on(h) { v.note.lines.map { it.ink } })
        // The clock passes midnight; the minute tick (or the midnight timer) reloads the new day.
        h.onActivity { v.clock = { at(t.plusDays(1), 0, 1) } }
        h.onActivity { it.widgetController.tickCalendars() }
        waitFor("the new day loaded") { on(h) { v.loadedDay } == t.plusDays(1) }
        assertEquals(StickyNote.dateLabel(t.plusDays(1), Locale.getDefault()), on(h) { v.note.dateLabel })
        assertEquals(listOf("Flight"), on(h) { v.note.lines.map { it.event.title } })
        assertEquals(Ink.NEXT, on(h) { v.note.lines.single().ink })
        shot("134-sticky-midnight")
    }

    /** A gray (or clear) pixel: r, g and b within a few steps of each other. */
    private fun gray(c: Int): Boolean {
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        return android.graphics.Color.alpha(c) == 0 || maxOf(r, g, b) - minOf(r, g, b) <= 6
    }

    // ---- 8. The other widgets are untouched ----

    @Test fun theCalendarWidgetIsStillItsOwn() = home { h ->
        h.onActivity {
            it.widgetController.onActivityResult(WidgetController.REQUEST_PICK, Activity.RESULT_OK,
                Intent().putExtra(WidgetPickerActivity.EXTRA_BUILTIN, BuiltInWidget.CALENDAR.key))
        }
        waitFor("calendar widget on the grid") { on(h) { it.widgetController.calendarViews().size == 1 } }
        addNote(h)
        val specs = layout(h)
        assertEquals(2, specs.size)
        val calSpec = specs.first { it.colSpan == 4 }
        assertEquals("the Calendar is 4 × 3 as ever", 4 to 3, calSpec.colSpan to calSpec.rowSpan)
        assertEquals("and still resizes", true to true, on(h) { it.widgetController.resizable(calSpec.id) })
        shot("136-sticky-beside-calendar")
    }
}
