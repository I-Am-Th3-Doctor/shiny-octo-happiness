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
import io.github.iamth3doctor.searchlight.weather.DayPoint
import io.github.iamth3doctor.searchlight.weather.GeoPlace
import io.github.iamth3doctor.searchlight.weather.HourPoint
import io.github.iamth3doctor.searchlight.weather.Moon
import io.github.iamth3doctor.searchlight.weather.TempUnit
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherDetail
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import io.github.iamth3doctor.searchlight.weather.WeatherText
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

    private fun <A : Activity, T> on(h: ActivityScenario<A>, read: (A) -> T): T {
        var v: T? = null
        h.onActivity { v = read(it) }
        @Suppress("UNCHECKED_CAST") return v as T
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

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
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
                windMph = wind, windFromDeg = windFrom)
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
        h.onActivity { it.render(); it.scene.jumpTo(it.conditions()) }
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
        Thread.sleep(600)
        assertTrue("frames advance: it's alive", on(h) { it.scene.renderer.frames } > f0 + 10)

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
        assertEquals("the arrow points the way it blows (east)", 90.0, on(h) { it.windArrow.screenAngle }, 1.0)
        val c = on(h) { it.scene.conditions!! }
        assertTrue(c.rain > 0 && c.windEastMph > 20)

        // Wind from the north-east: the arrow points down and to the left.
        force(h, reading(0, 2, 0.0, windFrom = 45.0, wind = 12.0))
        assertEquals(-135.0, on(h) { it.windArrow.screenAngle }, 1.0)
    }

    @Test fun scrubbingPlaysTheDay() = screen { h ->
        force(h, reading(0, 2, 0.0))
        assertNull(on(h) { it.scrubTime })
        assertEquals(0, look(h).drops)
        // Tap the bar four hours on: it lands on that hour, and the rain in the forecast plays.
        val t = h0 + 4 * hour
        val x = on(h) { it.bar.xOf(t + 10 * 60_000L) }
        h.onActivity { it.bar.tap(x) }
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
        assertEquals("Rain", on(h) { it.heroCondition.text.toString() })
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
        h.onActivity { it.bar.tap(it.bar.xOf(System.currentTimeMillis())) }
        assertNull(on(h) { it.scrubTime })
        assertTrue(on(h) { it.conditions().live })
        assertEquals(11.1, on(h) { it.conditions().tempC }, 1e-9)
        shot("86-scene-day-bar")
    }

    /** Waits until no screen change is under way. */
    private fun settle(h: ActivityScenario<WeatherActivity>) {
        waitFor("screens settled") { on(h) { !it.screens.moving } }
        instrumentation.waitForIdleSync()
    }

    private fun rect(v: View): android.graphics.Rect = onMain {
        val r = android.graphics.Rect()
        if (v.isShown) v.getGlobalVisibleRect(r)
        r
    }

    @Test fun tapTheSceneForTheQuickNumbersTapAgainForTheScene() = screen { h ->
        force(h, reading(2, 40, 0.0))
        assertFalse(on(h) { it.quickShown })
        h.onActivity { it.scene.performClick() }
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
        h.onActivity { it.bar.scrubToHour(DayBar.BEFORE_HOURS + 4) }
        assertEquals("80%", v("precip"))
        assertEquals("100%", v("cloud"))
        h.onActivity { it.bar.goLive() }

        // Tap again: just the scene, the number back.
        h.onActivity { quick.root.performClick() }
        assertFalse(on(h) { it.quickShown })
        assertTrue(on(h) { it.hero.isShown && !it.scrim.isShown })
        // Back puts them away first, then closes the screen.
        h.onActivity { it.scene.performClick() }
        h.onActivity { it.back() }
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
        h.onActivity { it.scene.performClick() }
        val overlaps = watchForOverlap(h, 900) { h.onActivity { it.quick.precipCell.performClick() } }
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
        assertTrue(now, now.contains("52°F") && now.contains("Feels 45°") && now.contains("H 52° · L 39°") && now.contains("Updated "))
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
        assertEquals(emptyList<String>(), watchForOverlap(h, 900) { h.onActivity { it.back() } })
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

        // Hold a day: its scene plays; let go: it stops.
        assertFalse(on(h) { week.preview.animating })
        val d = SystemClock.uptimeMillis()
        val row = rows[5]
        main { row.dispatchTouchEvent(MotionEvent.obtain(d, d, MotionEvent.ACTION_DOWN, 40f, 20f, 0)) }
        waitFor("holding plays the day") { on(h) { week.previewDay == 5 && week.preview.animating } }
        val c = on(h) { week.preview.conditions!! }
        assertTrue("that day's weather: thunder ($c)", c.code == 95 && c.thunder)
        val t1 = on(h) { week.previewTime }
        val frames = on(h) { week.preview.renderer.frames }
        Thread.sleep(1500)
        assertNotEquals("the day plays on", t1, on(h) { week.previewTime })
        assertTrue(on(h) { week.preview.renderer.frames } > frames + 10)
        assertTrue(on(h) { week.caption.text.toString() }, on(h) { week.caption.text.toString() }.contains("Thunderstorm"))
        shot("100-week-hold")
        main { row.dispatchTouchEvent(MotionEvent.obtain(d, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 40f, 20f, 0)) }
        assertNull(on(h) { week.previewDay })
        assertFalse("let go: it stops", on(h) { week.preview.animating })

        // The details from the 7 days, then back twice: never two at once.
        val seen = watchForOverlap(h, 2200) {
            h.onActivity { it.weekScreen.findViewWithTag<View>("weather_week_details").performClick() }
            Thread.sleep(700)
            assertTrue(on(h) { it.dataShown })
            h.onActivity { it.back() }
            Thread.sleep(700)
            assertTrue(on(h) { it.weekShown })
        }
        assertEquals(emptyList<String>(), seen)
        settle(h)
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
        assertEquals("Rain", on(h) { it.heroCondition.text.toString() })
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
        h.onActivity { it.bar.tap(it.bar.xOf(System.currentTimeMillis())) }
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
        h.onActivity { it.scene.updateRunning() }
        assertFalse(on(h) { it.scene.animating })
        WeatherSceneView.powerSaveOverride = false
        h.onActivity { it.scene.updateRunning() }
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
            h.onActivity { it.placeButton.performClick() }
            settle(h)
            assertTrue(on(h) { it.placesShown })
            assertTrue("solid", on(h) { opaque(it.placesScreen) })
            val places = on(h) { it.places }
            assertEquals("home first", listOf("Tacoma (home)"), texts(on(h) { places.saved }).map { it.text.toString() })
            h.onActivity { places.search.setText("Seattle"); places.find("Seattle") }
            waitFor("results") { on(h) { places.results.childCount } == 2 }
            shot("103-places-search")
            h.onActivity { places.results.getChildAt(0).performClick() }
            settle(h)
            assertFalse(on(h) { it.placesShown })
            assertEquals("Seattle", onMain { repo.city?.name })
            assertEquals(listOf("Seattle"), onMain { repo.savedCities.map { it.name } })
            show(seattleReading())
            h.onActivity { it.render() }
            assertEquals("60", on(h) { it.hero.number })
            assertTrue(on(h) { it.placeButton.text.toString() }.startsWith("Seattle"))
            shot("104-seattle")

            // Switch back to Tacoma: its last reading at once.
            h.onActivity { it.placeButton.performClick() }
            settle(h)
            val saved = texts(on(h) { places.saved }).map { it.text.toString() }
            assertTrue(saved.toString(), saved.contains("Tacoma (home)") && saved.contains("Seattle"))
            h.onActivity { places.saved.findViewWithTag<View>("weather_place_home").performClick() }
            settle(h)
            assertNull(onMain { repo.city })
            assertEquals("Tacoma", onMain { repo.state.data?.locationName })
            assertEquals("52", on(h) { it.hero.number })
            // And to Seattle again, from the saved list: its last reading, offline or not.
            h.onActivity { it.placeButton.performClick() }
            settle(h)
            h.onActivity { places.saved.findViewWithTag<View>("weather_place_saved").performClick() }
            settle(h)
            assertEquals("Seattle", onMain { repo.state.data?.locationName })
            assertEquals("60", on(h) { it.hero.number })
        }
        // The widget and the Weather tile show the same city, in °F.
        home { h ->
            val v = addWeather(h)
            assertEquals(listOf("60°F", "H 62° · L 50°"), texts(v).map { it.text.toString() })
            val tile = tileLine(h)
            assertTrue("tile: $tile", tile.contains("60°F"))
        }
        main { repo.selectCity(null) }
        assertEquals("Tacoma", onMain { repo.state.data?.locationName })
    }

    @Test fun silencedPhoneStaysQuiet() = screen { h ->
        assertTrue(on(h) { it.soundOn() })
        WeatherActivity.quietOverride = true
        h.onActivity { it.updateSoundButton() }
        assertFalse("silent / Do Not Disturb wins", on(h) { it.soundOn() })
        force(h, reading(95, 100, 7.0))
        val felt = on(h) { it.strikesFelt }
        waitFor("lightning", 12_000) { look(h).strikes > 0 }
        Thread.sleep(300)
        assertEquals("no thunder or haptic while silenced", felt, on(h) { it.strikesFelt })
        WeatherActivity.quietOverride = false
        // The toggle: off, then on again.
        h.onActivity { it.muteButton.performClick() }
        assertFalse(on(h) { it.soundOn() })
        h.onActivity { it.muteButton.performClick() }
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
        h.onActivity {
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
        h.onActivity { a ->
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

        // Just the temperature and high/low: no table, no sentences.
        val shown = texts(v).map { it.text.toString() }
        assertEquals(listOf("52°F", "H 52° · L 39°"), shown)
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
        h.onActivity { v.scene.updateRunning() }
        waitFor("battery saver stops it") { on(h) { !v.scene.animating } }
        val f2 = frames(h, v)
        Thread.sleep(1000)
        assertEquals("still in battery saver", f2, frames(h, v))
        shell("settings put global low_power 0")
        shell("dumpsys battery reset")
        WeatherSceneView.powerSaveOverride = false
        h.onActivity { v.scene.updateRunning() }
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
            h.onActivity { it.widgetController.onCommit(spec.copy(colSpan = size.first, rowSpan = size.second)); it.widgetController.render() }
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
            assertEquals(listOf("52°F", "H 52° · L 39°"), texts(v).map { it.text.toString() })
            assertTrue("$size animating", on(h) { v.scene.animating })
            shot(name)
        }

        // Tapping the widget, or the Weather tile, opens the weather screen.
        val monitor = instrumentation.addMonitor(WeatherActivity::class.java.name, Instrumentation.ActivityResult(Activity.RESULT_OK, null), true)
        try {
            h.onActivity { v.performClick() }
            waitFor("widget opens the weather screen") { monitor.hits >= 1 }
            h.onActivity { a ->
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
            h.onActivity { a ->
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
            assertEquals("the last good data", listOf("52°F", "H 52° · L 39°"), texts(v).map { it.text.toString() })
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
            s.onActivity { it.openDetails() }
            settle(s)
            val now = texts(on(s) { it.panel.sections["now"]!! }).map { it.text.toString() }
            assertTrue(now.toString(), "52°F" in now && now.any { it.startsWith("Updated ") })
            // The last good precip: this hour's rows and the rain coming.
            val card = on(s) { it.panel.precip }
            assertEquals("0.00 IN", on(s) { card.rateValue.text.toString() })
            assertTrue(on(s) { card.expected.text.toString() }.startsWith("Rain expected around "))
            s.onActivity { it.bar.scrubTo(h0 + 3 * hour + 30 * 60_000L) }
            assertEquals("0.16 IN", on(s) { card.rateValue.text.toString() })
            assertEquals("80%", on(s) { card.chanceValue.text.toString() })
        }
        shell("cmd connectivity airplane-mode disable")
    }

    // ---- phase 2: the hero, the precip card, severe alerts ----

    @Test fun heroNumbersStandOverTheSceneInBothThemes() {
        for ((night, name) in listOf("no" to "91-hero-light", "yes" to "92-hero-dark")) {
            shell("cmd uimode night $night")
            Thread.sleep(1500)
            screen { h ->
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
                assertEquals("Mainly clear", on(h) { it.heroCondition.text.toString() })
                assertEquals("H 52° · L 39°", on(h) { it.heroHiLo.text.toString() })
                assertTrue("the scene still plays behind it", on(h) { it.scene.isShown && it.scene.animating })
                // Scrubbing: the hero shows that hour.
                h.onActivity { it.bar.scrubTo(h0 + 4 * hour) }
                assertEquals("${WeatherText.f(11.1 + 4 * 0.25)}", on(h) { it.hero.number })
                h.onActivity { it.bar.goLive() }
                shot(name)
            }
        }
        shell("cmd uimode night $savedNight")
    }

    @Test fun precipCardRowsGraphAndBarMoveTogether() = screen { h ->
        force(h, reading(0, 2, 0.0))
        h.onActivity { it.openDetails() }
        settle(h)
        val card = on(h) { it.panel.precip }
        fun rows() = on(h) { listOf(card.rateValue.text.toString(), card.chanceValue.text.toString(), card.typeValue.text.toString()) }
        assertEquals(listOf("0.00 IN", "5%", "NONE"), rows())
        assertEquals("RATE", on(h) { card.rateLabel.text.toString() })
        assertTrue(on(h) { card.expected.text.toString() }, on(h) { card.expected.text.toString() }.startsWith("Rain expected around "))
        assertEquals(0, on(h) { card.island.particles })
        assertEquals(PrecipGraphView.Mode.DAY, on(h) { card.graph.mode })
        h.onActivity { it.panel.sections["precip"]!!.let { v -> (v.parent.parent as? android.widget.ScrollView)?.smoothScrollTo(0, v.top) } }
        shot("93-precip-now")

        // Scrub the graph into the rain: the main bar, the scene and the rows all follow.
        val t = h0 + 3 * hour + 30 * 60_000L
        h.onActivity { card.graph.touchAt(card.graph.xOf(t), tap = false) }
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
        h.onActivity { it.bar.scrubTo(h0 + 10 * hour) }
        assertEquals(on(h) { it.scrubTime }, on(h) { card.graph.scrubTime })
        assertEquals(listOf("0.00 IN", "5%", "NONE"), rows())

        // WEEK: the 7 days; pick one.
        h.onActivity { card.weekChip.performClick() }
        assertEquals(PrecipGraphView.Mode.WEEK, on(h) { card.graph.mode })
        assertEquals(7, on(h) { card.graph.pointCount })
        assertEquals("TOTAL", on(h) { card.rateLabel.text.toString() })
        h.onActivity { card.graph.touchAt(card.graph.xOfDay(1), tap = true) }
        assertEquals(1, on(h) { card.graph.weekIndex })
        assertEquals("80%", rows()[1])
        assertEquals("RAIN", rows()[2])
        shot("95-precip-week")
        // Back to DAY: in step with the bar again.
        h.onActivity { card.dayChip.performClick() }
        assertEquals(PrecipGraphView.Mode.DAY, on(h) { card.graph.mode })
        assertEquals(on(h) { it.scrubTime }, on(h) { card.graph.scrubTime })
        assertEquals("RATE", on(h) { card.rateLabel.text.toString() })
        // Now, from the graph: live everywhere.
        h.onActivity { card.graph.touchAt(card.graph.xOf(System.currentTimeMillis()), tap = true) }
        assertNull(on(h) { it.scrubTime })
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
        val c = on(h) { it.scene.conditions!! }
        assertEquals(io.github.iamth3doctor.searchlight.weather.AlertKind.THUNDER, c.severe)
        assertTrue("storm: dark clouds, heavy rain", c.thunder && c.cloudCover == 1.0 && c.rain >= 0.85)
        waitFor("rain streaks") { look(h).drops > 200 }
        waitFor("lightning", 12_000) { look(h).strikes > 0 }
        shot("96-alert-scene")

        // The pill never sits on the number.
        assertFalse(android.graphics.Rect.intersects(rect(on(h) { it.alertPill }), rect(on(h) { it.hero })))
        h.onActivity { it.alertPill.performClick() }
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
        h.onActivity { it.back() }
        assertFalse(on(h) { it.alertShown })
        settle(h)

        // No alert: nothing about alerts anywhere.
        force(h, reading(1, 25, 0.0))
        assertFalse(on(h) { it.alertPill.isShown })
        assertFalse(on(h) { it.alertShown })
        assertNull(on(h) { it.scene.conditions!!.severe })
        assertTrue(texts(on(h) { it.window.decorView }).none { t -> t.text.contains("!") || t.text.contains("alert", ignoreCase = true) })
    }

    @Test fun widgetAlertGlyphOpensTheAlert() = home { h ->
        show(reading(1, 25, 0.0))
        val v = addWeather(h)
        assertFalse(on(h) { v.alertShown })
        show(storm(reading(1, 25, 0.0)))
        waitFor("alert glyph") { on(h) { v.alertShown } }
        assertEquals("still just the temperature and high/low", listOf("52°F", "H 52° · L 39°"), texts(v).map { it.text.toString() })
        shot("98-widget-alert")
        val monitor = instrumentation.addMonitor(WeatherActivity::class.java.name, null, false)
        try {
            h.onActivity { v.alertGlyph.performClick() }
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
                val dog = Thread {
                    try {
                        Thread.sleep(seconds * 1000)
                        val mainThread = android.os.Looper.getMainLooper().thread
                        dump = "main thread:\n" + mainThread.stackTrace.joinToString("\n") { "  at $it" } +
                            "\ntest thread:\n" + test.stackTrace.joinToString("\n") { "  at $it" }
                        android.util.Log.e("Watchdog", "${description.methodName} stuck\n$dump")
                        test.interrupt()
                    } catch (_: InterruptedException) {
                    }
                }
                dog.isDaemon = true
                dog.start()
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    dump?.let { throw AssertionError("timed out after ${seconds}s\n$it", t) }
                    throw t
                } finally {
                    dog.interrupt()
                    Thread.interrupted()
                }
                dump?.let { throw AssertionError("timed out after ${seconds}s\n$it") }
            }
        }
}
