package io.github.iamth3doctor.searchlight

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.apps.AppEntry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The long-press menu on a real app (the Clock): its own shortcuts come first and open that
 * shortcut, "Widgets" opens the picker with only the Clock's widgets.
 */
@RunWith(AndroidJUnit4::class)
class AppMenuDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(250)
        }
        throw AssertionError("timed out: $what")
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun find(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun resumedActivity() = shell("dumpsys activity activities").lineSequence()
        .firstOrNull { it.contains("mResumedActivity") || it.contains("ResumedActivity:") }.orEmpty()

    /** Long-presses the Clock in search results, like a finger would. */
    private fun openMenu(home: ActivityScenario<MainActivity>, clock: AppEntry) {
        home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText(clock.label) }
        Thread.sleep(1000)
        instrumentation.waitForIdleSync()
        home.onActivity { a ->
            var v: View? = find(a.findViewById(R.id.resultsPanel), clock.label)
            while (v != null && !v.isLongClickable) v = v.parent as? View
            v!!.performLongClick()
        }
        waitFor("menu") { var open = false; home.onActivity { open = it.appMenu.isShowing }; open }
        Thread.sleep(600)
    }

    /** Taps the menu row labelled [label] with a real touch at its place on screen. */
    private fun tapRow(home: ActivityScenario<MainActivity>, label: String) {
        var x = 0
        var y = 0
        home.onActivity { a ->
            val row = find(a.appMenu.contentView!!, label)!!.parent as View
            val loc = IntArray(2)
            row.getLocationOnScreen(loc)
            x = loc[0] + row.width / 2
            y = loc[1] + row.height / 2
        }
        shell("input tap $x $y")
    }

    @Test fun shortcutsFirstAndWidgetsFiltered() {
        // The default home (only it may read other apps' shortcuts).
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        shell("cmd package set-home-activity $pkg/.MainActivity")
        Thread.sleep(1000)
        waitFor("apps loaded") { var n = 0; main { n = app.apps.apps.size }; n > 0 }
        var clock: AppEntry? = null
        main { clock = app.apps.apps.first { it.packageName == "com.google.android.deskclock" } }
        val c = clock!!
        main { app.shortcuts.refreshIfAccessChanged() }

        var labels = emptyList<String>()
        var hasWidgets = false
        try {
            waitFor("clock shortcuts") {
                main { app.shortcuts.menuFor(c).let { m -> labels = m.shortcuts.map { it.label.toString() }; hasWidgets = m.hasWidgets } }
                labels.isNotEmpty()
            }
        } catch (e: AssertionError) {
            val la = app.getSystemService(android.content.pm.LauncherApps::class.java)
            val access = runCatching { la.hasShortcutHostPermission() }.getOrElse { it.toString() }
            throw AssertionError(
                "No Clock shortcuts. hasShortcutHostPermission=$access; " +
                    "home role=${shell("cmd role get-role-holders --user 0 android.app.role.HOME").trim()}; " +
                    "default launcher=${shell("cmd shortcut get-default-launcher").trim()}; " +
                    "clock in dumpsys shortcut: ${shell("dumpsys shortcut").lines().filter { "deskclock" in it }.take(8)}",
                e,
            )
        }
        assertTrue(hasWidgets)
        android.util.Log.i("AppMenuDeviceTest", "Clock shortcuts: $labels")

        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            openMenu(home, c)
            shell("screencap -p $dir/${prefix}_font1.0_13-app-menu.png")

            // Every shortcut row comes before App info.
            home.onActivity { a ->
                val rows = a.appMenu.contentView as ViewGroup
                val texts = (0 until rows.childCount).mapNotNull { i ->
                    val row = rows.getChildAt(i) as? ViewGroup ?: return@mapNotNull null // the hairline
                    (0 until row.childCount).map(row::getChildAt).filterIsInstance<TextView>().firstOrNull()?.text?.toString()
                }
                assertTrue("rows: $texts", texts.take(labels.size) == labels && texts.indexOf(a.getString(R.string.menu_app_info)) == labels.size)
                assertTrue("rows: $texts", a.getString(R.string.menu_widgets) in texts)
            }

            // A shortcut opens the Clock (on that shortcut's screen).
            tapRow(home, labels.first())
            waitFor("clock opened") { resumedActivity().contains(c.packageName) }
            shell("input keyevent KEYCODE_HOME")
            waitFor("back home") { resumedActivity().contains(pkg) }
            Thread.sleep(1000)

            // Widgets: the picker, with only the Clock's widgets.
            openMenu(home, c)
            tapRow(home, app.getString(R.string.menu_widgets))
            waitFor("widget picker") { resumedActivity().contains("WidgetPickerActivity") }
            Thread.sleep(2500)
            shell("screencap -p $dir/${prefix}_font1.0_14-app-widgets.png")
            val shown = shell("uiautomator dump /dev/tty")
            assertTrue("picker shows other apps' widgets: $shown", !shown.contains("Chrome") && !shown.contains("Calendar"))
            shell("input keyevent KEYCODE_BACK")
        }
    }
}
