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
import io.github.iamth3doctor.searchlight.ui.CollapsibleSections
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

    /**
     * Did an app matching [names] open? Its screen on top, or its task in recents (on a first
     * launch an app may put a permission dialog over itself, which is then what's on top).
     */
    private fun opened(vararg names: String): Boolean {
        val top = resumed().lowercase()
        if (names.any { it in top }) return true
        return shell("dumpsys activity recents").lineSequence()
            .filter { "Recent #" in it }.take(3).any { line -> names.any { it in line.lowercase() } }
    }

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
            waitFor("YouTube search (resumed: ${resumed()})", 20_000) { opened("youtube", "chrome", "browser") }
        }
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1000)
        home { h ->
            type(h, "m tacos")
            val maps = results(h).filterIsInstance<SearchResult.Shortcut>().single()
            assertEquals("tacos", maps.match.query)
            h.onActivity { it.onClick(maps, it.findViewById(R.id.results)) }
            waitFor("Maps search (resumed: ${resumed()})", 20_000) { opened("maps", "chrome", "browser") }
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

    private fun pager(a: MainActivity) = a.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.cards)

    /** The Focus button's centre on screen, after bringing its tile into view (folded: its pager page). */
    private fun focusButton(h: ActivityScenario<MainActivity>): Pair<Int, Int> {
        val qa = app.prefs.enabledCards.indexOf(CardType.QUICK_ACTIONS)
        h.onActivity { a -> if (!app.prefs.focusMode && pager(a).adapter != null) pager(a).setCurrentItem(qa, false) }
        Thread.sleep(800)
        return on(h) { a ->
            val v = a.findViewById<View>(R.id.qaFocusCircle)
            assertTrue("Focus button on screen", v.isShown)
            val l = IntArray(2)
            v.getLocationOnScreen(l)
            (l[0] + v.width / 2) to (l[1] + v.height / 2)
        }
    }

    private fun tap(p: Pair<Int, Int>) {
        shell("input tap ${p.first} ${p.second}")
        Thread.sleep(900)
        instrumentation.waitForIdleSync()
    }

    /** What home shows: (tiles shown by type, folded page, widgets shown, favorites). */
    private fun layout(h: ActivityScenario<MainActivity>) = on(h) { a ->
        val grid = a.findViewById<WidgetGridView>(R.id.widgetGrid)
        listOf(
            a.findViewById<View>(R.id.cardArea).isShown,
            if (pager(a).adapter != null) pager(a).currentItem else -1,
            (0 until grid.childCount).count { grid.getChildAt(it).isShown },
            app.prefs.favorites.toList(),
        )
    }

    /** Focus as it should look: the Focus button and search/favorites, nothing else and nothing new. */
    private fun assertFocused(h: ActivityScenario<MainActivity>) = h.onActivity { a ->
        assertTrue(app.prefs.focusMode)
        val row = a.findViewById<ViewGroup>(R.id.tileRow)
        assertTrue("only the Focus button's tile", row.isShown && row.childCount == 1)
        assertTrue("the Focus button stays", a.findViewById<View>(R.id.qaFocusCircle).isShown)
        for (id in listOf(R.id.qaSlot0, R.id.qaSlot1, R.id.qaSlot2, R.id.qaSlot3)) assertFalse("toggle shown", a.findViewById<View>(id).isShown)
        assertFalse("pager", a.findViewById<View>(R.id.cards).isShown)
        assertFalse("dots", a.findViewById<View>(R.id.dots).isShown)
        val grid = a.findViewById<WidgetGridView>(R.id.widgetGrid)
        assertTrue("widgets hidden", (0 until grid.childCount).none { grid.getChildAt(it).isShown })
    }

    /** Two fingers down and up together on empty home space, injected as one real touch. */
    private fun twoFingerTap(h: ActivityScenario<MainActivity>) {
        val (x, y) = on(h) { emptySpot(it) }
        val t = SystemClock.uptimeMillis()
        val props = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        fun coords(dx: Float) = MotionEvent.PointerCoords().apply { this.x = x + dx; this.y = y.toFloat(); pressure = 1f; size = 1f }
        val both = arrayOf(coords(-60f), coords(60f))
        fun ev(action: Int, at: Long, n: Int) = MotionEvent.obtain(t, at, action, n, props, both, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        val events = listOf(
            ev(MotionEvent.ACTION_DOWN, t, 1),
            ev(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), t + 20, 2),
            ev(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), t + 120, 2),
            ev(MotionEvent.ACTION_UP, t + 140, 1),
        )
        for (e in events) {
            automation.injectInputEvent(e, true)
            e.recycle()
        }
        Thread.sleep(900)
        instrumentation.waitForIdleSync()
    }

    @Test fun focusTogglesFromItsButtonAndLeavesEveryWay() {
        main { app.prefs.focusMode = false }
        home { h ->
            // Folded: start on another page, to check the page comes back too.
            h.onActivity { a -> if (pager(a).adapter != null) pager(a).setCurrentItem(0, false) }
            Thread.sleep(600)
            val button = focusButton(h)
            val before = on(h) { a ->
                listOf(true, if (pager(a).adapter != null) pager(a).currentItem else -1,
                    a.findViewById<WidgetGridView>(R.id.widgetGrid).let { g -> (0 until g.childCount).count { g.getChildAt(it).isShown } },
                    app.prefs.favorites.toList())
            }

            // 1. In with the button, out with the same button: no Settings.
            tap(button)
            assertFocused(h)
            shell("screencap -p $dir/${prefix}_font1.0_44-focus-on.png")
            // Search and favorites keep working while focused.
            // (In Focus only allowed apps show: search for one of them.)
            var allowedLabel = ""
            main { allowedLabel = app.prefs.focusAllowlist.firstNotNullOf { app.apps.findByPackage(it) }.label }
            type(h, allowedLabel)
            assertTrue(results(h).any { it is SearchResult.App && it.entry.packageName in app.prefs.focusAllowlist })
            type(h, "")
            tap(focusButton(h))
            assertFalse(app.prefs.focusMode)
            assertEquals("Focus mode off", on(h) { it.lastToast })
            assertEquals("layout restored", before, layout(h))
            h.onActivity { it.lastToast = null }

            // 3. In with the button, out with a two-finger tap.
            tap(focusButton(h))
            assertFocused(h)
            twoFingerTap(h)
            assertFalse("two-finger tap left Focus", app.prefs.focusMode)
            assertEquals("Focus mode off", on(h) { it.lastToast })
            assertEquals("layout restored", before, layout(h))
            h.onActivity { it.lastToast = null }
        }

        // 2. In from Settings (and still on after a restart), out with the button.
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            Thread.sleep(800)
            s.onActivity { a -> a.findViewById<Switch>(R.id.focusSwitch).isChecked = true }
        }
        assertTrue(app.prefs.focusMode)
        home { h ->
            assertFocused(h)
            tap(focusButton(h))
            assertFalse(app.prefs.focusMode)
            h.onActivity { a -> assertTrue("tiles back", a.findViewById<View>(R.id.cardArea).isShown) }
            if (!unfoldedNow()) h.onActivity { a -> assertTrue("pager back", a.findViewById<View>(R.id.cards).isShown) }
        }

        // And Settings' switch still turns it off.
        main { app.prefs.focusMode = true }
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            Thread.sleep(800)
            s.onActivity { a -> a.findViewById<Switch>(R.id.focusSwitch).isChecked = false }
        }
        assertFalse(app.prefs.focusMode)
    }

    // ---- Focus wears clear glass ----

    /** The glass setting as stored, as the surfaces use it, and the home card's drawn opacity. */
    private fun glass(h: ActivityScenario<MainActivity>): Triple<Int, Int, Int> = on(h) { a ->
        val card = a.findViewById<View>(R.id.homeCard).background as io.github.iamth3doctor.searchlight.ui.FrostedDrawable
        Triple(app.prefs.glassLevel, io.github.iamth3doctor.searchlight.ui.FrostedDrawable.glassLevel, card.drawnColor ushr 24)
    }

    /** The home card's opacity at [level], for the way it draws (over the blur or not). */
    private fun expectedCardAlpha(h: ActivityScenario<MainActivity>, level: Int): Int = on(h) { a ->
        val card = a.findViewById<View>(R.id.homeCard).background as io.github.iamth3doctor.searchlight.ui.FrostedDrawable
        val sample = io.github.iamth3doctor.searchlight.ui.WallpaperBlur.sampledColor()
        val stock = when {
            !card.drewBlur -> a.getColor(R.color.glass_card_fallback)
            sample != null -> io.github.iamth3doctor.searchlight.ui.GlassTint.tint(sample, io.github.iamth3doctor.searchlight.ui.GlassTint.Layer.CARD)
            else -> a.getColor(R.color.glass_card)
        }
        if (sample != null && card.drewBlur) io.github.iamth3doctor.searchlight.ui.GlassTint.tint(sample, io.github.iamth3doctor.searchlight.ui.GlassTint.Layer.CARD, level) ushr 24
        else io.github.iamth3doctor.searchlight.ui.GlassTint.scale(stock, level) ushr 24
    }

    private fun assertGlass(h: ActivityScenario<MainActivity>, level: Int, what: String) {
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
        val (stored, drawnLevel, cardAlpha) = glass(h)
        assertEquals("$what: stored", level, stored)
        assertEquals("$what: surfaces", level, drawnLevel)
        assertEquals("$what: card drawn", expectedCardAlpha(h, level), cardAlpha)
    }

    /** What Settings' Glass transparency slider and readout say. */
    private fun settingsGlass(): Pair<Int, String> {
        var out = 0 to ""
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            Thread.sleep(800)
            s.onActivity { a ->
                out = a.findViewById<android.widget.SeekBar>(R.id.glassSlider).progress to
                    a.findViewById<android.widget.TextView>(R.id.glassValue).text.toString()
            }
        }
        return out
    }

    private fun setGlass(level: Int) = main {
        app.prefs.setGlassLevelByUser(level)
        io.github.iamth3doctor.searchlight.ui.FrostedDrawable.setGlassLevel(level)
    }

    @Test fun focusWearsClearGlassAndGivesItBack() {
        main { app.prefs.focusMode = false }
        try {
            // At 70, 0 and 100: in with the button, everything clear (Settings says 0), out, exactly back.
            for ((level, exitByTwoFingers) in listOf(70 to true, 0 to false, 100 to false)) {
                setGlass(level)
                home { h ->
                    assertGlass(h, level, "before Focus")
                    tap(focusButton(h))
                    assertTrue(app.prefs.focusMode)
                    assertGlass(h, 0, "in Focus (from $level)")
                    if (level == 70) shell("screencap -p $dir/${prefix}_font1.0_46-focus-clear-glass.png")
                }
                assertEquals("Settings in Focus", 0 to "0%", settingsGlass())
                home { h ->
                    if (exitByTwoFingers) twoFingerTap(h) else tap(focusButton(h))
                    assertFalse(app.prefs.focusMode)
                    assertGlass(h, level, "after Focus (was $level)")
                }
                assertEquals("Settings after Focus", level to "$level%", settingsGlass())
            }

            // In from Settings, out with the dock button.
            setGlass(70)
            ActivityScenario.launch(SettingsActivity::class.java).use { s ->
                Thread.sleep(800)
                s.onActivity { a -> a.findViewById<Switch>(R.id.focusSwitch).isChecked = true }
                Thread.sleep(400)
                s.onActivity { a -> assertEquals("Settings shows 0 at once", 0, a.findViewById<android.widget.SeekBar>(R.id.glassSlider).progress) }
            }
            home { h ->
                assertGlass(h, 0, "in Focus from Settings")
                tap(focusButton(h))
                assertGlass(h, 70, "out with the button")
            }

            // In, out, in, out: still the original.
            home { h ->
                tap(focusButton(h)); assertGlass(h, 0, "first time in")
                tap(focusButton(h)); assertGlass(h, 70, "first time out")
                tap(focusButton(h)); assertGlass(h, 0, "second time in")
                tap(focusButton(h)); assertGlass(h, 70, "second time out")
            }
            // Turning it on again while on doesn't save the clear level.
            main { app.prefs.focusMode = true; app.prefs.focusMode = true; app.prefs.focusMode = false }
            assertEquals(70, app.prefs.glassLevel)

            // The slider moved while in Focus: that level stays after leaving.
            home { h -> tap(focusButton(h)); assertGlass(h, 0, "in") }
            var moved = -1
            ActivityScenario.launch(SettingsActivity::class.java).use { s ->
                Thread.sleep(800)
                s.onActivity { a ->
                    if (CollapsibleSections.bodyOf(a.window.decorView, "appearance")!!.visibility != View.VISIBLE) {
                        CollapsibleSections.headerOf(a.window.decorView, "appearance")!!.performClick()
                    }
                }
                Thread.sleep(700)
                s.onActivity { a -> a.findViewById<View>(R.id.glassRow).let { it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height), true) } }
                Thread.sleep(700)
                val (x, y) = run {
                    var p = 0 to 0
                    s.onActivity { a ->
                        val bar = a.findViewById<android.widget.SeekBar>(R.id.glassSlider)
                        val l = IntArray(2)
                        bar.getLocationOnScreen(l)
                        p = (l[0] + bar.paddingLeft + ((bar.width - bar.paddingLeft - bar.paddingRight) * 0.35f).toInt()) to (l[1] + bar.height / 2)
                    }
                    p
                }
                shell("input tap $x $y")
                Thread.sleep(800)
                s.onActivity { a -> moved = a.findViewById<android.widget.SeekBar>(R.id.glassSlider).progress }
            }
            assertTrue("slider moved: $moved", moved in 20..50)
            home { h ->
                tap(focusButton(h))
                assertFalse(app.prefs.focusMode)
                assertGlass(h, moved, "manual level kept")
            }
        } finally {
            main { app.prefs.focusMode = false }
            setGlass(io.github.iamth3doctor.searchlight.ui.GlassTint.LEVEL_DEFAULT)
        }
    }

    // ---- Focus: Do Not Disturb, allowed apps, Blocked / Hidden ----

    private val nm get() = app.getSystemService(android.app.NotificationManager::class.java)

    /** The apps the drawer lists (all of them, not just those on screen). */
    private fun drawerPackages(h: ActivityScenario<MainActivity>): List<String> {
        h.onActivity { it.openAppTable() }
        Thread.sleep(1000)
        instrumentation.waitForIdleSync()
        val shown = on(h) { a ->
            (a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.appTableList).adapter as io.github.iamth3doctor.searchlight.ui.AppTableAdapter)
                .items.map { it.packageName }
        }
        @Suppress("DEPRECATION") h.onActivity { it.onBackPressed() }
        Thread.sleep(600)
        return shown
    }

    private fun entryOf(p: String): io.github.iamth3doctor.searchlight.apps.AppEntry {
        var e: io.github.iamth3doctor.searchlight.apps.AppEntry? = null
        main { e = app.apps.findByPackage(p) }
        return e ?: throw AssertionError("$p not installed")
    }

    @Test fun focusTurnsOnDoNotDisturbAndRestoresIt() {
        shell("cmd notification allow_dnd $pkg")
        main {
            app.prefs.focusMode = false
            nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        val policy = nm.notificationPolicy.toString()
        try {
            home { h ->
                // In with the dock button: DND on (priority: the person's own exceptions), rules untouched.
                tap(focusButton(h))
                waitFor("DND on") { nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY }
                assertEquals("DND rules untouched", policy, nm.notificationPolicy.toString())
                // Out with a two-finger tap: exactly as before.
                twoFingerTap(h)
                waitFor("DND back off") { nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL }

                // Already in DND (alarms only) before Focus: left as it was, and still that after.
                main { nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS) }
                tap(focusButton(h))
                assertEquals(android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS, nm.currentInterruptionFilter)
                // Entering again while on doesn't overwrite what was saved.
                main { app.prefs.focusMode = true }
                tap(focusButton(h))
                waitFor("alarms-only back") { nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS }
            }
            // In and out from Settings: the same.
            main { nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL) }
            ActivityScenario.launch(SettingsActivity::class.java).use { s ->
                Thread.sleep(800)
                s.onActivity { a -> a.findViewById<Switch>(R.id.focusSwitch).isChecked = true }
                waitFor("DND on from Settings") { nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY }
                s.onActivity { a -> a.findViewById<Switch>(R.id.focusSwitch).isChecked = false }
                waitFor("DND off from Settings") { nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL }
            }
        } finally {
            main { app.prefs.focusMode = false; nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL) }
        }
    }

    @Test fun focusWithoutDndAccessStillWorksAndSaysSoOnce() {
        main { app.prefs.focusMode = false }
        shell("cmd notification disallow_dnd $pkg")
        app.getSharedPreferences("searchlight", 0).edit()
            .putBoolean(io.github.iamth3doctor.searchlight.data.Prefs.KEY_FOCUS_DND_ASKED, false)
            .putBoolean(io.github.iamth3doctor.searchlight.data.Prefs.KEY_FOCUS_DND_NOTICE, false).commit()
        val before = nm.currentInterruptionFilter
        home { h ->
            tap(focusButton(h))
            assertTrue("Focus on", app.prefs.focusMode)
            assertTrue("asked to grant access", on(h) { it.lastPrompt?.isShowing == true })
            assertEquals("DND untouched", before, nm.currentInterruptionFilter)
            h.onActivity { it.lastPrompt?.dismiss(); it.lastPrompt = null }
            assertFocused(h) // the declutter works without DND
            tap(focusButton(h))
            // Next time: no prompt, a one-time notice instead.
            h.onActivity { it.lastToast = null }
            tap(focusButton(h))
            assertFalse("asked twice", on(h) { it.lastPrompt?.isShowing == true })
            assertEquals(app.getString(R.string.focus_dnd_unavailable), on(h) { it.lastToast })
            tap(focusButton(h))
            h.onActivity { it.lastToast = null }
            tap(focusButton(h))
            assertEquals("notice once", null, on(h) { it.lastToast })
            tap(focusButton(h))
        }
    }

    @Test fun allowedAppsOnlyAndBlockedOrHiddenForTheRest() {
        main { app.prefs.focusMode = false; app.prefs.focusRestriction = io.github.iamth3doctor.searchlight.focus.FocusRules.Restriction.BLOCKED }
        val allowed = app.autoFavorites.defaultPackages().filter { p -> app.apps.apps.any { it.packageName == p } }.toSet()
        assertTrue("default phone/messaging apps: $allowed", allowed.isNotEmpty())
        var savedAllow = emptySet<String>()
        main { savedAllow = app.prefs.focusAllowlist; app.prefs.focusAllowlist = allowed }
        val chrome = entryOf("com.android.chrome")
        try {
            home { h ->
                val everyone = drawerPackages(h)
                assertTrue(chrome.packageName in everyone)
                tap(focusButton(h))
                // Drawer, search and favorites: only the allowed apps.
                assertEquals(allowed, drawerPackages(h).toSet())
                type(h, "Chrome")
                assertTrue("restricted in search: ${results(h)}", results(h).none { it is SearchResult.App })
                type(h, "")
                var row = emptyList<String>()
                main { row = io.github.iamth3doctor.searchlight.ui.homeFavorites(app.prefs, app.apps).mapNotNull { it?.entry?.packageName } }
                assertTrue("favorites: $row", row.all { it in allowed })

                // Blocked: opening Chrome from Searchlight (its shortcut, or the app) is paused; it doesn't open.
                h.onActivity { a -> a.showAppMenu(chrome, a.findViewById(R.id.searchPill)) }
                Thread.sleep(800)
                h.onActivity { a ->
                    val rows = a.appMenu.rowsView!!
                    (0 until rows.childCount).map(rows::getChildAt).first { it is ViewGroup }.performClick() // its first shortcut
                }
                waitFor("paused screen (resumed: ${resumed()})") { "PausedActivity" in resumed() }
                assertFalse("Chrome opened", "com.android.chrome" in resumed())
                shell("screencap -p $dir/${prefix}_font1.0_47-focus-paused.png")
                shell("input keyevent KEYCODE_BACK") // "Go back"
                Thread.sleep(1000)
                h.onActivity { it.onClick(SearchResult.App(chrome), it.findViewById(R.id.results)) }
                waitFor("paused again") { "PausedActivity" in resumed() }

                // "Exit focus mode" on that screen: Focus off, everything back.
                instrumentation.waitForIdleSync()
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                main {
                    monitor.getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .first { it is io.github.iamth3doctor.searchlight.focus.PausedActivity }
                        .findViewById<View>(R.id.pausedExitFocus).performClick()
                }
                waitFor("Focus off from the paused screen") { !app.prefs.focusMode }
                Thread.sleep(1200)
                assertTrue("everything back in the drawer", drawerPackages(h).size == everyone.size)

            }
            // Hidden: still hidden, but nothing is intercepted, and switching applies at once.
            // (A fresh home screen after each other app opens: Home brings back a new one.)
            var everyone = 0
            home { h ->
                everyone = drawerPackages(h).size
                tap(focusButton(h))
                main { app.prefs.focusRestriction = io.github.iamth3doctor.searchlight.focus.FocusRules.Restriction.HIDDEN }
                assertEquals(allowed, drawerPackages(h).toSet())
                type(h, "Chrome")
                assertTrue(results(h).none { it is SearchResult.App })
                type(h, "")
                h.onActivity { it.onClick(SearchResult.App(chrome), it.findViewById(R.id.results)) }
                waitFor("Chrome opens in Hidden (resumed: ${resumed()})", 20_000) { opened("chrome") && "PausedActivity" !in resumed() }
            }
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(1500)
            // A notification tap (here: its PendingIntent) is outside Searchlight: it opens normally.
            val pi = android.app.PendingIntent.getActivity(app, 7,
                app.packageManager.getLaunchIntentForPackage("com.android.chrome")!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE)
            pi.send()
            waitFor("Chrome from a notification", 20_000) { opened("chrome") }
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(1500)

            // Back to Blocked mid-focus (still on): at once.
            home { h ->
                assertTrue(app.prefs.focusMode)
                main { app.prefs.focusRestriction = io.github.iamth3doctor.searchlight.focus.FocusRules.Restriction.BLOCKED }
                h.onActivity { it.onClick(SearchResult.App(chrome), it.findViewById(R.id.results)) }
                waitFor("paused at once") { "PausedActivity" in resumed() }
                shell("input keyevent KEYCODE_BACK")
                Thread.sleep(1000)
                tap(focusButton(h))
                assertFalse(app.prefs.focusMode)
                assertEquals(everyone, drawerPackages(h).size)
            }
            // The allowlist is the person's: a later "first setup" doesn't replace it.
            main { app.prefs.ensureFocusAllowlist(listOf("some.other.app")) }
            assertEquals(allowed, app.prefs.focusAllowlist)
        } finally {
            main {
                app.prefs.focusMode = false
                app.prefs.focusAllowlist = savedAllow
                app.prefs.focusRestriction = io.github.iamth3doctor.searchlight.focus.FocusRules.Restriction.BLOCKED
            }
        }
    }

    private fun unfoldedNow() = io.github.iamth3doctor.searchlight.ui.Posture.of(app.resources.configuration) ==
        io.github.iamth3doctor.searchlight.ui.Posture.UNFOLDED
}
