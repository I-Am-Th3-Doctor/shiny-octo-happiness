package io.github.iamth3doctor.searchlight

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import io.github.iamth3doctor.searchlight.calendar.DayEvent
import io.github.iamth3doctor.searchlight.cards.CardType
import io.github.iamth3doctor.searchlight.cards.WeatherCard
import io.github.iamth3doctor.searchlight.ui.Actions
import io.github.iamth3doctor.searchlight.widgets.BuiltInWidget
import io.github.iamth3doctor.searchlight.widgets.GridSpec
import io.github.iamth3doctor.searchlight.widgets.WidgetController
import io.github.iamth3doctor.searchlight.widgets.WidgetPickerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Next up" in the Weather tile: an event starting within the hour, or in progress, takes the
 * tile's line (with its call platform); tapping joins the call, or shows the agenda; the tile's
 * page dot turns gold without the strip moving; past the event it's weather again by itself.
 */
@RunWith(AndroidJUnit4::class)
class NextUpTileDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"

    private val now = (System.currentTimeMillis() / 60_000L) * 60_000L + 5_000L
    private var events = emptyList<DayEvent>()
    private var savedFolded = emptyList<GridSpec>()
    private var savedUnfolded = emptyList<GridSpec>()

    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).use {
        java.io.FileInputStream(it.fileDescriptor).readBytes()
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> on(h: ActivityScenario<MainActivity>, read: (MainActivity) -> T): T {
        var v: T? = null
        h.onActivity { v = read(it) }
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

    private fun min(n: Long) = n * 60_000L

    @Before fun setUp() {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        app.calendarEvents.permissionOverride = true
        app.calendarEvents.eventsOverride = { _, _ -> events }
        Actions.startCallLinks = false
        Actions.startCalendar = false
        main {
            savedFolded = app.prefs.widgetGrid
            savedUnfolded = app.prefs.widgetGridUnfolded
            app.prefs.widgetGrid = emptyList()
            app.prefs.widgetGridUnfolded = emptyList()
            app.prefs.focusMode = false
        }
        assertTrue("the Weather tile is on", CardType.WEATHER in app.prefs.enabledCards)
    }

    @After fun restore() {
        app.calendarEvents.permissionOverride = null
        app.calendarEvents.eventsOverride = null
        Actions.startCallLinks = true
        Actions.startCalendar = true
        main {
            app.prefs.widgetGrid = savedFolded
            app.prefs.widgetGridUnfolded = savedUnfolded
        }
    }

    private fun home(block: (ActivityScenario<MainActivity>, WeatherCard) -> Unit) {
        val s = ActivityScenario.launch(MainActivity::class.java)
        try {
            Thread.sleep(1500)
            val card = on(s) { it.homeCards.filterIsInstance<WeatherCard>().single() }
            s.onActivity { card.clock = { now } }
            block(s, card)
        } finally {
            runCatching { s.close() }
        }
    }

    private fun reload(h: ActivityScenario<MainActivity>, card: WeatherCard) = h.onActivity { card.reloadNextUp() }

    private fun line(h: ActivityScenario<MainActivity>, card: WeatherCard) = on(h) { card.lineText?.toString() }

    private fun pager(a: MainActivity): ViewPager2 = a.findViewById(R.id.cards)
    private fun weatherIndex(a: MainActivity) = a.homeCards.indexOfFirst { c -> c is WeatherCard }
    /** Folded: one tile at a time, with page dots (unfolded shows every tile side by side). */
    private fun folded(h: ActivityScenario<MainActivity>) = on(h) { pager(it).isShown }
    private fun currentTile(h: ActivityScenario<MainActivity>) = on(h) { pager(it).currentItem }
    private fun showWeatherTile(h: ActivityScenario<MainActivity>) = h.onActivity { pager(it).setCurrentItem(weatherIndex(it), false) }

    private fun dotIsGold(h: ActivityScenario<MainActivity>): Boolean = on(h) {
        val dots = it.findViewById<ViewGroup>(R.id.dots)
        dots.getChildAt(it.homeCards.indexOfFirst { c -> c is WeatherCard })?.tag == MainActivity.DOT_EVENT
    }

    private fun tap(h: ActivityScenario<MainActivity>, card: WeatherCard) = h.onActivity {
        val tiles = it.homeCards.indexOf(card)
        // The tile's own clickable card view: found from its line.
        fun find(v: View): View? {
            if (v.id == R.id.card && (v as? ViewGroup)?.findViewById<View>(R.id.weatherLine) != null) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { r -> return r }
            return null
        }
        check(tiles >= 0)
        find(it.window.decorView)!!.performClick()
    }

    @Test fun anEventStartingWithinTheHourTakesTheTileAndJoinsTheCall() = home { h, card ->
        // On another tile than Weather (folded): the strip must stay there.
        val other = if (folded(h)) on(h) { (weatherIndex(it) + 1) % it.homeCards.size } else -1
        if (other >= 0) h.onActivity { pager(it).setCurrentItem(other, false) }

        events = listOf(DayEvent(1, "Team Sync", now + min(14), now + min(44), false, "Work", 0,
            location = "https://us02web.zoom.us/j/8812345678?pwd=abc"))
        reload(h, card)
        waitFor("next up shown") { on(h) { card.nextUp != null } }
        assertEquals("• Team Sync starts in 14 min (Zoom)", line(h, card))
        assertFalse("no weather icon while it shows an event", on(h) { card.iconShown })
        if (other >= 0) {
            assertEquals("the strip didn't move", other, currentTile(h))
            assertTrue("gold dot on the Weather tile's page", dotIsGold(h))
        }

        // Tap: the call link opens.
        Actions.lastCallLink = null
        tap(h, card)
        assertEquals("https://us02web.zoom.us/j/8812345678?pwd=abc", Actions.lastCallLink)

        if (folded(h)) showWeatherTile(h)
        instrumentation.waitForIdleSync()
        Thread.sleep(800)
        shell("screencap -p $dir/${prefix}_font1.0_92-next-up-starting.png")

        // Other platforms, live: Meet, Teams, any other call link, and none.
        for ((loc, suffix) in listOf("https://meet.google.com/abc-defg-hij" to " (Meet)",
            "https://teams.microsoft.com/l/meetup-join/19%3a" to " (Teams)", "https://acme.webex.com/meet/jane" to " (video link)", "Room 4" to "")) {
            events = listOf(DayEvent(1, "Team Sync", now + min(14), now + min(44), false, "Work", 0, location = loc))
            reload(h, card)
            waitFor("$suffix shown") { line(h, card) == "• Team Sync starts in 14 min$suffix" }
        }
    }

    @Test fun inProgressCountsDownAndTapShowsTheAgenda() = home { h, card ->
        events = listOf(DayEvent(2, "Design Review", now - min(8), now + min(22), false, "Work", 0))
        reload(h, card)
        waitFor("in progress shown") { line(h, card) == "• In progress: Design Review (Ends in 22 min)" }
        if (folded(h)) assertTrue(dotIsGold(h))
        if (folded(h)) showWeatherTile(h)
        instrumentation.waitForIdleSync()
        Thread.sleep(800)
        shell("screencap -p $dir/${prefix}_font1.0_93-next-up-in-progress.png")

        // No call link, no Calendar widget: the calendar app on today.
        Actions.lastCalendarUri = null
        tap(h, card)
        assertEquals("app", on(h) { it.lastAgenda })
        assertTrue(Actions.lastCalendarUri != null)

        // With the Calendar widget on home: it goes to today instead.
        h.onActivity {
            it.widgetController.onActivityResult(WidgetController.REQUEST_PICK, Activity.RESULT_OK,
                Intent().putExtra(WidgetPickerActivity.EXTRA_BUILTIN, BuiltInWidget.CALENDAR.key))
        }
        waitFor("calendar widget") { on(h) { it.widgetController.calendarViews().isNotEmpty() } }
        val v = on(h) { it.widgetController.calendarViews().single() }
        h.onActivity { v.shiftMonth(2) }
        tap(h, card)
        assertEquals("widget", on(h) { it.lastAgenda })
        waitFor("widget back on today") { on(h) { v.selected == v.today && v.month == java.time.YearMonth.from(v.today) } }

        // A minute later the countdown moves on.
        h.onActivity { card.clock = { now + min(1) } }
        reload(h, card)
        waitFor("21 min") { line(h, card) == "• In progress: Design Review (Ends in 21 min)" }
    }

    @Test fun pastTheEventItsWeatherAgain() = home { h, card ->
        events = listOf(DayEvent(3, "Standup", now + min(5), now + min(20), false, "Work", 0))
        reload(h, card)
        waitFor("shown") { on(h) { card.nextUp != null } }
        // The event's over: back to weather by itself, the dot plain again.
        h.onActivity { card.clock = { now + min(21) } }
        reload(h, card)
        waitFor("weather again") { on(h) { card.nextUp == null } }
        assertTrue(on(h) { card.iconShown })
        assertFalse(line(h, card).orEmpty().startsWith("•"))
        if (folded(h)) assertFalse(dotIsGold(h))
        // Nothing within the hour (61 min away), or all-day: weather.
        events = listOf(DayEvent(4, "Later", now + min(82), now + min(90), false, "Work", 0),
            DayEvent(5, "Holiday", now - min(600), now + min(800), true, "Holidays", 0))
        reload(h, card)
        Thread.sleep(800)
        assertNull(on(h) { card.nextUp })
    }
}
