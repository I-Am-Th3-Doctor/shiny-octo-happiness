package io.github.iamth3doctor.searchlight

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.data.FavoritesMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hold, slide, go with a real held finger (`input motionevent`, one continuous touch): hold the
 * magnifier and lift without reaching the row (it stays open), slide in and off (nothing launches,
 * it closes), and slide along it slot by slot and lift (that slot's app launches).
 */
@RunWith(AndroidJUnit4::class)
class SlideToLaunchDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private var savedManual = emptyList<String>()

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitFor(what: String, timeoutMs: Long = 8_000, check: () -> Boolean) {
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

    private fun center(v: View): Pair<Int, Int> {
        val l = IntArray(2)
        v.getLocationOnScreen(l)
        return (l[0] + v.width / 2) to (l[1] + v.height / 2)
    }

    private fun touch(action: String, p: Pair<Int, Int>) {
        shell("input motionevent $action ${p.first} ${p.second}")
        Thread.sleep(120)
    }

    private fun showing(home: ActivityScenario<MainActivity>) = on(home) { it.favoritesPopup.isShowing }

    private fun focusedWindow() = shell("dumpsys window").lineSequence().firstOrNull { "mCurrentFocus" in it }.orEmpty()

    /** The magnifier, visible (unfolded, the search row shows only once asked for). */
    private fun magnifier(home: ActivityScenario<MainActivity>): Pair<Int, Int> {
        home.onActivity { a ->
            val icon = a.findViewById<View>(R.id.searchIcon)
            if (!icon.isShown) a.findViewById<View>(R.id.railSearch)?.performClick()
        }
        Thread.sleep(1500)
        return on(home) { center(it.findViewById(R.id.searchIcon)) }
    }

    /** Holds the magnifier until the row opens; the finger stays down. */
    private fun holdOpen(home: ActivityScenario<MainActivity>, at: Pair<Int, Int>) {
        touch("DOWN", at)
        Thread.sleep(600) // past the 250ms hold
        waitFor("favorites row") { showing(home) }
        Thread.sleep(400) // its pop-in animation
    }

    @After fun restore() = main {
        app.prefs.manualFavorites.forEach { app.prefs.removeFavorite(it) }
        savedManual.forEach { app.prefs.addFavorite(it) }
    }

    @Test fun holdSlideGo() {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        waitFor("apps loaded") { app.apps.apps.isNotEmpty() }
        main {
            savedManual = app.prefs.manualFavorites
            app.prefs.favoritesMode = FavoritesMode.MANUAL
            app.prefs.predictedSlot = false
            app.prefs.manualFavorites.forEach { app.prefs.removeFavorite(it) }
            listOf("com.android.chrome", "com.google.android.gm", "com.google.android.apps.maps",
                "com.google.android.apps.photos", "com.google.android.youtube", "com.google.android.deskclock")
                .mapNotNull { p -> app.apps.apps.firstOrNull { it.packageName == p && !it.isSelf } }
                .forEach { app.prefs.addFavorite(it.key) }
        }
        val packages = mutableListOf<String>()
        main { app.prefs.manualFavorites.forEach { k -> packages += app.apps.find(k)!!.packageName } }

        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            val icon = magnifier(home)

            // Hold and lift without reaching the row: it stays open, for tapping.
            holdOpen(home, icon)
            touch("UP", icon)
            Thread.sleep(400)
            assertTrue("lifting before the row closed it", showing(home))
            home.onActivity { it.favoritesPopup.dismiss() }
            Thread.sleep(400)

            // Hold, slide into the row, then off it, and lift: nothing launches, the row closes.
            holdOpen(home, icon)
            val cells = on(home) { a -> a.favoritesPopup.cells.map(::center) }
            touch("MOVE", cells[1])
            assertEquals(1, on(home) { it.favoritesPopup.slideSelection })
            val off = cells[1].first to 150 // far above the row
            touch("MOVE", off)
            assertNull(on(home) { it.favoritesPopup.slideSelection })
            touch("UP", off)
            waitFor("row closed after sliding off") { !showing(home) }
            Thread.sleep(500)
            assertTrue("something launched: ${focusedWindow()}", pkg in focusedWindow())

            // Hold, slide along slot by slot (the selection follows), back one, lift: that app opens.
            holdOpen(home, icon)
            for (i in 0..4) {
                touch("MOVE", cells[i])
                assertEquals("finger on slot ${i + 1}", i, on(home) { it.favoritesPopup.slideSelection })
            }
            touch("MOVE", cells[2])
            assertEquals(2, on(home) { it.favoritesPopup.slideSelection })
            assertTrue("highlighted slot isn't enlarged",
                on(home) { it.favoritesPopup.cells[2].findViewById<View>(R.id.icon).scaleX } > 1.05f)
            shell("screencap -p $dir/${prefix}_font1.0_22-slide-to-launch.png")
            touch("UP", cells[2])
            waitFor("${packages[2]} launched", 15_000) { packages[2] in focusedWindow() }
            assertFalse(showing(home))
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(1000)
        }
    }
}
