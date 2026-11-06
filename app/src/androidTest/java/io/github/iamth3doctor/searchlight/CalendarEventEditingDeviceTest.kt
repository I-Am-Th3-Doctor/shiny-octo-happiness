package io.github.iamth3doctor.searchlight

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.UiAutomation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.calendar.CalendarWriter
import io.github.iamth3doctor.searchlight.calendar.EventDraft
import io.github.iamth3doctor.searchlight.calendar.Recurrence
import io.github.iamth3doctor.searchlight.ui.Actions
import io.github.iamth3doctor.searchlight.widgets.BuiltInWidget
import io.github.iamth3doctor.searchlight.widgets.CalendarEventActions
import io.github.iamth3doctor.searchlight.widgets.CalendarWidgetView
import io.github.iamth3doctor.searchlight.widgets.EventEditSheet
import io.github.iamth3doctor.searchlight.widgets.GridSpec
import io.github.iamth3doctor.searchlight.widgets.Snackbar
import io.github.iamth3doctor.searchlight.widgets.WidgetController
import io.github.iamth3doctor.searchlight.widgets.WidgetPickerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Creating, opening, editing and deleting events from the Calendar widget, against a Google
 * calendar and a phone-only one in the real Calendar Provider (the emulator has no Google
 * account, so the Google calendar is made here the way Google's sync adapter makes it: what
 * Searchlight writes, and that it's marked for upload, is what's checked):
 * - a new event goes on the Google calendar (dirty: queued for Google), never the phone-only one;
 * - tapping any event opens its detail view; Edit changes the same row, Delete removes it
 *   (marked deleted for Google's sync to remove it there);
 * - a long-pressed event offers Delete; a repeating one asks this occurrence or the series;
 * - an event stranded on the phone-only calendar shows, deletes, and moves (once, no duplicate).
 */
@RunWith(AndroidJUnit4::class)
class CalendarEventEditingDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val zone = ZoneId.systemDefault()
    private val today get() = LocalDate.now(zone)
    private val writer = CalendarWriter(app)

    private val googleAccount = "searchlight.test@gmail.com"
    private val localAccount = "searchlight-phone"
    private var googleCal = -1L
    private var localCal = -1L
    private var savedFolded = emptyList<GridSpec>()
    private var savedUnfolded = emptyList<GridSpec>()
    private var savedTarget = -1L

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

    private fun asSyncAdapter(uri: Uri, account: String, type: String) = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, type).build()

    private fun makeCalendar(account: String, type: String, name: String, primary: Boolean): Long =
        app.contentResolver.insert(asSyncAdapter(Calendars.CONTENT_URI, account, type), ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account)
            put(Calendars.ACCOUNT_TYPE, type)
            put(Calendars.NAME, name)
            put(Calendars.CALENDAR_DISPLAY_NAME, name)
            put(Calendars.CALENDAR_COLOR, 0xFF4285F4.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, account)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            if (primary) put(Calendars.IS_PRIMARY, 1)
        })!!.lastPathSegment!!.toLong()

    @Before fun setUp() {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        shell("pm grant $pkg ${Manifest.permission.READ_CALENDAR}")
        shell("pm grant $pkg ${Manifest.permission.WRITE_CALENDAR}")
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        try {
            localCal = makeCalendar(localAccount, CalendarContract.ACCOUNT_TYPE_LOCAL, "Phone calendar", primary = true)
            googleCal = makeCalendar(googleAccount, CalendarWriter.GOOGLE, googleAccount, primary = true)
        } finally {
            automation.dropShellPermissionIdentity()
        }
        main {
            savedFolded = app.prefs.widgetGrid
            savedUnfolded = app.prefs.widgetGridUnfolded
            savedTarget = app.prefs.calendarTarget
            app.prefs.widgetGrid = emptyList()
            app.prefs.widgetGridUnfolded = emptyList()
            app.prefs.focusMode = false
            app.prefs.calendarTarget = -1L // the default: the primary Google calendar
        }
        Actions.startCalendar = false
        CalendarEventActions.lastDialog = null
    }

    @After fun restore() {
        Actions.startCalendar = true
        main {
            app.prefs.widgetGrid = savedFolded
            app.prefs.widgetGridUnfolded = savedUnfolded
            app.prefs.calendarTarget = savedTarget
            // Anything still open from a test whose activity is already gone: tolerate it.
            runCatching { Snackbar.current?.dismiss() }
            runCatching { CalendarEventActions.lastDialog?.dismiss() }
            runCatching { EventEditSheet.current?.dialog?.dismiss() }
        }
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CALENDAR)
        runCatching {
            app.contentResolver.delete(asSyncAdapter(Calendars.CONTENT_URI, googleAccount, CalendarWriter.GOOGLE), "${Calendars._ID}=?", arrayOf(googleCal.toString()))
            app.contentResolver.delete(asSyncAdapter(Calendars.CONTENT_URI, localAccount, CalendarContract.ACCOUNT_TYPE_LOCAL), "${Calendars._ID}=?", arrayOf(localCal.toString()))
        }
        automation.dropShellPermissionIdentity()
        shell("input keyevent KEYCODE_HOME")
    }

    // ---- helpers ----

    private fun home(block: (ActivityScenario<MainActivity>) -> Unit) {
        val s = ActivityScenario.launch(MainActivity::class.java)
        try {
            Thread.sleep(1500)
            block(s)
        } finally {
            runCatching { s.close() }
        }
    }

    private fun addCalendar(h: ActivityScenario<MainActivity>): CalendarWidgetView {
        h.onActivity {
            it.widgetController.onActivityResult(WidgetController.REQUEST_PICK, Activity.RESULT_OK,
                Intent().putExtra(WidgetPickerActivity.EXTRA_BUILTIN, BuiltInWidget.CALENDAR.key))
        }
        waitFor("calendar widget on the grid") { on(h) { it.widgetController.calendarViews().any { v -> v.isAttachedToWindow && v.width > 0 } } }
        return on(h) { it.widgetController.calendarViews().single() }
    }

    private class Row(val title: String, val calendar: Long, val start: Long, val deleted: Boolean, val dirty: Boolean, val rrule: String?, val status: Int?, val originalId: Long?)

    private fun row(id: Long): Row? = app.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id),
        arrayOf(Events.TITLE, Events.CALENDAR_ID, Events.DTSTART, Events.DELETED, Events.DIRTY, Events.RRULE, Events.STATUS, Events.ORIGINAL_ID),
        null, null, null)?.use { c ->
        if (!c.moveToFirst()) null
        else Row(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getInt(4) == 1, c.getString(5),
            if (c.isNull(6)) null else c.getInt(6), if (c.isNull(7)) null else c.getLong(7))
    }

    /** Gone for good, or marked deleted (Google's sync adapter then removes it there and here). */
    private fun goneOrDeleted(id: Long) = row(id).let { it == null || it.deleted }

    private fun eventsOn(cal: Long): List<Long> = app.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
        "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0", arrayOf(cal.toString()), null)
        ?.use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }.orEmpty()

    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** A day this month other than today (so tapping it stays in the grid). */
    private fun otherDay(): LocalDate = today.plusDays(2).let { if (YearMonth.from(it) == YearMonth.from(today)) it else today.minusDays(2) }

    private fun select(h: ActivityScenario<MainActivity>, v: CalendarWidgetView, d: LocalDate) {
        h.onActivity { v.tapDay(d) }
        waitFor("day loaded") { on(h) { v.selected == d && v.loadedMonth == v.month } }
    }

    /** What the provider itself has on [d] (title, event, status, original), for failure messages. */
    private fun providerDay(d: LocalDate): String {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, at(d, 0)); ContentUris.appendId(it, at(d.plusDays(1), 0))
        }.build()
        return app.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.STATUS, CalendarContract.Instances.ORIGINAL_ID, CalendarContract.Instances.CALENDAR_ID), null, null, null)
            ?.use { c -> generateSequence { if (c.moveToNext()) "${c.getString(0)}#${c.getLong(1)} st=${if (c.isNull(2)) "-" else c.getInt(2)} orig=${if (c.isNull(3)) "-" else c.getLong(3)} cal=${c.getLong(4)}" else null }.toList() }
            .toString()
    }

    private fun rowView(h: ActivityScenario<MainActivity>, v: CalendarWidgetView, title: String): View {
        try {
            waitFor("$title in the agenda") { on(h) { (0 until v.eventList.childCount).any { i -> (v.eventList.getChildAt(i).getTag(R.id.calendar_event) as? io.github.iamth3doctor.searchlight.calendar.DayEvent)?.title == title } } }
        } catch (e: AssertionError) {
            val day = on(h) { v.selected }
            throw AssertionError("$title not in the agenda for $day (month ${on(h) { v.month }}, loaded ${on(h) { v.loadedMonth }}). " +
                "widget: ${on(h) { v.byDay[day].orEmpty().map { "${it.title}#${it.eventId}" } }}; provider: ${providerDay(day)}", e)
        }
        waitFor("$title in the agenda") { on(h) { (0 until v.eventList.childCount).any { i -> (v.eventList.getChildAt(i).getTag(R.id.calendar_event) as? io.github.iamth3doctor.searchlight.calendar.DayEvent)?.title == title } } }
        return on(h) { (0 until v.eventList.childCount).map(v.eventList::getChildAt).first { (it.getTag(R.id.calendar_event) as? io.github.iamth3doctor.searchlight.calendar.DayEvent)?.title == title } }
    }

    private fun dialog(): AlertDialog {
        waitFor("a dialog") { CalendarEventActions.lastDialog?.isShowing == true }
        return CalendarEventActions.lastDialog!!
    }

    private fun message(h: ActivityScenario<MainActivity>, d: AlertDialog): String = on(h) { d.findViewById<TextView>(android.R.id.message)?.text?.toString().orEmpty() }

    private fun press(h: ActivityScenario<MainActivity>, d: AlertDialog, which: Int) {
        val shown = d
        CalendarEventActions.lastDialog = null
        h.onActivity { shown.getButton(which).performClick() }
    }

    private fun insertOnGoogle(title: String, d: LocalDate, h: Int, recurrence: Recurrence? = null, location: String = ""): Long =
        writer.insert(EventDraft(title, d, LocalTime.of(h, 0), LocalTime.of(h + 1, 0), recurrence = recurrence, location = location), googleCal, zone)

    // ---- 1. Add: to the Google calendar, never phone-only ----

    @Test fun addGoesToTheGoogleCalendar() = home { h ->
        // Phone-only calendars are never offered, and the default is the primary Google calendar.
        assertFalse(writer.writable().any { it.id == localCal })
        assertEquals(googleCal, writer.target(-1L)!!.id)

        val v = addCalendar(h)
        val day = otherDay()
        select(h, v, day)
        assertTrue("+ in the header", on(h) { v.addButton.isShown })
        h.onActivity { v.addButton.performClick() }
        waitFor("add sheet") { EventEditSheet.current?.dialog?.isShowing == true }
        val sheet = EventEditSheet.current!!
        assertEquals("the tapped day", day, on(h) { sheet.draft.date })
        assertTrue(on(h) { sheet.calendarRow.text.toString() }, on(h) { sheet.calendarRow.text.toString().endsWith(googleAccount) })
        shot("80-calendar-add-sheet")
        CalendarEventActions.lastSavedId = -1
        h.onActivity { sheet.titleField.setText("Gym"); sheet.locationField.setText("Main St gym"); sheet.save() }
        waitFor("saved") { CalendarEventActions.lastSavedId > 0 }
        val id = CalendarEventActions.lastSavedId
        val r = row(id)!!
        assertEquals("Gym", r.title)
        assertEquals("on the Google calendar", googleCal, r.calendar)
        assertTrue("marked for upload to Google", r.dirty)
        assertEquals(googleAccount, CalendarWriter.lastSyncRequested)
        assertTrue("nothing on the phone-only calendar", eventsOn(localCal).isEmpty())
        rowView(h, v, "Gym")
        shot("81-calendar-added")

        // Undo removes it again (marked deleted, so Google drops it too).
        val snack = on(h) { Snackbar.current }!!
        h.onActivity { snack.action!!.performClick() }
        waitFor("undone") { goneOrDeleted(id) }
    }

    // ---- 2. Tap: details with Edit and Delete ----

    @Test fun tappingAnEventOpensDetailsWithWorkingEditAndDelete() = home { h ->
        val day = otherDay()
        val id = insertOnGoogle("Dentist", day, 10, location = "12 Oak Rd")
        val v = addCalendar(h)
        select(h, v, day)
        rowView(h, v, "Dentist").let { rv -> h.onActivity { rv.performClick() } }
        val details = dialog()
        val msg = message(h, details)
        assertTrue(msg, googleAccount in msg && "12 Oak Rd" in msg)
        assertEquals("Dentist", CalendarEventActions.lastDetails?.title)
        assertTrue(on(h) { details.getButton(AlertDialog.BUTTON_POSITIVE).isShown && details.getButton(AlertDialog.BUTTON_NEGATIVE).isShown })
        shot("82-calendar-event-details")

        // Edit: the same row changes.
        press(h, details, AlertDialog.BUTTON_POSITIVE)
        waitFor("edit sheet") { EventEditSheet.current?.dialog?.isShowing == true }
        val sheet = EventEditSheet.current!!
        assertEquals("Dentist", on(h) { sheet.titleField.text.toString() })
        CalendarEventActions.lastUpdatedId = -1
        h.onActivity { sheet.titleField.setText("Dentist checkup"); sheet.save() }
        waitFor("updated") { CalendarEventActions.lastUpdatedId == id }
        assertEquals("Dentist checkup", row(id)!!.title)
        assertTrue(row(id)!!.dirty)

        // Delete: asked first, then gone (from the widget, and marked deleted for Google).
        rowView(h, v, "Dentist checkup").let { rv -> h.onActivity { rv.performClick() } }
        press(h, dialog(), AlertDialog.BUTTON_NEGATIVE)
        val confirm = dialog()
        assertTrue(message(h, confirm).contains(googleAccount))
        shot("83-calendar-delete-confirm")
        press(h, confirm, AlertDialog.BUTTON_POSITIVE)
        waitFor("deleted") { goneOrDeleted(id) }
        waitFor("off the widget") { on(h) { v.byDay[day].orEmpty().none { it.eventId == id } } }
    }

    @Test fun longPressingAnEventOffersDelete() = home { h ->
        val day = otherDay()
        val id = insertOnGoogle("Call", day, 15)
        val v = addCalendar(h)
        select(h, v, day)
        val r = android.graphics.Rect()
        val rv = rowView(h, v, "Call")
        main { rv.getGlobalVisibleRect(r) }
        shell("input swipe ${r.centerX()} ${r.centerY()} ${r.centerX()} ${r.centerY()} 900")
        waitFor("event menu") { on(h) { v.lastMenu != null } }
        val titles = on(h) { (0 until v.lastMenu!!.menu.size()).map { v.lastMenu!!.menu.getItem(it).title.toString() } }
        assertEquals(listOf(app.getString(R.string.cal_event_details), app.getString(R.string.cal_event_edit), app.getString(R.string.cal_event_delete)), titles)
        assertFalse("not edit mode", on(h) { it.findViewById<io.github.iamth3doctor.searchlight.widgets.WidgetGridView>(R.id.widgetGrid).editMode })
        shot("84-calendar-event-menu")
        h.onActivity {
            val m = v.lastMenu!!.menu
            m.performIdentifierAction((0 until m.size()).map(m::getItem).first { it.title == app.getString(R.string.cal_event_delete) }.itemId, 0)
            v.lastMenu!!.dismiss()
        }
        press(h, dialog(), AlertDialog.BUTTON_POSITIVE)
        waitFor("deleted") { goneOrDeleted(id) }
    }

    // ---- 3. Repeating events: this occurrence or the series ----

    @Test fun deletingARepeatingEventAsksOccurrenceOrSeries() = home { h ->
        val dow = today.dayOfWeek
        val series = insertOnGoogle("Standup", today, 9, recurrence = Recurrence.weekly(dow))
        assertEquals("FREQ=WEEKLY;BYDAY=" + Recurrence.CODES.getValue(dow), row(series)!!.rrule)
        val v = addCalendar(h)
        select(h, v, today)
        try {
            rowView(h, v, "Standup")
        } catch (e: AssertionError) {
            // What the provider itself expands for the series, and what the widget holds.
            val (from, to) = io.github.iamth3doctor.searchlight.calendar.CalendarModel.queryRange(today.minusDays(7), today.plusDays(7), zone)
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
            val inst = app.contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.VISIBLE, CalendarContract.Instances.STATUS),
                "${CalendarContract.Instances.EVENT_ID} = ?", arrayOf(series.toString()), null)
                ?.use { c -> generateSequence { if (c.moveToNext()) "${java.time.Instant.ofEpochMilli(c.getLong(0))} vis=${c.getInt(1)} st=${if (c.isNull(2)) "null" else c.getInt(2)}" else null }.toList() }
            val ev = app.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, series),
                arrayOf(Events.DTSTART, Events.DURATION, Events.DTEND, Events.EVENT_TIMEZONE, Events.LAST_DATE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) "start=${java.time.Instant.ofEpochMilli(c.getLong(0))} dur=${c.getString(1)} end=${c.getString(2)} tz=${c.getString(3)} last=${c.getString(4)}" else "none" }
            throw AssertionError("Standup not shown. event: $ev; instances: $inst; widget today: ${on(h) { v.byDay[today].orEmpty().map { it.title } }}", e)
        }
        rowView(h, v, "Standup").let { rv -> h.onActivity { rv.performClick() } }
        press(h, dialog(), AlertDialog.BUTTON_NEGATIVE)
        val ask = dialog()
        val items = on(h) { (0 until ask.listView.adapter.count).map { ask.listView.adapter.getItem(it).toString() } }
        assertEquals(listOf(app.getString(R.string.cal_delete_this), app.getString(R.string.cal_delete_all)), items)
        shot("85-calendar-delete-repeating")
        CalendarEventActions.lastDialog = null
        val next = today.plusWeeks(1)
        assertTrue("next week's there before: ${providerDay(next)}", "Standup#$series" in providerDay(next))
        h.onActivity { ask.listView.performItemClick(null, 0, 0) } // this event only
        // Today's occurrence is excluded from the series (EXDATE, synced like any edit); the rest stay.
        try {
            waitFor("today's gone") { on(h) { v.byDay[today].orEmpty().none { it.title == "Standup" } } }
        } catch (e: AssertionError) {
            val ex = app.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, series), arrayOf(Events.EXDATE, Events.DTSTART), null, null, null)
                ?.use { c -> if (c.moveToFirst()) "exdate=${c.getString(0)} start=${java.time.Instant.ofEpochMilli(c.getLong(1))}" else "none" }
            throw AssertionError("today's occurrence still shown. series: $ex; provider today: ${providerDay(today)}", e)
        }
        val exdate = app.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, series), arrayOf(Events.EXDATE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        assertEquals(CalendarWriter.exdateWith(null, at(today, 9), false), exdate)
        assertFalse("the series stays", row(series)!!.deleted)
        assertTrue(row(series)!!.dirty)
        assertTrue("next week's still there: ${providerDay(next)}", "Standup#$series" in providerDay(next))

        // Next week's occurrence: the whole series this time.
        if (YearMonth.from(next) != YearMonth.from(today)) h.onActivity { v.shiftMonth(1) }
        select(h, v, next)
        rowView(h, v, "Standup").let { rv -> h.onActivity { rv.performClick() } }
        press(h, dialog(), AlertDialog.BUTTON_NEGATIVE)
        val ask2 = dialog()
        CalendarEventActions.lastDialog = null
        h.onActivity { ask2.listView.performItemClick(null, 1, 1) }
        waitFor("series deleted") { goneOrDeleted(series) }
    }

    // ---- 4. Stranded phone-only events ----

    @Test fun strandedPhoneOnlyEventsShowDeleteAndMoveOnce() {
        // Two events stranded on the phone-only calendar (as an older build could leave them).
        val day = otherDay()
        val stranded = (1..2).map { n ->
            app.contentResolver.insert(asSyncAdapter(Events.CONTENT_URI, localAccount, CalendarContract.ACCOUNT_TYPE_LOCAL), ContentValues().apply {
                put(Events.CALENDAR_ID, localCal)
                put(Events.TITLE, "Stranded $n")
                put(Events.DTSTART, at(day, 10 + n))
                put(Events.DTEND, at(day, 11 + n))
                put(Events.EVENT_TIMEZONE, zone.id)
            })!!.lastPathSegment!!.toLong()
        }
        val third = app.contentResolver.insert(asSyncAdapter(Events.CONTENT_URI, localAccount, CalendarContract.ACCOUNT_TYPE_LOCAL), ContentValues().apply {
            put(Events.CALENDAR_ID, localCal)
            put(Events.TITLE, "Stranded 3")
            put(Events.DTSTART, at(day, 16))
            put(Events.DTEND, at(day, 17))
            put(Events.EVENT_TIMEZONE, zone.id)
        })!!.lastPathSegment!!.toLong()

        home { h ->
            val v = addCalendar(h)
            select(h, v, day)
            // Visible, with a detail view that says where it lives, and a working Delete.
            rowView(h, v, "Stranded 3").let { rv -> h.onActivity { rv.performClick() } }
            val details = dialog()
            assertTrue(message(h, details), message(h, details).contains(app.getString(R.string.cal_event_phone_only)))
            shot("86-calendar-stranded-details")
            press(h, details, AlertDialog.BUTTON_NEGATIVE)
            press(h, dialog(), AlertDialog.BUTTON_POSITIVE)
            waitFor("stranded deleted") { row(third) == null }
            rowView(h, v, "Stranded 1")
        }

        // Settings: the two left are offered a one-time move to Google Calendar.
        val s = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            var info = ""
            waitFor("count shown") {
                s.onActivity { a -> info = a.findViewById<TextView>(R.id.calendarLocalInfo).text.toString() }
                info == app.resources.getQuantityString(R.plurals.settings_calendar_move_count, 2, 2)
            }
            var moveShown = false
            s.onActivity { a -> moveShown = a.findViewById<View>(R.id.calendarMoveLocal).visibility == View.VISIBLE }
            assertTrue(moveShown)
            s.onActivity { a -> a.findViewById<View>(R.id.calendarMoveLocal).performClick() }
            var confirm: AlertDialog? = null
            waitFor("move confirm") { s.onActivity { a -> confirm = a.moveDialog }; confirm?.isShowing == true }
            s.onActivity { confirm!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            waitFor("moved") { eventsOn(localCal).isEmpty() }
            // On Google now, each once (copied then removed, never both), and queued for upload.
            val onGoogle = eventsOn(googleCal).mapNotNull(::row)
            assertEquals(listOf("Stranded 1", "Stranded 2"), onGoogle.map { it.title }.sorted())
            assertTrue(onGoogle.all { it.dirty })
            for (id in stranded) assertNull("original gone", row(id))
            waitFor("none left") {
                s.onActivity { a -> info = a.findViewById<TextView>(R.id.calendarLocalInfo).text.toString() }
                info == app.getString(R.string.settings_calendar_move_none)
            }
            s.onActivity { a -> moveShown = a.findViewById<View>(R.id.calendarMoveLocal).visibility == View.VISIBLE }
            assertFalse("the move is offered no more", moveShown)
        } finally {
            s.close()
        }
    }

    // ---- 6. Readable sheets: dark text on the light sheet, in every state ----

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(android.graphics.Color.red(c)) + 0.7152 * ch(android.graphics.Color.green(c)) + 0.0722 * ch(android.graphics.Color.blue(c))
    }

    /** [fg] as it shows over [bg] (its own alpha blended in). */
    private fun over(fg: Int, bg: Int): Int {
        val a = android.graphics.Color.alpha(fg) / 255.0
        fun mix(f: Int, b: Int) = (f * a + b * (1 - a)).toInt()
        return android.graphics.Color.rgb(mix(android.graphics.Color.red(fg), android.graphics.Color.red(bg)),
            mix(android.graphics.Color.green(fg), android.graphics.Color.green(bg)), mix(android.graphics.Color.blue(fg), android.graphics.Color.blue(bg)))
    }

    /** WCAG contrast ratio of [fg] (with its alpha) on the opaque [bg] (1 to 21). */
    private fun contrast(fg: Int, bg: Int): Double {
        val (x, y) = lum(over(fg, bg)) to lum(bg)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    /** The sheet's own background color (its theme's). */
    private fun sheetBackground(ctx: android.content.Context): Int {
        val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        try { return a.getColor(0, android.graphics.Color.WHITE) } finally { a.recycle() }
    }

    /** Every shown text in [root]: its text (or, empty, its hint) readable on [bg]. */
    private fun assertLegible(h: ActivityScenario<MainActivity>, root: View, bg: Int, what: String) {
        val problems = on(h) {
            val out = ArrayList<String>()
            fun walk(v: View) {
                if (v is TextView && v.isShown) {
                    val label = v.text?.toString().orEmpty().ifEmpty { v.hint?.toString().orEmpty() }
                    val textOk = contrast(v.currentTextColor, bg) >= 4.5
                    val hintOk = v.hint.isNullOrEmpty() || contrast(v.currentHintTextColor, bg) >= 3.0
                    if (!textOk || !hintOk) out += "\"$label\" text ${"%.1f".format(contrast(v.currentTextColor, bg))}:1 hint ${"%.1f".format(contrast(v.currentHintTextColor, bg))}:1"
                }
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            out
        }
        assertTrue("$what: $problems", problems.isEmpty())
    }

    @Test fun newEventSheetTextIsReadableEmptyTypedAndWithTheKeyboard() = home { h ->
        val v = addCalendar(h)
        h.onActivity { v.addButton.performClick() }
        waitFor("add sheet") { EventEditSheet.current?.dialog?.isShowing == true }
        val sheet = EventEditSheet.current!!
        val bg = sheetBackground(sheet.ui)
        assertTrue("the sheet is light", lum(bg) > 0.6)
        val form = on(h) { sheet.titleField.parent as View }
        // Empty: the "Title" and "Location" placeholders, and every row's label and value.
        assertEquals("", on(h) { sheet.titleField.text.toString() })
        assertLegible(h, form, bg, "empty")
        for (r in listOf(sheet.dateRow, sheet.repeatRow, sheet.calendarRow, sheet.reminderRow)) {
            val t = on(h) { r.text.toString() }
            assertTrue("row shows label and value: $t", ": " in t && t.substringAfter(": ").isNotBlank())
        }
        assertTrue(on(h) { sheet.calendarRow.text.toString() }, on(h) { sheet.calendarRow.text.toString() }.endsWith(googleAccount))
        shot("87-calendar-sheet-empty")
        // Keyboard open, typing: the typed text stays readable.
        h.onActivity {
            sheet.titleField.requestFocus()
            it.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(sheet.titleField, 0)
        }
        Thread.sleep(800)
        shell("input text Gym")
        waitFor("typed") { on(h) { sheet.titleField.text.toString() } == "Gym" }
        assertLegible(h, form, bg, "typing")
        shot("88-calendar-sheet-typing")
        // All day on: the switch, rows and fields still readable.
        h.onActivity { sheet.allDaySwitch.isChecked = true }
        assertLegible(h, form, bg, "all day")
        h.onActivity { sheet.dialog.dismiss() }
    }

    @Test fun editSheetAndDetailViewAreReadable() = home { h ->
        val day = otherDay()
        insertOnGoogle("Review", day, 11, location = "Room 4")
        val v = addCalendar(h)
        select(h, v, day)
        rowView(h, v, "Review").let { rv -> h.onActivity { rv.performClick() } }
        val details = dialog()
        val msg = on(h) { details.findViewById<TextView>(android.R.id.message) }
        assertLegible(h, msg, sheetBackground(details.context), "detail view")
        press(h, details, AlertDialog.BUTTON_POSITIVE)
        waitFor("edit sheet") { EventEditSheet.current?.dialog?.isShowing == true }
        val sheet = EventEditSheet.current!!
        val form = on(h) { sheet.titleField.parent as View }
        assertLegible(h, form, sheetBackground(sheet.ui), "edit, filled")
        h.onActivity { sheet.titleField.setText(""); sheet.locationField.setText("") }
        assertLegible(h, form, sheetBackground(sheet.ui), "edit, emptied")
        shot("89-calendar-edit-sheet")
        h.onActivity { sheet.dialog.dismiss() }
    }

    @Test fun theDefaultIsTheFirstGoogleAccountAndAPickSticks() {
        // A second Google account, added later, whose address sorts first: not the default.
        var second = -1L
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        try {
            second = makeCalendar("aaa.second@gmail.com", CalendarWriter.GOOGLE, "aaa.second@gmail.com", primary = true)
        } finally {
            automation.dropShellPermissionIdentity()
        }
        try {
            assertEquals("the phone's first Google account", googleCal, writer.target(-1L)!!.id)
            home { h ->
                val v = addCalendar(h)
                h.onActivity { v.addButton.performClick() }
                waitFor("add sheet") { EventEditSheet.current?.dialog?.isShowing == true }
                val sheet = EventEditSheet.current!!
                assertTrue(on(h) { sheet.calendarRow.text.toString() }.endsWith(googleAccount))
                // Pick the other one: the row shows it, readably.
                h.onActivity { sheet.calendarRow.performClick() }
                waitFor("calendar picker") { EventEditSheet.lastPicker?.isShowing == true }
                val picker = EventEditSheet.lastPicker!!
                val labels = on(h) { (0 until picker.listView.adapter.count).map { picker.listView.adapter.getItem(it).toString() } }
                h.onActivity { picker.listView.performItemClick(null, labels.indexOf("aaa.second@gmail.com"), 0) }
                waitFor("picked") { on(h) { sheet.calendarRow.text.toString() }.endsWith("aaa.second@gmail.com") }
                assertLegible(h, on(h) { sheet.calendarRow }, sheetBackground(sheet.ui), "calendar row")
                CalendarEventActions.lastSavedId = -1
                h.onActivity { sheet.titleField.setText("On the second"); sheet.save() }
                waitFor("saved") { CalendarEventActions.lastSavedId > 0 }
                assertEquals(second, row(CalendarEventActions.lastSavedId)!!.calendar)
                // The pick is where the next event goes.
                assertEquals(second, app.prefs.calendarTarget)
            }
        } finally {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CALENDAR)
            runCatching { app.contentResolver.delete(asSyncAdapter(Calendars.CONTENT_URI, "aaa.second@gmail.com", CalendarWriter.GOOGLE), "${Calendars._ID}=?", arrayOf(second.toString())) }
            automation.dropShellPermissionIdentity()
        }
    }

    // ---- 5. A long-pressed day: Add event, prefilled ----

    @Test fun aLongPressedDayAddsOnThatDay() = home { h ->
        val v = addCalendar(h)
        val day = otherDay()
        val cell = on(h) { v.dayViews.first { it.day == day } }
        val r = android.graphics.Rect()
        main { cell.getGlobalVisibleRect(r) }
        shell("input swipe ${r.centerX()} ${r.centerY()} ${r.centerX()} ${r.centerY()} 900")
        waitFor("day menu") { on(h) { v.lastMenu != null } }
        h.onActivity {
            val m = v.lastMenu!!.menu
            m.performIdentifierAction((0 until m.size()).map(m::getItem).first { it.title == app.getString(R.string.cal_add_event) }.itemId, 0)
            v.lastMenu!!.dismiss()
        }
        waitFor("add sheet") { EventEditSheet.current?.dialog?.isShowing == true }
        assertEquals(day, on(h) { EventEditSheet.current!!.draft.date })
        assertEquals(googleCal, on(h) { EventEditSheet.current!!.draft.calendarId })
        h.onActivity { EventEditSheet.current!!.dialog.dismiss() }
    }
}
