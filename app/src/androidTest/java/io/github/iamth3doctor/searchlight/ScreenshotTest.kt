package io.github.iamth3doctor.searchlight

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import io.github.iamth3doctor.searchlight.weather.GeoPlace
import io.github.iamth3doctor.searchlight.weather.TempUnit
import io.github.iamth3doctor.searchlight.widgets.WidgetPickerActivity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a behavior test: captures every screen in a fixed state, at normal and large text, so
 * layout problems (clipping, overlap with system bars) can be checked folded and unfolded.
 * Run by .github/workflows/screenshots.yml; the device posture is set by the workflow and passed
 * in as the "shotPrefix" instrumentation argument.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as SearchlightApp
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"

    private fun shell(cmd: String) {
        // Reading to EOF waits for the command to finish.
        instrumentation.uiAutomation.executeShellCommand(cmd).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }

    private var scale = "1.0"

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(1500)
        shell("screencap -p $DIR/${prefix}_font${scale}_$name.png")
    }

    private fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        return null
    }

    @Test fun captureScreens() {
        shell("mkdir -p $DIR")
        // The emulator's stock launcher can stall on a slow CI machine and throw an
        // "isn't responding" dialog over the screenshots: make Searchlight home and stop it.
        val pkg = instrumentation.targetContext.packageName
        shell("cmd package set-home-activity $pkg/.MainActivity")
        shell("am force-stop com.google.android.apps.nexuslauncher")
        // A deterministic, realistic state: weather for a fixed place, in °F.
        instrumentation.runOnMainSync {
            app.prefs.manualPlace = GeoPlace("Austin", "Texas", "US", 30.27, -97.74)
            app.prefs.tempUnit = TempUnit.FAHRENHEIT
            app.weather.refresh()
        }
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && app.weather.state.data == null) Thread.sleep(250)

        try {
            for (fontScale in listOf("1.0", "1.3")) {
                scale = fontScale
                shell("settings put system font_scale $fontScale")
                Thread.sleep(2000)
                captureAll()
            }
        } finally {
            shell("settings put system font_scale 1.0")
        }
    }

    private fun captureAll() {
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            var tiles = 0
            home.onActivity { tiles = it.findViewById<ViewPager2>(R.id.cards).adapter?.itemCount ?: 0 }
            for (i in 0 until tiles) {
                home.onActivity { it.findViewById<ViewPager2>(R.id.cards).setCurrentItem(i, false) }
                shot("01-home-tile$i")
            }
            for ((name, query) in listOf("02-search-grid" to "c", "03-search-mixed" to "se", "04-search-calculator" to "42 x 18")) {
                home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText(query) }
                shot(name)
            }
        }
        ActivityScenario.launch(WidgetCenterActivity::class.java).use { wc ->
            shot("05-widget-center")
            wc.onActivity { (it.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView).fullScroll(ScrollView.FOCUS_DOWN) }
            shot("06-widget-center-bottom")
        }
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            shot("07-settings")
            s.onActivity { it.findViewById<ScrollView>(R.id.scroll).let { v -> v.scrollTo(0, v.getChildAt(0).height / 2 - v.height / 2) } }
            shot("08-settings-middle")
            s.onActivity { it.findViewById<ScrollView>(R.id.scroll).fullScroll(ScrollView.FOCUS_DOWN) }
            shot("09-settings-bottom")
            s.onActivity { it.findViewById<ScrollView>(R.id.scroll).fullScroll(ScrollView.FOCUS_UP) }
            s.onActivity { act -> findText(act.window.decorView, act.getString(R.string.qa_slot, 1))?.let { (it.parent as View).performClick() } }
            shot("10-settings-dialog")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        ActivityScenario.launch(WidgetPickerActivity::class.java).use {
            Thread.sleep(3000)
            shot("11-widget-picker")
        }
    }

    private companion object {
        const val DIR = "/sdcard/Download/searchlight-shots"
    }
}
