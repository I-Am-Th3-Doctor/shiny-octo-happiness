package io.github.iamth3doctor.searchlight

import android.app.Activity
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.data.FavoritesMode
import io.github.iamth3doctor.searchlight.ui.CollapsibleSections
import io.github.iamth3doctor.searchlight.ui.FrostedDrawable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collapsible settings sections: the first open and the rest closed at first, a header tap
 * toggles that section only, the state outlives the screen (recreated or opened again), and
 * rows a setting hides stay hidden (and shown ones stay behind a closed header).
 */
@RunWith(AndroidJUnit4::class)
class SettingsSectionsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val settingsKeys = listOf("home", "appearance", "gestures", "permissions", "favorites", "about")
    private val widgetCenterKeys = listOf("cards", "idle", "weather")
    private val settingsRows = listOf(
        Triple("home", "Home", "Layout, tiles and search bar"),
        Triple("appearance", "Appearance", "Theme, icon packs and fonts"),
        Triple("gestures", "Gestures", "Swipes, taps and shortcuts"),
        Triple("permissions", "Permissions", "Access and privacy"),
        Triple("favorites", "Favorites (up to 6)", "Your six quick apps"),
        Triple("about", "About", "Version and details"),
    )

    private fun textsIn(v: View): List<String> = when (v) {
        is android.widget.TextView -> listOf(v.text.toString())
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { textsIn(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).close()

    /** Back to "never opened": the stored states gone. */
    private fun forgetSections() {
        val sp = app.getSharedPreferences("searchlight", 0)
        sp.edit().apply { sp.all.keys.filter { it.startsWith("section_open.") }.forEach(::remove) }.commit()
    }

    @Before fun fresh() = forgetSections()
    @After fun clean() {
        forgetSections()
        instrumentation.runOnMainSync { app.prefs.favoritesMode = FavoritesMode.MANUAL }
    }

    private fun <A : Activity> open(s: ActivityScenario<A>): List<Boolean> {
        var states = emptyList<Boolean>()
        s.onActivity { a ->
            val root = a.window.decorView
            states = keysOf(a).map { k -> CollapsibleSections.bodyOf(root, k)!!.visibility == View.VISIBLE }
        }
        return states
    }

    private fun keysOf(a: Activity) = if (a is WidgetCenterActivity) widgetCenterKeys else settingsKeys

    private fun <A : Activity> tap(s: ActivityScenario<A>, key: String) {
        s.onActivity { a -> CollapsibleSections.headerOf(a.window.decorView, key)!!.performClick() }
        Thread.sleep(600) // the open/close animation
        instrumentation.waitForIdleSync()
    }

    @Test fun settingsSections() {
        shell("mkdir -p $dir")
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            Thread.sleep(1000)
            // Every section has a header; first open, the rest closed.
            assertEquals(listOf(true, false, false, false, false, false), open(s))
            // Each section in its own frosted card, 28dp corners, its title over its subtitle.
            s.onActivity { a ->
                val r = a.resources.getDimension(R.dimen.radius_card)
                for ((k, title, subtitle) in settingsRows) {
                    val bg = CollapsibleSections.cardOf(a.window.decorView, k)!!.background as FrostedDrawable
                    assertEquals("$k card corners", r, bg.radius, 0.5f)
                    val texts = textsIn(CollapsibleSections.headerOf(a.window.decorView, k)!!)
                    assertEquals("$k row", listOf(title, subtitle), texts)
                }
            }
            shell("screencap -p $dir/${prefix}_font1.0_26-settings-sections.png")

            // A header toggles its own section only.
            tap(s, "gestures")
            assertEquals(listOf(true, false, true, false, false, false), open(s))
            tap(s, "home")
            assertEquals(listOf(false, false, true, false, false, false), open(s))
            tap(s, "gestures")
            tap(s, "favorites")
            assertEquals(listOf(false, false, false, false, true, false), open(s))

            // Leaving and coming back (the screen rebuilt): the same.
            s.recreate()
            Thread.sleep(800)
            assertEquals(listOf(false, false, false, false, true, false), open(s))
        }
        // A new launch reads it back from storage (what a restart does).
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            Thread.sleep(800)
            assertEquals(listOf(false, false, false, false, true, false), open(s))
        }
    }

    @Test fun hiddenRowsStayHiddenAndShownRowsStayBehindAClosedHeader() {
        instrumentation.runOnMainSync { app.prefs.favoritesMode = FavoritesMode.AUTOMATIC }
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            Thread.sleep(800)
            tap(s, "favorites") // open it
            var visibility = 0
            var shown = true
            // Automatic: the predicted-slot switch is hidden by the setting, section open or not.
            s.onActivity { a -> a.findViewById<View>(R.id.predictedSlotSwitch).let { visibility = it.visibility; shown = it.isShown } }
            assertEquals(View.GONE, visibility)
            assertFalse(shown)

            // Manual: it's there in the open section...
            instrumentation.runOnMainSync { app.prefs.favoritesMode = FavoritesMode.MANUAL }
            s.recreate()
            Thread.sleep(800)
            s.onActivity { a -> a.findViewById<View>(R.id.predictedSlotSwitch).let { visibility = it.visibility; shown = it.isShown } }
            assertEquals(View.VISIBLE, visibility)
            assertTrue(shown)

            // ...and behind the header once the section is closed (still VISIBLE to its setting).
            tap(s, "favorites")
            s.onActivity { a -> a.findViewById<View>(R.id.predictedSlotSwitch).let { visibility = it.visibility; shown = it.isShown } }
            assertEquals(View.VISIBLE, visibility)
            assertFalse(shown)
        }
    }

    @Test fun widgetCenterSections() {
        ActivityScenario.launch(WidgetCenterActivity::class.java).use { s ->
            Thread.sleep(1000)
            assertEquals(listOf(true, false, false), open(s))
            tap(s, "weather")
            assertEquals(listOf(true, false, true), open(s))
            tap(s, "cards")
            assertEquals(listOf(false, false, true), open(s))
            shell("screencap -p $dir/${prefix}_font1.0_27-widget-center-sections.png")
            // The screen re-renders on resume: the state holds.
            s.recreate()
            Thread.sleep(800)
            assertEquals(listOf(false, false, true), open(s))
        }
    }
}
