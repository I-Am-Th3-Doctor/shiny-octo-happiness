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
import io.github.iamth3doctor.searchlight.weather.SceneModel
import io.github.iamth3doctor.searchlight.weather.TempUnit
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherDetail
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import io.github.iamth3doctor.searchlight.weather.WeatherText
import io.github.iamth3doctor.searchlight.weather.WeatherSkin
import io.github.iamth3doctor.searchlight.weather.HeroReadings
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
 * The weather as a living scene: clear, cloudy, rain and thunder each play differently; the day
 * bar scrubs the scene through the forecast and "now" brings it back; tapping shows and hides the
 * numbers; the home widget is a moving window into the scene that stops when home isn't showing
 * and in battery saver; offline both keep the last scene with only a small glyph; °F everywhere,
 * agreeing with the Weather tile.
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
            repo.showForTest(reading(0, 2, 0.0), offline = false)
        }
    }


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
            waitFor("scene laid out") { on(s) { it.scene.width > 0 && it.scene.conditions != null } }
            block(s)
        } finally {
            runCatching { s.close() }
        }
    }

    /** Shows [w] and snaps the scene to it (no easing), then lets it play a moment. */
    private fun force(h: ActivityScenario<WeatherActivity>, w: WeatherData) {
        show(w)
        on(h) { it.render(); it.scene.jumpTo(it.conditions()) }
        val f = on(h) { it.scene.renderer.frames }
        waitFor("scene playing") { on(h) { it.scene.renderer.frames > f + 20 } }
    }

    private data class Look(val clouds: Int, val drops: Int, val sky: Int, val strikes: Int)

    private fun look(h: ActivityScenario<WeatherActivity>) = on(h) {
        val r = it.scene.renderer
        Look(r.cloudCount, r.dropCount, r.skyTop, r.strikes)
    }

    @Test fun eachWeatherIsItsOwnLivingScene() = screen { h ->
        assertTrue("animating", on(h) { it.scene.animating })
        val f0 = on(h) { it.scene.renderer.frames }
        waitFor("frames advance: it's alive", 3_000) { onMain { WeatherActivity.current!!.scene.renderer.frames } > f0 + 10 }

        force(h, reading(0, 2, 0.0))
        val clear = look(h)
        assertEquals("clear: no clouds", 0, clear.clouds)
        assertEquals("clear: no rain", 0, clear.drops)
        shot("81-scene-clear")

        force(h, reading(3, 96, 0.0))
        val cloudy = look(h)
        assertTrue("cloudy: a sky full of clouds ($cloudy)", cloudy.clouds >= 4)
        assertEquals(0, cloudy.drops)
        assertNotEquals("cloudy sky is grayer", clear.sky, cloudy.sky)
        shot("82-scene-cloudy")

        force(h, reading(63, 100, 3.0))
        waitFor("rain falling") { look(h).drops > 100 }
        val rain = look(h)
        assertTrue(rain.clouds >= 4)
        shot("83-scene-rain")

        force(h, reading(95, 100, 7.0))
        waitFor("a storm's harder rain") { look(h).drops > rain.drops }
        waitFor("lightning", 12_000) { look(h).strikes > 0 }
        // Thunder and a haptic come with the lightning (sound is on, phone not silenced).
        waitFor("thunder heard") { on(h) { it.sound.thunders > 0 && it.strikesFelt > 0 } }
        shot("84-scene-thunder")
        assertNotEquals(cloudy.sky, look(h).sky)

        // Sunny, windy and raining at once: some cloud, rain, and the wind leaning it all east.
        force(h, reading(80, 45, 1.2, windFrom = 270.0, wind = 25.0))
        waitFor("shower falling") { look(h).drops > 20 }
        val mixed = look(h)
        assertTrue("partly cloudy, not overcast: $mixed vs $cloudy", mixed.clouds in 1 until cloudy.clouds + 1)
        on(h) { it.setQuick(true) }
        assertEquals("the arrow points the way it blows (east)", 90.0, on(h) { it.quick.windArrow.screenAngle }, 1.0)
        on(h) { it.setQuick(false) }
        val c = on(h) { it.scene.conditions!! }
        assertTrue(c.rain > 0 && c.windEastMph > 20)

        // Wind from the north-east: the arrow points down and to the left.
        force(h, reading(0, 2, 0.0, windFrom = 45.0, wind = 12.0))
        on(h) { it.setQuick(true) }
        assertEquals(-135.0, on(h) { it.quick.windArrow.screenAngle }, 1.0)
        on(h) { it.setQuick(false) }

        // The top-left button: a cloud in its circle (top left, as before); one tap opens the data screen.
        cloudButtonOpensTheDataScreen(h)
    }

    /** One tap on the cloud: the data screen (summary, PRECIP, HOURLY, 7 DAYS), not the metric grid. */
    private fun cloudButtonOpensTheDataScreen(h: ActivityScenario<WeatherActivity>) {
        val menu = on(h) { it.menuButton }
        assertTrue(on(h) { menu.isShown && menu.width == menu.height && menu.drawable != null })
        val (mx, my) = screenAt(menu, 0f, 0f)
        val m = 24 * app.resources.displayMetrics.density
        assertTrue("top left: $mx, $my", mx < m * 2 && my < m * 6)
        touch(menu, on(h) { menu.width / 2f }, on(h) { menu.height / 2f })
        settle(h)
        assertTrue("one tap opens the data screen", on(h) { it.dataShown })
        assertFalse("not the metric grid", on(h) { it.quickShown })
        assertEquals(1, on(h) { it.screens.depth })
        val panel = on(h) { it.panel }
        for (k in listOf("now", "precip", "hourly", "daily")) assertTrue(k, on(h) { panel.sections[k] != null && panel.sections[k]!!.isAttachedToWindow })
        assertTrue("the summary and the precip card in view", on(h) { panel.sections["now"]!!.isShown && panel.sections["precip"]!!.isShown })
        shot("121-cloud-data-screen")
        on(h) { it.back() }
        settle(h)
        assertEquals(0, on(h) { it.screens.depth })
        // The metric grid is still the scene's tap, as before.
        on(h) { it.scene.performClick() }
        assertTrue(on(h) { it.quickShown })
        val all = listOf("temp", "feels", "cloud", "precip", "wind", "humidity", "dew", "uv", "visibility", "sun", "moon")
        assertEquals(all, on(h) { it.quick.values.keys.toList() })
        on(h) { it.setQuick(false) }
    }

    @Test fun scrubbingPlaysTheDay() = screen { h ->
        force(h, reading(0, 2, 0.0))
        assertNull(on(h) { it.scrubTime })
        assertEquals(0, look(h).drops)
        // Tap the bar four hours on: it lands on that hour, and the rain in the forecast plays.
        val t = h0 + 4 * hour
        val x = on(h) { it.bar.xOf(t + 10 * 60_000L) }
        on(h) { it.bar.tap(x) }
        val scrubbed = on(h) { it.scrubTime!! }
        assertEquals("snapped to the hour", t, scrubbed)
        assertFalse(on(h) { it.conditions().live })
        assertEquals(63, on(h) { it.conditions().code })
        waitFor("rain plays in", 8_000) { look(h).drops > 100 }
        val readout = on(h) { it.bar.readout(scrubbed) }
        val wantF = DayBar.readout(onMain { repo.state.data!! }, scrubbed).first
        assertTrue(readout, readout.contains("$wantF°") && readout.contains("80% rain"))
        // The hour's sound: rain.
        assertTrue("rain sound", on(h) { it.sound.rain } > 0.3f)
        assertEquals(0f, on(h) { it.sound.crickets })
        // The number and the condition show the hour too.
        assertEquals("$wantF", on(h) { it.hero.number })
        assertEquals("RAIN", on(h) { it.heroCondition.text.toString() })
        shot("85-scene-scrubbed")

        // Drag back across the bar: the scene follows the finger, hour by hour.
        val bar = on(h) { it.bar }
        val y = on(h) { bar.height / 2f }
        val from = on(h) { bar.xOf(t) }
        val to = on(h) { bar.xOf(h0 + 10 * hour) }
        val down = SystemClock.uptimeMillis()
        main {
            bar.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, from, y, 0))
            for (k in 1..10) bar.dispatchTouchEvent(MotionEvent.obtain(down, down + k * 16, MotionEvent.ACTION_MOVE, from + (to - from) * k / 10, y, 0))
            bar.dispatchTouchEvent(MotionEvent.obtain(down, down + 200, MotionEvent.ACTION_UP, to, y, 0))
        }
        val dragged = on(h) { it.scrubTime!! }
        assertEquals("ends on the hour where the finger lifted", h0 + 10 * hour, dragged)
        assertEquals(0, on(h) { it.conditions().code })
        assertEquals("no rain sound", 0f, on(h) { it.sound.rain })
        waitFor("rain clears", 8_000) { look(h).drops == 0 }

        // Forgiving: a short wobble stays on the hour; a clear move goes one hour, never skipping one.
        val step = on(h) { bar.xOf(h0 + 11 * hour) - bar.xOf(h0 + 10 * hour) }
        fun drag(fromX: Float, dx: Float) {
            val d = SystemClock.uptimeMillis()
            main {
                bar.dispatchTouchEvent(MotionEvent.obtain(d, d, MotionEvent.ACTION_DOWN, fromX, y, 0))
                for (k in 1..12) bar.dispatchTouchEvent(MotionEvent.obtain(d, d + k * 16, MotionEvent.ACTION_MOVE, fromX + dx * k / 12, y, 0))
                bar.dispatchTouchEvent(MotionEvent.obtain(d, d + 220, MotionEvent.ACTION_UP, fromX + dx, y, 0))
            }
        }
        drag(to, step * 0.6f)
        assertEquals("a wobble stays put", h0 + 10 * hour, on(h) { it.scrubTime })
        drag(to, step * 0.85f)
        assertEquals("one hour on", h0 + 11 * hour, on(h) { it.scrubTime })
        drag(on(h) { bar.xOf(h0 + 11 * hour) }, step * 2.2f)
        assertEquals("two hours on, none skipped", h0 + 13 * hour, on(h) { it.scrubTime })

        // Tap "now": live again.
        on(h) { it.bar.tap(it.bar.xOf(System.currentTimeMillis())) }
        assertNull(on(h) { it.scrubTime })
        assertTrue(on(h) { it.conditions().live })
        assertEquals(11.1, on(h) { it.conditions().tempC }, 1e-9)
        shot("86-scene-day-bar")
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

    @Test fun leftAloneItGoesBackToNow() = screen { h ->
        main { WeatherActivity.idleMsOverride = null } // the real 5 s
        force(h, reading(0, 2, 0.0))
        val liveNumber = on(h) { it.hero.number }
        val liveCondition = on(h) { it.heroCondition.text.toString() }
        val bar = on(h) { it.bar }
        val y = on(h) { bar.height / 2f }
        assertNull("live: nothing counting", on(h) { it.idleReturnAt })

        // Scrub the bar four hours on (rain there), then put the phone down.
        val t = h0 + 4 * hour
        touch(bar, on(h) { bar.xOf(t + 10 * 60_000L) }, y)
        val scrubbedAt = SystemClock.uptimeMillis()
        assertEquals(t, on(h) { it.scrubTime })
        assertEquals("RAIN", on(h) { it.heroCondition.text.toString() })
        val armed = on(h) { it.idleReturnAt }
        assertNotNull("away from now: counting", armed)
        assertEquals("5 s from the scrub", 5_000.0, (armed!! - scrubbedAt).toDouble(), 1_000.0)
        // A finger down pauses the count; lifting it starts it over.
        val (bx, by) = screenAt(bar, on(h) { bar.xOf(t) }, y)
        val d = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(d, d, MotionEvent.ACTION_DOWN, bx, by, 0))
        assertNull("held: paused", on(h) { it.idleReturnAt })
        instrumentation.sendPointerSync(MotionEvent.obtain(d, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bx, by, 0))
        val restarted = SystemClock.uptimeMillis()
        assertEquals(t, on(h) { it.scrubTime })
        assertEquals(5_000.0, (on(h) { it.idleReturnAt }!! - restarted).toDouble(), 1_000.0)

        // Not before 5 s...
        Thread.sleep((restarted + 3_800 - SystemClock.uptimeMillis()).coerceAtLeast(0))
        // Read straight on the main thread: ActivityScenario.onActivity first waits for the app to go idle,
        // which a living scene can put off past the 5 s.
        val at38 = onMain { WeatherActivity.current!!.scrubTime to SystemClock.uptimeMillis() - restarted }
        assertTrue("read in time: ${at38.second} ms", at38.second < 4_800)
        assertEquals("still on the scrubbed hour at 3.8 s", t, at38.first)
        // ...then on its own: the scrubber slides back to NOW, the scene and number with it.
        val slid = ArrayList<Long>()
        var backAt = 0L
        // What the bar says the moment it's back (the hour's readout shows only for a moment).
        var readoutAtBack: String? = null
        val until = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < until) {
            val (s, readout) = onMain {
                val a = WeatherActivity.current!!
                a.scrubTime to a.bar.takeIf { it.nowReadoutShown }?.readout(System.currentTimeMillis())
            }
            if (s == null) { backAt = SystemClock.uptimeMillis(); readoutAtBack = readout; break }
            if (s != slid.lastOrNull()) slid += s
            Thread.sleep(16)
        }
        assertTrue("went back to now", backAt > 0)
        val after = backAt - restarted
        assertTrue("about 5 s after the last touch: $after ms", after in 4_600L..8_000L)
        val between = slid.filter { it < t }
        assertTrue("it slid back, not a jump: $slid", between.isNotEmpty())
        for (i in 1 until slid.size) assertTrue("only ever toward now: $slid", slid[i] <= slid[i - 1])
        assertFalse("done returning", on(h) { it.bar.returning })
        assertTrue("the scene is now's", on(h) { it.conditions().live })
        assertEquals("the number is now's again", liveNumber, on(h) { it.hero.number })
        assertEquals(liveCondition, on(h) { it.heroCondition.text.toString() })
        // The readout says the hour it's back at, for a moment.
        assertNotNull("the readout shows the hour it's back at", readoutAtBack)
        val nowHour = io.github.iamth3doctor.searchlight.weatherapp.WeatherFormat.hour(app)
            .format(java.time.Instant.ofEpochMilli(System.currentTimeMillis()).atOffset(java.time.ZoneOffset.ofTotalSeconds(off)))
        assertTrue("the readout: $readoutAtBack", readoutAtBack!!.startsWith("$nowHour · "))
        shot("114-back-to-now")
        assertNull("back at now: nothing counting", on(h) { it.idleReturnAt })
        waitFor("the rain clears off the scene", 8_000) { look(h).drops == 0 }

        // Scrub, then touch again at 3 s: the count starts over, so it's still there at 6 s and back about 8 s.
        // Where to touch (the quick numbers), worked out first: on a slow emulator the reading takes time.
        val scene = on(h) { it.scene }
        val (qx, qy) = screenAt(scene, on(h) { scene.width / 2f }, on(h) { scene.height * 0.45f })
        touch(bar, on(h) { bar.xOf(t + 10 * 60_000L) }, y)
        val second = SystemClock.uptimeMillis()
        assertEquals(t, onMain { WeatherActivity.current!!.scrubTime })
        Thread.sleep((second + 3_000 - SystemClock.uptimeMillis()).coerceAtLeast(0))
        val qd = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(qd, qd, MotionEvent.ACTION_DOWN, qx, qy, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(qd, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, qx, qy, 0))
        val touchedAt = SystemClock.uptimeMillis()
        assertTrue("touched before the 5 s were up: ${touchedAt - second} ms", touchedAt - second < 4_800)
        assertTrue("the touch shows the quick numbers", on(h) { it.quickShown })
        Thread.sleep((touchedAt + 4_000 - SystemClock.uptimeMillis()).coerceAtLeast(0))
        assertTrue("4 s past the touch, 7 s past the scrub", SystemClock.uptimeMillis() - second >= 6_500)
        assertEquals("not back at 5 s from the scrub: the touch at 3 s reset it", t, on(h) { it.scrubTime })
        waitFor("back 5 s after the touch", 6_000) { on(h) { it.scrubTime == null } }
        val second2 = SystemClock.uptimeMillis() - touchedAt
        assertTrue("$second2 ms", second2 in 4_600L..8_000L)

        // The 7 days: while a day plays nothing counts; let go and the window is straight back on now.
        on(h) { it.setQuick(false); it.openWeek() }
        settle(h)
        val week = on(h) { it.week }
        on(h) { week.startPreview(1) }
        assertNull("playing: paused", on(h) { it.idleReturnAt })
        on(h) { week.stopPreview() }
        assertTrue("the week's window back on now", on(h) { week.preview.conditions!!.live && week.preview.animating })
        assertFalse("not away from now", on(h) { it.awayFromNow })
        assertNull("back on now: nothing counting", on(h) { it.idleReturnAt })
    }

    @Test fun theDetailsSitOverTheLivingSceneAndATappedDayOpensInItsBox() = screen { h ->
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
        assertTrue("dimmed, like the hero's tap-to-reveal", on(h) { it.dataScrim.isShown && it.dataScrim.alpha == 1f })
        assertTrue("the weather now", on(h) { lapse.preview.conditions!!.live })
        assertEquals(on(h) { it.scene.conditions!!.tempC }, on(h) { lapse.preview.conditions!!.tempC }, 0.05)
        val f0 = on(h) { lapse.preview.renderer.frames }
        waitFor("alive, not a still picture") { on(h) { lapse.preview.animating && lapse.preview.renderer.frames > f0 + 15 } }
        assertTrue("dark text on the frosted veil", WeatherSkin.luminance(on(h) { panel.nowTemp!!.currentTextColor }) < 0.05)
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

        // Tap tomorrow: one tap, no hold, and the 7 DAYS box goes into that day.
        val (rx, ry) = screenAt(rows[1], 40f, 20f)
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, rx, ry, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, rx, ry, 0))
        waitFor("into tomorrow") { on(h) { panel.openDay == 1 && panel.dayView?.isShown == true } }
        waitFor("the list gone from the box") { on(h) { rows.none { it.isShown } } }
        val day = on(h) { repo.state.data!!.detail!!.daily[1] }
        val dayView = on(h) { panel.dayView!! }
        assertTrue("in the 7 DAYS box", on(h) { generateSequence(dayView.parent) { it.parent }.any { it === panel.sections["daily"] } })
        val shown = texts(dayView).map { it.text.toString() }
        val tomorrowName = java.time.Instant.ofEpochMilli(day.date).atOffset(java.time.ZoneOffset.ofTotalSeconds(off)).dayOfWeek
            .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        assertTrue(shown.toString(), tomorrowName in shown)
        assertTrue(shown.toString(), io.github.iamth3doctor.searchlight.weather.WeatherCodes.describe(day.code) in shown)
        assertTrue(shown.toString(), "H ${WeatherText.f(day.highC)}° · L ${WeatherText.f(day.lowC)}°" in shown)
        assertTrue(shown.toString(), "80%" in shown)
        // Its 24 hours, rain 3 to 6.
        val cells = ArrayList<View>()
        main {
            fun walk(v: View) {
                if (v.tag == "weather_day_hour") cells += v
                if (v is ViewGroup) for (k in 0 until v.childCount) walk(v.getChildAt(k))
            }
            walk(dayView)
        }
        assertEquals(24, cells.size)
        val hf = io.github.iamth3doctor.searchlight.weatherapp.WeatherFormat.hour(app)
        val tf = io.github.iamth3doctor.searchlight.weatherapp.WeatherFormat.time(app)
        fun local(t: Long) = java.time.Instant.ofEpochMilli(t).atOffset(java.time.ZoneOffset.ofTotalSeconds(off))
        assertEquals(hf.format(local(day.date)), texts(cells[0]).first().text.toString())
        assertTrue("3 PM wet", texts(cells[15]).any { it.text.toString() == "80%" })
        assertTrue("noon dry", texts(cells[12]).none { it.text.toString() == "80%" })
        // Sunrise and sunset, the wind.
        assertTrue(shown.toString(), tf.format(local(day.sunrise!!)) in shown && tf.format(local(day.sunset!!)) in shown)
        assertTrue(shown.toString(), "7 mph" in shown)
        // The scene behind stays on now: going into a day isn't a time-lapse.
        assertNull(on(h) { lapse.day })
        assertTrue(on(h) { lapse.preview.conditions!!.live })
        assertNoBarOnTheData(h)
        assertEquals(liveTemp, on(h) { panel.nowTemp!!.text.toString() })
        assertTrue(on(h) { it.dataShown })
        shot("113-details-day")

        // Back out with the box's own back: the list again, everything where it was.
        val back = on(h) { dayView.findViewWithTag<View>("weather_day_back") }
        val (bx, by) = screenAt(back, 8f, 8f)
        val d2 = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(d2, d2, MotionEvent.ACTION_DOWN, bx, by, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(d2, d2 + 60, MotionEvent.ACTION_UP, bx, by, 0))
        waitFor("back to the 7 days") { on(h) { panel.openDay == null && panel.dayView == null && rows.all { it.isShown } } }
        waitFor("everything where it was") { panel.sections.mapValues { screenTop(it.value) } == beforeTops }
        assertEquals(beforeScroll, on(h) { scroll.scrollY })
        assertEquals(beforeText, texts(on(h) { panel.sections["daily"]!! }).map { it.text.toString() })

        // A held finger is still just a tap: no time-lapse, the day opens when it lifts.
        val d3 = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(d3, d3, MotionEvent.ACTION_DOWN, rx, ry, 0))
        Thread.sleep(900)
        assertNull("holding plays nothing", on(h) { lapse.day })
        instrumentation.sendPointerSync(MotionEvent.obtain(d3, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, rx, ry, 0))
        assertNull(on(h) { lapse.day })
        // Another day straight from the list; Back comes out of the day before it leaves the screen.
        main { panel.openDay(4) }
        waitFor("into day 5") { on(h) { panel.openDay == 4 && panel.dayView?.isShown == true } }
        on(h) { it.back() }
        waitFor("Back: out of the day") { on(h) { panel.openDay == null && rows.all { r -> r.isShown } } }
        assertTrue("still on the details", on(h) { it.dataShown })
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

    @Test fun tapTheSceneForTheQuickNumbersTapAgainForTheScene() = screen { h ->
        force(h, reading(2, 40, 0.0))
        assertFalse(on(h) { it.quickShown })
        on(h) { it.scene.performClick() }
        assertTrue(on(h) { it.quickShown })
        assertTrue("over the dimmed scene", on(h) { it.scrim.isShown && it.scene.isShown })
        assertFalse("in the hero's place, never over it", on(h) { it.hero.isShown || it.heroCondition.isShown })
        val quick = on(h) { it.quick }
        val want = listOf("temp", "feels", "cloud", "precip", "wind", "humidity", "dew", "uv", "visibility", "sun", "moon")
        assertEquals(want, on(h) { quick.values.keys.toList() })
        fun v(k: String) = on(h) { quick.values[k]!!.text.toString() }
        for (k in want) assertTrue("$k: ${v(k)}", v(k).isNotBlank() && v(k) != "—" && quick.values[k]!!.isShown)
        assertEquals("52°F", v("temp"))
        assertEquals("45°F", v("feels"))
        assertEquals("40%", v("cloud"))
        assertEquals("5%", v("precip"))
        assertEquals("6 mph SSW", v("wind"))
        assertTrue(on(h) { quick.windArrow.isShown })
        assertEquals("81%", v("humidity"))
        assertEquals("44°F", v("dew"))
        assertTrue(v("uv"), v("uv").startsWith("1 ") && v("uv").contains("Low"))
        assertEquals("15 mi", v("visibility"))
        assertTrue(v("sun"), v("sun").contains("7:25"))
        assertTrue(v("moon"), v("moon").contains("% lit"))
        assertTrue("°F everywhere", texts(on(h) { it.window.decorView }).none { it.text.contains("°C") })
        // No two texts on top of each other.
        val shown = texts(on(h) { it.heroScreen }).filter { it.text.isNotBlank() }.map { it.text.toString() to rect(it) }
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            val (a, ra) = shown[i]
            val (b, rb) = shown[j]
            assertFalse("'$a' overlaps '$b'", android.graphics.Rect.intersects(ra, rb))
        }
        shot("87-quick-numbers")

        // Scrub with the numbers up: they follow the hour.
        on(h) { it.bar.scrubToHour(DayBar.BEFORE_HOURS + 4) }
        assertEquals("80%", v("precip"))
        assertEquals("100%", v("cloud"))
        on(h) { it.bar.goLive() }

        // Tap again: just the scene, the number back.
        on(h) { quick.root.performClick() }
        assertFalse(on(h) { it.quickShown })
        assertTrue(on(h) { it.hero.isShown && !it.scrim.isShown })
        // Back puts them away first, then closes the screen.
        on(h) { it.scene.performClick() }
        on(h) { it.back() }
        assertFalse(on(h) { it.quickShown })
        assertEquals(Lifecycle.State.RESUMED, h.state)
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
                val up = listOf(a.heroScreen, a.weekScreen, a.dataScreen, a.alertScreen, a.placesScreen)
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

    @Test fun detailsAndSevenDaysAreScreensOfTheirOwn() = screen { h ->
        force(h, reading(2, 40, 0.0))
        // The details: from the quick numbers' precipitation. The scene goes out completely first.
        on(h) { it.scene.performClick() }
        val overlaps = watchForOverlap(h, 900) { on(h) { it.quick.precipCell.performClick() } }
        assertEquals("never two screens at once", emptyList<String>(), overlaps)
        settle(h)
        assertTrue(on(h) { it.dataShown })
        assertFalse("the scene is gone", on(h) { it.heroScreen.isShown || it.hero.isShown })
        assertFalse("and paused", on(h) { it.scene.animating })
        assertTrue("solid", on(h) { opaque(it.dataScreen) })
        val panel = on(h) { it.panel }
        val want = listOf("now", "summary", "precip", "hourly", "daily", "sun", "moon", "humidity", "visibility", "pressure", "uv", "aqi", "wind")
        assertEquals(want, on(h) { panel.sections.keys.toList() })
        for (k in want) {
            val shown = texts(on(h) { panel.sections[k]!! }).map { it.text.toString() }.filter { it.isNotBlank() }
            assertTrue("$k filled: $shown", shown.isNotEmpty() && shown.none { it == "—" })
        }
        fun section(k: String) = texts(on(h) { panel.sections[k]!! }).joinToString(" | ") { it.text.toString() }
        val now = section("now")
        assertTrue(now, now.contains("52°F") && now.contains("Feels 45°") && now.contains("H 52° · L 39°"))
        assertTrue("when it was fetched, in the header", on(h) { it.dataUpdated.isShown && it.dataUpdated.text.startsWith("Updated ") })
        assertTrue(section("visibility"), section("visibility").contains("15 mi") && section("visibility").contains("Good"))
        assertTrue(section("pressure"), section("pressure").contains("30.02 inHg"))
        assertTrue(section("aqi"), section("aqi").contains("42") && section("aqi").contains("Good"))
        assertTrue(section("wind"), section("wind").contains("6 mph from SSW"))
        assertTrue(section("humidity"), section("humidity").contains("81%") && section("humidity").contains("Dew point 44°"))
        assertTrue(section("moon"), section("moon").contains("% lit"))
        assertTrue(section("sun"), section("sun").contains("7:25"))
        // The day's summary is in here, under the header: never over the scene.
        assertTrue(on(h) { panel.sections["summary"]!!.isShown })
        val all = texts(on(h) { it.window.decorView }).map { it.text.toString() }
        assertFalse("nothing from the scene's screen: $all", all.any { it.contains("▾") || it == "52" })
        assertTrue("°F everywhere", all.none { it.contains("°C") })
        shot("88-details-screen")

        // Back: the reverse, again one at a time.
        assertEquals(emptyList<String>(), watchForOverlap(h, 900) { on(h) { it.back() } })
        settle(h)
        assertTrue(on(h) { it.heroScreen.isShown && it.scene.animating && !it.dataShown })

        // Swipe up from the scene: the 7 days.
        val hero = on(h) { it.heroScreen }
        val x = on(h) { hero.width / 2f }
        val y0 = on(h) { it.weekHandle.top - 60 * app.resources.displayMetrics.density + (it.weekHandle.parent as View).top }
        assertEquals(emptyList<String>(), watchForOverlap(h, 900) {
            val d = SystemClock.uptimeMillis()
            main {
                hero.dispatchTouchEvent(MotionEvent.obtain(d, d, MotionEvent.ACTION_DOWN, x, y0, 0))
                for (k in 1..10) hero.dispatchTouchEvent(MotionEvent.obtain(d, d + k * 16, MotionEvent.ACTION_MOVE, x, y0 - k * 30 * app.resources.displayMetrics.density, 0))
                hero.dispatchTouchEvent(MotionEvent.obtain(d, d + 200, MotionEvent.ACTION_UP, x, y0 - 300 * app.resources.displayMetrics.density, 0))
            }
        })
        settle(h)
        assertTrue("swiped up to the 7 days", on(h) { it.weekShown })
        assertFalse(on(h) { it.quickShown })
        assertFalse(on(h) { it.heroScreen.isShown })
        assertTrue("solid", on(h) { opaque(it.weekScreen) })
        val week = on(h) { it.week }
        val rows = on(h) { (0 until week.list.childCount).map { i -> week.list.getChildAt(i) } }
        assertEquals(7, rows.size)
        val first = texts(rows[1]).map { it.text.toString() }
        assertTrue("a day's condition, chance, low and high: $first", first.contains("80%") && first.any { it.endsWith("°") })
        assertTrue(on(h) { rows[1].contentDescription.toString() }.contains("Rain", ignoreCase = true))
        shot("99-week")

        // Hold a day: that whole day plays as a time-lapse, midnight to midnight in about 9 seconds,
        // the hour readout and the bar riding along; let go: it stops, still, on that day.
        show(lapseReading())
        idle()
        val lapseRows = on(h) { (0 until week.list.childCount).map { i -> week.list.getChildAt(i) } }
        val row = lapseRows[1]
        val tomorrow = Moon.dayStart(System.currentTimeMillis(), off) + 24 * hour
        val hf = io.github.iamth3doctor.searchlight.weatherapp.WeatherFormat.hour(app)
        fun hourText(k: Int) = hf.format(java.time.Instant.ofEpochMilli(tomorrow + k * hour).atOffset(java.time.ZoneOffset.ofTotalSeconds(off)))
        // Untouched, the window is the weather now, alive: moving, not a still picture.
        assertTrue("alive", on(h) { week.preview.animating })
        assertTrue("now's weather", on(h) { week.preview.conditions!!.live })
        assertEquals(on(h) { it.scene.conditions!!.tempC }, on(h) { week.preview.conditions!!.tempC }, 0.05)
        assertEquals(on(h) { it.scene.conditions!!.code }, on(h) { week.preview.conditions!!.code })
        val f0 = on(h) { week.preview.renderer.frames }
        waitFor("frames keep coming") { on(h) { week.preview.renderer.frames } > f0 + 15 }
        // At rest: the bar is today, the gold pin at NOW, the readout now; the day's numbers only while one plays.
        assertEquals(View.VISIBLE, on(h) { week.overlay.visibility })
        assertEquals(View.GONE, on(h) { week.window.lapseTop.visibility })
        val nowF = { ((System.currentTimeMillis() - Moon.dayStart(System.currentTimeMillis(), off)) / (24f * hour)) }
        assertTrue(on(h) { week.lapseBar.atNow })
        assertEquals("the pin at NOW", nowF(), on(h) { week.lapseBar.progress }, 0.01f)
        val nowDeg = "${WeatherText.f(onMain { repo.state.data!!.temperatureC })}°"
        assertTrue(on(h) { week.readout.text.toString() }, on(h) { week.readout.text.toString() }.startsWith("NOW · $nowDeg · "))
        shot("101-week-live")
        fun hold(): Long {
            val d = SystemClock.uptimeMillis()
            main { row.dispatchTouchEvent(MotionEvent.obtain(d, d, MotionEvent.ACTION_DOWN, 40f, 20f, 0)) }
            waitFor("holding plays the day") { on(h) { week.previewDay == 1 && week.preview.animating } }
            return d
        }
        fun release(d: Long) = main { row.dispatchTouchEvent(MotionEvent.obtain(d, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 40f, 20f, 0)) }
        var down = hold()
        // When the play began: now, less however far it has already got (seeing the hold can lag a slow frame).
        val began = on(h) { SystemClock.uptimeMillis() - (week.progress * DayLapse.PLAY_MS).toLong() }
        // (Exactly from the start is checked on the stepped clock below; here, a slow frame may have passed.)
        // From the start of the day: no further in than the time since the finger went down.
        val (startP, sinceDown) = onMain { week.progress to SystemClock.uptimeMillis() - down }
        assertTrue("from the start of the day: $startP after $sinceDown ms",
            startP * DayLapse.PLAY_MS <= sinceDown + 250 && startP < 0.5)
        assertEquals(View.VISIBLE, on(h) { week.overlay.visibility })
        assertEquals(View.VISIBLE, on(h) { week.window.lapseTop.visibility })
        assertFalse("the pin travels the day", on(h) { week.lapseBar.atNow })
        assertTrue(on(h) { week.caption.text.toString() }, on(h) { week.caption.text.toString() }.contains("rain ${hourText(15)}–${hourText(18)}"))
        assertEquals("the bar marks when rain is due", listOf(15 / 24f to 18 / 24f), on(h) { week.lapseBar.windows })
        val readouts = LinkedHashSet<String>()
        val pairs = ArrayList<Pair<Double, String>>()
        var last = -1.0
        var ended = 0L
        while (SystemClock.uptimeMillis() - began < 13_000) {
            val (p, r, bar) = on(h) { Triple(week.progress, week.readout.text.toString(), week.lapseBar.progress) }
            assertTrue("it only moves forward", p >= last)
            assertEquals(p.toFloat(), bar, 0.001f)
            last = p
            readouts += r
            pairs += p to r
            if (p >= 1.0) { ended = SystemClock.uptimeMillis(); break }
            Thread.sleep(100)
        }
        val took = ended - began
        assertTrue("the whole day in about 9 s: $took ms", took in 8_000L..10_800L)
        // Every sample's readout is the hour the playhead is in (the 3 PM rain itself: on the stepped clock below).
        assertTrue("the readout moves through the hours: $readouts", readouts.size >= 8)
        // The pin went the whole way along, start to end, with the readout.
        assertTrue("from the start", startP < 0.15 && pairs.first().first >= startP)
        assertEquals("to the end", 1.0, on(h) { week.lapseBar.progress }.toDouble(), 0.001)
        for ((p, r) in pairs) {
            val k = ((DayLapse.time(tomorrow, p) - tomorrow) / hour).toInt()
            assertTrue("at ${"%.3f".format(p)} the readout is hour $k's: $r", r.startsWith("${hourText(k)} · ") || r.startsWith("${hourText(k - 1)} · "))
        }
        release(down)
        assertNull(on(h) { week.previewDay })
        assertEquals(View.GONE, on(h) { week.window.lapseTop.visibility })
        // Let go: the pin slides back to NOW, the readout with it.
        assertTrue(on(h) { week.lapseBar.atNow })
        waitFor("the pin back at NOW") { abs(on(h) { week.lapseBar.progress } - nowF()) < 0.01f && !on(h) { week.lapseBar.returning } }
        assertTrue(on(h) { week.readout.text.toString() }.startsWith("NOW · "))
        // Let go: back to the weather now, still alive.
        assertTrue("back on now", on(h) { week.preview.conditions!!.live })
        assertEquals(System.currentTimeMillis().toDouble(), on(h) { week.preview.conditions!!.timeMs }.toDouble(), 60_000.0)
        val f1 = on(h) { week.preview.renderer.frames }
        waitFor("and alive") { on(h) { week.preview.animating && week.preview.renderer.frames > f1 + 15 } }

        // Again, on a clock the test drives: from the start once more, and rain exactly at 3 PM.
        var fake = 1_000_000L
        main { week.playClock = { fake } }
        down = hold()
        assertEquals("replays from the start", 0.0, on(h) { week.progress }, 0.001)
        fun at(hours: Double) {
            fake = 1_000_000L + (DayLapse.PLAY_MS * hours / 24).toLong()
            waitFor("the playhead at $hours h") { abs(on(h) { week.progress } - hours / 24) < 0.002 }
        }
        at(14.95)
        assertEquals("${hourText(14)} · ${WeatherText.f(4.0 + 14 * 0.45)}° · 10% rain", on(h) { week.readout.text.toString() })
        assertEquals("dry until 3", 0.0, on(h) { week.preview.conditions!!.rain }, 0.0)
        at(15.1)
        assertEquals("${hourText(15)} · ${WeatherText.f(4.0 + 15 * 0.45)}° · 80% rain", on(h) { week.readout.text.toString() })
        assertTrue("raining from 3", on(h) { week.preview.conditions!!.rain } > 0.4)
        assertEquals("Rain", on(h) { week.lapseCondition.text.toString() })
        assertEquals("${WeatherText.f(on(h) { week.preview.conditions!!.tempC })}°", on(h) { week.lapseTemp.text.toString() })
        waitFor("the rain falls in the window") { on(h) { week.preview.renderer.dropCount } > 10 }
        at(16.5)
        shot("100-week-hold")
        at(18.2)
        assertEquals("stopped by 6", 0.0, on(h) { week.preview.conditions!!.rain }, 0.0)
        assertTrue("the sun has set", on(h) { week.preview.conditions!!.sunAltDeg } < 0)
        at(12.5)
        assertTrue("midday: the sun is up", on(h) { week.preview.conditions!!.sunAltDeg } > 5)
        release(down)
        main { week.playClock = { SystemClock.uptimeMillis() } }
        assertNull(on(h) { week.previewDay })

        // The details from the 7 days, then back twice: never two at once.
        val seen = watchForOverlap(h, 2200) {
            on(h) { it.weekScreen.findViewWithTag<View>("weather_week_details").performClick() }
            Thread.sleep(700)
            assertTrue(on(h) { it.dataShown })
            on(h) { it.back() }
            Thread.sleep(700)
            assertTrue(on(h) { it.weekShown })
        }
        assertEquals(emptyList<String>(), seen)
        settle(h)

        // One vertical stack: swipe up again (with the list at its bottom) for the details, and
        // swipe down from the details back to the 7 days.
        fun swipe(v: View, up: Boolean) {
            val dp = app.resources.displayMetrics.density
            val sx = on(h) { v.width / 2f }
            val sy = on(h) { v.height * (if (up) 0.7f else 0.3f) }
            val step = (if (up) -30 else 30) * dp
            val t = SystemClock.uptimeMillis()
            main {
                v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, sx, sy, 0))
                for (k in 1..10) v.dispatchTouchEvent(MotionEvent.obtain(t, t + k * 16, MotionEvent.ACTION_MOVE, sx, sy + k * step, 0))
                v.dispatchTouchEvent(MotionEvent.obtain(t, t + 200, MotionEvent.ACTION_UP, sx, sy + 10 * step, 0))
            }
            settle(h)
        }
        val wk = on(h) { it.weekScreen }
        if (on(h) { it.weekScroll.canScrollVertically(1) }) {
            // Not at the bottom yet: a swipe up just scrolls the list.
            swipe(wk, up = true)
            assertTrue("scrolled, still the 7 days", on(h) { it.weekShown && !it.dataShown })
        }
        h.onActivity { it.weekScroll.fullScroll(View.FOCUS_DOWN) }
        Thread.sleep(400)
        swipe(wk, up = true)
        assertTrue("swiped up to the details", on(h) { it.dataShown })
        assertEquals("over the 7 days", 2, on(h) { it.screens.depth })
        swipe(on(h) { it.dataScreen }, up = false)
        assertTrue("swiped down to the 7 days", on(h) { it.weekShown && !it.dataShown })
        h.onActivity { it.weekScroll.scrollTo(0, 0) }
        Thread.sleep(300)

        // Swipe down: back to the scene.
        val ws = on(h) { it.weekScreen }
        val sx = on(h) { ws.width / 2f }
        val sy = on(h) { ws.height * 0.3f }
        val d2 = SystemClock.uptimeMillis()
        main {
            ws.dispatchTouchEvent(MotionEvent.obtain(d2, d2, MotionEvent.ACTION_DOWN, sx, sy, 0))
            for (k in 1..10) ws.dispatchTouchEvent(MotionEvent.obtain(d2, d2 + k * 16, MotionEvent.ACTION_MOVE, sx, sy + k * 30 * app.resources.displayMetrics.density, 0))
            ws.dispatchTouchEvent(MotionEvent.obtain(d2, d2 + 200, MotionEvent.ACTION_UP, sx, sy + 300 * app.resources.displayMetrics.density, 0))
        }
        settle(h)
        assertTrue("swiped down to the scene", on(h) { it.heroScreen.isShown && !it.weekShown })
        assertEquals(Lifecycle.State.RESUMED, h.state)
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

    @Test fun theGiantNumberIsAFidget() = screen { h ->
        force(h, reading(0, 2, 0.0))
        val hero = on(h) { it.hero }
        val step = on(h) { hero.stepPx() }
        val ticks0 = on(h) { it.bar.ticks }
        val cx = on(h) { hero.width / 2f }
        val cy = on(h) { hero.height / 2f }
        fun drag(hours: Float) {
            val d = SystemClock.uptimeMillis()
            main {
                hero.dispatchTouchEvent(MotionEvent.obtain(d, d, MotionEvent.ACTION_DOWN, cx, cy, 0))
                for (k in 1..20) hero.dispatchTouchEvent(MotionEvent.obtain(d, d + k * 16, MotionEvent.ACTION_MOVE, cx + step * hours * k / 20, cy, 0))
                hero.dispatchTouchEvent(MotionEvent.obtain(d, d + 340, MotionEvent.ACTION_UP, cx + step * hours, cy, 0))
            }
        }
        // Drag right four hours: into the rain, a tick for each hour passed.
        drag(4f)
        assertEquals(h0 + 4 * hour, on(h) { it.scrubTime })
        assertEquals("a tick per hour", ticks0 + 4, on(h) { it.bar.ticks })
        val wantF = DayBar.readout(onMain { repo.state.data!! }, h0 + 4 * hour).first
        assertEquals("$wantF", on(h) { hero.number })
        assertEquals("RAIN", on(h) { it.heroCondition.text.toString() })
        assertTrue(on(h) { it.bar.readout(h0 + 4 * hour) }.contains("80% rain"))
        assertTrue("the scene rains", on(h) { it.scene.conditions!!.rain } > 0.5)
        assertTrue("and sounds like rain", on(h) { it.sound.rain } > 0.3f)
        assertFalse("a drag is not a tap", on(h) { it.quickShown })
        shot("101-hero-drag")
        // Back two hours: two more ticks, still on whole hours.
        drag(-2f)
        assertEquals(h0 + 2 * hour, on(h) { it.scrubTime })
        assertEquals(ticks0 + 6, on(h) { it.bar.ticks })
        // Now: live again.
        on(h) { it.bar.tap(it.bar.xOf(System.currentTimeMillis())) }
        assertNull(on(h) { it.scrubTime })
        assertEquals("52", on(h) { hero.number })
    }

    @Test fun theSceneIsAlive() = screen { h ->
        // Clear and calm: birds.
        force(h, reading(0, 2, 0.0, wind = 3.0))
        val day = on(h) { it.scene.conditions!!.darkness < 0.35 }
        if (day) waitFor("birds", 15_000) { on(h) { it.scene.renderer.birdCount } > 0 }
        assertEquals(0, on(h) { it.scene.renderer.glassDropCount })
        // Rain: drops gather on the glass; a strong wind leans the rain.
        force(h, reading(63, 100, 4.0, windFrom = 270.0, wind = 25.0))
        waitFor("drops on the glass", 15_000) { on(h) { it.scene.renderer.glassDropCount } > 4 }
        // Birds already up fly on out of the rain; no new ones come.
        if (day) waitFor("birds leave the rain", 60_000) { on(h) { it.scene.renderer.birdCount } == 0 }
        shot("102-scene-rain-glass")
        // Paused off screen and in battery saver.
        WeatherSceneView.powerSaveOverride = true
        on(h) { it.scene.updateRunning() }
        assertFalse(on(h) { it.scene.animating })
        WeatherSceneView.powerSaveOverride = false
        on(h) { it.scene.updateRunning() }
        assertTrue(on(h) { it.scene.animating })
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
            assertEquals("60", on(h) { it.hero.number })
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
            assertEquals("52", on(h) { it.hero.number })
            // And to Seattle again, from the saved list: its last reading, offline or not.
            on(h) { it.placeButton.performClick() }
            settle(h)
            on(h) { places.saved.findViewWithTag<View>("weather_place_saved").performClick() }
            settle(h)
            assertEquals("Seattle", onMain { repo.state.data?.locationName })
            assertEquals("60", on(h) { it.hero.number })
        }
        // The widget and the Weather tile show the same city, in °F.
        home { h ->
            val v = addWeather(h)
            // The 2x2 "now" widget: the city on top and the temperature.
            assertEquals(listOf("Seattle", "60°F"), texts(v).map { it.text.toString() })
            val tile = tileLine(h)
            assertTrue("tile: $tile", tile.contains("60°F"))
        }
        main { repo.selectCity(null) }
        assertEquals("Tacoma", onMain { repo.state.data?.locationName })
    }

    @Test fun silencedPhoneStaysQuiet() = screen { h ->
        assertTrue(on(h) { it.soundOn() })
        WeatherActivity.quietOverride = true
        on(h) { it.updateSoundButton() }
        assertFalse("silent / Do Not Disturb wins", on(h) { it.soundOn() })
        force(h, reading(95, 100, 7.0))
        val felt = on(h) { it.strikesFelt }
        waitFor("lightning", 12_000) { look(h).strikes > 0 }
        Thread.sleep(300)
        assertEquals("no thunder or haptic while silenced", felt, on(h) { it.strikesFelt })
        WeatherActivity.quietOverride = false
        // The toggle: off, then on again.
        on(h) { it.muteButton.performClick() }
        assertFalse(on(h) { it.soundOn() })
        on(h) { it.muteButton.performClick() }
        assertTrue(on(h) { it.soundOn() })
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

    private fun frames(h: ActivityScenario<MainActivity>, v: WeatherWidgetView) = on(h) { v.scene.renderer.frames }

    @Test fun widgetIsALiveWindowThatRestsOffScreen() = home { h ->
        show(reading(63, 100, 3.0))
        val before = repo.refreshRequests
        val v = addWeather(h)
        assertTrue("adding it refreshes", repo.refreshRequests > before)
        assertEquals(2 to 2, layout(h).single().let { it.colSpan to it.rowSpan })

        // The scene moves while home shows.
        waitFor("animating") { on(h) { v.scene.animating } }
        val f0 = frames(h, v)
        Thread.sleep(1000)
        assertTrue("frames advance on home", frames(h, v) > f0 + 10)
        waitFor("rain in the window") { on(h) { v.scene.renderer.dropCount > 10 } }

        // 2x2: just the place, small at the top, and the temperature: no forecast, no other numbers.
        val shown = texts(v).map { it.text.toString() }
        assertEquals(listOf("Tacoma", "52°F"), shown)
        assertTrue("the place over the number", rect(v.placeView).bottom <= rect(v.tempView).top)
        // Same reading as the Weather tile.
        val tile = tileLine(h)
        assertTrue("tile: $tile", tile.contains("52°F"))
        assertEquals(on(h) { repo.state.data!!.weatherCode }, on(h) { v.scene.conditions!!.code })
        shot("88-widget-rain")

        // Home hidden: it stops.
        h.moveToState(Lifecycle.State.CREATED)
        waitFor("paused off-screen") { on(h) { !v.scene.animating } }
        val f1 = frames(h, v)
        Thread.sleep(1000)
        assertEquals("no frames in the background", f1, frames(h, v))
        h.moveToState(Lifecycle.State.RESUMED)
        waitFor("animating again") { on(h) { v.scene.animating } }

        // Battery saver: it holds still (the real setting where the emulator allows it).
        shell("dumpsys battery unplug")
        shell("settings put global low_power 1")
        WeatherSceneView.powerSaveOverride = null
        val real = onMain { app.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode }
        if (!real) WeatherSceneView.powerSaveOverride = true
        on(h) { v.scene.updateRunning() }
        waitFor("battery saver stops it") { on(h) { !v.scene.animating } }
        val f2 = frames(h, v)
        Thread.sleep(1000)
        assertEquals("still in battery saver", f2, frames(h, v))
        shell("settings put global low_power 0")
        shell("dumpsys battery reset")
        WeatherSceneView.powerSaveOverride = false
        on(h) { v.scene.updateRunning() }
        waitFor("moving again") { on(h) { v.scene.animating } }
    }

    @Test fun widgetSizesAndTaps() = home { h ->
        show(reading(0, 2, 0.0))
        val v = addWeather(h)
        val spec = layout(h).single()
        val grid = on(h) { it.findViewById<WidgetGridView>(R.id.widgetGrid) }
        val gap = 2 * app.resources.getDimension(R.dimen.space_s)
        val density = app.resources.displayMetrics.density
        for ((size, mode, name) in listOf(
            Triple(2 to 1, WeatherWidgetView.Size.COMPACT, "74-weather-2x1"),
            Triple(4 to 2, WeatherWidgetView.Size.WIDE, "76-weather-4x2"),
            Triple(2 to 2, WeatherWidgetView.Size.STANDARD, "75-weather-2x2"),
        )) {
            on(h) { it.widgetController.onCommit(spec.copy(colSpan = size.first, rowSpan = size.second)); it.widgetController.render() }
            waitFor("resized to $size") {
                on(h) {
                    val wantW = grid.cellWidthDp * density * size.first - gap
                    val wantH = grid.cellHeightDp * density * size.second - gap
                    abs(v.width - wantW) <= 2 && abs(v.height - wantH) <= 2
                }
            }
            Thread.sleep(500)
            assertEquals(mode, on(h) { v.size })
            assertNull("$size: temperature and high/low whole and inside", on(h) { v.fitProblem() })
            assertEquals(if (mode == WeatherWidgetView.Size.STANDARD) listOf("Tacoma", "52°F") else listOf("52°F", "H 52° · L 39°"),
                texts(v).map { it.text.toString() })
            assertTrue("$size animating", on(h) { v.scene.animating })
            shot(name)
        }

        // Tapping the widget, or the Weather tile, opens the weather screen.
        val monitor = instrumentation.addMonitor(WeatherActivity::class.java.name, Instrumentation.ActivityResult(Activity.RESULT_OK, null), true)
        try {
            on(h) { v.performClick() }
            waitFor("widget opens the weather screen") { monitor.hits >= 1 }
            on(h) { a ->
                val card = a.homeCards.first { it is WeatherCard }
                fun find(x: View): View? {
                    if (x.id == R.id.card && (x as? ViewGroup)?.findViewById<View>(R.id.weatherLine) != null) return x
                    if (x is ViewGroup) for (i in 0 until x.childCount) find(x.getChildAt(i))?.let { r -> return r }
                    return null
                }
                assertNotNull(card)
                a.findViewById<ViewPager2?>(R.id.cards)?.takeIf { it.isShown }?.setCurrentItem(a.homeCards.indexOf(card), false)
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
            waitFor("the tile opens the weather screen") { monitor.hits >= 2 }
        } finally {
            instrumentation.removeMonitor(monitor)
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
            var v = addWeather(h)
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
            // Airplane mode can rebuild the home screen (its radios change): the widget as it is now.
            fun widget() = on(h) { it.widgetController.weatherViews().single() }
            waitFor("offline glyph") { widget().let { w -> on(h) { w.offlineShown } } }
            v = widget()
            assertEquals("the last good data", listOf("Tacoma", "52°F"), texts(v).map { it.text.toString() })
            assertTrue("the last good scene, still moving", on(h) { v.scene.conditions!!.cloudCover > 0.9 && v.scene.animating })
            assertTrue("the last known alert", on(h) { v.alertShown })
            val glyph = on(h) { v.offlineGlyph }
            assertTrue("small", on(h) { glyph.width <= (24 * app.resources.displayMetrics.density) })
            shot("89-widget-offline")
        }
        screen { s ->
            assertTrue(on(s) { it.offlineGlyph.isShown })
            assertTrue("no warning text", texts(on(s) { it.window.decorView }).none { it.text.contains("offline", ignoreCase = true) })
            // The last known alert: pill and storm scene, details still there.
            assertTrue(on(s) { it.alertPill.isShown })
            assertEquals(io.github.iamth3doctor.searchlight.weather.AlertKind.THUNDER, on(s) { it.scene.conditions!!.severe })
            assertTrue("the last scene", on(s) { it.scene.conditions!!.cloudCover > 0.9 })
            shot("90-scene-offline")
            on(s) { it.openDetails() }
            settle(s)
            val now = texts(on(s) { it.panel.sections["now"]!! }).map { it.text.toString() }
            assertTrue(now.toString(), "52°F" in now && on(s) { it.dataUpdated.text.startsWith("Updated ") })
            // The last good precip: this hour's rows and the rain coming.
            val card = on(s) { it.panel.precip }
            assertEquals("0.00 IN", on(s) { card.rateValue.text.toString() })
            assertTrue(on(s) { card.expected.text.toString() }.startsWith("Rain expected around "))
            on(s) { it.bar.scrubTo(h0 + 3 * hour + 30 * 60_000L) }
            assertEquals("0.16 IN", on(s) { card.rateValue.text.toString() })
            assertEquals("80%", on(s) { card.chanceValue.text.toString() })
        }
        shell("cmd connectivity airplane-mode disable")
    }

    @Test fun theRefreshButtonFetchesNowAndKeepsTheLastDataWhenItFails() = screen { h ->
        val old = reading(2, 40, 0.0).let { it.copy(fetchedAt = it.fetchedAt - 25 * 60_000L) }
        force(h, old)
        on(h) { it.openDetails() }
        settle(h)
        val tf = io.github.iamth3doctor.searchlight.weatherapp.WeatherFormat.time(app)
        fun stamp(t: Long) = "Updated " + tf.format(java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()))
        val button = on(h) { it.refreshButton }
        val updated = on(h) { it.dataUpdated }
        assertEquals(stamp(old.fetchedAt), on(h) { updated.text.toString() })
        // In the header: round, beside the place and its time, at the right.
        assertTrue(on(h) { button.isShown && button.width == button.height && button.background != null })
        val b = rect(button)
        val t = rect(on(h) { it.dataScreen.findViewWithTag<View>("weather_updated") })
        assertTrue("beside the time: $b $t", b.left >= t.left && b.top < t.bottom && b.bottom > rect(on(h) { updated.parent as View }).top)
        assertFalse(on(h) { it.refreshNote.isShown })

        // A tap fetches now, once, with a spinner meanwhile; the screen keeps what it shows.
        val asked = onMain { repo.refreshRequests }
        val beforeSections = on(h) { it.panel.sections.keys.toList() }
        touch(button, on(h) { button.width / 2f }, on(h) { button.height / 2f })
        assertEquals(asked + 1, onMain { repo.refreshRequests })
        idle(600)
        assertTrue("spinning", on(h) { it.manualRefreshing && it.refreshSpinner.isShown && !it.refreshIcon.isShown })
        on(h) { it.refreshNow() }
        assertEquals("one fetch at a time", asked + 1, onMain { repo.refreshRequests })
        assertEquals(beforeSections, on(h) { it.panel.sections.keys.toList() })
        shot("128-refreshing")
        assertTrue("still spinning through the shot", on(h) { it.manualRefreshing && it.refreshSpinner.isShown && !it.refreshIcon.isShown })

        // It lands: the new reading, and the time it was fetched.
        val fresh = reading(2, 40, 0.0)
        show(fresh)
        idle(600)
        assertFalse(on(h) { it.manualRefreshing })
        assertTrue("the arrow back", on(h) { it.refreshIcon.isShown && !it.refreshSpinner.isShown })
        assertEquals(stamp(fresh.fetchedAt), on(h) { updated.text.toString() })
        assertFalse(on(h) { it.refreshNote.isShown })

        // It fails: the last reading stays, everything on screen with it, and a short note says so.
        touch(button, on(h) { button.width / 2f }, on(h) { button.height / 2f })
        assertTrue(on(h) { it.manualRefreshing })
        show(fresh, offline = true)
        idle(600)
        assertFalse(on(h) { it.manualRefreshing })
        assertTrue(on(h) { it.refreshNote.isShown })
        assertEquals("Couldn't refresh — showing last data", on(h) { it.refreshNote.text.toString() })
        assertEquals(stamp(fresh.fetchedAt), on(h) { updated.text.toString() })
        assertEquals(beforeSections, on(h) { it.panel.sections.keys.toList() })
        assertTrue(texts(on(h) { it.panel.sections["now"]!! }).any { it.text.toString() == "52°F" })
        shot("129-refresh-failed")

        // The next good reading clears the note.
        show(reading(2, 40, 0.0))
        idle(300)
        assertFalse(on(h) { it.refreshNote.isShown })
    }

    @Test fun theHeroHasTheSameRefreshButton() = screen { h ->
        val old = reading(2, 40, 0.0).let { it.copy(fetchedAt = it.fetchedAt - 25 * 60_000L) }
        force(h, old)
        val tf = io.github.iamth3doctor.searchlight.weatherapp.WeatherFormat.time(app)
        fun stamp(t: Long) = "Updated " + tf.format(java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()))
        val chip = on(h) { it.heroRefresh }
        val button = chip.button
        val note = on(h) { it.heroRefreshNote }
        // In the hero's top row: round frosted glass, between the place and the sound button, overlapping neither.
        assertTrue(on(h) { button.isShown && button.width == button.height && button.background != null })
        val b = rect(button)
        val place = rect(on(h) { it.placeButton })
        val mute = rect(on(h) { it.muteButton })
        assertTrue("in the top row, left of the sound button: $b $mute", b.right <= mute.left && b.top == mute.top)
        assertFalse("clear of the place: $b $place", android.graphics.Rect.intersects(b, place))
        assertFalse(on(h) { note.isShown })

        // A tap fetches now, once, with a spinner in the arrow's place; the hero keeps what it shows.
        val asked = onMain { repo.refreshRequests }
        touch(button, on(h) { button.width / 2f }, on(h) { button.height / 2f })
        assertEquals(asked + 1, onMain { repo.refreshRequests })
        idle(600)
        assertTrue("spinning", on(h) { it.manualRefreshing && chip.spinner.isShown && !chip.icon.isShown })
        assertEquals("52", on(h) { it.hero.number })
        shot("131-hero-refreshing")

        // It lands: the arrow back, and the new time shown for a moment, under the top row.
        val fresh = reading(2, 40, 0.0)
        show(fresh)
        idle(600)
        assertFalse(on(h) { it.manualRefreshing })
        assertTrue("the arrow back", on(h) { chip.icon.isShown && !chip.spinner.isShown })
        assertTrue(on(h) { note.isShown })
        assertEquals(stamp(fresh.fetchedAt), on(h) { note.text.toString() })
        assertTrue("under the top row", rect(note).top >= b.bottom)
        waitFor("the time goes", 8_000) { on(h) { !note.isShown } }

        // It fails: the last reading stays, and the note says so.
        touch(button, on(h) { button.width / 2f }, on(h) { button.height / 2f })
        assertTrue(on(h) { it.manualRefreshing })
        show(fresh, offline = true)
        idle(600)
        assertFalse(on(h) { it.manualRefreshing })
        assertTrue(on(h) { note.isShown })
        assertEquals("Couldn't refresh — showing last data", on(h) { note.text.toString() })
        assertEquals("52", on(h) { it.hero.number })
        shot("132-hero-refresh-failed")

        // The next good reading clears it.
        show(reading(2, 40, 0.0))
        idle(300)
        assertFalse(on(h) { note.isShown })
    }

    /** No line of [v] starts with an AM/PM left behind by its time. */
    private fun assertNoDanglingAmPm(v: TextView) {
        val lines = onMain {
            val l = v.layout
            if (l == null) emptyList() else (0 until l.lineCount).map { k -> v.text.subSequence(l.getLineStart(k), l.getLineEnd(k)).toString().trim() }
        }
        for (line in lines.drop(1)) assertFalse("$lines splits a time", line.startsWith("AM") || line.startsWith("PM"))
    }

    /** None of the words [art] drew last overlaps another. */
    private fun assertLabelsClear(art: io.github.iamth3doctor.searchlight.weatherapp.MetricArtView, atLeast: Int) {
        val labels = onMain { art.drawnLabels.map { it.first to android.graphics.RectF(it.second) } }
        assertTrue("labels drawn: $labels", labels.size >= atLeast)
        for (i in labels.indices) for (j in i + 1 until labels.size) {
            assertFalse("${labels[i]} overlaps ${labels[j]}", android.graphics.RectF.intersects(labels[i].second, labels[j].second))
        }
        val width = onMain { art.width }
        assertTrue("inside the card: $labels", labels.all { it.second.left >= 0 && it.second.right <= width })
    }

    @Test fun weatherTimesNeverWrapOrOverlap() = screen { h ->
        // Rain a sure thing every day: "100%" in the 7 days.
        val base = reading(2, 40, 0.0)
        val w = base.copy(detail = base.detail!!.copy(daily = base.detail!!.daily.map { it.copy(precipPct = 100) }))
        force(h, w)
        on(h) { it.openDetails() }
        settle(h)
        val panel = on(h) { it.panel }
        // The 7 DAYS: "100%" on one line.
        val dayRows = onMain { panel.sections["daily"]!!.let { root -> ArrayList<View>().also { root.findViewsWithText(it, "100%", View.FIND_VIEWS_WITH_TEXT) } } }
        assertEquals(7, dayRows.size)
        for (v in dayRows) assertEquals("\"100%\" on one line", 1, onMain { (v as TextView).lineCount })
        // Every time on the details: whole, its AM/PM never on a line of its own.
        for (t in texts(on(h) { it.dataScroll })) assertNoDanglingAmPm(t)

        // The sun: sunrise under the left end of the arc, sunset under the right, apart.
        on(h) { panel.toggle("sun") }
        idle(1500)
        val sun = on(h) { (panel.sections["sun"] as io.github.iamth3doctor.searchlight.weatherapp.MetricTile).art }
        assertLabelsClear(sun, 2)
        val (rise, set) = onMain { sun.drawnLabels.take(2).map { android.graphics.RectF(it.second) } }
        val half = onMain { sun.width / 2f }
        assertTrue("sunrise left, sunset right: $rise $set", rise.left < half && set.right > half && rise.right < set.left)
        shot("133-sun-times")

        // The moon: its hours spaced, never one on another; its rise and set each whole.
        on(h) { panel.toggle("moon") }
        idle(1500)
        val moonTile = on(h) { panel.sections["moon"]!! }
        on(h) { moonTile.requestRectangleOnScreen(android.graphics.Rect(0, 0, moonTile.width, moonTile.height), true) }
        idle(600)
        assertLabelsClear(on(h) { (moonTile as io.github.iamth3doctor.searchlight.weatherapp.MetricTile).art }, 2)
        for (t in texts(moonTile)) assertNoDanglingAmPm(t)
        shot("134-moon-times")

        // The 7-day page: "100%" on one line there too.
        on(h) { it.screens.pop(); it.openWeek() }
        settle(h)
        val weekRain = on(h) { a -> ArrayList<View>().also { a.week.root.findViewsWithText(it, "100%", View.FIND_VIEWS_WITH_TEXT) } }
        assertEquals(7, weekRain.size)
        for (v in weekRain) assertEquals("\"100%\" on one line", 1, onMain { (v as TextView).lineCount })
    }

    // ---- phase 2: the hero, the precip card, severe alerts ----

    @Test fun heroNumbersStandOverTheSceneInBothThemes() {
        for ((night, name) in listOf("no" to "91-hero-light", "yes" to "92-hero-dark")) {
            shell("cmd uimode night $night")
            Thread.sleep(1500)
            idle()
            screen { h ->
                // The theme change may still be landing: wait until the screen is in that theme.
                runCatching { waitFor("theme applied") { on(h) { (it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_YES != 0) == (night == "yes") } } }
                force(h, reading(1, 25, 0.0))
                assertEquals("52", on(h) { it.hero.number })
                assertTrue("the whole number fits", on(h) { it.hero.fits() })
                val minPx = 100 * app.resources.displayMetrics.density
                assertTrue("oversized: ${on(h) { it.hero.textSize }}px", on(h) { it.hero.textSize } >= minPx)
                val whole = on(h) {
                    val r = android.graphics.Rect()
                    it.hero.getGlobalVisibleRect(r) && r.width() == it.hero.width && r.height() == it.hero.height
                }
                assertTrue("nothing clipped", whole)
                for (other in listOf(on(h) { it.placeButton }, on(h) { it.heroCondition }, on(h) { it.bar }, on(h) { it.weekHandle })) {
                    assertFalse("nothing over the number", android.graphics.Rect.intersects(rect(on(h) { it.hero }), rect(other)))
                }
                assertEquals("MAINLY CLEAR", on(h) { it.heroCondition.text.toString() })
                assertTrue("the scene still plays behind it", on(h) { it.scene.isShown && it.scene.animating })
                // Scrubbing: the hero shows that hour.
                on(h) { it.bar.scrubTo(h0 + 4 * hour) }
                assertEquals("${WeatherText.f(11.1 + 4 * 0.25)}", on(h) { it.hero.number })
                on(h) { it.bar.goLive() }
                shot(name)
            }
        }
        shell("cmd uimode night $savedNight")
    }

    @Test fun overcastShowsNoSunAnywhere() = screen { h ->
        // The Renton afternoon: Overcast, 100% cloud, nothing falling, a low chance, rain in the strip
        // 3 hours on. Gray cloud on the island and in the scene, no sun, no streaks, "Rain None".
        force(h, reading(3, 100, 0.0))
        settle(h)
        // The hero's scene first, while it's on screen and animating: the sun fades out.
        waitFor("the scene's sun fades out under overcast") { on(h) { !it.scene.renderer.sunShowing } }
        assertEquals(SceneModel.Sky.CLOUD, on(h) { it.conditions().sky })
        assertEquals(0.0, on(h) { it.conditions().rain }, 0.0)
        assertEquals("Rain None", on(h) { io.github.iamth3doctor.searchlight.weather.HeroReadings.precipLine(it.conditions()) })
        on(h) { it.openDetails() }
        settle(h)
        val card = on(h) { it.panel.precip }
        assertFalse("no sun on the island when it's overcast", on(h) { card.island.showsSun })
        assertEquals(0, on(h) { card.island.particles })
        // Rain in the strip within 6 hours: TYPE Rain, though none falls yet; expected around its first hour.
        assertEquals("RAIN", on(h) { card.typeValue.text.toString() })
        val firstRain = io.github.iamth3doctor.searchlight.weatherapp.WeatherFormat.hour(app)
            .format(java.time.Instant.ofEpochMilli(h0 + 3 * hour).atOffset(java.time.ZoneOffset.ofTotalSeconds(off)))
        val expected = on(h) { card.expected.text.toString() }
        assertTrue(expected, expected.endsWith(firstRain))
        on(h) { it.panel.sections["precip"]!!.let { v -> (v.parent.parent as? android.widget.ScrollView)?.smoothScrollTo(0, v.top) } }
        shot("135-overcast-island")

        // Rain now (a shower code): rain on the island and in the scene, still no sun.
        force(h, reading(80, 60, 1.2))
        settle(h)
        assertEquals(SceneModel.Sky.RAIN, on(h) { it.conditions().sky })
        assertFalse(on(h) { card.island.showsSun })
        assertTrue("rain falls on the island", on(h) { card.island.particles } > 5)
        assertEquals("RAIN", on(h) { card.typeValue.text.toString() })
        shot("136-showers-island")
    }

    @Test fun precipCardRowsGraphAndBarMoveTogether() = screen { h ->
        force(h, reading(0, 2, 0.0))
        on(h) { it.openDetails() }
        settle(h)
        val card = on(h) { it.panel.precip }
        fun rows() = on(h) { listOf(card.rateValue.text.toString(), card.chanceValue.text.toString(), card.typeValue.text.toString()) }
        // Clear and dry now, rain in the strip 3 hours on: RATE and CHANCE are now's; TYPE isn't NONE.
        assertEquals(listOf("0.00 IN", "5%", "RAIN"), rows())
        assertEquals("RATE", on(h) { card.rateLabel.text.toString() })
        assertTrue(on(h) { card.expected.text.toString() }, on(h) { card.expected.text.toString() }.startsWith("Rain expected around "))
        assertEquals(0, on(h) { card.island.particles })
        assertTrue("a clear sky: the island has its sun", on(h) { card.island.showsSun })
        assertEquals(PrecipGraphView.Mode.DAY, on(h) { card.graph.mode })
        on(h) { it.panel.sections["precip"]!!.let { v -> (v.parent.parent as? android.widget.ScrollView)?.smoothScrollTo(0, v.top) } }
        shot("93-precip-now")

        // Scrub the graph into the rain: the main bar, the scene and the rows all follow.
        val t = h0 + 3 * hour + 30 * 60_000L
        on(h) { card.graph.touchAt(card.graph.xOf(t), tap = false) }
        val barTime = on(h) { it.scrubTime!! }
        assertTrue(abs(barTime - t) < 20 * 60_000L)
        assertEquals(barTime, on(h) { card.graph.scrubTime })
        assertEquals(listOf("0.16 IN", "80%", "RAIN"), rows())
        assertTrue("rain falls on the island", on(h) { card.island.particles } > 5)
        assertTrue("the scene (behind this screen) is at that hour", on(h) { it.conditions().rain } > 0.5)
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

        // Scrub the main bar: the graph's scrubber and the rows follow.
        on(h) { it.bar.scrubTo(h0 + 10 * hour) }
        assertEquals(on(h) { it.scrubTime }, on(h) { card.graph.scrubTime })
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
        // Back to DAY: in step with the bar again.
        on(h) { card.dayChip.performClick() }
        assertEquals(PrecipGraphView.Mode.DAY, on(h) { card.graph.mode })
        assertEquals(on(h) { it.scrubTime }, on(h) { card.graph.scrubTime })
        assertEquals("RATE", on(h) { card.rateLabel.text.toString() })
        // Now, from the graph: live everywhere.
        on(h) { card.graph.touchAt(card.graph.xOf(System.currentTimeMillis()), tap = true) }
        assertNull(on(h) { it.scrubTime })
        assertEquals(listOf("0.00 IN", "5%", "RAIN"), rows())
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
        val c = on(h) { it.scene.conditions!! }
        assertEquals(io.github.iamth3doctor.searchlight.weather.AlertKind.THUNDER, c.severe)
        assertTrue("storm: dark clouds, heavy rain", c.thunder && c.cloudCover == 1.0 && c.rain >= 0.85)
        waitFor("rain streaks") { look(h).drops > 200 }
        waitFor("lightning", 12_000) { look(h).strikes > 0 }
        shot("96-alert-scene")

        // The pill never sits on the number.
        assertFalse(android.graphics.Rect.intersects(rect(on(h) { it.alertPill }), rect(on(h) { it.hero })))
        on(h) { it.alertPill.performClick() }
        assertTrue(on(h) { it.alertShown })
        settle(h)
        assertFalse("its own screen", on(h) { it.heroScreen.isShown })
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
        assertNull(on(h) { it.scene.conditions!!.severe })
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

    @Test fun theHeroIsOneRealisticSceneWithTheDataRoundIt() = screen { h ->
        force(h, reading(80, 60, 1.2, windFrom = 250.0, wind = 14.0))
        val a = current()
        // One scene, realistic (not the cartoon style), filling the screen; the number inside it (clouds drift in front of it).
        assertTrue(onMain { !a.scene.renderer.cartoon && a.scene.renderer.layered })
        assertEquals(onMain { a.heroScreen.width to a.heroScreen.height }, onMain { a.scene.width to a.scene.height })
        assertTrue("the number is in the scene, not a header over it", onMain { a.hero.parent === a.heroBox })
        assertTrue(onMain { a.hero.isShown && a.hero.fits() })
        assertEquals("${WeatherText.f(11.1)}", onMain { a.hero.number })
        assertNull("nothing overlaps", onMain { a.heroBox.overlaps() })
        // The callouts: wind, clouds and rain, sun and moon, temperature, UV and visibility.
        val callouts = onMain { a.heroBox.callouts }
        assertEquals(HeroReadings.KEYS, callouts.keys.toList())
        fun lines(k: String) = onMain { callouts.getValue(k).lines.filter { it.isShown }.map { it.text.toString() } }
        for (k in HeroReadings.KEYS) assertTrue(k, onMain { callouts.getValue(k).isShown } && lines(k).all { it.isNotBlank() && it != "—" })
        assertTrue(lines("wind").toString(), lines("wind").single().contains("14 mph WSW"))
        assertEquals(listOf("Cloud 60%", "Rain 0.05 in/h"), lines("sky"))
        assertTrue(lines("sun").toString(), lines("sun")[0].contains("7:25") && lines("sun")[0].contains("4:30") && lines("sun")[1].endsWith("% lit"))
        assertEquals("52°F · Feels 45°", lines("temp")[0])
        assertEquals("Hum 81% · Dew 44°", lines("temp")[1])
        assertEquals("30.02 inHg", lines("temp")[2])
        assertTrue(lines("uv").toString(), lines("uv")[0].startsWith("Max UV ") && lines("uv")[1] == "Vis 15 mi")
        // A thin leader line from each callout to what it describes.
        waitFor("leader lines drawn") { onMain { a.leaders.drawn.keys.toSet() } == HeroReadings.KEYS.toSet() }
        // Drawn and aimed in the same instant: the clouds drift between frames.
        val (drawn, targets) = onMain {
            val v = a.leaders
            val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            bmp.recycle()
            v.drawn.toMap() to listOf("sun", "sky", "uv", "wind").associateWith { v.targetOf(it)!! }
        }
        val leaderBox = screenBox(onMain { a.leaders })
        for ((k, l) in drawn) {
            val box = screenBox(callouts.getValue(k))
            val sx = l[0] + leaderBox.left
            val sy = l[1] + leaderBox.top
            val onEdge = (abs(sx - box.left) < 2 || abs(sx - box.right) < 2 || abs(sy - box.top) < 2 || abs(sy - box.bottom) < 2) &&
                sx >= box.left - 2 && sx <= box.right + 2 && sy >= box.top - 2 && sy <= box.bottom + 2
            assertTrue("$k's line starts on its callout's edge", onEdge)
            assertFalse("$k's line ends outside its callout", box.contains((l[2] + leaderBox.left).toInt(), (l[3] + leaderBox.top).toInt()))
        }
        val number = screenBox(onMain { a.hero })
        assertTrue("the temperature's line points at the number", number.contains((drawn.getValue("temp")[2] + leaderBox.left).toInt(), (drawn.getValue("temp")[3] + leaderBox.top).toInt()))
        // Each points where it says: the sun or moon, a cloud, the horizon (the sun and moon's callout across the sky from them).
        val sceneBox = screenBox(onMain { a.scene })
        for (k in listOf("sun", "sky", "uv", "wind")) {
            val want = targets.getValue(k)
            val end = drawn.getValue(k)
            assertEquals("$k's line end", want[0] + sceneBox.left, end[2] + leaderBox.left, 40f)
            assertEquals(want[1] + sceneBox.top, end[3] + leaderBox.top, 40f)
        }
        onMain { a.scene.renderer.bodyPoint() }?.let { body ->
            val sun = screenBox(callouts.getValue("sun"))
            assertFalse("the sun or moon isn't behind its own callout", sun.contains((body[0] + sceneBox.left).toInt(), (body[1] + sceneBox.top).toInt()))
        }
        // The condition in words at the bottom, the short timeline under it: NOW, then the next hours.
        assertEquals(onMain { io.github.iamth3doctor.searchlight.weather.WeatherCodes.describe(80).uppercase() }, onMain { a.heroCondition.text.toString() })
        val tl = onMain { a.heroBox.timeline }
        val labels = onMain { tl.labels }
        assertEquals(5, labels.size)
        assertEquals("NOW", labels[0])
        val hf = io.github.iamth3doctor.searchlight.weatherapp.WeatherFormat.hour(app)
        assertEquals((1..4).map { hf.format(java.time.Instant.ofEpochMilli(h0 + it * hour).atOffset(java.time.ZoneOffset.ofTotalSeconds(off))) }, labels.drop(1))
        assertEquals((1..4).map { "${WeatherText.f(11.1 + it * 0.25)}°" }, onMain { tl.temps }.drop(1))
        val cond = screenBox(onMain { a.heroCondition })
        val line = screenBox(tl)
        for (k in HeroReadings.KEYS) assertTrue("$k above the condition", screenBox(callouts.getValue(k)).bottom <= cond.top)
        assertTrue("the number above the condition", number.bottom <= cond.top)
        assertTrue("the timeline under the condition", line.top >= cond.bottom - 1)
        assertTrue("over the 7 DAYS and the bar", line.bottom <= screenBox(onMain { a.weekHandle }).top)
        // The cloud top left, the place in the middle, mute top right: where they were.
        val top = listOf(onMain { a.menuButton }, onMain { a.placeButton }, onMain { a.muteButton }).map { screenBox(it) }
        assertTrue(top[0].right < top[1].left && top[1].right < top[2].left)
        assertEquals("the place centered", onMain { a.heroScreen.width } / 2f, top[1].exactCenterX(), 4f)
        assertTrue("°F only", texts(onMain { a.window.decorView }).none { it.text.contains("°C") })
        assertTrue("still alive", onMain { a.scene.animating })
        shot("122-hero-callouts")

        // Scrubbing: the number, condition, callouts and timeline all show that hour.
        on(h) { it.bar.scrubTo(h0 + 4 * hour) }
        assertEquals("${WeatherText.f(11.1 + 4 * 0.25)}", onMain { a.hero.number })
        assertEquals("RAIN", onMain { a.heroCondition.text.toString() })
        assertEquals("Cloud 100%", lines("sky")[0])
        assertEquals("Rain 0.16 in/h", lines("sky")[1])
        assertTrue("from that hour, not NOW", onMain { tl.labels[0] } != "NOW")
        waitFor("rain falls") { look(h).drops > 50 }
        shot("123-hero-scrubbed")
        on(h) { it.bar.goLive() }
        assertEquals("NOW", onMain { tl.labels[0] })

        // Tap the scene: the metric grid in the hero's place (the callouts and lines go with it).
        on(h) { it.scene.performClick() }
        assertTrue(onMain { a.quickShown && !a.heroBox.isShown && !a.leaders.isShown })
        on(h) { it.setQuick(false) }
        assertTrue(onMain { a.heroBox.isShown && a.leaders.isShown })

        // Offline: the last good data, the small glyph, nothing else.
        show(reading(80, 60, 1.2, windFrom = 250.0, wind = 14.0), offline = true)
        on(h) { it.render() }
        assertTrue(onMain { a.offlineGlyph.isShown })
        assertEquals("52°F · Feels 45°", lines("temp")[0])
        assertTrue("no warning text", texts(onMain { a.window.decorView }).none { it.isShown && it.text.contains("offline", ignoreCase = true) })
    }

    /**
     * The weather screen opened the way a person opens it: a tap on the home screen's Weather widget,
     * so the home (and its widgets) stay underneath. (ActivityScenario.launch starts a new task,
     * which clears the home's.) Closed after [block].
     */
    private fun fromHome(home: ActivityScenario<MainActivity>, block: () -> Unit) {
        on(home) { it.widgetController.exitEditMode(); it.widgetController.weatherViews().single().performClick() }
        waitFor("the weather screen open") {
            onMain { WeatherActivity.current?.let { !it.isFinishing && it.scene.width > 0 && it.scene.conditions != null } == true }
        }
        try {
            block()
        } finally {
            runCatching { onMain { WeatherActivity.current?.finish() } }
            runCatching { waitFor("home again") { onMain { WeatherActivity.current == null || WeatherActivity.current!!.isFinishing } } }
        }
    }

    private fun forceNow(w: WeatherData) {
        show(w)
        onMain { val a = WeatherActivity.current!!; a.render(); a.scene.jumpTo(a.conditions()) }
        val f = onMain { WeatherActivity.current!!.scene.renderer.frames }
        waitFor("scene playing") { onMain { WeatherActivity.current!!.scene.renderer.frames } > f + 20 }
    }

    private fun settleNow() {
        waitFor("screens settled") { onMain { !WeatherActivity.current!!.screens.moving } }
        idle()
    }

    /** How many pixels in [b] (sampled every [step]) aren't gray: r, g and b more than [tol] apart. */
    private fun colored(b: android.graphics.Bitmap, box: android.graphics.Rect? = null, step: Int = 3, tol: Int = 6): Int {
        val r = box ?: android.graphics.Rect(0, 0, b.width, b.height)
        var n = 0
        var y = r.top.coerceAtLeast(0)
        while (y < r.bottom.coerceAtMost(b.height)) {
            var x = r.left.coerceAtLeast(0)
            while (x < r.right.coerceAtMost(b.width)) {
                val c = b.getPixel(x, y)
                val cr = c shr 16 and 0xFF
                val cg = c shr 8 and 0xFF
                val cb = c and 0xFF
                if (maxOf(cr, cg, cb) - minOf(cr, cg, cb) > tol) n++
                x += step
            }
            y += step
        }
        return n
    }

    /** The screen without its status and navigation bars (the system's, not the app's). */
    private fun appArea(px: android.graphics.Bitmap) = android.graphics.Rect(0, px.height * 7 / 100, px.width, px.height * 93 / 100)

    private fun isGray(c: Int): Boolean {
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b) <= 12
    }

    @Test fun frostedChromeOverTheWeatherInItsNaturalColors() = home { home ->
        // A clear day: blue sky, yellow sun.
        show(reading(0, 5, 0.0))
        addWeather(home)
        addForecast(home, BuiltInWidget.WEATHER_3DAY)
        addForecast(home, BuiltInWidget.WEATHER_DAY)
        Thread.sleep(1500)
        // On the home screen: the live widget's scene in its natural colors; the cards' text in grays.
        val live = on(home) { it.widgetController.weatherViews().single() }
        screenPixels().let { px ->
            val b = screenBox(live).apply { inset(width() / 8, height() / 8) }
            assertTrue("the live widget's sky in color", colored(px, b) > 20)
            px.recycle()
        }
        assertTrue("its text gray", isGray(on(home) { live.tempView.currentTextColor }))
        for (f in on(home) { it.widgetController.forecastViews() }) {
            assertTrue("the cards' text gray", isGray(on(home) { f.tempView.currentTextColor }) && isGray(on(home) { f.conditionView.currentTextColor }))
        }
        shot("124-frost-widgets")
        // The weather screen: the scene real and in color, not cartoon; the chrome monochrome frosted glass.
        on(home) { it.widgetController.exitEditMode(); live.performClick() }
        waitFor("the weather screen open") { onMain { WeatherActivity.current?.let { !it.isFinishing && it.scene.width > 0 && it.scene.conditions != null } == true } }
        try {
            val a = onMain { WeatherActivity.current!! }
            assertEquals(WeatherSkin.FROST, onMain { a.skin })
            assertFalse("not the cartoon style", onMain { a.scene.renderer.cartoon })
            assertTrue("no skin button", onMain { a.window.decorView.findViewWithTag<View>("weather_skin_button") == null })
            Thread.sleep(1200)
            screenPixels().let { px -> assertTrue("the sky in color", colored(px, appArea(px)) > 200); px.recycle() }
            // The chrome: dark gray text on frosted pads.
            assertTrue(onMain { isGray(a.heroCondition.currentTextColor) && WeatherSkin.luminance(a.heroCondition.currentTextColor) < 0.05 })
            for (c in onMain { a.heroBox.callouts.values }) assertTrue(onMain { isGray(c.lines[0].currentTextColor) })
            assertTrue(onMain { isGray(a.placeButton.currentTextColor) })
            shot("125-frost-hero")
            // The data screen: frosted veil and panels, dark text.
            onMain { a.openDetails() }
            waitFor("data screen") { onMain { a.dataShown && !a.screens.moving } }
            Thread.sleep(800)
            assertTrue(onMain { isGray(a.panel.nowTemp!!.currentTextColor) && WeatherSkin.luminance(a.panel.nowTemp!!.currentTextColor) < 0.05 })
            shot("126-frost-data")
            onMain { a.back() }
            waitFor("back") { onMain { !a.dataShown && !a.screens.moving } }
            onMain { a.openWeek() }
            waitFor("7 days") { onMain { a.weekShown && !a.screens.moving } }
            Thread.sleep(800)
            shot("127-frost-week")
        } finally {
            runCatching { onMain { WeatherActivity.current?.finish() } }
            runCatching { waitFor("home again") { onMain { WeatherActivity.current == null || WeatherActivity.current!!.isFinishing } } }
        }
    }

    // ---- the three Weather widgets ----

    private fun addForecast(h: ActivityScenario<MainActivity>, type: BuiltInWidget): io.github.iamth3doctor.searchlight.widgets.WeatherForecastWidgetView {
        val before = on(h) { it.widgetController.forecastViews().toSet() }
        on(h) {
            it.widgetController.onActivityResult(WidgetController.REQUEST_PICK, Activity.RESULT_OK,
                Intent().putExtra(WidgetPickerActivity.EXTRA_BUILTIN, type.key))
        }
        waitFor("$type on the grid") { on(h) { it.widgetController.forecastViews().any { v -> v !in before && v.isAttachedToWindow && v.width > 0 } } }
        return on(h) { it.widgetController.forecastViews().single { v -> v !in before } }
    }

    private fun opensTheWeatherScreen(click: () -> Unit) {
        val monitor = instrumentation.addMonitor(WeatherActivity::class.java.name, null, false)
        try {
            click()
            val opened = monitor.waitForActivityWithTimeout(10_000) as WeatherActivity?
            assertNotNull("the weather screen opened", opened)
            main { opened!!.finish() }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun threeWeatherWidgetsInThePicker() {
        val picker = ActivityScenario.launchActivityForResult(WidgetPickerActivity::class.java)
        try {
            val want = listOf(R.string.widget_weather, R.string.widget_weather_3day, R.string.widget_weather_day).map { app.getString(it) }
            waitFor("all three Weather widgets offered") {
                var decor: View? = null
                picker.onActivity { decor = it.window.decorView }
                val shown = texts(decor!!).map { it.text.toString() }
                want.all { it in shown }
            }
            shot("118-weather-widget-picker")
        } finally {
            picker.close()
        }
    }

    @Test fun threeDayWidgetShowsTodayAndTheNextTwoRealDays() = home { h ->
        val w = reading(61, 100, 1.2)
        show(w)
        val v = addForecast(h, BuiltInWidget.WEATHER_3DAY)
        assertEquals(2 to 2, layout(h).single().let { it.colSpan to it.rowSpan })
        val days = w.detail!!.daily
        val zone = java.time.ZoneOffset.ofTotalSeconds(off)
        fun name(i: Int) = java.time.Instant.ofEpochMilli(days[i].date + 12 * hour).atOffset(zone).dayOfWeek
            .getDisplayName(java.time.format.TextStyle.SHORT, app.resources.configuration.locales[0]).uppercase(app.resources.configuration.locales[0])
        val labels = on(h) { v.dayLabels.map { it.text.toString() } }
        assertEquals(listOf(app.getString(R.string.weather_detail_today).uppercase(), name(1), name(2)), labels)
        assertEquals("never the same day twice", 3, labels.toSet().size)
        // Today's high and low take in the reading now, like the Weather tile's.
        val highs = listOf(maxOf(days[0].highC, w.temperatureC), days[1].highC, days[2].highC).map { "${WeatherText.f(it)}°" }
        val lows = listOf(minOf(days[0].lowC, w.temperatureC), days[1].lowC, days[2].lowC).map { "${WeatherText.f(it)}°" }
        assertEquals(highs, on(h) { v.dayHighs.map { it.text.toString() } })
        assertEquals(lows, on(h) { v.dayLows.map { it.text.toString() } })
        assertEquals((0..2).map { io.github.iamth3doctor.searchlight.weather.ConditionVisual.of(days[it].code, true) }, on(h) { v.dayVisuals.map { it.visual } })
        // Equal to the pixel (a width that doesn't divide by three leaves one a pixel over).
        val widths = on(h) { v.dayLabels.map { (it.parent as View).width } }
        assertTrue("three equal columns: $widths", widths.max() - widths.min() <= 1 && widths.min() > 0)
        for (i in 0..2) assertTrue("column $i shown", on(h) { v.dayLabels[i].isShown && v.dayHighs[i].isShown && v.dayLows[i].isShown && v.dayVisuals[i].isShown })
        assertTrue("°F, never °C", texts(v).none { it.text.contains("°C") })
        assertFalse(on(h) { v.offlineShown })
        shot("119-weather-widget-3day")

        // A new forecast: it follows.
        show(w.copy(detail = w.detail!!.copy(daily = days.mapIndexed { i, d -> if (i == 1) d.copy(highC = 20.0) else d })))
        waitFor("updates with the forecast") { on(h) { v.dayHighs[1].text.toString() } == "${WeatherText.f(20.0)}°" }

        // Offline: the last good days, with just the small glyph.
        show(w, offline = true)
        waitFor("offline glyph") { on(h) { v.offlineShown } }
        assertEquals(highs, on(h) { v.dayHighs.map { it.text.toString() } })
        assertTrue("no warning text", texts(v).none { it.text.contains("offline", ignoreCase = true) })
        show(w)

        opensTheWeatherScreen { on(h) { v.performClick() } }
    }

    @Test fun wideWidgetShowsNowAndTheDaysCurve() = home { h ->
        val w = reading(61, 100, 1.2)
        show(w)
        val v = addForecast(h, BuiltInWidget.WEATHER_DAY)
        assertEquals(4 to 2, layout(h).single().let { it.colSpan to it.rowSpan })
        assertEquals("52°F", on(h) { v.tempView.text.toString() })
        assertEquals("Tacoma", on(h) { v.placeView.text.toString() })
        assertEquals(w.condition.uppercase(), on(h) { v.conditionView.text.toString() })
        assertEquals(io.github.iamth3doctor.searchlight.weather.ConditionVisual.of(61, true), on(h) { v.visual.visual })
        // The bar: the whole width, under the rest.
        val bar = on(h) { v.bar }
        assertTrue("across the widget", on(h) { bar.width > v.width * 0.85 })
        assertTrue("under the temperature", rect(bar).top >= rect(on(h) { v.tempView }).bottom)
        val curve = on(h) { bar.curve }
        assertNotNull(curve)
        curve!!
        // The curve is the forecast's hours from now on (the reading now first).
        val now = curve.start
        val hours = w.detail!!.hourly.filter { it.time > now && it.time <= now + 12 * hour }
        assertEquals(listOf(w.temperatureC) + hours.map { it.tempC }, curve.points.map { it.second })
        assertEquals(listOf(now) + hours.map { it.time }, curve.points.map { it.first })
        val hi = curve.points.maxOf { it.second }
        val lo = curve.points.minOf { it.second }
        waitFor("drawn") { on(h) { bar.markLabels.isNotEmpty() } }
        assertEquals("H ${WeatherText.f(hi)}°", on(h) { bar.highLabel })
        assertEquals("L ${WeatherText.f(lo)}°", on(h) { bar.lowLabel })
        // NOW, then the hours on the hour, every 3.
        val marks = curve.marks
        assertEquals(now, marks.first())
        assertEquals(app.getString(R.string.weather_widget_now), on(h) { bar.markLabels.first() })
        assertTrue("$marks", marks.size >= 4)
        for (k in 2 until marks.size) assertEquals(3 * hour, marks[k] - marks[k - 1])
        val hf = io.github.iamth3doctor.searchlight.weatherapp.WeatherFormat.hour(app)
        assertEquals(marks.drop(1).map { hf.format(java.time.Instant.ofEpochMilli(it).atOffset(java.time.ZoneOffset.ofTotalSeconds(off))) },
            on(h) { bar.markLabels.drop(1) })
        // The high sits higher on the bar than the low.
        assertTrue(on(h) { bar.yOf(hi) < bar.yOf(lo) })
        assertTrue("°F, never °C", texts(v).none { it.text.contains("°C") })
        shot("120-weather-widget-wide")

        // Offline: the last good data, just the small glyph.
        show(w, offline = true)
        waitFor("offline glyph") { on(h) { v.offlineShown } }
        assertEquals("52°F", on(h) { v.tempView.text.toString() })
        assertTrue("small", on(h) { v.offlineGlyph.width <= (24 * app.resources.displayMetrics.density) })
        show(w)

        opensTheWeatherScreen { on(h) { v.performClick() } }
    }

    @Test fun widgetAlertGlyphOpensTheAlert() = home { h ->
        show(reading(1, 25, 0.0))
        val v = addWeather(h)
        assertFalse(on(h) { v.alertShown })
        show(storm(reading(1, 25, 0.0)))
        waitFor("alert glyph") { on(h) { v.alertShown } }
        assertEquals("still just the place and the temperature", listOf("Tacoma", "52°F"), texts(v).map { it.text.toString() })
        shot("98-widget-alert")
        val monitor = instrumentation.addMonitor(WeatherActivity::class.java.name, null, false)
        try {
            on(h) { v.alertGlyph.performClick() }
            val opened = monitor.waitForActivityWithTimeout(10_000) as WeatherActivity?
            assertNotNull("the weather screen opened", opened)
            waitFor("at the alert") { onMain { opened!!.alertShown } }
            assertTrue(onMain { opened!!.intent.getBooleanExtra(WeatherActivity.EXTRA_ALERT, false) })
            main { opened!!.finish() }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        // Offline: the last known alert stays, with the small offline glyph.
        show(storm(reading(1, 25, 0.0)), offline = true)
        waitFor("offline") { on(h) { v.offlineShown && v.alertShown } }
        // Cleared: the glyph goes.
        show(reading(1, 25, 0.0))
        waitFor("no alert glyph") { on(h) { !v.alertShown } }
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
