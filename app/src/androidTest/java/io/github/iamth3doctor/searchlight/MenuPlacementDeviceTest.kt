package io.github.iamth3doctor.searchlight

import android.graphics.Rect
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.ui.AppMenu
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The long-press menu never leaves the screen's safe area: a real app's menu from search results
 * near the bottom, and a menu far too tall for the screen, which must open upward, scroll with
 * a finger to its last row, still act on a tap there, and close on a tap outside. Runs folded
 * and unfolded.
 */
@RunWith(AndroidJUnit4::class)
class MenuPlacementDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"

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

    private fun screenRect(v: View): Rect {
        val l = IntArray(2)
        v.getLocationOnScreen(l)
        return Rect(l[0], l[1], l[0] + v.width, l[1] + v.height)
    }

    /** The screen minus system bars, cutout and keyboard, minus the 12dp margin. */
    private fun safeArea(a: MainActivity): Rect {
        val root = a.window.decorView
        val i = root.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        val r = screenRect(root)
        val m = (12 * a.resources.displayMetrics.density).toInt()
        return Rect(r.left + i.left + m, r.top + i.top + m, r.right - i.right - m, r.bottom - i.bottom - m)
    }

    private fun menuRect(home: ActivityScenario<MainActivity>) = on(home) { screenRect(it.appMenu.contentView!!) }

    private fun assertInside(what: String, home: ActivityScenario<MainActivity>) {
        val menu = menuRect(home)
        val safe = on(home) { safeArea(it) }
        // A pixel of rounding either way.
        assertTrue("$what: menu $menu outside the safe area $safe",
            menu.left >= safe.left - 1 && menu.top >= safe.top - 1 && menu.right <= safe.right + 1 && menu.bottom <= safe.bottom + 1)
    }

    @Test fun realMenuNearTheBottomStaysOnScreen() {
        shell("cmd role add-role-holder --user 0 android.app.role.HOME ${app.packageName}")
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            val calendar = on(home) { app.apps.apps.first { it.packageName == "com.google.android.calendar" } }
            home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText(calendar.label) }
            Thread.sleep(1200)
            home.onActivity { a ->
                fun find(v: View): View? = when {
                    v is TextView && v.text?.toString() == calendar.label -> v
                    v is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
                    else -> null
                }
                var cell = find(a.findViewById(R.id.resultsPanel))
                while (cell != null && !cell.isLongClickable) cell = cell.parent as? View
                cell!!.performLongClick()
            }
            waitFor("menu") { on(home) { it.appMenu.isShowing } }
            Thread.sleep(600)
            assertInside("Calendar's menu", home)
        }
    }

    @Test fun tooTallMenuOpensUpwardAndScrollsToItsLastRow() {
        shell("mkdir -p $dir")
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            var tapped: String? = null
            // 30 shortcuts and 3 actions: far taller than any screen, on the bottom-most row there is.
            home.onActivity { a ->
                val anchor = a.findViewById<View>(R.id.homeCard)
                val shortcuts = (1..30).map { n -> AppMenu.Item("Shortcut $n", iconRes = R.drawable.ic_search) { tapped = "Shortcut $n" } }
                val actions = listOf("App info", "Widgets", "Last row").map { l -> AppMenu.Item(l, iconRes = R.drawable.ic_info) { tapped = l } }
                a.appMenu.show(anchor, shortcuts, actions) {}
            }
            waitFor("menu") { on(home) { it.appMenu.isShowing } }
            Thread.sleep(600)
            assertInside("tall menu", home)
            val card = menuRect(home)
            val anchorTop = on(home) { screenRect(it.findViewById(R.id.homeCard)).top }
            assertTrue("opened downward over the card: menu $card, card top $anchorTop", card.bottom <= anchorTop)
            val scroll = on(home) { it.appMenu.rowsView!!.parent as ScrollView }
            assertTrue("a menu this tall must scroll", on(home) { scroll.canScrollVertically(1) })
            shell("screencap -p $dir/${prefix}_font1.0_19-menu-tall-top.png")

            // Scroll with a finger until the end.
            var swipes = 0
            while (on(home) { scroll.canScrollVertically(1) } && swipes++ < 25) {
                shell("input swipe ${card.centerX()} ${card.bottom - 80} ${card.centerX()} ${card.top + 80} 300")
                Thread.sleep(400)
            }
            assertFalse("touch scrolling never reached the end", on(home) { scroll.canScrollVertically(1) })
            val last = on(home) { a ->
                fun find(v: View): View? = when {
                    v is TextView && v.text?.toString() == "Last row" -> v.parent as View
                    v is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
                    else -> null
                }
                screenRect(find(a.appMenu.rowsView!!)!!)
            }
            assertTrue("last row $last not fully inside the menu $card", last.top >= card.top && last.bottom <= card.bottom)
            shell("screencap -p $dir/${prefix}_font1.0_19-menu-tall-end.png")

            // Its last row still works.
            shell("input tap ${last.centerX()} ${last.centerY()}")
            waitFor("last row tapped") { tapped == "Last row" }
            waitFor("menu closed after a choice") { on(home) { !it.appMenu.isShowing } }

            // A tap outside closes it, as before.
            home.onActivity { a ->
                a.appMenu.show(a.findViewById(R.id.homeCard), listOf(AppMenu.Item("Only", iconRes = R.drawable.ic_info) {}), emptyList()) {}
            }
            waitFor("menu again") { on(home) { it.appMenu.isShowing } }
            val safe = on(home) { safeArea(it) }
            shell("input tap ${safe.left + 10} ${safe.top + 10}")
            waitFor("closed by a tap outside") { on(home) { !it.appMenu.isShowing } }
        }
    }
}
