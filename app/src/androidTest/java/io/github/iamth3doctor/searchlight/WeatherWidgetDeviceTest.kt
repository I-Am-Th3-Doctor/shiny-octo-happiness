package io.github.iamth3doctor.searchlight

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import io.github.iamth3doctor.searchlight.cards.WeatherCard
import io.github.iamth3doctor.searchlight.weather.ConditionVisual
import io.github.iamth3doctor.searchlight.weather.DayPoint
import io.github.iamth3doctor.searchlight.weather.GeoPlace
import io.github.iamth3doctor.searchlight.weather.HourPoint
import io.github.iamth3doctor.searchlight.weather.TempUnit
import io.github.iamth3doctor.searchlight.weather.WeatherCodes
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherDetail
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import io.github.iamth3doctor.searchlight.widgets.BuiltInWidget
import io.github.iamth3doctor.searchlight.widgets.ConditionView
import io.github.iamth3doctor.searchlight.widgets.GridSpec
import io.github.iamth3doctor.searchlight.widgets.WeatherWidgetView
import io.github.iamth3doctor.searchlight.widgets.WidgetController
import io.github.iamth3doctor.searchlight.widgets.WidgetGridView
import io.github.iamth3doctor.searchlight.widgets.WidgetPickerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Weather widget: its visual and tint follow the conditions (from the same reading as the
 * Weather tile, so they agree); 2x1, 2x2 and 4x2 each show their layout whole; a tap opens the
 * detail view with every section filled, and Back or a tap outside closes it; offline (airplane
 * mode) it keeps the last good data and says so.
 */
@RunWith(AndroidJUnit4::class)
class WeatherWidgetDeviceTest {

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

    private fun shell(cmd: String): String = instrumentation.uiAutomation.executeShellCommand(cmd).use {
        String(java.io.FileInputStream(it.fileDescriptor).readBytes())
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

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(800)
        shell("screencap -p $dir/${prefix}_font1.0_$name.png")
    }

    @Before fun setUp() {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        repo.holdForTest = true
        main {
            savedFolded = app.prefs.widgetGrid
            savedUnfolded = app.prefs.widgetGridUnfolded
            savedTypes = app.prefs.builtInTypes
            savedWeather = app.prefs.lastWeather
            savedPlace = app.prefs.manualPlace
            savedUnit = app.prefs.tempUnit
            app.prefs.widgetGrid = emptyList()
            app.prefs.widgetGridUnfolded = emptyList()
            app.prefs.focusMode = false
            app.prefs.tempUnit = TempUnit.FAHRENHEIT
            repo.showForTest(reading(0, true), offline = false)
        }
    }

    @After fun restore() {
        runCatching { shell("cmd connectivity airplane-mode disable") }
        repo.holdForTest = false
        main {
            app.prefs.widgetGrid = savedFolded
            app.prefs.widgetGridUnfolded = savedUnfolded
            app.prefs.builtInTypes = savedTypes
            app.prefs.lastWeather = savedWeather
            app.prefs.manualPlace = savedPlace
            app.prefs.tempUnit = savedUnit
            repo.showForTest(savedWeather, offline = false)
        }
        shell("input keyevent KEYCODE_HOME")
    }

    // ---- the reading ----

    private val hour = 3_600_000L
    private val off = -8 * 3600

    /** Tacoma, 52°F now ([code]), with a full forecast: 24 hours, 7 days, every reading. */
    private fun reading(code: Int, isDay: Boolean, fetchedAt: Long = System.currentTimeMillis()): WeatherData {
        val now = System.currentTimeMillis()
        val h0 = now / hour * hour
        val hours = (0 until 25).map { i -> HourPoint(h0 + i * hour, 11.1 - i * 0.3, listOf(code, code, 3, 61, 0, 2)[i % 6], i < 6) }
        val dayStart = io.github.iamth3doctor.searchlight.weather.Moon.dayStart(now, off)
        val days = (0 until 7).map { i ->
            DayPoint(dayStart + i * 24 * hour, 11.1 + i % 3, 3.9 + i % 2, listOf(code, 61, 3, 0, 2, 95, 71)[i], listOf(10, 80, 40, 0, 20, 60, 30)[i],
                dayStart + i * 24 * hour + 7 * hour + 25 * 60_000L, dayStart + i * 24 * hour + 16 * hour + 30 * 60_000L)
        }
        val detail = WeatherDetail(off, 7.2, 81, 6.4, 1016.6, 24140.0, 4.2, 44.0, 1.4, hours, days, 42)
        return WeatherData("Tacoma", 47.25, -122.44, 11.1, code, isDay, 11.1, 3.9, fetchedAt, detail)
    }

    private fun show(code: Int, isDay: Boolean = true, offline: Boolean = false) = main { repo.showForTest(reading(code, isDay), offline) }

    // ---- home and the widget ----

    private fun home(block: (ActivityScenario<MainActivity>) -> Unit) {
        val s = ActivityScenario.launch(MainActivity::class.java)
        try {
            Thread.sleep(1500)
            block(s)
        } finally {
            runCatching { s.onActivity { it.widgetController.weatherDetail?.dismiss() } }
            runCatching { s.close() }
        }
    }

    /** What the picker hands back for "Weather", delivered the way the picker delivers it. */
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

    /** The Weather tile's line, brought on screen first when the strip pages (folded). */
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

    // ---- 1. Conditions morph; widget and tile agree ----

    @Test fun visualAndTintFollowTheConditions() = home { h ->
        val before = repo.refreshRequests
        val v = addWeather(h)
        assertTrue("adding it refreshes", repo.refreshRequests > before)
        assertEquals("2 × 2", 2 to 2, layout(h).single().let { it.colSpan to it.rowSpan })
        assertTrue(BuiltInWidget.isBuiltIn(layout(h).single().id))
        assertEquals(WeatherWidgetView.Size.STANDARD, on(h) { v.size })

        val tints = HashMap<String, Int>()
        for ((name, code, want) in listOf(
            Triple("clear", 0, ConditionVisual.CLEAR_DAY),
            Triple("cloudy", 3, ConditionVisual.CLOUDY),
            Triple("rain", 63, ConditionVisual.RAIN),
        )) {
            show(code)
            waitFor("$name shown") { on(h) { v.visual == want } }
            assertEquals(want, on(h) { v.conditionView.visual })
            val tint = on(h) { v.tint }
            assertEquals(ConditionView.tintOf(app, want), tint)
            tints[name] = tint
            // °F, and the same reading as the Weather tile: temperature and condition.
            assertEquals("52°F", on(h) { v.tempView.text.toString() })
            assertEquals("Feels 45°", on(h) { v.feelsView!!.text.toString() })
            assertEquals("H 52° · L 39°", on(h) { v.hiLoView.text.toString() })
            assertEquals("Tacoma", on(h) { v.placeView!!.text.toString() })
            assertTrue(on(h) { v.updatedView!!.text.toString().startsWith("Updated ") })
            val tile = tileLine(h)
            assertTrue("tile: $tile", tile.contains("52°F"))
            // The full line (folded) also names the condition; side by side (unfolded) it's the temperature alone.
            if (tile.contains("·")) assertTrue("tile: $tile", tile.contains(WeatherCodes.describe(code)))
            shot("71-weather-$name")
        }
        assertEquals("three different tints: $tints", 3, tints.values.toSet().size)
        assertNotEquals(tints["clear"], tints["rain"])

        // Night: the moon, and its cool dark tint.
        show(0, isDay = false)
        waitFor("clear night") { on(h) { v.visual == ConditionVisual.CLEAR_NIGHT } }
        assertNotEquals(tints["clear"], on(h) { v.tint })
        for ((code, want) in listOf(2 to ConditionVisual.PARTLY_DAY, 95 to ConditionVisual.THUNDER, 73 to ConditionVisual.SNOW, 45 to ConditionVisual.FOG)) {
            show(code)
            waitFor("$want") { on(h) { v.visual == want } }
        }
    }

    // ---- 2. Sizes ----

    @Test fun eachSizeShowsWhole() = home { h ->
        show(0)
        val v = addWeather(h)
        val spec = layout(h).single()
        val grid = on(h) { it.findViewById<WidgetGridView>(R.id.widgetGrid) }
        val (minW, minH) = on(h) { it.widgetController.minSpan(spec.id) }
        assertTrue("2x1 allowed: min $minW × $minH", minW <= 2 && minH <= 1)
        val gap = 2 * app.resources.getDimension(R.dimen.space_s)
        val density = app.resources.displayMetrics.density
        val minText = app.resources.getDimension(R.dimen.text_caption) * 0.6f
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
                    kotlin.math.abs(v.width - wantW) <= 2 && kotlin.math.abs(v.height - wantH) <= 2
                }
            }
            Thread.sleep(400)
            assertEquals(mode, on(h) { v.size })
            assertTrue("$size: everything inside, nothing cut (scale ${on(h) { v.fitScale }})", on(h) { v.contentFits() })
            assertTrue("$size: still readable (scale ${on(h) { v.fitScale }})", on(h) { v.fitScale } >= 0.6f)
            for (t in texts(v)) assertTrue("${t.text} at ${t.textSize}px", t.textSize * on(h) { v.fitScale } >= minText)
            val shown = texts(v).map { it.text.toString() }
            assertTrue("$size temp: $shown", "52°F" in shown)
            assertTrue("$size high/low: $shown", "H 52° · L 39°" in shown)
            when (mode) {
                WeatherWidgetView.Size.COMPACT -> assertFalse("2x1 is just visual, temp, high/low: $shown", shown.any { it.startsWith("Feels") || it == "Tacoma" })
                WeatherWidgetView.Size.STANDARD -> assertTrue(shown.containsAll(listOf("Feels 45°", "Tacoma")))
                WeatherWidgetView.Size.WIDE -> {
                    assertTrue(shown.containsAll(listOf("Feels 45°", "Tacoma")))
                    val cells = on(h) { v.hourCells.map { (t, cv, temp) -> Triple(t.text.toString(), cv.isShown, temp.text.toString()) } }
                    assertEquals("six hours", 6, cells.size)
                    assertTrue("every hour filled: $cells", cells.all { it.first.isNotBlank() && it.second && it.third.endsWith("°") })
                }
            }
            shot(name)
        }
    }

    // ---- 3. Detail view ----

    @Test fun tapOpensTheDetailViewAndItCloses() = home { h ->
        show(0)
        val v = addWeather(h)
        val before = repo.refreshRequests
        h.onActivity { v.performClick() }
        waitFor("detail open") { on(h) { it.widgetController.weatherDetail?.dialog?.isShowing == true } }
        assertTrue("opening it refreshes", repo.refreshRequests > before)
        val sheet = on(h) { it.widgetController.weatherDetail!! }
        val want = listOf("now", "summary", "hourly", "daily", "sun", "moon", "humidity", "visibility", "pressure", "uv", "aqi", "wind")
        assertEquals(want, on(h) { sheet.sections.keys.toList() })
        for (k in want) {
            val shown = texts(on(h) { sheet.sections[k]!! }).map { it.text.toString() }.filter { it.isNotBlank() }
            assertTrue("$k filled: $shown", shown.isNotEmpty() && shown.none { it == "—" || it.contains("— ") })
        }
        fun section(k: String) = texts(on(h) { sheet.sections[k]!! }).joinToString(" | ") { it.text.toString() }
        assertTrue(section("now"), section("now").contains("52°F") && section("now").contains("Feels 45°") && section("now").contains("H 52° · L 39°"))
        assertTrue(section("summary"), section("summary").contains("highs around 52°"))
        assertTrue(section("visibility"), section("visibility").contains("15 mi") && section("visibility").contains("Good"))
        assertTrue(section("pressure"), section("pressure").contains("30.02 inHg"))
        assertTrue(section("uv"), section("uv").contains("Low"))
        assertTrue(section("aqi"), section("aqi").contains("42") && section("aqi").contains("Good"))
        assertTrue(section("wind"), section("wind").contains("4 mph from NE"))
        assertTrue(section("humidity"), section("humidity").contains("81%") && section("humidity").contains("Dew point 44°"))
        assertTrue(section("moon"), section("moon").contains("moon") || section("moon").contains("quarter") || section("moon").contains("crescent") || section("moon").contains("gibbous"))
        assertTrue(section("sun"), section("sun").contains("7:25") && (section("sun").contains("4:30") || section("sun").contains("16:30")))
        val hourCells = on(h) { (sheet.sections["hourly"] as ViewGroup).let { box -> countTagged(box, "weather_hour") } }
        assertTrue("12-24 hours: $hourCells", hourCells in 12..24)
        assertEquals("7 days", 7, on(h) { countTagged(sheet.sections["daily"] as ViewGroup, "weather_day") })
        assertTrue("°F everywhere", texts(on(h) { sheet.panel }).none { it.text.contains("°C") })
        shot("77-weather-detail")
        h.onActivity { _ ->
            fun scroller(v: View): android.widget.ScrollView? =
                v as? android.widget.ScrollView ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { scroller(g.getChildAt(it)) } }
            scroller(sheet.panel)?.fullScroll(View.FOCUS_DOWN)
        }
        shot("78-weather-detail-more")

        // Back closes it: home again.
        shell("input keyevent KEYCODE_BACK")
        waitFor("closed by Back") { on(h) { it.widgetController.weatherDetail == null } }
        assertTrue("home still there", on(h) { it.hasWindowFocus() || it.window.decorView.isShown })

        // A tap outside the panel closes it too.
        h.onActivity { v.performClick() }
        waitFor("open again") { on(h) { it.widgetController.weatherDetail?.dialog?.isShowing == true } }
        Thread.sleep(500)
        val (x, y) = on(h) {
            val r = android.graphics.Rect()
            it.widgetController.weatherDetail!!.panel.getGlobalVisibleRect(r)
            val dm = it.resources.displayMetrics
            // Below the panel when there's room, else beside it.
            if (dm.heightPixels - r.bottom > 80) r.centerX() to (r.bottom + (dm.heightPixels - r.bottom) / 2) else (r.left / 2).coerceAtLeast(4) to r.centerY()
        }
        shell("input tap $x $y")
        waitFor("closed by a tap outside") { on(h) { it.widgetController.weatherDetail == null } }
    }

    private fun countTagged(v: View, tag: String): Int =
        (if (v.tag == tag) 1 else 0) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).sumOf { countTagged(g.getChildAt(it), tag) } } ?: 0)

    // ---- 4. Offline ----

    @Test fun offlineKeepsTheLastGoodDataAndSaysSo() = home { h ->
        main {
            app.prefs.manualPlace = GeoPlace("Tacoma", "Washington", "US", 47.25, -122.44)
            repo.showForTest(reading(3, true), offline = false)
        }
        val v = addWeather(h)
        waitFor("cloudy shown") { on(h) { v.visual == ConditionVisual.CLOUDY } }
        assertFalse(on(h) { v.offlineShown })

        // Airplane mode, then a real refresh: it fails, and the last reading stays, marked offline.
        shell("cmd connectivity airplane-mode enable")
        Thread.sleep(3000)
        val airplane = shell("settings get global airplane_mode_on").trim() == "1"
        if (airplane) {
            repo.holdForTest = false
            main { repo.refresh() }
            waitFor("refresh failed offline", 40_000) { on(h) { !repo.state.loading && repo.state.problem != null } }
        } else {
            // No airplane mode on this image: the same state a failed refresh leaves.
            main { repo.showForTest(repo.state.data, offline = true) }
        }
        assertEquals(WeatherRepository.Problem.NETWORK, on(h) { repo.state.problem })
        waitFor("offline note") { on(h) { v.offlineShown } }
        assertEquals("offline — showing last update", on(h) { v.updatedView!!.text.toString() })
        assertEquals("the last good data", "52°F", on(h) { v.tempView.text.toString() })
        assertEquals(ConditionVisual.CLOUDY, on(h) { v.visual })
        shot("79-weather-offline")

        h.onActivity { v.performClick() }
        waitFor("detail open") { on(h) { it.widgetController.weatherDetail?.dialog?.isShowing == true } }
        waitFor("detail settled") { on(h) { !repo.state.loading } }
        val now = texts(on(h) { it.widgetController.weatherDetail!!.sections["now"]!! }).map { it.text.toString() }
        assertTrue("detail says offline: $now", "offline — showing last update" in now)
        assertTrue(now.toString(), "52°F" in now)
        repo.holdForTest = true
        shell("cmd connectivity airplane-mode disable")
    }
}
