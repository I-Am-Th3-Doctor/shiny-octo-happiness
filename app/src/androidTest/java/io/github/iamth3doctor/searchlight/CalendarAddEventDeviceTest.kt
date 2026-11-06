package io.github.iamth3doctor.searchlight

import android.Manifest
import android.app.Activity
import android.app.UiAutomation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.provider.CalendarContract
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.calendar.EventParser
import io.github.iamth3doctor.searchlight.calendar.EventText
import io.github.iamth3doctor.searchlight.data.SecretStore
import io.github.iamth3doctor.searchlight.ui.Actions
import io.github.iamth3doctor.searchlight.widgets.BuiltInWidget
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Adding events from the Calendar widget by typing: "Gym tomorrow 8-9pm" previews and saves on
 * the chosen calendar; "Yoga every Mon/Thu 7am" makes a series; without a key the on-device
 * rules handle "Dinner Fri 7pm"; "Confirm before saving" off saves at once; Undo deletes; a
 * failed parse keeps the text; a long-pressed day prefills its date; the edit sheet; the AI path
 * (stood in for) and its fallback; the real SDK on the device with a bad key; Settings > AI.
 */
@RunWith(AndroidJUnit4::class)
class CalendarAddEventDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val zone = ZoneId.systemDefault()
    private val today get() = LocalDate.now(zone)

    private var savedFolded = emptyList<GridSpec>()
    private var savedUnfolded = emptyList<GridSpec>()
    private var savedTarget = -1L
    private var savedConfirm = true
    private var savedKey: String? = null
    private var calId = -1L

    private val calUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "searchlight-add-test")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

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
        shell("pm grant $pkg ${Manifest.permission.WRITE_CALENDAR}")
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        try {
            calId = app.contentResolver.insert(calUri, ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, "searchlight-add-test")
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, "Add test")
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Add test")
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF33B679.toInt())
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, "searchlight-add-test")
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            })!!.lastPathSegment!!.toLong()
        } finally {
            automation.dropShellPermissionIdentity()
        }
        savedKey = app.eventParser.apiKey
        main {
            savedFolded = app.prefs.widgetGrid
            savedUnfolded = app.prefs.widgetGridUnfolded
            savedTarget = app.prefs.calendarTarget
            savedConfirm = app.prefs.calendarConfirm
            app.prefs.widgetGrid = emptyList()
            app.prefs.widgetGridUnfolded = emptyList()
            app.prefs.focusMode = false
            app.prefs.calendarTarget = calId
            app.prefs.calendarConfirm = true
        }
        app.eventParser.apiKey = null
        app.eventParser.aiOverride = null
        Actions.startCalendar = false
    }

    @After fun restore() {
        Actions.startCalendar = true
        app.eventParser.aiOverride = null
        app.eventParser.apiKey = savedKey
        main {
            app.prefs.widgetGrid = savedFolded
            app.prefs.widgetGridUnfolded = savedUnfolded
            app.prefs.calendarTarget = savedTarget
            app.prefs.calendarConfirm = savedConfirm
            Snackbar.current?.dismiss()
        }
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CALENDAR)
        runCatching { app.contentResolver.delete(calUri, "${CalendarContract.Calendars._ID}=?", arrayOf(calId.toString())) }
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

    /** "+", type [text], Done. */
    private fun type(h: ActivityScenario<MainActivity>, v: CalendarWidgetView, text: String) {
        h.onActivity {
            if (!v.composing) v.addButton.performClick()
            v.addInput.setText(text)
            v.addInput.onEditorAction(EditorInfo.IME_ACTION_DONE)
        }
    }

    private fun previewShown(h: ActivityScenario<MainActivity>, v: CalendarWidgetView) =
        waitFor("preview") { on(h) { v.previewCard.isShown && v.pendingDraft != null } }

    private class Row(val title: String, val calendar: Long, val start: Long, val end: Long, val rrule: String?, val duration: String?, val allDay: Boolean)

    private fun event(id: Long): Row? = app.contentResolver.query(
        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
        arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND, CalendarContract.Events.RRULE, CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY, CalendarContract.Events.DELETED),
        null, null, null,
    )?.use { c -> if (c.moveToFirst() && c.getInt(7) == 0) Row(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getString(4), c.getString(5), c.getInt(6) == 1) else null }

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun savedAfter(h: ActivityScenario<MainActivity>, before: Long): Long {
        waitFor("saved") { on(h) { it.eventAdder.lastSavedId } != before }
        return on(h) { it.eventAdder.lastSavedId }
    }

    private fun time(h: ActivityScenario<MainActivity>, d: LocalDate, t: LocalTime): String =
        on(h) { android.text.format.DateFormat.getTimeFormat(it).format(java.util.Date.from(d.atTime(t).atZone(zone).toInstant())) }

    // ---- 1. Gym tomorrow 8-9pm: preview, save, snackbar, undo ----

    @Test fun gymTomorrowPreviewsSavesAndUndoes() = home { h ->
        val v = addCalendar(h)
        assertTrue("+ in the agenda header", on(h) { v.addButton.isShown })
        type(h, v, "Gym tomorrow 8-9pm")
        previewShown(h, v)
        val tomorrow = today.plusDays(1)
        val locale = app.resources.configuration.locales[0]
        assertEquals("Gym", on(h) { v.previewTitle.text.toString() })
        val whenText = on(h) { v.previewWhen.text.toString() }
        assertTrue(whenText, whenText.startsWith(EventText.day(tomorrow, locale)))
        assertTrue(whenText, EventText.range(time(h, tomorrow, LocalTime.of(20, 0)), time(h, tomorrow, LocalTime.of(21, 0))) in whenText)
        assertEquals("Add test", on(h) { v.previewCalendar.text.toString() })
        assertFalse(on(h) { v.previewRepeat.isShown })
        shot("70-add-event-preview")

        val before = on(h) { it.eventAdder.lastSavedId }
        h.onActivity { v.previewSave.performClick() }
        val id = savedAfter(h, before)
        val row = event(id)!!
        assertEquals("Gym", row.title)
        assertEquals(calId, row.calendar)
        assertEquals(at(tomorrow, 20), row.start)
        assertEquals(at(tomorrow, 21), row.end)
        assertNull(row.rrule)
        // The field closes, the widget moves to the event's day and shows it.
        assertFalse(on(h) { v.composing })
        assertEquals(tomorrow, on(h) { v.selected })
        waitFor("shown in the agenda") { on(h) { v.byDay[tomorrow].orEmpty().any { it.eventId == id } } }

        // The snackbar: "Saved: Gym, Sat Nov 7, 8:00–9:00 PM", with Undo.
        val snack = on(h) { Snackbar.current }
        assertNotNull(snack)
        val msg = on(h) { snack!!.message.text.toString() }
        assertEquals(app.getString(R.string.cal_add_saved, EventText.summary(on(h) { v.pendingDraft } ?: io.github.iamth3doctor.searchlight.calendar.EventDraft(
            "Gym", tomorrow, LocalTime.of(20, 0), LocalTime.of(21, 0)), locale) { t -> time(h, tomorrow, t) }), msg)
        shot("71-add-event-saved")
        h.onActivity { snack!!.action!!.performClick() }
        waitFor("undone") { on(h) { it.eventAdder.lastUndoneId } == id }
        assertNull("deleted", event(id))
    }

    // ---- 2. A repeating series ----

    @Test fun yogaEveryMonThuMakesASeries() = home { h ->
        val v = addCalendar(h)
        type(h, v, "Yoga every Mon/Thu 7am")
        previewShown(h, v)
        val words = on(h) { v.repeatWords() }
        assertEquals(EventText.repeat(io.github.iamth3doctor.searchlight.calendar.Recurrence.weekly(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            app.resources.configuration.locales[0], words), on(h) { v.previewRepeat.text.toString() })
        shot("72-add-event-series")
        val before = on(h) { it.eventAdder.lastSavedId }
        h.onActivity { v.previewSave.performClick() }
        val row = event(savedAfter(h, before))!!
        assertEquals("Yoga", row.title)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TH", row.rrule)
        assertEquals("PT60M", row.duration)
        val first = generateSequence(today) { it.plusDays(1) }.first { it.dayOfWeek == DayOfWeek.MONDAY || it.dayOfWeek == DayOfWeek.THURSDAY }
        assertEquals(at(first, 7), row.start)
    }

    // ---- 3. No key: on-device rules ----

    @Test fun withoutAKeyRulesHandleDinnerFri7pm() = home { h ->
        assertFalse(app.eventParser.hasKey)
        val v = addCalendar(h)
        type(h, v, "Dinner Fri 7pm")
        previewShown(h, v)
        assertEquals(EventParser.Source.RULES, app.eventParser.lastSource)
        val d = on(h) { v.pendingDraft }!!
        val fri = generateSequence(today) { it.plusDays(1) }.first { it.dayOfWeek == DayOfWeek.FRIDAY }
        assertEquals("Dinner", d.title)
        assertEquals(fri, d.date)
        assertEquals(LocalTime.of(19, 0) to LocalTime.of(20, 0), d.start to d.end)
    }

    // ---- 4. Confirm before saving off ----

    @Test fun confirmOffSavesStraightAway() = home { h ->
        main { app.prefs.calendarConfirm = false }
        val v = addCalendar(h)
        val before = on(h) { it.eventAdder.lastSavedId }
        type(h, v, "Call mom tomorrow 5pm")
        val row = event(savedAfter(h, before))!!
        assertEquals("Call mom", row.title)
        assertEquals(at(today.plusDays(1), 17), row.start)
        assertFalse("no preview step", on(h) { v.previewCard.isShown })
        assertFalse(on(h) { v.composing })
    }

    // ---- 5. Not understood ----

    @Test fun aFailedParseExplainsAndKeepsTheText() = home { h ->
        val v = addCalendar(h)
        type(h, v, "tomorrow 8pm")
        waitFor("error") { on(h) { v.addStatus.isShown && v.addStatus.text.toString() == app.getString(R.string.cal_add_failed) } }
        assertEquals("tomorrow 8pm", on(h) { v.addInput.text.toString() })
        assertFalse(on(h) { v.previewCard.isShown })
        assertTrue(on(h) { v.composing })
        shot("73-add-event-not-understood")
        // Fixed, it goes through.
        type(h, v, "Movie tomorrow 8pm")
        previewShown(h, v)
        assertEquals("Movie", on(h) { v.previewTitle.text.toString() })
    }

    // ---- 6. A tapped / long-pressed day prefills its date ----

    @Test fun aTappedDayIsTheEventsDay() = home { h ->
        val v = addCalendar(h)
        val day = today.plusDays(3).let { if (java.time.YearMonth.from(it) == java.time.YearMonth.from(today)) it else today.minusDays(2) }
        // Long-press the day: "Add event".
        val cell = on(h) { v.dayViews.first { it.day == day } }
        val r = android.graphics.Rect()
        main { cell.getGlobalVisibleRect(r) }
        shell("input swipe ${r.centerX()} ${r.centerY()} ${r.centerX()} ${r.centerY()} 900")
        waitFor("day menu") { on(h) { v.lastDayMenu != null } }
        shot("74-add-event-day-menu")
        h.onActivity {
            val menu = v.lastDayMenu!!.menu
            val item = (0 until menu.size()).map(menu::getItem).first { m -> m.title == app.getString(R.string.cal_add_event) }
            menu.performIdentifierAction(item.itemId, 0)
            v.lastDayMenu!!.dismiss()
        }
        waitFor("composer open") { on(h) { v.composing } }
        assertEquals(day, on(h) { v.selected })
        assertFalse("long-press on a day is not edit mode", on(h) { it.findViewById<io.github.iamth3doctor.searchlight.widgets.WidgetGridView>(R.id.widgetGrid).editMode })
        type(h, v, "Haircut 3pm")
        previewShown(h, v)
        assertEquals(day, on(h) { v.pendingDraft!!.date })
        assertEquals(LocalTime.of(15, 0), on(h) { v.pendingDraft!!.start })
    }

    // ---- 7. The edit sheet ----

    @Test fun editOpensTheFullSheetAndSavesWhatsEdited() = home { h ->
        val v = addCalendar(h)
        type(h, v, "Gym tomorrow 8-9pm")
        previewShown(h, v)
        h.onActivity { v.previewEdit.performClick() }
        waitFor("edit sheet") { EventEditSheet.current?.dialog?.isShowing == true }
        val sheet = EventEditSheet.current!!
        assertEquals("Gym", on(h) { sheet.titleField.text.toString() })
        assertTrue("every field", on(h) { listOf(sheet.dateRow, sheet.startRow, sheet.endRow, sheet.repeatRow, sheet.calendarRow, sheet.reminderRow).all { it.isShown } })
        assertFalse(on(h) { sheet.allDaySwitch.isChecked })
        assertTrue(on(h) { sheet.calendarRow.text.toString() }, on(h) { sheet.calendarRow.text.toString().endsWith("Add test") })
        shot("75-add-event-edit-sheet")
        val before = on(h) { it.eventAdder.lastSavedId }
        h.onActivity { sheet.titleField.setText("Gym session"); sheet.save() }
        val row = event(savedAfter(h, before))!!
        assertEquals("Gym session", row.title)
        assertEquals(at(today.plusDays(1), 20), row.start)
    }

    // ---- 8. The AI path (stood in for) and its fallback ----

    @Test fun aiResultsAreUsedAndFailuresFallBackToRules() = home { h ->
        val v = addCalendar(h)
        val day = today.plusDays(2)
        app.eventParser.aiOverride = { _ ->
            """{"understood":true,"title":"Lunch with Blake","date":"$day","all_day":false,"start":"12:00","end":"13:30","repeat":"none","days":[]}"""
        }
        type(h, v, "lunch w blake in 2 days at noon for 90 min")
        previewShown(h, v)
        assertEquals(EventParser.Source.AI, app.eventParser.lastSource)
        assertEquals("Lunch with Blake", on(h) { v.previewTitle.text.toString() })
        assertEquals(LocalTime.of(13, 30), on(h) { v.pendingDraft!!.end })

        // Offline / a failed call: the rules take over.
        app.eventParser.aiOverride = { _ -> throw java.io.IOException("offline") }
        type(h, v, "Dinner Fri 7pm")
        waitFor("rules preview") { on(h) { v.pendingDraft?.title == "Dinner" } }
        assertEquals(EventParser.Source.RULES, app.eventParser.lastSource)
    }

    /** The real Anthropic SDK on the device: a bad key is refused, and parsing falls back to the rules. */
    @Test fun theSdkRunsOnTheDeviceAndABadKeyFallsBack() {
        var result: String? = "pending"
        app.eventParser.test("sk-ant-invalid-searchlight-test") { result = it }
        waitFor("key test", 40_000) { result != "pending" }
        // "unauthorized" with a connection; "network" if the emulator is offline. Never a crash.
        assertTrue("test result: $result", result == "unauthorized" || result == "network")

        app.eventParser.apiKey = "sk-ant-invalid-searchlight-test"
        assertTrue(app.eventParser.hasKey)
        var draft: io.github.iamth3doctor.searchlight.calendar.EventDraft? = null
        var done = false
        app.eventParser.parse("Dinner Fri 7pm", today, today, zone) { draft = it.draft; done = true }
        waitFor("parse", 40_000) { done }
        assertEquals(EventParser.Source.RULES, app.eventParser.lastSource)
        assertEquals("Dinner", draft?.title)
    }

    // ---- 9. Settings > AI ----

    @Test fun settingsAiStoresTheKeyEncrypted() {
        val s = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            s.onActivity { a ->
                a.findViewById<TextView>(R.id.aiKeyField).text = "sk-ant-settings-test-key"
                a.findViewById<View>(R.id.aiKeySave).performClick()
            }
            assertTrue(app.eventParser.hasKey)
            assertEquals("sk-ant-settings-test-key", app.eventParser.apiKey)
            // On disk it's ciphertext, never the key.
            val raw = app.getSharedPreferences(SecretStore.FILE, 0).getString(SecretStore.AI_KEY, null)
            assertNotNull(raw)
            assertFalse("stored encrypted", raw!!.contains("sk-ant"))
            var status = ""
            s.onActivity { a -> status = a.findViewById<TextView>(R.id.aiKeyStatus).text.toString() }
            assertEquals(app.getString(R.string.settings_ai_saved), status)
            s.onActivity { a -> a.findViewById<View>(R.id.aiKeyRemove).performClick() }
            assertFalse(app.eventParser.hasKey)
        } finally {
            s.close()
        }
    }
}
