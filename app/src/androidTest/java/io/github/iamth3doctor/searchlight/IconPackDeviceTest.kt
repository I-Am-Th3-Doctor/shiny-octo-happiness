package io.github.iamth3doctor.searchlight

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.iconpack.IconPack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import kotlin.math.abs

/**
 * Icon packs with real installed packs: the repo's test pack (installed by the workflow, it
 * themes apps in each possible way) and Arcticons from F-Droid when the workflow could fetch it.
 * Screenshots of the drawer, search and favorites with each.
 */
@RunWith(AndroidJUnit4::class)
class IconPackDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            instrumentation.runOnMainSync { ok = check() }
            if (ok) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out: $what")
    }

    private fun select(pack: String?): Boolean {
        var result: Boolean? = null
        instrumentation.runOnMainSync { app.iconPacks.select(pack) { result = it } }
        waitFor("selecting $pack") { result != null }
        return result!!
    }

    private fun entry(packageName: String): AppEntry? {
        var e: AppEntry? = null
        instrumentation.runOnMainSync { e = app.apps.apps.firstOrNull { it.packageName == packageName && !it.isSelf } }
        return e
    }

    /** The icon the launcher shows for [e] right now (drawn by the icon threads). */
    private fun icon(e: AppEntry): Bitmap {
        var bmp: Bitmap? = null
        instrumentation.runOnMainSync { bmp = app.apps.icon(e) { bmp = it } }
        waitFor("icon for ${e.label}") { bmp != null }
        return bmp!!
    }

    private fun opaquePixels(b: Bitmap): Int {
        var n = 0
        for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) if (Color.alpha(b.getPixel(x, y)) > 32) n++
        return n
    }

    private fun near(c: Int, r: Int, g: Int, b: Int) = abs(Color.red(c) - r) < 40 && abs(Color.green(c) - g) < 40 && abs(Color.blue(c) - b) < 40

    private fun shots(name: String) {
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(2000)
            home.onActivity { it.openAppTable() }
            Thread.sleep(2000)
            shell("screencap -p $dir/${prefix}_font1.0_16-$name-drawer.png")
            @Suppress("DEPRECATION") home.onActivity { it.onBackPressed() }
            Thread.sleep(800)
            home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("o") }
            Thread.sleep(2000)
            shell("screencap -p $dir/${prefix}_font1.0_17-$name-search.png")
            home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("") }
            Thread.sleep(800)
            home.onActivity { it.findViewById<View>(R.id.searchIcon).performLongClick() }
            Thread.sleep(1500)
            shell("screencap -p $dir/${prefix}_font1.0_18-$name-favorites.png")
        }
    }

    @After fun systemIcons() {
        select(null)
        shell("pm install -r /data/local/tmp/testiconpack.apk") // in case the test removed it
    }

    @Test fun testPackThemesEveryWayAndFallsBack() {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        waitFor("apps loaded") { app.apps.apps.isNotEmpty() }
        instrumentation.runOnMainSync {
            if (app.prefs.manualFavorites.isEmpty()) app.apps.apps.filter { !it.isSelf }.take(6).forEach { app.prefs.addFavorite(it.key) }
        }

        val packs = app.iconPacks.installedPacks().map { it.packageName }
        assertTrue("test pack not listed: $packs", TEST_PACK in packs)
        assertFalse("Searchlight lists itself: $packs", pkg in packs)

        // A pack that can't be loaded is refused; the choice stays as it was.
        assertTrue(select(TEST_PACK))
        assertFalse(select("com.example.not.an.icon.pack"))
        assertEquals(TEST_PACK, app.iconPacks.selected)

        val pack = IconPack.load(app, TEST_PACK)
        val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val settings = checkNotNull(entry("com.android.settings")) { "com.android.settings isn't installed" }
        val clock = checkNotNull(entry("com.google.android.deskclock")) { "com.google.android.deskclock isn't installed" }
        val calendar = checkNotNull(entry("com.google.android.calendar")) { "com.google.android.calendar isn't installed" }
        val youtube = checkNotNull(entry("com.google.android.youtube")) { "com.google.android.youtube isn't installed" }
        fun source(e: AppEntry) = pack.source(e.component.packageName, e.component.className, day)
        assertEquals(IconPack.Source.APPFILTER, source(settings))
        assertEquals(IconPack.Source.NAME_MATCH, source(clock))
        assertEquals("calendar is ${calendar.component.flattenToShortString()}", IconPack.Source.CALENDAR, source(calendar))
        assertEquals("an entry with a missing drawable falls through", IconPack.Source.FALLBACK, source(youtube))

        // What the launcher actually draws: the pack's own art for themed apps...
        val s = icon(settings)
        assertTrue(near(s.getPixel(s.width / 2, s.height / 4), 0xFF, 0x6F, 0x00)) // orange disc
        // ...and, for the others, their own icon on the pack's blue back with the white dot: never blank.
        for (e in app.apps.apps.filter { !it.isSelf }) {
            val b = icon(e)
            assertTrue("blank icon for ${e.label}", opaquePixels(b) > (b.width / 4) * (b.height / 4) / 2)
        }
        val y = icon(youtube)
        assertTrue("fallback has no pack back", near(y.getPixel(2, y.height / 2), 0x15, 0x65, 0xC0))
        assertTrue("fallback has no upon", near(y.getPixel(y.width * 40 / 48, y.height * 8 / 48), 0xFF, 0xFF, 0xFF))

        shots("testpack")

        // The chosen pack uninstalled: back to the system's icons, without a word.
        shell("pm uninstall $TEST_PACK")
        waitFor("revert after uninstall") { app.iconPacks.selected == null && app.apps.iconPack == null }
    }

    @Test fun arcticonsFromFDroid() {
        val packs = app.iconPacks.installedPacks().map { it.packageName }
        if (ARCTICONS !in packs) {
            // The workflow couldn't download it (network): say so in the log, don't fail the run.
            android.util.Log.w("IconPackDeviceTest", "Arcticons isn't installed; skipped. Packs: $packs")
            org.junit.Assume.assumeTrue("Arcticons not installed", false)
        }
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        waitFor("apps loaded") { app.apps.apps.isNotEmpty() }
        assertTrue(select(ARCTICONS))
        val pack = IconPack.load(app, ARCTICONS)
        val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val themed = app.apps.apps.filter { pack.source(it.component.packageName, it.component.className, day) != IconPack.Source.FALLBACK }
        val unthemed = app.apps.apps.filter { it !in themed }
        android.util.Log.i("IconPackDeviceTest", "Arcticons themes ${themed.map { it.label }}; falls back for ${unthemed.map { it.label }}")
        assertTrue("Arcticons themes none of the emulator's apps", themed.isNotEmpty())
        for (e in app.apps.apps) assertTrue("blank icon for ${e.label}", opaquePixels(icon(e)) > 0)
        shots("arcticons")
    }

    private companion object {
        const val TEST_PACK = "io.github.iamth3doctor.searchlight.testiconpack"
        const val ARCTICONS = "com.donnnno.arcticons"
    }
}
