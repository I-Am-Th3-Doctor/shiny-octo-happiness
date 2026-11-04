package io.github.iamth3doctor.searchlight

import android.app.NotificationManager
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.cards.CardType
import io.github.iamth3doctor.searchlight.cards.QuickAction
import io.github.iamth3doctor.searchlight.weather.TempUnit
import io.github.iamth3doctor.searchlight.ui.Posture
import io.github.iamth3doctor.searchlight.widgets.WidgetGridView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unfolded, home is one at-a-glance screen: nothing on it scrolls or slides up, and the bottom
 * bar shows the temperature (°F), the time, the battery %, and the four toggles, each whole.
 * Folded, swipe up still opens the app drawer. (The suite runs folded, then unfolded.)
 */
@RunWith(AndroidJUnit4::class)
class GlanceBarDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private var savedUnit: TempUnit? = null
    private var savedCards: List<CardType> = emptyList()
    private var savedDisabled: Set<CardType> = emptySet()
    private var savedSlots: List<QuickAction> = emptyList()

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> on(home: ActivityScenario<MainActivity>, read: (MainActivity) -> T): T {
        var v: T? = null
        home.onActivity { v = read(it) }
        @Suppress("UNCHECKED_CAST") return v as T
    }

    private fun waitFor(what: String, timeoutMs: Long = 8_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out: $what")
    }

    private val unfolded get() = Posture.of(app.resources.configuration) == Posture.UNFOLDED

    @Before fun setUp() {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        main {
            savedUnit = app.prefs.tempUnit
            savedCards = app.prefs.cardOrder
            savedDisabled = app.prefs.disabledCards
            savedSlots = app.prefs.quickActionSlots
            // Every tile in the bar, the stock toggles, °F.
            app.prefs.tempUnit = TempUnit.FAHRENHEIT
            app.prefs.disabledCards = emptySet()
            app.prefs.quickActionSlots = QuickAction.DEFAULTS
        }
    }

    @After fun restore() = main {
        savedUnit?.let { app.prefs.tempUnit = it }
        app.prefs.cardOrder = savedCards
        app.prefs.disabledCards = savedDisabled
        app.prefs.quickActionSlots = savedSlots
    }

    /** Every view under [v] that is shown, depth first. */
    private fun shown(v: View): List<View> =
        if (!v.isShown) emptyList() else listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { shown(g.getChildAt(it)) } } ?: emptyList())

    /** A text that shows whole: no ellipsis, one line, and as wide as it needs within its view. */
    private fun whole(t: TextView): Boolean {
        val l = t.layout ?: return false
        if (l.lineCount != 1) return false
        if (l.getEllipsisCount(0) > 0) return false
        val needs = android.text.Layout.getDesiredWidth(t.text, t.paint) // (spans included: the bold temperature)
        return needs <= t.width - t.totalPaddingLeft - t.totalPaddingRight + 1
    }

    private fun emptySpot(a: MainActivity): Pair<Int, Int> {
        val grid = a.findViewById<WidgetGridView>(R.id.widgetGrid)
        val loc = IntArray(2)
        grid.getLocationOnScreen(loc)
        val taken = (0 until grid.childCount).map { i ->
            val c = grid.getChildAt(i)
            Rect(loc[0] + c.left - 40, loc[1] + c.top - 40, loc[0] + c.right + 40, loc[1] + c.bottom + 40)
        }
        val xs = listOf(0.5f, 0.35f, 0.65f).map { (loc[0] + grid.width * it).toInt() }
        val ys = listOf(0.45f, 0.6f, 0.3f).map { (loc[1] + grid.height * it).toInt() }
        return xs.flatMap { x -> ys.map { y -> x to y } }.first { (x, y) -> taken.none { it.contains(x, y) } }
    }

    @Test fun unfoldedHomeIsOneScreenWithAWholeGlanceBar() {
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(2500)
            val spot = on(home) { emptySpot(it) }
            shell("input swipe ${spot.first} ${spot.second} ${spot.first} ${spot.second - 600} 250")
            Thread.sleep(900)
            val drawer = on(home) { it.findViewById<View>(R.id.appTable).isShown }

            if (!unfolded) {
                // Folded: as before, swipe up opens the drawer.
                assertTrue("folded: swipe up should open the drawer", drawer)
                @Suppress("DEPRECATION") home.onActivity { it.onBackPressed() }
                return@use
            }

            // Unfolded: nothing slid up, and nothing on home scrolls vertically.
            assertFalse("unfolded: swipe up opened the drawer", drawer)
            home.onActivity { a ->
                val scrollers = shown(a.findViewById(R.id.root)).filter { it.canScrollVertically(1) || it.canScrollVertically(-1) }
                assertTrue("vertically scrollable on home: $scrollers", scrollers.isEmpty())
                // Every home element inside the screen.
                val root = a.findViewById<View>(R.id.root)
                for (id in listOf(R.id.homeCard, R.id.rail, R.id.widgetGrid)) {
                    val v = a.findViewById<View>(id)
                    val r = Rect()
                    assertTrue("${a.resources.getResourceEntryName(id)} on screen", v.getGlobalVisibleRect(r) && r.height() == v.height && r.bottom <= root.bottom)
                }
            }
            shell("screencap -p $dir/${prefix}_font1.0_38-glance-bar.png")

            // The bar: temperature in °F, the time, battery %, the four toggles, each whole.
            home.onActivity { a ->
                val row = a.findViewById<ViewGroup>(R.id.tileRow)
                assertTrue("the bar shows unfolded", row.isShown)
                val texts = shown(row).filterIsInstance<TextView>()
                val bad = texts.filter { it.text.isNotEmpty() && it.width > 0 && !whole(it) && !isMusic(it) }
                assertTrue("cut off: ${bad.map { "'${it.text}' ${it.width}px" }}", bad.isEmpty())

                val all = texts.joinToString(" | ") { it.text.toString() }
                assertTrue("temperature in °F: $all", texts.any { Regex("""(-?\d+|--)°F""").matches(it.text) })
                assertTrue("time: $all", texts.any { Regex("""\d{1,2}:\d{2}""").matches(it.text) })
                assertTrue("battery %: $all", texts.any { Regex("""\d{1,3}%""").matches(it.text) })
                for (id in listOf(R.id.qaSlot0, R.id.qaSlot1, R.id.qaSlot2, R.id.qaSlot3)) {
                    val v = row.findViewById<View>(id)
                    val r = Rect()
                    assertTrue("toggle ${a.resources.getResourceEntryName(id)} whole", v.isShown && v.getGlobalVisibleRect(r) && r.width() == v.width)
                }
            }
        }
    }

    /** The music tile may give up its title width (it isn't in the glance set). */
    private fun isMusic(t: TextView) = t.id == R.id.musicTitle

    @Test fun doNotDisturbToggleSwitchesTheRealSetting() {
        shell("cmd notification allow_dnd $pkg")
        val nm = app.getSystemService(NotificationManager::class.java)
        main { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(2000)
            // The DND toggle (slot 4 by default), tapped as a finger would.
            // Folded, the toggles are a page of the tile pager: go to it. Unfolded they're in the bar.
            val page = app.prefs.enabledCards.indexOf(CardType.QUICK_ACTIONS)
            home.onActivity { a -> a.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.cards).setCurrentItem(page, false) }
            Thread.sleep(800)
            val (x, y) = on(home) { a ->
                val v = a.findViewById<View>(R.id.qaSlot3)
                val l = IntArray(2)
                v.getLocationOnScreen(l)
                (l[0] + v.width / 2) to (l[1] + v.height / 2)
            }
            shell("input tap $x $y")
            waitFor("DND on") { nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL }
            shell("input tap $x $y")
            waitFor("DND off") { nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL }
        }
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter)
    }
}
