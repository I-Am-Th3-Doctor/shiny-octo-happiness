package io.github.iamth3doctor.searchlight

import android.Manifest
import android.app.UiAutomation
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.calendar.DayEvent
import io.github.iamth3doctor.searchlight.widgets.CalendarWidgetView
import io.github.iamth3doctor.searchlight.widgets.GridSpec
import io.github.iamth3doctor.searchlight.widgets.StickyNoteWidgetView
import io.github.iamth3doctor.searchlight.widgets.WidgetGridView
import io.github.iamth3doctor.searchlight.widgets.WidgetPickerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Sticky note widget is not part of Searchlight (it's kept for a second launcher): it isn't in
 * the widget picker, and one placed before leaves the home screen, while the Month + Day Calendar
 * widget beside it stays, showing its events as before. Runs folded and unfolded.
 */
@RunWith(AndroidJUnit4::class)
class StickyNoteRemovedDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val zone = ZoneId.systemDefault()

    private var savedFolded = emptyList<GridSpec>()
    private var savedUnfolded = emptyList<GridSpec>()
    private var savedTypes = emptyMap<Int, String>()

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
            savedTypes = app.prefs.builtInTypes
            app.prefs.focusMode = false
        }
        app.calendarEvents.permissionOverride = true
        app.calendarEvents.eventsOverride = { _, _ -> events() }
    }

    @After fun restore() {
        app.calendarEvents.permissionOverride = null
        app.calendarEvents.eventsOverride = null
        main {
            app.prefs.widgetGrid = savedFolded
            app.prefs.widgetGridUnfolded = savedUnfolded
            app.prefs.builtInTypes = savedTypes
        }
        shell("input keyevent KEYCODE_HOME")
    }

    private val today get() = LocalDate.now(zone)
    private fun at(h: Int) = today.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun events() = listOf(DayEvent(102, "Design review", at(14), at(15), false, "Work", 0xFF4285F4.toInt()))

    private fun texts(root: View): List<String> =
        if (root is TextView && root.isShown) listOf(root.text.toString())
        else (root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { texts(g.getChildAt(it)) } }.orEmpty()

    private fun all(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Test fun theWidgetPickerOffersNoStickyNote() {
        val picker = ActivityScenario.launchActivityForResult(WidgetPickerActivity::class.java)
        try {
            val seen = HashSet<String>()
            waitFor("the Calendar in the picker") {
                picker.onActivity { a -> seen += texts(a.window.decorView) }
                app.getString(R.string.widget_calendar) in seen
            }
            // Down the whole list: nowhere a Sticky note.
            repeat(12) {
                picker.onActivity { a ->
                    fun find(x: View): RecyclerView? = x as? RecyclerView ?: (x as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
                    find(a.window.decorView)?.let { it.scrollBy(0, it.height / 2) }
                }
                instrumentation.waitForIdleSync()
                picker.onActivity { a -> seen += texts(a.window.decorView) }
            }
            assertFalse("no Sticky note: $seen", seen.any { it.contains("Sticky", ignoreCase = true) })
            assertTrue("the Calendar and the Weather widget still offered",
                app.getString(R.string.widget_calendar) in seen && app.getString(R.string.widget_weather) in seen)
        } finally {
            picker.close()
        }
    }

    @Test fun aStickyNotePlacedBeforeLeavesTheHomeScreenAndTheCalendarStays() {
        main {
            app.prefs.builtInTypes = mapOf(-21 to "calendar", -22 to "sticky_note")
            val grid = listOf(GridSpec(-21, 0, 0, 4, 3), GridSpec(-22, 0, 3, 2, 2))
            app.prefs.widgetGrid = grid
            app.prefs.widgetGridUnfolded = grid
        }
        val h = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitFor("the Calendar widget on the grid") { on(h) { it.widgetController.calendarViews().any { v -> v.isAttachedToWindow && v.width > 0 } } }
            val grid = on(h) { it.findViewById<WidgetGridView>(R.id.widgetGrid) }
            assertTrue("no sticky note on the home screen", on(h) { all(grid).none { it is StickyNoteWidgetView } })
            assertEquals("only the Calendar is left", listOf(GridSpec(-21, 0, 0, 4, 3)), on(h) { app.prefs.widgetGrid })
            assertEquals(listOf(GridSpec(-21, 0, 0, 4, 3)), on(h) { app.prefs.widgetGridUnfolded })
            assertNull("its record gone too", on(h) { app.prefs.builtInTypes[-22] })
            // The Month + Day widget as ever: its month and today's agenda, with the event.
            val cal: CalendarWidgetView = on(h) { it.widgetController.calendarViews().single() }
            waitFor("today's event in the agenda") { on(h) { "Design review" in texts(cal.eventList) } }
            assertEquals("resizes as ever", true to true, on(h) { it.widgetController.resizable(-21) })
            shot("130-calendar-without-sticky-note")
        } finally {
            runCatching { h.close() }
        }
    }
}
