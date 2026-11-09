package io.github.iamth3doctor.searchlight

import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import io.github.iamth3doctor.searchlight.calendar.DayEvent
import io.github.iamth3doctor.searchlight.cards.CardType
import io.github.iamth3doctor.searchlight.cards.HomeCard
import io.github.iamth3doctor.searchlight.cards.NextUpCard
import io.github.iamth3doctor.searchlight.cards.WeatherCard
import io.github.iamth3doctor.searchlight.ui.Actions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Next up" as its own tile: while an event is in progress or starts within the hour, it's the
 * first tile, Weather takes Calendar & Date's place (still all there), and Calendar & Date is out;
 * its dot is gold; tapping joins the call or goes straight to the event in the calendar app;
 * past the event the strip is back in its usual order.
 */
@RunWith(AndroidJUnit4::class)
class NextUpTileDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"

    private val now = (System.currentTimeMillis() / 60_000L) * 60_000L + 5_000L
    @Volatile private var events = emptyList<DayEvent>()
    private var savedOrder = emptyList<CardType>()

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
        main { app.prefs.focusMode = false }
        savedOrder = app.prefs.enabledCards
        assertTrue("Weather and Calendar & Date are on", CardType.WEATHER in savedOrder && CardType.CALENDAR in savedOrder)
    }

    @After fun restore() {
        app.calendarEvents.permissionOverride = null
        app.calendarEvents.eventsOverride = null
        Actions.startCallLinks = true
        Actions.startCalendar = true
    }

    private fun home(block: (ActivityScenario<MainActivity>, NextUpCard) -> Unit) {
        val s = ActivityScenario.launch(MainActivity::class.java)
        try {
            Thread.sleep(1500)
            val card = on(s) { it.nextUpTile }
            s.onActivity { card.clock = { now } }
            block(s, card)
        } finally {
            runCatching { s.close() }
        }
    }

    private fun reload(h: ActivityScenario<MainActivity>, card: NextUpCard) = h.onActivity { card.reload() }

    private fun line(h: ActivityScenario<MainActivity>, card: NextUpCard) = on(h) { card.lineText?.toString() }

    /** The strip's tiles as kinds: "next", or the tile's type. */
    private fun strip(h: ActivityScenario<MainActivity>): List<String> =
        on(h) { it.homeCards.map { c: HomeCard -> if (c is NextUpCard) "next" else c.type.name } }

    private fun usual() = savedOrder.map { it.name }

    private fun pager(a: MainActivity): ViewPager2 = a.findViewById(R.id.cards)
    private fun folded(h: ActivityScenario<MainActivity>) = on(h) { pager(it).isShown }

    private fun dotGold(h: ActivityScenario<MainActivity>, index: Int): Boolean =
        on(h) { it.findViewById<ViewGroup>(R.id.dots).getChildAt(index)?.tag == MainActivity.DOT_EVENT }

    /** The tile's own (clickable) card view, found through the strip. */
    private fun tap(h: ActivityScenario<MainActivity>, card: NextUpCard) = h.onActivity {
        fun find(v: View): View? {
            if (v.id == R.id.card && (v as? ViewGroup)?.findViewById<View>(R.id.nextUpLine) != null) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { r -> return r }
            return null
        }
        find(it.window.decorView)!!.performClick()
    }

    /** What the Weather tile currently says (its line). */
    private fun weatherLine(h: ActivityScenario<MainActivity>): String = on(h) {
        val w = it.homeCards.first { c -> c is WeatherCard }
        fun find(v: View): String? {
            if (v.id == R.id.weatherLine) return (v as android.widget.TextView).text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { r -> return r }
            return null
        }
        check(w is WeatherCard)
        find(it.window.decorView).orEmpty()
    }

    @Test fun anEventIsTheFirstTileAndWeatherTakesTheCalendarSpot() = home { h, card ->
        assertEquals("the usual order", usual(), strip(h))
        events = listOf(DayEvent(1, "Team Sync", now + min(14), now + min(44), false, "Work", 0,
            location = "https://us02web.zoom.us/j/8812345678?pwd=abc"))
        reload(h, card)
        waitFor("event tile in front") { strip(h).firstOrNull() == "next" }

        // [next, Weather in Calendar's place, the rest]: Calendar & Date out, Weather moved, not gone.
        val expected = listOf("next") + usual().mapNotNull {
            when (it) { "CALENDAR" -> "WEATHER"; "WEATHER" -> null; else -> it }
        }
        assertEquals(expected, strip(h))
        assertEquals("• Team Sync starts in 14 min (Zoom)", line(h, card))
        assertFalse("weather still shows weather", weatherLine(h).startsWith("•"))
        if (folded(h)) {
            assertEquals("the event tile is shown first", 0, on(h) { pager(it).currentItem })
            assertTrue("its dot is gold", dotGold(h, 0))
            assertFalse(dotGold(h, 1))
            shell("screencap -p $dir/${prefix}_font1.0_92-next-up-starting.png")
            // Still swipeable: on to Weather, which works as ever.
            h.onActivity { pager(it).setCurrentItem(1, false) }
            assertEquals(1, on(h) { pager(it).currentItem })
            assertTrue("gold dot while on another tile", dotGold(h, 0))
            h.onActivity { pager(it).setCurrentItem(0, false) }
        } else {
            shell("screencap -p $dir/${prefix}_font1.0_92-next-up-starting.png")
        }

        // Tap: the call link.
        Actions.lastCallLink = null
        tap(h, card)
        assertEquals("https://us02web.zoom.us/j/8812345678?pwd=abc", Actions.lastCallLink)

        // Other platforms, live.
        for ((loc, suffix) in listOf("https://meet.google.com/abc-defg-hij" to " (Meet)",
            "https://teams.microsoft.com/l/meetup-join/19%3a" to " (Teams)", "https://acme.webex.com/meet/jane" to " (video link)", "Room 4" to "")) {
            events = listOf(DayEvent(1, "Team Sync", now + min(14), now + min(44), false, "Work", 0, location = loc))
            reload(h, card)
            waitFor("$suffix shown") { line(h, card) == "• Team Sync starts in 14 min$suffix" }
        }
    }

    @Test fun inProgressTapOpensTheEventInTheCalendarApp() = home { h, card ->
        events = listOf(DayEvent(2, "Design Review", now - min(8), now + min(22), false, "Work", 0))
        reload(h, card)
        waitFor("in progress shown") { line(h, card) == "• In progress: Design Review (Ends in 22 min)" }
        assertEquals("next", strip(h).first())
        shell("screencap -p $dir/${prefix}_font1.0_93-next-up-in-progress.png")

        // No call link: one tap, straight to the calendar app at the event (no overlay).
        Actions.lastCalendarUri = null
        tap(h, card)
        assertEquals(android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, 2L), Actions.lastCalendarUri)
        assertTrue("nothing of Searchlight's opened over home", on(h) { it.hasWindowFocus() })

        // A minute later the countdown moves on.
        h.onActivity { card.clock = { now + min(1) } }
        reload(h, card)
        waitFor("21 min") { line(h, card) == "• In progress: Design Review (Ends in 21 min)" }
    }

    @Test fun pastTheEventTheStripIsBackInItsUsualOrder() = home { h, card ->
        events = listOf(DayEvent(3, "Standup", now + min(5), now + min(20), false, "Work", 0))
        reload(h, card)
        waitFor("event tile in front") { strip(h).firstOrNull() == "next" }
        h.onActivity { card.clock = { now + min(21) } }
        reload(h, card)
        waitFor("usual order again") { strip(h) == usual() }
        if (folded(h)) assertFalse("no gold dot", (0 until usual().size).any { dotGold(h, it) })
        // Nothing within the hour (61 min away), or all-day: no event tile.
        events = listOf(DayEvent(4, "Later", now + min(82), now + min(90), false, "Work", 0),
            DayEvent(5, "Holiday", now - min(600), now + min(800), true, "Holidays", 0))
        reload(h, card)
        Thread.sleep(800)
        assertNull(on(h) { card.status })
        assertEquals(usual(), strip(h))
    }
}
