package io.github.iamth3doctor.searchlight

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.ClipboardManager
import android.content.ContentValues
import android.os.PowerManager
import android.os.SystemClock
import android.provider.CalendarContract
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.apps.Badges
import io.github.iamth3doctor.searchlight.cards.CardType
import io.github.iamth3doctor.searchlight.data.Prefs
import io.github.iamth3doctor.searchlight.gestures.GestureService
import io.github.iamth3doctor.searchlight.search.SearchResult
import io.github.iamth3doctor.searchlight.ui.BadgedImageView
import io.github.iamth3doctor.searchlight.widgets.WidgetGridView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search answers (math, units, currency, prefix shortcuts, calendar events), unread badges,
 * double tap to lock, and Focus mode, each as the person would use it. Runs folded and unfolded.
 */
@RunWith(AndroidJUnit4::class)
class NewFeaturesDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val sp get() = app.getSharedPreferences("searchlight", 0)

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

    private fun resumed() = shell("dumpsys activity activities").lineSequence()
        .firstOrNull { it.contains("mResumedActivity") || it.contains("ResumedActivity:") }.orEmpty()

    private fun home(block: (ActivityScenario<MainActivity>) -> Unit) {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        val s = ActivityScenario.launch(MainActivity::class.java)
        try {
            Thread.sleep(1500)
            block(s)
        } finally {
            runCatching { s.close() }
        }
    }

    private fun type(home: ActivityScenario<MainActivity>, text: String) {
        home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText(text) }
        Thread.sleep(900)
        instrumentation.waitForIdleSync()
    }

    private fun results(home: ActivityScenario<MainActivity>): List<SearchResult> = on(home) { it.currentResultsForTest() }

    @After fun cleanUp() {
        main { app.prefs.focusMode = false }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    // ---- 1. Search that answers ----

    @Test fun mathAnswersAndCopies() = home { h ->
        type(h, "42 x 18")
        val calc = results(h).firstOrNull { it is SearchResult.Calc } as? SearchResult.Calc
        assertNotNull("no answer: ${results(h)}", calc)
        assertEquals("756", calc!!.value)
        // The answer is the first row of its section and leads the list when no app matches.
        assertTrue(results(h).first() is SearchResult.Calc)
        shell("screencap -p $dir/${prefix}_font1.0_39-answer-math.png")
        h.onActivity { it.onClick(calc, it.findViewById(R.id.results)) }
        Thread.sleep(500)
        val clip = on(h) { it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
        assertEquals("756", clip)
    }

    @Test fun unitsAndCurrency() = home { h ->
        type(h, "10 miles to km")
        val unit = results(h).filterIsInstance<SearchResult.Convert>().firstOrNull()
        assertEquals("16.0934 km", unit?.value)
        shell("screencap -p $dir/${prefix}_font1.0_40-answer-units.png")

        // Rates as cached (the emulator may be offline): the answer, with the rates' date.
        main { app.currency.save("""{"amount":1.0,"base":"EUR","date":"2026-11-03","rates":{"USD":1.08,"GBP":0.84}}""") }
        type(h, "25 USD to EUR")
        val money = results(h).filterIsInstance<SearchResult.Convert>().firstOrNull()
        assertEquals("23.15 EUR", money?.value)
        assertTrue(money!!.detail, "2026-11-03" in money.detail)
        shell("screencap -p $dir/${prefix}_font1.0_41-answer-currency.png")
    }

    @Test fun prefixShortcutsSearchInsideTheApp() {
        home { h ->
            type(h, "y")
            assertTrue("hint: ${results(h)}", results(h).any { it is SearchResult.ShortcutHint })
            type(h, "y lofi")
            val yt = results(h).filterIsInstance<SearchResult.Shortcut>().single()
            assertEquals("lofi", yt.match.query)
            h.onActivity { it.onClick(yt, it.findViewById(R.id.results)) }
            waitFor("YouTube search (resumed: ${resumed()})", 20_000) { resumed().let { "youtube" in it || "chrome" in it || "browser" in it } }
        }
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1000)
        home { h ->
            type(h, "m tacos")
            val maps = results(h).filterIsInstance<SearchResult.Shortcut>().single()
            assertEquals("tacos", maps.match.query)
            h.onActivity { it.onClick(maps, it.findViewById(R.id.results)) }
            waitFor("Maps search (resumed: ${resumed()})", 20_000) { resumed().let { "maps" in it || "chrome" in it || "browser" in it } }
        }
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1000)
    }

    @Test fun upcomingCalendarEventsInResults() {
        shell("pm grant $pkg ${Manifest.permission.READ_CALENDAR}")
        val title = "Searchlight zebra standup"
        var eventId = -1L
        var calId = -1L
        // A local calendar and an event tomorrow, written as the shell (it may write calendars).
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        try {
            val cr = app.contentResolver
            val calUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "searchlight-test")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
            calId = cr.insert(calUri, ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, "searchlight-test")
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, "Searchlight test")
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Searchlight test")
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, "searchlight-test")
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            })!!.lastPathSegment!!.toLong()
            val start = System.currentTimeMillis() + 86_400_000L
            eventId = cr.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + 3_600_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            })!!.lastPathSegment!!.toLong()
        } finally {
            automation.dropShellPermissionIdentity()
        }
        try {
            home { h ->
                type(h, "zebra")
                waitFor("event in results: ${results(h)}") { results(h).any { it is SearchResult.Event } }
                val ev = results(h).filterIsInstance<SearchResult.Event>().first()
                assertEquals(title, ev.entry.title)
                shell("screencap -p $dir/${prefix}_font1.0_42-answer-event.png")
                h.onActivity { it.onClick(ev, it.findViewById(R.id.results)) }
                waitFor("calendar opened (resumed: ${resumed()})", 20_000) { "calendar" in resumed().lowercase() }
            }
            shell("input keyevent KEYCODE_HOME")
        } finally {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CALENDAR)
            runCatching {
                app.contentResolver.delete(CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "searchlight-test")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build(),
                    "${CalendarContract.Calendars._ID}=?", arrayOf(calId.toString()))
            }
            automation.dropShellPermissionIdentity()
        }
    }

    // ---- 2. Unread badges ----

    private fun badgeOf(root: View, label: String): BadgedImageView? {
        fun walk(v: View): BadgedImageView? {
            if (v is ViewGroup) {
                val icon = (0 until v.childCount).map(v::getChildAt).filterIsInstance<BadgedImageView>().firstOrNull()
                val text = (0 until v.childCount).map(v::getChildAt).filterIsInstance<android.widget.TextView>().firstOrNull()
                if (icon != null && text?.text?.toString() == label) return icon
                for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    @Test fun badgesAskOnceThenCountAndClear() {
        val listener = "$pkg/${pkg}.media.MediaListenerService"
        // No access, never asked: the home screen asks, once.
        shell("cmd notification disallow_listener $listener")
        sp.edit().putBoolean(Prefs.KEY_BADGE_ASKED, false).commit()
        home { h ->
            Thread.sleep(800)
            assertTrue("asked", on(h) { it.lastPrompt?.isShowing == true })
            shell("screencap -p $dir/${prefix}_font1.0_45-badges-ask.png")
            h.onActivity { it.lastPrompt?.dismiss() }
        }
        home { h ->
            Thread.sleep(800)
            assertFalse("asked twice", on(h) { it.lastPrompt?.isShowing == true })
        }

        // Access on, a notification posted: Searchlight's own icon shows 1, in results and the drawer.
        shell("cmd notification allow_listener $listener")
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_DEFAULT))
        var selfLabel = ""
        main { selfLabel = app.apps.apps.first { it.isSelf }.label }
        try {
            home { h ->
                nm.notify(42, android.app.Notification.Builder(app, "test").setSmallIcon(R.drawable.ic_search).setContentTitle("Hello").build())
                waitFor("count 1 (counts: ${Badges.counts})", 15_000) { Badges.countFor(pkg) == 1 }
                Thread.sleep(500)
                type(h, "Searchlight")
                val inResults = on(h) { a -> badgeOf(a.findViewById(R.id.results), selfLabel)?.badgeText }
                assertEquals("search result badge", "1", inResults)
                shell("screencap -p $dir/${prefix}_font1.0_43-badge-results.png")
                type(h, "")
                h.onActivity { it.openAppTable() }
                Thread.sleep(1500)
                assertEquals("drawer badge", "1", on(h) { a -> badgeOf(a.findViewById(R.id.appTableList), selfLabel)?.badgeText })

                // Dismissed: the badge goes.
                nm.cancel(42)
                waitFor("count 0") { Badges.countFor(pkg) == 0 }
                Thread.sleep(500)
                assertNull("drawer badge cleared", on(h) { a -> badgeOf(a.findViewById(R.id.appTableList), selfLabel)?.badgeText })
            }
        } finally {
            nm.cancelAll()
        }
    }

    // ---- 3. Double tap to lock ----

    private fun emptySpot(a: MainActivity): Pair<Int, Int> {
        val grid = a.findViewById<WidgetGridView>(R.id.widgetGrid)
        val loc = IntArray(2)
        grid.getLocationOnScreen(loc)
        val taken = (0 until grid.childCount).map { i ->
            val c = grid.getChildAt(i)
            android.graphics.Rect(loc[0] + c.left - 40, loc[1] + c.top - 40, loc[0] + c.right + 40, loc[1] + c.bottom + 40)
        }
        val xs = listOf(0.5f, 0.35f, 0.65f).map { (loc[0] + grid.width * it).toInt() }
        val ys = listOf(0.45f, 0.6f, 0.3f).map { (loc[1] + grid.height * it).toInt() }
        return xs.flatMap { x -> ys.map { y -> x to y } }.first { (x, y) -> taken.none { it.contains(x, y) } }
    }

    /** Two real taps 120ms apart, injected with exact timing (shell taps are too slow for a double tap). */
    private fun doubleTap(x: Int, y: Int) {
        fun tap() {
            val t = SystemClock.uptimeMillis()
            for ((action, at) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 40)) {
                val ev = MotionEvent.obtain(t, at, action, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                automation.injectInputEvent(ev, true)
                ev.recycle()
            }
        }
        tap()
        Thread.sleep(120)
        tap()
        Thread.sleep(800)
    }

    @Test fun doubleTapEmptySpaceLocks() {
        val service = "$pkg/$pkg.gestures.GestureService"
        // Without the accessibility service: asked once, then nothing.
        shell("settings delete secure enabled_accessibility_services")
        sp.edit().putBoolean(Prefs.KEY_LOCK_ASKED, false).commit()
        home { h ->
            val spot = on(h) { emptySpot(it) }
            doubleTap(spot.first, spot.second)
            assertEquals("no access", on(h) { it.lastDoubleTap })
            assertTrue("asked", on(h) { it.lastPrompt?.isShowing == true })
            h.onActivity { it.lastPrompt?.dismiss(); it.lastPrompt = null }
            Thread.sleep(500)
            doubleTap(spot.first, spot.second)
            assertFalse("asked twice", on(h) { it.lastPrompt?.isShowing == true })
        }

        shell("settings put secure enabled_accessibility_services $service")
        shell("settings put secure accessibility_enabled 1")
        try {
            waitFor("gesture service", 15_000) { GestureService.isConnected }
            home { h ->
                // A double tap on the home card (a tile, not empty space) does nothing new.
                val card = on(h) { a ->
                    val v = a.findViewById<View>(R.id.searchPill)
                    val l = IntArray(2)
                    v.getLocationOnScreen(l)
                    (l[0] + v.width / 2) to (l[1] + v.height / 2)
                }
                h.onActivity { it.lastDoubleTap = null }
                doubleTap(card.first, card.second)
                assertNull(on(h) { it.lastDoubleTap })
                assertTrue(app.getSystemService(PowerManager::class.java).isInteractive)

                // Long-press on empty space still opens widget edit mode.
                val spot = on(h) { emptySpot(it) }
                shell("input swipe ${spot.first} ${spot.second} ${spot.first} ${spot.second} 900")
                Thread.sleep(600)
                assertTrue("edit mode", on(h) { it.findViewById<WidgetGridView>(R.id.widgetGrid).editMode })
                @Suppress("DEPRECATION") h.onActivity { it.onBackPressed() }
                Thread.sleep(600)

                // A double tap on empty space locks.
                doubleTap(spot.first, spot.second)
                assertEquals("locked", on(h) { it.lastDoubleTap })
                waitFor("screen off / locked") {
                    !app.getSystemService(PowerManager::class.java).isInteractive ||
                        app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                }
            }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("settings delete secure enabled_accessibility_services")
        }
    }

    // ---- 4. Focus ----

    @Test fun focusShowsOnlySearchAndFavorites() {
        main { app.prefs.focusMode = false }
        home { h ->
            // The Focus toggle in the Quick Actions tile (folded: its pager page; unfolded: the bar).
            val qa = app.prefs.enabledCards.indexOf(CardType.QUICK_ACTIONS)
            h.onActivity { a -> a.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.cards).setCurrentItem(qa, false) }
            Thread.sleep(800)
            h.onActivity { a -> a.findViewById<View>(R.id.qaFocusCircle).performClick() }
            Thread.sleep(800)
            assertTrue(app.prefs.focusMode)
            h.onActivity { a ->
                assertFalse("tiles hidden", a.findViewById<View>(R.id.cardArea).isShown)
                val grid = a.findViewById<WidgetGridView>(R.id.widgetGrid)
                assertTrue("widgets hidden", (0 until grid.childCount).none { grid.getChildAt(it).isShown })
                assertTrue("search", a.findViewById<View>(R.id.searchIcon).let { it.isShown || a.findViewById<View>(R.id.rail).isShown })
                // No empty card frame left behind (unfolded, the search row waits for the rail's magnifier).
                val card = a.findViewById<View>(R.id.homeCard)
                assertTrue("empty card shown", !card.isShown || a.findViewById<View>(R.id.searchPill).isShown)
            }
            shell("screencap -p $dir/${prefix}_font1.0_44-focus-on.png")

            // Search still works, and launches.
            type(h, "Settings")
            assertTrue(results(h).any { it is SearchResult.App })
            type(h, "")
            // The favorites row still opens (folded: hold the magnifier; unfolded: the rail is there).
            h.onActivity { a ->
                if (a.findViewById<View>(R.id.rail).isShown) assertTrue(a.findViewById<ViewGroup>(R.id.railFavorites).childCount > 0)
                else a.favoritesPopup.show(a.findViewById(R.id.homeCard))
            }
            Thread.sleep(600)
            h.onActivity { a -> if (!a.findViewById<View>(R.id.rail).isShown) { assertTrue(a.favoritesPopup.isShowing); a.favoritesPopup.dismiss() } }
            // The drawer is still reachable.
            h.onActivity { it.openAppTable() }
            Thread.sleep(800)
            assertTrue(on(h) { it.findViewById<View>(R.id.appTable).isShown })
            @Suppress("DEPRECATION") h.onActivity { it.onBackPressed() }
            Thread.sleep(600)
        }
        // A restart keeps it on.
        home { h -> assertFalse("still focused after restart", on(h) { it.findViewById<View>(R.id.cardArea).isShown }) }

        // Off from Settings: the full layout back.
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            Thread.sleep(800)
            s.onActivity { a -> a.findViewById<Switch>(R.id.focusSwitch).isChecked = false }
        }
        assertFalse(app.prefs.focusMode)
        home { h ->
            h.onActivity { a ->
                assertTrue("tiles back", a.findViewById<View>(R.id.cardArea).isShown)
                val grid = a.findViewById<WidgetGridView>(R.id.widgetGrid)
                assertTrue("widgets back", (0 until grid.childCount).all { grid.getChildAt(it).visibility == View.VISIBLE })
            }
        }
    }
}
