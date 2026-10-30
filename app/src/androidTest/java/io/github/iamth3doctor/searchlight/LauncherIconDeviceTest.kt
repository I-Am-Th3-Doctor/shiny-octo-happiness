package io.github.iamth3doctor.searchlight

import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launcher icon as Android sees it (adaptive, the artwork as its full-bleed foreground, the
 * label unchanged), and screenshots of it where it shows: Searchlight's own entry in the app
 * drawer, the system's App info page, and recents.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pkg = context.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    @Test fun iconAndLabel() {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        assertEquals("Searchlight", pm.getApplicationLabel(info).toString())
        val icon = pm.getApplicationIcon(info)
        assertTrue("not adaptive: $icon", icon is AdaptiveIconDrawable)
        val fg = (icon as AdaptiveIconDrawable).foreground
        assertTrue("foreground isn't the artwork: $fg", fg is BitmapDrawable)
        val bmp = (fg as BitmapDrawable).bitmap
        assertEquals("square artwork", bmp.width, bmp.height)
    }

    @Test fun whereTheIconShows() {
        shell("mkdir -p $dir")
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            home.onActivity { it.openAppTable() } // Searchlight's own entry is in the drawer
            Thread.sleep(2000)
            shell("screencap -p $dir/${prefix}_font1.0_23-icon-drawer.png")
        }
        shell("am start -W -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$pkg")
        Thread.sleep(2500)
        shell("screencap -p $dir/${prefix}_font1.0_24-icon-app-info.png")
        // Recents: Searchlight's card shows for its Settings screen (the home screen has none).
        shell("am start -W -n $pkg/.SettingsActivity")
        Thread.sleep(1500)
        shell("input keyevent KEYCODE_APP_SWITCH")
        Thread.sleep(2500)
        shell("screencap -p $dir/${prefix}_font1.0_25-icon-recents.png")
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1000)
    }
}
