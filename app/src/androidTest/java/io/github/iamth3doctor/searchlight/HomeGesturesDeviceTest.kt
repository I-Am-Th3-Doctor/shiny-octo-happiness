package io.github.iamth3doctor.searchlight

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.gestures.GestureAction
import io.github.iamth3doctor.searchlight.gestures.GestureService
import io.github.iamth3doctor.searchlight.gestures.HomeGesture
import io.github.iamth3doctor.searchlight.widgets.WidgetGridView
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home gestures with real swipes (`input swipe`): each action, only from empty space, never
 * in edit mode or over the open app drawer, and the accessibility actions with access off and on.
 */
@RunWith(AndroidJUnit4::class)
class HomeGesturesDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val service = "$pkg/$pkg.gestures.GestureService"

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(150)
        }
        throw AssertionError("timed out: $what")
    }

    private fun <T> on(home: ActivityScenario<MainActivity>, read: (MainActivity) -> T): T {
        var v: T? = null
        home.onActivity { v = read(it) }
        @Suppress("UNCHECKED_CAST") return v as T
    }

    private fun set(g: HomeGesture, a: GestureAction, appPackage: String? = null) =
        instrumentation.runOnMainSync { app.prefs.setGesture(g, a, appPackage) }

    private fun focusedWindow() = shell("dumpsys window").lineSequence().firstOrNull { "mCurrentFocus" in it }.orEmpty()

    private fun shadeOpen() = "NotificationShade" in focusedWindow()

    /** A point on the widget canvas with no widget under it (and room to swipe 300px any way). */
    private fun emptySpot(home: ActivityScenario<MainActivity>): Pair<Int, Int> = on(home) { a ->
        val grid = a.findViewById<WidgetGridView>(R.id.widgetGrid)
        val loc = IntArray(2)
        grid.getLocationOnScreen(loc)
        val taken = (0 until grid.childCount).map { i ->
            val c = grid.getChildAt(i)
            Rect(loc[0] + c.left - 40, loc[1] + c.top - 40, loc[0] + c.right + 40, loc[1] + c.bottom + 40)
        }
        val xs = listOf(0.5f, 0.35f, 0.65f).map { (loc[0] + grid.width * it).toInt() }
        val ys = listOf(0.3f, 0.2f, 0.45f, 0.6f).map { (loc[1] + grid.height * it).toInt() }
        xs.flatMap { x -> ys.map { y -> x to y } }.first { (x, y) -> taken.none { it.contains(x, y) } }
    }

    private fun swipe(from: Pair<Int, Int>, dx: Int, dy: Int) {
        shell("input swipe ${from.first} ${from.second} ${from.first + dx} ${from.second + dy} 250")
        Thread.sleep(700)
        instrumentation.waitForIdleSync()
    }

    private fun drawerOpen(home: ActivityScenario<MainActivity>) = on(home) { it.findViewById<View>(R.id.appTable).isShown }

    @After fun restore() {
        shell("settings delete secure enabled_accessibility_services")
        shell("cmd statusbar collapse")
        instrumentation.runOnMainSync { HomeGesture.entries.forEach { app.prefs.setGesture(it, it.default) } }
    }

    @Test fun swipesRunTheirActionsOnlyFromEmptySpace() {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        shell("settings delete secure enabled_accessibility_services")
        set(HomeGesture.SWIPE_DOWN, GestureAction.NOTIFICATIONS)
        set(HomeGesture.SWIPE_LEFT, GestureAction.APP_DRAWER)
        set(HomeGesture.SWIPE_RIGHT, GestureAction.SEARCH)

        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            val spot = emptySpot(home)

            // Left: the app drawer. Back closes it.
            swipe(spot, -300, 0)
            waitFor("drawer from swipe left") { drawerOpen(home) }
            @Suppress("DEPRECATION") home.onActivity { it.onBackPressed() }
            waitFor("drawer closed") { !drawerOpen(home) }

            // Right: search, focused.
            swipe(spot, 300, 0)
            waitFor("search from swipe right") { on(home) { it.findViewById<EditText>(R.id.searchInput).isFocused } }
            @Suppress("DEPRECATION") home.onActivity { it.onBackPressed() }
            Thread.sleep(500)

            // A swipe that starts on the card is the card's: no drawer.
            val pill = on(home) { a ->
                val v = a.findViewById<View>(R.id.homeCard) // (the search row is hidden unfolded until asked for)
                val l = IntArray(2)
                v.getLocationOnScreen(l)
                (l[0] + v.width / 2) to (l[1] + v.height / 2)
            }
            swipe(pill, -300, 0)
            assertFalse("a swipe from the card opened the drawer", drawerOpen(home))

            // Down without accessibility access: nothing crashes, the shade stays shut (a toast explains).
            swipe(spot, 0, 300)
            assertFalse(shadeOpen())
            assertTrue(on(home) { !it.isFinishing })

            // Widget Center: edit mode; while editing, gestures do nothing.
            set(HomeGesture.SWIPE_RIGHT, GestureAction.WIDGET_CENTER)
            swipe(spot, 300, 0)
            waitFor("edit mode from swipe right") { on(home) { it.findViewById<WidgetGridView>(R.id.widgetGrid).editMode } }
            swipe(spot, -300, 0)
            assertFalse("a gesture ran in edit mode", drawerOpen(home))
            home.onActivity { it.findViewById<WidgetGridView>(R.id.widgetGrid).setEditMode(false) }

            // Launch App: an uninstalled app does nothing; an installed one opens.
            set(HomeGesture.SWIPE_RIGHT, GestureAction.LAUNCH_APP, "com.example.not.installed")
            swipe(spot, 300, 0)
            assertTrue("Searchlight should still be in front", pkg in focusedWindow())
            set(HomeGesture.SWIPE_RIGHT, GestureAction.LAUNCH_APP, "com.android.settings")
            swipe(spot, 300, 0)
            waitFor("Settings launched", 8_000) { "com.android.settings" in focusedWindow() }
        }

        // Accessibility on: down opens the shade; over the open drawer, down only closes the drawer.
        shell("settings put secure enabled_accessibility_services $service")
        shell("settings put secure accessibility_enabled 1")
        waitFor("gesture service connected", 10_000) { GestureService.isConnected }
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            val spot = emptySpot(home)
            swipe(spot, 0, 300)
            waitFor("shade from swipe down") { shadeOpen() }
            shell("screencap -p $dir/${prefix}_font1.0_15-gesture-notifications.png")
            shell("cmd statusbar collapse")
            Thread.sleep(800)

            home.onActivity { it.openAppTable() }
            waitFor("drawer open") { drawerOpen(home) }
            Thread.sleep(500)
            swipe(spot, 0, 300)
            waitFor("drawer closed by swipe down") { !drawerOpen(home) }
            assertFalse("swipe down over the drawer also opened the shade", shadeOpen())
        }
    }
}
