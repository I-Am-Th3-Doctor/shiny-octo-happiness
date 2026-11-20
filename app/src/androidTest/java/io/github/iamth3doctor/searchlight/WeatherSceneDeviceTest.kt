package io.github.iamth3doctor.searchlight

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import io.github.iamth3doctor.searchlight.cards.WeatherCard
import io.github.iamth3doctor.searchlight.weather.DayBar
import io.github.iamth3doctor.searchlight.weather.DayLapse
import io.github.iamth3doctor.searchlight.weather.MetricArt
import io.github.iamth3doctor.searchlight.weather.DayPoint
import io.github.iamth3doctor.searchlight.weather.GeoPlace
import io.github.iamth3doctor.searchlight.weather.HourPoint
import io.github.iamth3doctor.searchlight.weather.Moon
import io.github.iamth3doctor.searchlight.weather.TempUnit
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherDetail
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import io.github.iamth3doctor.searchlight.weather.WeatherText
import io.github.iamth3doctor.searchlight.weather.WeatherSkin
import io.github.iamth3doctor.searchlight.weather.HeroReadings
import io.github.iamth3doctor.searchlight.weatherapp.Skins
import io.github.iamth3doctor.searchlight.weatherapp.PrecipGraphView
import io.github.iamth3doctor.searchlight.weatherapp.WeatherActivity
import io.github.iamth3doctor.searchlight.weatherapp.WeatherSceneView
import io.github.iamth3doctor.searchlight.widgets.BuiltInWidget
import io.github.iamth3doctor.searchlight.widgets.GridSpec
import io.github.iamth3doctor.searchlight.widgets.WeatherWidgetView
import io.github.iamth3doctor.searchlight.widgets.WidgetController
import io.github.iamth3doctor.searchlight.widgets.WidgetGridView
import io.github.iamth3doctor.searchlight.widgets.WidgetPickerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Searchlight's weather: the conditions screen (over its dimmed living scene, dragged through the
 * hours, a held day playing), its precipitation card and metric cards, places and alerts; the
 * Weather tile opening your weather app, or else the conditions (never a hero screen); the plain
 * Weather widget on the Calendar's glass; offline, the last reading with only a small glyph; °F
 * everywhere, agreeing with the Weather tile.
 */
@RunWith(AndroidJUnit4::class)
class WeatherSceneDeviceTest {

    /** A stuck test fails on its own instead of holding up the whole run. */
    @get:Rule val watchdog = Watchdog(240)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val repo: WeatherRepository get() = app.weather

    private var savedFolded = emptyList<GridSpec>()
    private var savedUnfolded = emptyList<GridSpec>()
    private var savedTypes = emptyMap<Int, String>()
    private var savedWeather: WeatherData? = null
    private var savedPlace: GeoPlace? = null
    private var savedUnit = TempUnit.FAHRENHEIT
    private var savedSound = true
    private var savedCity: GeoPlace? = null
    private var savedCities = emptyList<GeoPlace>()

    private fun shell(cmd: String): String = instrumentation.uiAutomation.executeShellCommand(cmd).use {
        String(java.io.FileInputStream(it.fileDescriptor).readBytes())
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** How long [on] waits for the app to go idle before reading anyway. */
    private val IDLE_MS = 300L

    /**
     * Reads [read] on the main thread with [h]'s activity, after the app goes idle or [IDLE_MS] at
     * most. (ActivityScenario.onActivity waits for idle with no limit, which a living scene can put off
     * for good, so a test would hang instead of failing.)
     */
    private inline fun <reified A : Activity, T> on(h: ActivityScenario<A>, crossinline read: (A) -> T): T {
        idle(IDLE_MS)
        val a = onMain { liveActivity<A>() }
        if (a == null) {
            var v: T? = null
            h.onActivity { v = read(it) }
            @Suppress("UNCHECKED_CAST") return v as T
        }
        return onMain { read(a) }
    }

    /** The newest activity of type [A] that's not finished: resumed first. Main thread. */
    private inline fun <reified A : Activity> liveActivity(): A? {
        val reg = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        for (st in listOf(androidx.test.runner.lifecycle.Stage.RESUMED, androidx.test.runner.lifecycle.Stage.PAUSED,
            androidx.test.runner.lifecycle.Stage.STARTED, androidx.test.runner.lifecycle.Stage.STOPPED)) {
            reg.getActivitiesInStage(st).filterIsInstance<A>().lastOrNull { !it.isFinishing }?.let { return it }
        }
        return null
    }

    private fun <T> onMain(read: () -> T): T {
        var v: T? = null
        main { v = read() }
        @Suppress("UNCHECKED_CAST") return v as T
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(150)
        }
        throw AssertionError("timed out: $what")
    }

    /** Waits (a few seconds at most) for the app's main thread to go idle: a living scene may never quite. */
    private fun idle(ms: Long = 4_000) {
        val done = java.util.concurrent.CountDownLatch(1)
        instrumentation.waitForIdle { done.countDown() }
        done.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Waits for the app's next frame to be drawn. Where the living scene runs behind the screen, the main
     * thread never goes idle, so [idle] would wait its whole timeout every time.
     */
    private fun nextFrame() {
        val done = java.util.concurrent.CountDownLatch(1)
        main {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            android.view.Choreographer.getInstance().postFrameCallback { handler.post { done.countDown() } }
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun shot(name: String) {
        idle()
        Thread.sleep(900)
        shell("screencap -p $dir/${prefix}_font1.0_$name.png")
    }

    @Before fun setUp() {
        savedNight = if (shell("cmd uimode night").contains("yes")) "yes" else "no"
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        repo.holdForTest = true
        WeatherActivity.quietOverride = false
        WeatherSceneView.powerSaveOverride = false
        // Going back to now on its own is tested on its own; elsewhere it would cut a test's scrub short.
        WeatherActivity.idleMsOverride = 3_600_000L
        main {
            savedFolded = app.prefs.widgetGrid
            savedUnfolded = app.prefs.widgetGridUnfolded
            savedTypes = app.prefs.builtInTypes
            savedWeather = app.prefs.lastWeather
            savedPlace = app.prefs.manualPlace
            savedUnit = app.prefs.tempUnit
            savedSound = app.prefs.weatherSound
            savedCity = app.prefs.weatherCity
            savedCities = app.prefs.weatherCities
            app.prefs.weatherCity = null
            app.prefs.weatherCities = emptyList()
            app.prefs.widgetGrid = emptyList()
            app.prefs.widgetGridUnfolded = emptyList()
            app.prefs.focusMode = false
            app.prefs.tempUnit = TempUnit.FAHRENHEIT
            app.prefs.weatherSound = true
            savedSkin = app.prefs.weatherSkin
            Skins.set(app, WeatherSkin.GOLD)
            repo.showForTest(reading(0, 2, 0.0), offline = false)
        }
    }

    private var savedSkin: String? = null

    private var savedNight = "no"

    @After fun restoreNight() {
        shell("cmd uimode night $savedNight")
    }

    @After fun restore() {
        runCatching { shell("cmd connectivity airplane-mode disable") }
        runCatching { shell("settings put global low_power 0"); shell("dumpsys battery reset") }
        repo.holdForTest = false
        WeatherActivity.quietOverride = null
        WeatherSceneView.powerSaveOverride = null
        WeatherActivity.idleMsOverride = null
        repo.searchForTest = null
        main {
            app.prefs.weatherCity = savedCity
            app.prefs.weatherCities = savedCities
            app.prefs.widgetGrid = savedFolded
            app.prefs.widgetGridUnfolded = savedUnfolded
            app.prefs.builtInTypes = savedTypes
            app.prefs.lastWeather = savedWeather
            app.prefs.manualPlace = savedPlace
            app.prefs.tempUnit = savedUnit
            app.prefs.weatherSound = savedSound
            Skins.set(app, WeatherSkin.of(savedSkin))
            app.prefs.weatherSkin = savedSkin
            repo.showForTest(savedWeather, offline = false)
        }
        shell("input keyevent KEYCODE_HOME")
    }

    // ---- the reading ----

    private val hour = 3_600_000L
    private val off = -8 * 3600
    private val h0 get() = System.currentTimeMillis() / hour * hour

    /**
     * Tacoma, 52°F now with [code], [cloud]% cover and [precip] mm, wind from [windFrom]. The
     * forecast: the same, except rain (63, 4 mm, 80%) three to five hours from now, and 7 days.
     */
    private fun reading(code: Int, cloud: Int, precip: Double, windFrom: Double = 200.0, wind: Double = 6.0): WeatherData {
        val now = System.currentTimeMillis()
        val base = h0
        val hours = (-3..30).map { i ->
            val wet = i in 3..5
            HourPoint(base + i * hour, 11.1 + i * 0.25, if (wet) 63 else code, true,
                cloudPct = if (wet) 100 else cloud, precipMm = if (wet) 4.0 else precip, precipProb = if (wet) 80 else 5,
                windMph = wind, windFromDeg = windFrom, pressureHpa = 1016.6 - i * 0.4)
        }
        val dayStart = Moon.dayStart(now, off)
        val days = (0 until 7).map { i ->
            DayPoint(dayStart + i * 24 * hour, 11.1 + i % 3, 3.9 + i % 2, listOf(code, 61, 3, 0, 2, 95, 71)[i], listOf(10, 80, 40, 0, 20, 60, 30)[i],
                dayStart + i * 24 * hour + 7 * hour + 25 * 60_000L, dayStart + i * 24 * hour + 16 * hour + 30 * 60_000L)
        }
        val detail = WeatherDetail(off, 7.2, 81, 6.4, 1016.6, 24140.0, wind, windFrom, 1.4, hours, days, 42,
            cloudPct = cloud, precipMm = precip)
        return WeatherData("Tacoma", 47.25, -122.44, 11.1, code, true, 11.1, 3.9, now, detail)
    }

    /**
     * [reading] with the hourly forecast running from today's midnight through tomorrow, tomorrow
     * dry until 3 PM, raining 3 PM to 6 PM (80%), dry after: the 7-day time-lapse plays it.
     */
    private fun lapseReading(): WeatherData {
        val w = reading(2, 40, 0.0)
        val start = Moon.dayStart(System.currentTimeMillis(), off)
        val hours = (0 until 48).map { i ->
            val wet = i - 24 in 15..17
            HourPoint(start + i * hour, 4.0 + (i % 24) * 0.45, if (wet) 63 else 2, (i % 24) in 8..16,
                cloudPct = if (wet) 100 else 35, precipMm = if (wet) 3.0 else 0.0, precipProb = if (wet) 80 else 10,
                windMph = 7.0, windFromDeg = 200.0, pressureHpa = 1016.6)
        }
        return w.copy(detail = w.detail!!.copy(hourly = hours))
    }

    private fun show(w: WeatherData, offline: Boolean = false) = main { repo.showForTest(w, offline) }

    private fun texts(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        main { walk(root) }
        return out
    }

    // ---- the weather screen ----

    private fun screen(block: (ActivityScenario<WeatherActivity>) -> Unit) {
        val s = ActivityScenario.launch(WeatherActivity::class.java)
        try {
            waitFor("the conditions laid out") { on(s) { it.dataShown && it.dataScreen.width > 0 && it.detailLapse.preview.conditions != null } }
            block(s)
        } finally {
            runCatching { s.close() }
        }
    }

    /** Shows [w] on the conditions, then lets their scene play a moment. */
    private fun force(h: ActivityScenario<WeatherActivity>, w: WeatherData) {
        show(w)
        on(h) { it.render() }
        val f = on(h) { it.detailLapse.preview.renderer.frames }
        waitFor("scene playing") { on(h) { it.detailLapse.preview.renderer.frames > f + 20 } }
    }

    /** Waits until no screen change is under way. */
    private fun settle(h: ActivityScenario<WeatherActivity>) {
        waitFor("screens settled") { on(h) { !it.screens.moving } }
        idle()
    }

    /** A real touch on [view] at ([x], [y]) in its own coordinates, through the window: down, then up after [holdMs]. */
    private fun touch(view: View, x: Float, y: Float, holdMs: Long = 0) {
        val (sx, sy) = screenAt(view, x, y)
        val d = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(d, d, MotionEvent.ACTION_DOWN, sx, sy, 0))
        if (holdMs > 0) Thread.sleep(holdMs)
        instrumentation.sendPointerSync(MotionEvent.obtain(d, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, sx, sy, 0))
    }

    private fun screenAt(view: View, x: Float, y: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        main { view.getLocationOnScreen(loc) }
        return (loc[0] + x) to (loc[1] + y)
    }

    private fun screenTop(v: View): Int = onMain { IntArray(2).also { v.getLocationOnScreen(it) }[1] }

    private fun rect(v: View): android.graphics.Rect = onMain {
        val r = android.graphics.Rect()
        if (v.isShown) v.getGlobalVisibleRect(r)
        r
    }

    @Test fun theDetailsSitOverTheLivingSceneAndAHeldDayPlaysFullScreen() = screen { h ->
        force(h, lapseReading())
        on(h) { it.openDetails() }
        settle(h)
        val panel = on(h) { it.panel }
        val rows = on(h) { panel.dayRows.toList() }
        assertEquals(7, rows.size)
        val scroll = on(h) { it.dataScroll }
        val lapse = on(h) { it.detailLapse }
        val screen = on(h) { it.dataScreen }
        // No preview card at the top any more: the details' own scene is behind everything, full-screen.
        assertNull(on(h) { screen.findViewWithTag<View>("weather_detail_lapse") })
        assertEquals(on(h) { screen.width to screen.height }, on(h) { lapse.preview.width to lapse.preview.height })
        assertTrue("behind the numbers", on(h) { screen.indexOfChild(lapse.preview) < screen.indexOfChild(it.dataScrim) })
        assertTrue("dimmed", on(h) { it.dataScrim.isShown && it.dataScrim.alpha == 1f })
        assertTrue("the weather now", on(h) { lapse.preview.conditions!!.live })
        val f0 = on(h) { lapse.preview.renderer.frames }
        waitFor("alive, not a still picture") { on(h) { lapse.preview.animating && lapse.preview.renderer.frames > f0 + 15 } }
        assertEquals(0xFFFFFFFF.toInt(), on(h) { panel.nowTemp!!.currentTextColor })
        assertNoBarOnTheData(h)
        val liveTemp = on(h) { panel.nowTemp!!.text.toString() }
        val liveCondition = on(h) { panel.nowCondition!!.text.toString() }
        assertTrue(liveTemp, liveTemp.endsWith("°F"))
        shot("116-details-over-scene")

        // Scroll the 7 DAYS into view, as a person would.
        main { scroll.scrollTo(0, (panel.sections["daily"]!!.top - 24 * app.resources.displayMetrics.density).toInt()) }
        idle()
        val beforeScroll = on(h) { scroll.scrollY }
        assertTrue("scrolled down to the 7 days", beforeScroll > 0)
        val beforeTops = panel.sections.mapValues { screenTop(it.value) }
        val beforeText = texts(on(h) { panel.sections["daily"]!! }).map { it.text.toString() }

        // Hold tomorrow (on a clock the test drives).
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        main { lapse.playClock = { clock.get() } }
        val (rx, ry) = screenAt(rows[1], 40f, 20f)
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, rx, ry, 0))
        waitFor("holding plays the day") { on(h) { lapse.day == 1 && lapse.preview.animating } }
        // Up to the top of the details: the big number, the day, the precip card, the scene playing behind.
        waitFor("up to the data") { on(h) { scroll.scrollY == 0 && !it.detailsScrolling } }
        idle()
        assertEquals("from midnight", 0.0, on(h) { lapse.progress }, 0.001)
        assertNoBarOnTheData(h)
        assertTrue("the scene a little clearer", on(h) { it.dataScrim.alpha } < 1f)
        assertTrue(on(h) { panel.sections["now"]!!.isShown && panel.sections["precip"]!!.isShown })
        assertNull("holding: no count", on(h) { it.idleReturnAt })
        assertTrue(on(h) { it.dataShown })
        val hf = io.github.iamth3doctor.searchlight.weatherapp.WeatherFormat.hour(app)
        val tomorrow = Moon.dayStart(System.currentTimeMillis(), off) + 24 * hour
        fun hourText(k: Int) = hf.format(java.time.Instant.ofEpochMilli(tomorrow + k * hour).atOffset(java.time.ZoneOffset.ofTotalSeconds(off)))
        assertEquals(listOf(15 / 24f to 18 / 24f), on(h) { lapse.lapseBar.windows })
        fun at(hours: Double) {
            clock.set(1_000_000L + (DayLapse.PLAY_MS * hours / 24).toLong())
            waitFor("the playhead at $hours h") { abs(on(h) { lapse.progress } - hours / 24) < 0.002 }
        }
        at(14.95)
        assertEquals("${hourText(14)} · ${WeatherText.f(4.0 + 14 * 0.45)}° · 10% rain", on(h) { lapse.readout.text.toString() })
        assertEquals("dry until 3", 0.0, on(h) { lapse.preview.conditions!!.rain }, 0.0)
        // The details' own big number follows the played hour.
        assertEquals("${WeatherText.f(on(h) { lapse.preview.conditions!!.tempC })}°F", on(h) { panel.nowTemp!!.text.toString() })
        at(15.1)
        assertEquals("${hourText(15)} · ${WeatherText.f(4.0 + 15 * 0.45)}° · 80% rain", on(h) { lapse.readout.text.toString() })
        assertTrue("raining from 3", on(h) { lapse.preview.conditions!!.rain } > 0.4)
        assertEquals("Rain", on(h) { panel.nowCondition!!.text.toString() })
        assertEquals(on(h) { lapse.progress }.toFloat(), on(h) { lapse.lapseBar.progress }, 0.001f)
        at(16.5)
        waitFor("rain falls behind the data", 6_000) { on(h) { lapse.preview.renderer.dropCount } > 20 }
        shot("113-details-hold")
        at(18.2)
        assertEquals("dry after 6", 0.0, on(h) { lapse.preview.conditions!!.rain }, 0.0)

        // Let go: back to the details exactly as they were, over the weather now.
        instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, rx, ry, 0))
        assertNull(on(h) { lapse.day })
        assertTrue("back on now", on(h) { lapse.preview.conditions!!.live })
        assertNoBarOnTheData(h)
        assertEquals(liveTemp, on(h) { panel.nowTemp!!.text.toString() })
        assertEquals(liveCondition, on(h) { panel.nowCondition!!.text.toString() })
        waitFor("back where they were") { on(h) { scroll.scrollY == beforeScroll && !it.detailsScrolling } }
        idle()
        assertEquals(beforeTops, panel.sections.mapValues { screenTop(it.value) })
        assertEquals(beforeText, texts(on(h) { panel.sections["daily"]!! }).map { it.text.toString() })
        waitFor("the scene dims as before") { on(h) { it.dataScrim.alpha == 1f } }
        assertTrue("still alive", on(h) { lapse.preview.animating })
        assertNull("nothing away from now", on(h) { it.idleReturnAt })
        assertTrue(on(h) { it.dataShown })

        // A real hold, on the real clock: the whole day in about 9 s, and letting go stops it.
        main { lapse.playClock = { SystemClock.uptimeMillis() } }
        val d2 = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(d2, d2, MotionEvent.ACTION_DOWN, rx, ry, 0))
        waitFor("playing again") { on(h) { lapse.day == 1 } }
        val began = on(h) { SystemClock.uptimeMillis() - (lapse.progress * DayLapse.PLAY_MS).toLong() }
        waitFor("to the end of the day", 14_000) { on(h) { lapse.progress } >= 1.0 }
        val took = SystemClock.uptimeMillis() - began
        assertTrue("about 9 s: $took", took in 7_500L..11_500L)
        instrumentation.sendPointerSync(MotionEvent.obtain(d2, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, rx, ry, 0))
        waitFor("back") { on(h) { lapse.day == null && scroll.scrollY == beforeScroll && !it.detailsScrolling } }
        assertNoBarOnTheData(h)
    }

    /** No scrub bar or hour readout anywhere on the data screen: the scene alone shows the hour. */
    private fun assertNoBarOnTheData(h: ActivityScenario<WeatherActivity>) {
        val screen = on(h) { it.dataScreen }
        val lapse = on(h) { it.detailLapse }
        assertNull(on(h) { screen.findViewWithTag<View>("weather_data_lapse_foot") })
        assertNull(on(h) { screen.findViewWithTag<View>("weather_lapse_readout") })
        assertNull(on(h) { screen.findViewWithTag<View>("weather_lapse_bar") })
        assertNull("the time-lapse numbers aren't on the screen at all", on(h) { lapse.overlay.parent })
        val shown = texts(screen)
        assertTrue(on(h) { shown.none { it.isShown && it.text.contains(" · ") && it.text.contains("% rain") } })
    }

    @Test fun dragTheDataScreensSceneThroughTheHours() = screen { h ->
        force(h, lapseReading())
        on(h) { it.openDetails() }
        settle(h)
        val panel = on(h) { it.panel }
        val lapse = on(h) { it.detailLapse }
        val screen = on(h) { it.dataScreen }
        assertNoBarOnTheData(h)
        val liveTemp = on(h) { panel.nowTemp!!.text.toString() }
        // Start the drag on the big number (not the hourly strip or the precip card, which drag on their own).
        val (sx, sy) = screenAt(on(h) { panel.nowTemp!! }, 10f, 10f)
        val o = IntArray(2).also { a -> main { screen.getLocationOnScreen(a) } }
        val x0 = sx - o[0]
        val y = sy - o[1]
        val step = on(h) { (screen.width / DayBar.HOURS.toFloat()).coerceAtLeast(18 * app.resources.displayMetrics.density) }
        val d = SystemClock.uptimeMillis()
        main {
            screen.dispatchTouchEvent(MotionEvent.obtain(d, d, MotionEvent.ACTION_DOWN, x0, y, 0))
            for (k in 1..12) screen.dispatchTouchEvent(MotionEvent.obtain(d, d + k * 16, MotionEvent.ACTION_MOVE, x0 + step * 6 * k / 12, y, 0))
        }
        assertTrue("dragging", on(h) { it.detailScrubbing })
        val start = DayBar.start(System.currentTimeMillis())
        assertEquals("six hours on", start + (DayBar.BEFORE_HOURS + 6) * hour, on(h) { it.detailScrubTime })
        assertEquals(on(h) { it.detailScrubTime }, on(h) { lapse.previewTime })
        assertFalse("the scene shows that hour, not now", on(h) { lapse.preview.conditions!!.live })
        assertEquals("the big number follows", "${WeatherText.f(on(h) { lapse.preview.conditions!!.tempC })}°F", on(h) { panel.nowTemp!!.text.toString() })
        assertNull("dragging: no count", on(h) { it.idleReturnAt })
        assertNoBarOnTheData(h)
        shot("117-data-scene-scrub")
        main { screen.dispatchTouchEvent(MotionEvent.obtain(d, d + 400, MotionEvent.ACTION_UP, x0 + step * 6, y, 0)) }
        assertFalse(on(h) { it.detailScrubbing })
        assertEquals("it stays on that hour", start + (DayBar.BEFORE_HOURS + 6) * hour, on(h) { it.detailScrubTime })
        assertTrue("let go away from now: the count starts", on(h) { it.idleReturnAt } != null)
        // Left alone, back to now: the scene and the big number.
        on(h) { it.returnToNow() }
        waitFor("back to now") { on(h) { it.detailScrubTime == null && lapse.preview.conditions!!.live } }
        assertEquals(liveTemp, on(h) { panel.nowTemp!!.text.toString() })
        assertNull(on(h) { it.idleReturnAt })
        assertTrue(on(h) { it.dataShown })

        // A drag on the hourly strip is the strip's own: the scene stays on now.
        val strip = on(h) { firstOf<android.widget.HorizontalScrollView>(panel.sections["hourly"]!!) }
        if (strip != null) {
            val (hx, hy) = screenAt(strip, 40f, 20f)
            val d2 = SystemClock.uptimeMillis()
            main {
                screen.dispatchTouchEvent(MotionEvent.obtain(d2, d2, MotionEvent.ACTION_DOWN, hx - o[0], hy - o[1], 0))
                for (k in 1..12) screen.dispatchTouchEvent(MotionEvent.obtain(d2, d2 + k * 16, MotionEvent.ACTION_MOVE, hx - o[0] - step * 6 * k / 12, hy - o[1], 0))
                screen.dispatchTouchEvent(MotionEvent.obtain(d2, d2 + 300, MotionEvent.ACTION_UP, hx - o[0] - step * 6, hy - o[1], 0))
            }
            assertNull("the strip scrolled; the scene didn't move", on(h) { it.detailScrubTime })
        }
    }

    private inline fun <reified V : View> firstOf(root: View): V? = firstWhere(root) { it is V } as V?

    private fun firstWhere(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) firstWhere(root.getChildAt(i), match)?.let { return it }
        return null
    }

    /** Every frame from now for [ms], whether two screens were on screen at once. */
    private fun watchForOverlap(h: ActivityScenario<WeatherActivity>, ms: Long, during: () -> Unit): List<String> {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val frames = java.util.concurrent.atomic.AtomicInteger()
        var watching = true
        val cb = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                if (!watching) return
                frames.incrementAndGet()
                val a = WeatherActivity.current ?: return
                val up = listOf(a.dataScreen, a.alertScreen, a.placesScreen)
                    .filter { it.visibility == View.VISIBLE && it.alpha > 0.01f }
                if (up.size > 1) seen += up.joinToString { it.tag.toString() }
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        main { android.view.Choreographer.getInstance().postFrameCallback(cb) }
        during()
        Thread.sleep(ms)
        watching = false
        assertTrue("frames watched", frames.get() > 5)
        return seen
    }

    /** Whether [v]'s background is solid (call on the main thread, e.g. inside on(h) {}). */
    private fun opaque(v: View): Boolean = run {
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        v.background.setBounds(0, 0, v.width, v.height)
        v.background.draw(android.graphics.Canvas(bmp))
        val ok = (0 until 20).all { i -> android.graphics.Color.alpha(bmp.getPixel(v.width * (i % 5) / 5 + 1, v.height * (i / 5) / 4 + 1)) == 255 }
        bmp.recycle()
        ok
    }

    @Test fun metricCardsOpenIntoPicturesOfTheirNumbers() = screen { h ->
        force(h, reading(2, 40, 0.0))
        on(h) { it.openDetails() }
        settle(h)
        val panel = on(h) { it.panel }
        main { panel.animateOverride = true }
        val keys = listOf("sun", "moon", "humidity", "visibility", "pressure", "uv", "aqi", "wind")
        assertEquals(keys, on(h) { panel.cards.keys.toList() })
        val cards = on(h) { panel.cards.toMap() }
        // Shut: as before, label, value and line; every row's cards level.
        val shutHeights = onMain { cards.mapValues { it.value.height } }
        for (pair in keys.chunked(2)) assertEquals(pair.toString(), shutHeights[pair[0]], shutHeights[pair[1]])
        assertTrue(onMain { cards.values.all { it.expander.height == 0 && !it.art.active } })
        val scroll = onMain { generateSequence(cards.getValue("sun").parent) { it.parent }.first { it is android.widget.ScrollView } as android.widget.ScrollView }

        fun cardText(k: String) = texts(cards.getValue(k)).joinToString(" | ") { it.text.toString() }
        fun row(k: String) = cards.getValue(k).parent as View
        val body = on(h) { panel.body }
        fun below(k: String): View? = onMain { body.getChildAt(body.indexOfChild(row(k)) + 1) }

        var previous: String? = null
        for ((n, k) in keys.withIndex()) {
            val card = cards.getValue(k)
            main { scroll.scrollTo(0, (row(k).top - 24 * app.resources.displayMetrics.density).toInt().coerceAtLeast(0)) }
            nextFrame()
            val next = below(k)
            // Tap: it grows smoothly, the cards under it sliding down with it; nothing overlaps or jumps.
            // Each sample: the card's height, its bottom, the next row's top, this row's top (the panel's coordinates).
            val samples = ArrayList<List<Int>>()
            // The easing runs on a stepped clock, a 30 ms frame at a time, so a slow emulator can't skip frames.
            val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
            main { panel.expandClock = { clock.get() } }
            main { card.performClick() }
            repeat(14) {
                Thread.sleep(50)
                nextFrame() // that frame laid out
                samples += onMain { listOf(card.height, row(k).top + card.bottom, next?.top ?: Int.MAX_VALUE, row(k).top) }
                clock.addAndGet(30)
            }
            Thread.sleep(150)
            nextFrame()
            main { panel.expandClock = { SystemClock.uptimeMillis() } }
            assertEquals(k, on(h) { panel.expanded })
            assertTrue("$k: grows over several frames: ${samples.map { it[0] }.distinct()}", samples.map { it[0] }.distinct().size >= 4)
            // A jump would be the whole change in one step; easing spreads it over many (even with a slow frame or two).
            val gaps = samples.map { it[2] - it[3] }
            val tops = samples.map { it[3] }
            val jump = maxOf(90 * app.resources.displayMetrics.density,
                0.6f * maxOf(gaps.max() - gaps.min(), tops.max() - tops.min()))
            for (j in 1 until samples.size) {
                assertTrue("$k: only ever grows: $samples", samples[j][0] >= samples[j - 1][0])
                // The cards below follow smoothly: no step bigger than a frame's worth of easing.
                if (next != null) assertTrue("$k: the cards below jumped: $samples",
                    abs((samples[j][2] - samples[j][3]) - (samples[j - 1][2] - samples[j - 1][3])) <= jump)
                assertTrue("$k: the rows above moved smoothly: $samples", abs(samples[j][3] - samples[j - 1][3]) <= jump)
            }
            assertTrue("$k: never over the cards below: $samples", samples.all { it[1] <= it[2] })
            assertTrue(onMain { card.expander.open == 1f && card.height > shutHeights.getValue(k) + 100 * app.resources.displayMetrics.density })
            // The other card in its row stays its shut size.
            val partner = keys[n xor 1]
            assertEquals("$partner stays shut beside it", shutHeights[partner], onMain { cards.getValue(partner).height })
            // Only one open: the one before has closed.
            previous?.let { p ->
                assertTrue("$p closed when $k opened", onMain { cards.getValue(p).expander.open == 0f && !cards.getValue(p).art.active })
            }
            // The picture plays, and draws.
            val f0 = on(h) { card.art.frames }
            Thread.sleep(1_500) // the emulator draws slowly behind the living scene
            assertTrue("$k animates", on(h) { card.art.frames } > f0 + 5)
            val inked = onMain {
                val a = card.art
                val bmp = android.graphics.Bitmap.createBitmap(a.width, a.height, android.graphics.Bitmap.Config.ARGB_8888)
                a.draw(android.graphics.Canvas(bmp))
                var c = 0
                for (y in 0 until bmp.height step 4) for (x in 0 until bmp.width step 4) if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 40) c++
                bmp.recycle()
                c
            }
            assertTrue("$k draws its picture: $inked", inked > 40)
            assertTrue("$k: the shut card is still there on top: ${cardText(k)}", cardText(k).isNotBlank())
            shot("${105 + n}-metric-$k")
            previous = k
        }

        // Each picture is of the card's own numbers.
        val info = onMain { cards.mapValues { it.value.art.info!! } }
        val note = onMain { cards.mapValues { (it.value.note).text.toString() } }
        assertEquals(42, info.getValue("aqi").aqi)
        assertTrue(note.getValue("aqi"), note.getValue("aqi").startsWith("AQI 42 · Good"))
        assertEquals(1.4, info.getValue("uv").uv, 1e-9)
        assertTrue(note.getValue("uv"), note.getValue("uv").startsWith("UV 1 · Low"))
        assertEquals(0.81, info.getValue("humidity").humidity, 1e-9)
        assertTrue(note.getValue("humidity"), note.getValue("humidity").startsWith("Dew point 44°: "))
        assertEquals(15.0, info.getValue("visibility").visMiles, 0.1)
        assertEquals("You can see about 15 mi: Good", note.getValue("visibility"))
        assertEquals(MetricArt.pressureFraction(1016.6), info.getValue("pressure").pressureF, 1e-9)
        assertEquals("falling 0.4 hPa an hour", MetricArt.Trend.FALLING, info.getValue("pressure").trend)
        assertTrue(note.getValue("pressure"), note.getValue("pressure").startsWith("30.02 inHg ↓ Falling"))
        assertEquals("from SSW (200°): blowing toward 20°", 20.0, info.getValue("wind").windTowardDeg, 1e-9)
        assertEquals("6 mph from SSW: gentle drift", note.getValue("wind"))
        assertEquals(6.0, info.getValue("wind").windMph, 1e-9)
        val sun = info.getValue("sun")
        assertTrue(sun.riseText!!.startsWith("7:25"))
        assertTrue(note.getValue("sun"), note.getValue("sun").startsWith("Daylight 9h 05m"))
        val moon = info.getValue("moon")
        assertEquals(Moon.illumination(System.currentTimeMillis()), moon.illumination, 0.01)
        assertEquals(144, moon.moonDay!!.steps)
        assertTrue(note.getValue("moon"), note.getValue("moon").contains("% lit"))

        // Tap the open one: it closes; everything is back as it was.
        main { cards.getValue("wind").performClick() }
        waitFor("closed") { on(h) { panel.expanded == null && cards.getValue("wind").expander.open == 0f } }
        nextFrame()
        assertEquals(shutHeights, onMain { cards.mapValues { it.value.height } })
        assertFalse(onMain { cards.values.any { it.art.active } })

        // Open stays open when the details rebuild (a new reading).
        main { panel.animateOverride = false }
        main { cards.getValue("pressure").performClick() }
        show(reading(2, 40, 0.0))
        nextFrame()
        waitFor("rebuilt") { on(h) { panel.cards["pressure"] !== cards.getValue("pressure") } }
        assertTrue(on(h) { panel.cards.getValue("pressure").expander.open == 1f && panel.expanded == "pressure" })
        assertTrue("°F everywhere", texts(on(h) { panel.body }).none { it.text.contains("°C") })
        main { panel.animateOverride = null }
    }

    @Test fun searchSaveAndSwitchCities() {
        val seattle = GeoPlace("Seattle", "Washington", "US", 47.61, -122.33)
        repo.searchForTest = { q -> if (q.startsWith("Sea")) listOf(seattle, GeoPlace("Seaside", "Oregon", "US", 45.99, -123.92)) else emptyList() }
        fun seattleReading() = reading(61, 100, 1.2).copy(locationName = "Seattle", latitude = 47.61, longitude = -122.33,
            temperatureC = 15.6, highC = 16.7, lowC = 10.0)
        screen { h ->
            force(h, reading(2, 40, 0.0))
            assertTrue(on(h) { it.placeButton.text.toString() }.startsWith("Tacoma"))
            on(h) { it.placeButton.performClick() }
            settle(h)
            assertTrue(on(h) { it.placesShown })
            assertTrue("solid", on(h) { opaque(it.placesScreen) })
            val places = on(h) { it.places }
            assertEquals("home first", listOf("Tacoma (home)"), texts(on(h) { places.saved }).map { it.text.toString() })
            on(h) { places.search.setText("Seattle"); places.find("Seattle") }
            waitFor("results") { on(h) { places.results.childCount } == 2 }
            shot("103-places-search")
            on(h) { places.results.getChildAt(0).performClick() }
            settle(h)
            assertFalse(on(h) { it.placesShown })
            assertEquals("Seattle", onMain { repo.city?.name })
            assertEquals(listOf("Seattle"), onMain { repo.savedCities.map { it.name } })
            show(seattleReading())
            on(h) { it.render() }
            assertEquals("60°F", on(h) { it.panel.nowTemp!!.text.toString() })
            assertTrue(on(h) { it.placeButton.text.toString() }.startsWith("Seattle"))
            shot("104-seattle")

            // Switch back to Tacoma: its last reading at once.
            on(h) { it.placeButton.performClick() }
            settle(h)
            val saved = texts(on(h) { places.saved }).map { it.text.toString() }
            assertTrue(saved.toString(), saved.contains("Tacoma (home)") && saved.contains("Seattle"))
            on(h) { places.saved.findViewWithTag<View>("weather_place_home").performClick() }
            settle(h)
            assertNull(onMain { repo.city })
            assertEquals("Tacoma", onMain { repo.state.data?.locationName })
            assertEquals("52°F", on(h) { it.panel.nowTemp!!.text.toString() })
            // And to Seattle again, from the saved list: its last reading, offline or not.
            on(h) { it.placeButton.performClick() }
            settle(h)
            on(h) { places.saved.findViewWithTag<View>("weather_place_saved").performClick() }
            settle(h)
            assertEquals("Seattle", onMain { repo.state.data?.locationName })
            assertEquals("60°F", on(h) { it.panel.nowTemp!!.text.toString() })
        }
        // The widget and the Weather tile show the same city, in °F.
        home { h ->
            val v = addWeather(h)
            // The Weather widget: the city on top and the temperature.
            assertEquals(listOf("Seattle", "60°F"), texts(v).map { it.text.toString() }.take(2))
            val tile = tileLine(h)
            assertTrue("tile: $tile", tile.contains("60°F"))
        }
        main { repo.selectCity(null) }
        assertEquals("Tacoma", onMain { repo.state.data?.locationName })
    }

    // ---- the widget ----

    private fun home(block: (ActivityScenario<MainActivity>) -> Unit) {
        val s = ActivityScenario.launch(MainActivity::class.java)
        try {
            Thread.sleep(1500)
            block(s)
        } finally {
            runCatching { s.close() }
        }
    }

    private fun addWeather(h: ActivityScenario<MainActivity>): WeatherWidgetView {
        on(h) {
            it.widgetController.onActivityResult(WidgetController.REQUEST_PICK, Activity.RESULT_OK,
                Intent().putExtra(WidgetPickerActivity.EXTRA_BUILTIN, BuiltInWidget.WEATHER.key))
        }
        waitFor("weather widget on the grid") { on(h) { it.widgetController.weatherViews().any { v -> v.isAttachedToWindow && v.width > 0 } } }
        return on(h) { it.widgetController.weatherViews().single() }
    }

    private fun layout(h: ActivityScenario<MainActivity>): List<GridSpec> = on(h) {
        if (it.findViewById<WidgetGridView>(R.id.widgetGrid).unfolded) app.prefs.widgetGridUnfolded else app.prefs.widgetGrid
    }

    private fun tileLine(h: ActivityScenario<MainActivity>): String {
        on(h) { a ->
            val idx = a.homeCards.indexOfFirst { it is WeatherCard }
            a.findViewById<ViewPager2?>(R.id.cards)?.takeIf { it.isShown }?.setCurrentItem(idx, false)
        }
        var line = ""
        waitFor("the Weather tile's line") {
            line = on(h) { a ->
                fun find(v: View): String? {
                    if (v.id == R.id.weatherLine && v.isShown) return (v as TextView).text.toString()
                    if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { r -> return r }
                    return null
                }
                find(a.window.decorView).orEmpty()
            }
            line.isNotEmpty()
        }
        return line
    }

    /** A Calendar widget too, for the glass to be compared with. */
    private fun addCalendar(h: ActivityScenario<MainActivity>): io.github.iamth3doctor.searchlight.widgets.CalendarWidgetView {
        on(h) {
            it.widgetController.onActivityResult(WidgetController.REQUEST_PICK, Activity.RESULT_OK,
                Intent().putExtra(WidgetPickerActivity.EXTRA_BUILTIN, BuiltInWidget.CALENDAR.key))
        }
        waitFor("calendar widget on the grid") { on(h) { it.widgetController.calendarViews().any { v -> v.isAttachedToWindow && v.width > 0 } } }
        return on(h) { it.widgetController.calendarViews().single() }
    }

    /** What [v]'s frosted glass drew last, drawn now: (tint, blur alpha, blurred at all). */
    private fun glassOf(v: View): Triple<Int, Int, Boolean> {
        val b = android.graphics.Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val g = onMain {
            val d = v.background as io.github.iamth3doctor.searchlight.ui.FrostedDrawable
            d.draw(android.graphics.Canvas(b))
            Triple(d.drawnColor, d.drawnBlurAlpha, d.drewBlur)
        }
        b.recycle()
        return g
    }

    @Test fun widgetIsPlainCurrentConditionsOnTheCalendarsGlass() = home { h ->
        show(reading(3, 100, 0.0))
        val before = repo.refreshRequests
        val v = addWeather(h)
        assertTrue("adding it refreshes", repo.refreshRequests > before)
        val spec = layout(h).single()
        assertEquals("2 × 2", 2 to 2, spec.colSpan to spec.rowSpan)
        assertEquals("fixed: no resize handles", false to false, on(h) { it.widgetController.resizable(spec.id) })

        // Just the place, the temperature, the condition in words and today's high and low (and a small icon).
        val data = onMain { repo.state.data!! }
        assertEquals(listOf("Tacoma", "52°F", data.condition, "H ${f(data.highC)}° · L ${f(data.lowC)}°"), texts(v).map { it.text.toString() })
        assertEquals("Overcast", data.condition)
        assertEquals(io.github.iamth3doctor.searchlight.weather.ConditionVisual.of(data.weatherCode, data.isDay), on(h) { v.icon.visual })
        assertNull("all of it whole and inside", on(h) { v.fitProblem() })
        assertFalse("no offline glyph online", on(h) { v.offlineShown })
        // Quiet: no scene, nothing animating, no skin.
        fun all(x: View): List<View> = listOf(x) + ((x as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        assertTrue("no scene", on(h) { all(v).none { it is WeatherSceneView } })
        // Lexend, and the high/low in gold.
        assertEquals(app.getColor(R.color.gold), on(h) { v.hiLoView.currentTextColor })
        // Same reading as the Weather tile.
        val tile = tileLine(h)
        assertTrue("tile: $tile", tile.contains("52°F"))

        // Beside a Calendar widget: the same glass, at every Glass transparency setting.
        val cal = addCalendar(h)
        assertEquals("Lexend, as the calendar's text", on(h) { cal.emptyView.typeface }, on(h) { v.conditionView.typeface })
        val saved = onMain { app.prefs.glassLevel }
        try {
            for (level in listOf(0, io.github.iamth3doctor.searchlight.ui.GlassTint.LEVEL_DEFAULT, io.github.iamth3doctor.searchlight.ui.GlassTint.LEVEL_MAX)) {
                main { app.prefs.glassLevel = level; io.github.iamth3doctor.searchlight.ui.FrostedDrawable.setGlassLevel(level) }
                idle()
                assertEquals("glass at $level: as the calendar's", glassOf(cal), glassOf(v))
                // And the text shadow the glass gives its text (toward Clear), the same as the calendar's.
                assertEquals("text shadow at $level", on(h) { cal.dayTitle.shadowRadius to cal.dayTitle.shadowColor },
                    on(h) { v.conditionView.shadowRadius to v.conditionView.shadowColor })
                if (level == 0) shot("75-weather-2x2-clear")
            }
            // The setting changes it: clear and solid glass differ.
            main { io.github.iamth3doctor.searchlight.ui.FrostedDrawable.setGlassLevel(0) }
            val clear = glassOf(v)
            main { io.github.iamth3doctor.searchlight.ui.FrostedDrawable.setGlassLevel(io.github.iamth3doctor.searchlight.ui.GlassTint.LEVEL_MAX) }
            assertTrue("the slider moves it", glassOf(v) != clear)
        } finally {
            main { app.prefs.glassLevel = saved; io.github.iamth3doctor.searchlight.ui.FrostedDrawable.setGlassLevel(saved) }
        }
        idle()
        shot("75-weather-2x2")
    }

    @Test fun tappingTheWidgetOpensTheConditionsScreenWithTheSameReading() = home { h ->
        show(reading(3, 100, 0.0))
        val v = addWeather(h)
        val widget = texts(v).map { it.text.toString() }
        val monitor = instrumentation.addMonitor(WeatherActivity::class.java.name, null, false)
        try {
            on(h) { v.performClick() }
            val opened = monitor.waitForActivityWithTimeout(10_000) as WeatherActivity?
            assertNotNull("the weather app opened", opened)
            waitFor("at the conditions screen") { onMain { opened!!.dataShown && !opened.screens.moving } }
            assertTrue(onMain { opened!!.intent.getBooleanExtra(WeatherActivity.EXTRA_DETAILS, false) })
            val now = texts(onMain { opened!!.panel.sections["now"]!! }).map { it.text.toString() }
            for (t in widget.drop(1)) assertTrue("'$t' as on the conditions screen: $now", t in now)
            // The precip card, hourly and the 7 days are all there.
            for (k in listOf("summary", "precip", "hourly", "daily")) assertNotNull("the conditions screen's $k", onMain { opened!!.panel.sections[k] })
            shot("121-widget-opens-conditions")
            main { opened!!.finish() }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        // In edit mode it opens nothing.
        val monitor2 = instrumentation.addMonitor(WeatherActivity::class.java.name, null, true)
        try {
            on(h) { it.findViewById<WidgetGridView>(R.id.widgetGrid).setEditMode(true) }
            on(h) { v.performClick() }
            Thread.sleep(800)
            assertEquals(0, monitor2.hits)
            on(h) { it.widgetController.exitEditMode() }
        } finally {
            instrumentation.removeMonitor(monitor2)
        }
    }

    // ---- offline ----

    @Test fun offlineKeepsTheLastSceneWithJustAGlyph() {
        main {
            app.prefs.manualPlace = GeoPlace("Tacoma", "Washington", "US", 47.25, -122.44)
            // The last good reading has rain coming and a thunderstorm warning in effect.
            repo.showForTest(storm(reading(3, 96, 0.0)), offline = false)
        }
        home { h ->
            val v = addWeather(h)
            assertFalse(on(h) { v.offlineShown })
            shell("cmd connectivity airplane-mode enable")
            Thread.sleep(3000)
            val airplane = shell("settings get global airplane_mode_on").trim() == "1"
            if (airplane) {
                repo.holdForTest = false
                main { repo.refresh() }
                waitFor("refresh failed offline", 40_000) { onMain { !repo.state.loading && repo.state.problem != null } }
                repo.holdForTest = true
            } else {
                main { repo.showForTest(repo.state.data, offline = true) }
            }
            assertEquals(WeatherRepository.Problem.NETWORK, onMain { repo.state.problem })
            waitFor("offline glyph") { on(h) { v.offlineShown } }
            val last = onMain { repo.state.data!! }
            assertEquals("the last good data, and no warning text", listOf("Tacoma", "52°F", last.condition, "H ${f(last.highC)}° · L ${f(last.lowC)}°"),
                texts(v).map { it.text.toString() })
            val glyph = on(h) { v.offlineGlyph }
            assertTrue("small", on(h) { glyph.width <= (24 * app.resources.displayMetrics.density) })
            shot("89-widget-offline")
        }
        screen { s ->
            assertTrue(on(s) { it.offlineGlyph.isShown })
            assertTrue("no warning text", texts(on(s) { it.window.decorView }).none { it.text.contains("offline", ignoreCase = true) })
            // The last known alert's pill, and the last scene behind the conditions.
            assertTrue(on(s) { it.alertPill.isShown })
            assertTrue("the last scene", on(s) { it.detailLapse.preview.conditions!!.cloudCover > 0.9 })
            shot("90-scene-offline")
            val now = texts(on(s) { it.panel.sections["now"]!! }).map { it.text.toString() }
            assertTrue(now.toString(), "52°F" in now && now.any { it.startsWith("Updated ") })
            // The last good precip: this hour's rows and the rain coming.
            val card = on(s) { it.panel.precip }
            assertEquals("0.00 IN", on(s) { card.rateValue.text.toString() })
            assertTrue(on(s) { card.expected.text.toString() }.startsWith("Rain expected around "))
            on(s) { card.graph.touchAt(card.graph.xOf(h0 + 3 * hour + 30 * 60_000L), tap = false) }
            assertEquals("0.16 IN", on(s) { card.rateValue.text.toString() })
            assertEquals("80%", on(s) { card.chanceValue.text.toString() })
        }
        shell("cmd connectivity airplane-mode disable")
    }

    // ---- phase 2: the hero, the precip card, severe alerts ----

    @Test fun precipCardRowsGraphAndBarMoveTogether() = screen { h ->
        force(h, reading(0, 2, 0.0))
        on(h) { it.openDetails() }
        settle(h)
        val card = on(h) { it.panel.precip }
        fun rows() = on(h) { listOf(card.rateValue.text.toString(), card.chanceValue.text.toString(), card.typeValue.text.toString()) }
        assertEquals(listOf("0.00 IN", "5%", "NONE"), rows())
        assertEquals("RATE", on(h) { card.rateLabel.text.toString() })
        assertTrue(on(h) { card.expected.text.toString() }, on(h) { card.expected.text.toString() }.startsWith("Rain expected around "))
        assertEquals(0, on(h) { card.island.particles })
        assertEquals(PrecipGraphView.Mode.DAY, on(h) { card.graph.mode })
        on(h) { it.panel.sections["precip"]!!.let { v -> (v.parent.parent as? android.widget.ScrollView)?.smoothScrollTo(0, v.top) } }
        shot("93-precip-now")

        // Scrub the graph into the rain: the rows follow.
        val t = h0 + 3 * hour + 30 * 60_000L
        on(h) { card.graph.touchAt(card.graph.xOf(t), tap = false) }
        val scrubbed = on(h) { it.precipScrubTime!! }
        assertTrue(abs(scrubbed - t) < 20 * 60_000L)
        assertEquals(scrubbed, on(h) { card.graph.scrubTime })
        assertEquals(listOf("0.16 IN", "80%", "RAIN"), rows())
        assertTrue("rain falls on the island", on(h) { card.island.particles } > 5)
        // A filled area chart: under the rainy hours, the graph is solid colour from the curve down.
        val filled = onMain {
            val g = card.graph
            val bmp = android.graphics.Bitmap.createBitmap(g.width, g.height, android.graphics.Bitmap.Config.ARGB_8888)
            g.draw(android.graphics.Canvas(bmp))
            val x = g.xOf(h0 + 4 * hour).toInt()
            val col = (g.height / 4 until g.height * 3 / 4).count { y -> android.graphics.Color.alpha(bmp.getPixel(x, y)) > 20 }
            bmp.recycle()
            col.toFloat() / (g.height / 2)
        }
        assertTrue("filled: $filled", filled > 0.8f)
        shot("94-precip-scrubbed")

        // Later, dry again.
        on(h) { card.graph.touchAt(card.graph.xOf(h0 + 10 * hour), tap = false) }
        assertEquals(listOf("0.00 IN", "5%", "NONE"), rows())

        // WEEK: the 7 days; pick one.
        on(h) { card.weekChip.performClick() }
        assertEquals(PrecipGraphView.Mode.WEEK, on(h) { card.graph.mode })
        assertEquals(7, on(h) { card.graph.pointCount })
        assertEquals("TOTAL", on(h) { card.rateLabel.text.toString() })
        on(h) { card.graph.touchAt(card.graph.xOfDay(1), tap = true) }
        assertEquals(1, on(h) { card.graph.weekIndex })
        assertEquals("80%", rows()[1])
        assertEquals("RAIN", rows()[2])
        shot("95-precip-week")
        // Back to DAY: at the hour it was on.
        on(h) { card.dayChip.performClick() }
        assertEquals(PrecipGraphView.Mode.DAY, on(h) { card.graph.mode })
        assertEquals(on(h) { it.precipScrubTime }, on(h) { card.graph.scrubTime })
        assertEquals("RATE", on(h) { card.rateLabel.text.toString() })
        // Now, from the graph: live everywhere.
        on(h) { card.graph.touchAt(card.graph.xOf(System.currentTimeMillis()), tap = true) }
        assertNull(on(h) { it.precipScrubTime })
        assertEquals(listOf("0.00 IN", "5%", "NONE"), rows())
    }

    private fun storm(w: WeatherData, offline: Boolean = false): WeatherData {
        val now = System.currentTimeMillis()
        return w.copy(alerts = listOf(io.github.iamth3doctor.searchlight.weather.WeatherAlert(
            "urn:test:1", "Severe Thunderstorm Warning", "Severe Thunderstorm Warning issued for Pierce County",
            "Severe", now - hour, now + 2 * hour,
            "At 3:05 PM, a severe thunderstorm was located over Tacoma, moving east at 25 mph.",
            "For your protection move to an interior room on the lowest floor of a building.", "NWS Seattle WA")))
    }

    @Test fun aSevereAlertTakesOverTheSceneAndShowsItsDetails() = screen { h ->
        force(h, storm(reading(1, 25, 0.0)))
        assertTrue(on(h) { it.alertPill.isShown })
        assertEquals("! THUNDERSTORM !", on(h) { it.alertPill.text.toString() })
        // The storm plays behind the conditions.
        val c = on(h) { it.detailLapse.preview.conditions!! }
        assertTrue("storm: dark clouds, heavy rain", c.thunder && c.cloudCover == 1.0 && c.rain >= 0.85)
        shot("96-alert-scene")

        // The pill sits by the place, never on it.
        assertFalse(android.graphics.Rect.intersects(rect(on(h) { it.alertPill }), rect(on(h) { it.placeButton })))
        on(h) { it.alertPill.performClick() }
        assertTrue(on(h) { it.alertShown })
        settle(h)
        assertFalse("its own screen", on(h) { it.dataScreen.isShown })
        assertTrue("solid", on(h) { opaque(it.alertScreen) })
        val details = texts(on(h) { it.alertScreen }).joinToString(" | ") { it.text.toString() }
        for (want in listOf("Severe Thunderstorm Warning", "issued for Pierce County", "Effective ", "Expires ",
                "severe thunderstorm was located over Tacoma", "interior room", "WHAT TO DO")) {
            assertTrue("$want in: $details", details.contains(want))
        }
        shot("97-alert-details")
        on(h) { it.back() }
        assertFalse(on(h) { it.alertShown })
        settle(h)

        // No alert: nothing about alerts anywhere.
        force(h, reading(1, 25, 0.0))
        assertFalse(on(h) { it.alertPill.isShown })
        assertFalse(on(h) { it.alertShown })
        assertTrue(texts(on(h) { it.window.decorView }).none { t -> t.text.contains("!") || t.text.contains("alert", ignoreCase = true) })
    }


    // ---- phase 9: the hero as one cartoon scene, data round it; skins ----

    private fun current(): WeatherActivity = onMain { WeatherActivity.current!! }

    /** The screen's pixels now (a software copy). */
    private fun screenPixels(): android.graphics.Bitmap {
        idle()
        val b = instrumentation.uiAutomation.takeScreenshot()!!
        return b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle() }
    }

    /** [v]'s box on screen. */
    private fun screenBox(v: View): android.graphics.Rect = onMain {
        val o = IntArray(2).also { v.getLocationOnScreen(it) }
        android.graphics.Rect(o[0], o[1], o[0] + v.width, o[1] + v.height)
    }

    // ---- the Weather tile ----

    /** Taps the home card's Weather tile. */
    private fun tapTile(h: ActivityScenario<MainActivity>) {
        on(h) { a ->
            val idx = a.homeCards.indexOfFirst { it is WeatherCard }
            a.findViewById<ViewPager2?>(R.id.cards)?.takeIf { it.isShown }?.setCurrentItem(idx, false)
        }
        Thread.sleep(500)
        on(h) { a ->
            fun find(x: View): View? {
                if (x.id == R.id.card && x.isShown && (x as? ViewGroup)?.findViewById<View>(R.id.weatherLine) != null) return x
                if (x is ViewGroup) for (i in 0 until x.childCount) find(x.getChildAt(i))?.let { r -> return r }
                return null
            }
            find(a.window.decorView)!!.performClick()
        }
    }

    private fun allViews(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { allViews(g.getChildAt(it)) } } ?: emptyList())

    @Test fun theWeatherTileOpensTheConditionsNeverTheHero() = home { h ->
        show(reading(3, 100, 0.0))
        val monitor = instrumentation.addMonitor(WeatherActivity::class.java.name, null, false)
        try {
            tapTile(h)
            val opened = monitor.waitForActivityWithTimeout(10_000) as WeatherActivity?
            assertNotNull("the weather opened", opened)
            waitFor("straight on the conditions") { onMain { opened!!.dataShown && !opened.screens.moving && opened.dataScreen.width > 0 } }
            assertEquals("nothing under or over them", 0, onMain { opened!!.screens.depth })
            // The same data as before: now (temperature, condition, feels like, high/low), the day,
            // precipitation, the metric cards (humidity, wind, ...), the hours and the 7 days.
            val panel = onMain { opened!!.panel }
            for (k in listOf("now", "summary", "precip", "hourly", "daily")) assertNotNull("the conditions' $k", onMain { panel.sections[k] })
            val now = texts(onMain { panel.sections["now"]!! }).map { it.text.toString() }
            val data = onMain { repo.state.data!! }
            assertTrue(now.toString(), "52°F" in now && data.condition in now && now.any { it.startsWith("Feels ") } &&
                "H ${f(data.highC)}° · L ${f(data.lowC)}°" in now)
            assertTrue("humidity and wind", onMain { panel.cards.keys.containsAll(listOf("humidity", "wind")) })
            assertEquals(7, onMain { panel.dayRows.size })
            // No hero anywhere: no giant number, callouts, day bar or quick numbers.
            val views = onMain { allViews(opened!!.window.decorView) }
            assertTrue("no hero", views.none {
                it is io.github.iamth3doctor.searchlight.weatherapp.HeroLayout || it is io.github.iamth3doctor.searchlight.weatherapp.HeroTempView ||
                    it is io.github.iamth3doctor.searchlight.weatherapp.DayBarView || it is io.github.iamth3doctor.searchlight.weatherapp.QuickDataView ||
                    it.tag == "weather_screen_hero"
            })
            shot("115-tile-opens-conditions")
            // Back closes the weather: there's nothing under the conditions.
            main { opened!!.back() }
            waitFor("closed") { onMain { opened!!.isFinishing } }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    // ---- the one Weather widget ----

    @Test fun oneWeatherWidgetInThePicker() {
        val picker = ActivityScenario.launchActivityForResult(WidgetPickerActivity::class.java)
        try {
            var shown = emptyList<String>()
            waitFor("the Weather widget offered") {
                var decor: View? = null
                picker.onActivity { decor = it.window.decorView }
                shown = texts(decor!!).map { it.text.toString() }
                app.getString(R.string.widget_weather) in shown
            }
            assertEquals("just one", 1, shown.count { it.startsWith(app.getString(R.string.widget_weather)) })
            shot("118-weather-widget-picker")
        } finally {
            picker.close()
        }
    }

    @Test fun retiredWeatherWidgetsComeBackAsTheOne() {
        // A 4 × 2 "today" widget placed before: it opens as the plain one, at 2 × 2.
        main {
            app.prefs.builtInTypes = mapOf(-7 to "weather_day")
            app.prefs.widgetGrid = listOf(GridSpec(-7, 0, 0, 4, 2))
            app.prefs.widgetGridUnfolded = listOf(GridSpec(-7, 0, 0, 4, 2))
        }
        show(reading(3, 100, 0.0))
        home { h ->
            waitFor("the weather widget on the grid") { on(h) { it.widgetController.weatherViews().any { v -> v.isAttachedToWindow && v.width > 0 } } }
            assertEquals(2 to 2, layout(h).single().let { it.colSpan to it.rowSpan })
            assertEquals("weather", onMain { app.prefs.builtInTypes[-7] })
        }
    }

    @Suppress("unused") private fun f(c: Double) = WeatherText.f(c)
}

/**
 * Fails a test that runs longer than [seconds] instead of letting it hold up the run, keeping the
 * test on its own thread (unlike JUnit's Timeout). The failure says what the main thread was doing.
 */
class Watchdog(private val seconds: Long) : org.junit.rules.TestRule {
    override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) =
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val test = Thread.currentThread()
                var dump: String? = null
                var summary = ""
                val dog = Thread {
                    try {
                        Thread.sleep(seconds * 1000)
                        val mainThread = android.os.Looper.getMainLooper().thread
                        dump = "main thread:\n" + mainThread.stackTrace.joinToString("\n") { "  at $it" } +
                            "\ntest thread:\n" + test.stackTrace.joinToString("\n") { "  at $it" }
                        android.util.Log.e("Watchdog", "${description.methodName} stuck\n$dump")
                        // One line too, so the test report's console summary keeps it whole.
                        fun line(t: Thread) = t.stackTrace.take(30).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                        summary = "MAIN: ${line(mainThread)} || TEST: ${line(test)}"
                        runCatching {
                            // Also to a file beside the screenshots, which the workflow prints.
                            val reg = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                            val prefix = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
                            val fds = reg.uiAutomation.executeShellCommandRw(
                                "sh -c 'cat > /sdcard/Download/searchlight-shots/watchdog-$prefix-${description.methodName}.txt'")
                            android.os.ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { it.write(dump!!.toByteArray()) }
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(fds[0]).use { it.readBytes() }
                        }
                        test.interrupt()
                        // A test stuck in a main-thread call (runOnMainSync ignores interrupts) would
                        // hold up the whole run: end the process instead, so the run goes on to report.
                        Thread.sleep(30_000)
                        android.util.Log.e("Watchdog", "${description.methodName} still stuck: ending the test process\n$summary")
                        System.out.println("Watchdog: ${description.methodName} stuck: $summary")
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } catch (_: InterruptedException) {
                    }
                }
                dog.isDaemon = true
                dog.start()
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    dump?.let { throw AssertionError("timed out after ${seconds}s $summary\n$it", t) }
                    throw t
                } finally {
                    dog.interrupt()
                    Thread.interrupted()
                }
                dump?.let { throw AssertionError("timed out after ${seconds}s $summary\n$it") }
            }
        }
}
