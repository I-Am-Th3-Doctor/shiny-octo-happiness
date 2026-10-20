package io.github.iamth3doctor.searchlight

import android.widget.EditText
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.weather.GeoPlace
import io.github.iamth3doctor.searchlight.weather.TempUnit
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a behavior test: captures the main screens in a fixed state so design changes can be
 * compared before/after. Run by .github/workflows/screenshots.yml on an emulator; images land
 * in /sdcard/Download/searchlight-shots and are published as a release.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as SearchlightApp

    private fun shell(cmd: String) {
        // Reading to EOF waits for the command to finish.
        instrumentation.uiAutomation.executeShellCommand(cmd).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(1500)
        shell("screencap -p $DIR/$name.png")
    }

    @Test fun captureScreens() {
        shell("mkdir -p $DIR")
        // The emulator's stock launcher can stall on a slow CI machine and throw an
        // "isn't responding" dialog over the screenshots: make Searchlight home and stop it.
        val pkg = instrumentation.targetContext.packageName
        shell("cmd package set-home-activity $pkg/.MainActivity")
        shell("am force-stop com.google.android.apps.nexuslauncher")
        Thread.sleep(2000)
        // A deterministic, realistic state: weather for a fixed place, in °F.
        instrumentation.runOnMainSync {
            app.prefs.manualPlace = GeoPlace("Austin", "Texas", "US", 30.27, -97.74)
            app.prefs.tempUnit = TempUnit.FAHRENHEIT
            app.weather.refresh()
        }
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && app.weather.state.data == null) Thread.sleep(250)

        ActivityScenario.launch(MainActivity::class.java).use { home ->
            shot("01-home")
            home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("se") }
            shot("02-home-search")
            home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("42 x 18") }
            shot("03-home-calculator")
        }
        ActivityScenario.launch(WidgetCenterActivity::class.java).use { wc ->
            shot("04-widget-center")
            wc.onActivity { (it.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView).fullScroll(ScrollView.FOCUS_DOWN) }
            shot("05-widget-center-bottom")
        }
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            shot("06-settings")
            s.onActivity { it.findViewById<ScrollView>(R.id.scroll).fullScroll(ScrollView.FOCUS_DOWN) }
            shot("07-settings-bottom")
        }
    }

    private companion object {
        const val DIR = "/sdcard/Download/searchlight-shots"
    }
}
