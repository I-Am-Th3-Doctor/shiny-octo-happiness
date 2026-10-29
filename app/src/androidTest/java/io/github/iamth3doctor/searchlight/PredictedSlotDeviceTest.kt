package io.github.iamth3doctor.searchlight

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.apps.FavoriteSlots
import io.github.iamth3doctor.searchlight.data.FavoritesMode
import io.github.iamth3doctor.searchlight.ui.homeFavorites
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The predicted 6th favorite against real usage stats and the real favorites row: off is the six
 * picks; on, slot 6 is predicted (never one of slots 1-5) and marked; Pin prediction from its
 * long-press menu fixes it, Unpin brings the prediction back; turning it off restores the 6th
 * pick; no usage access falls back to it; Automatic mode ignores the setting.
 */
@RunWith(AndroidJUnit4::class)
class PredictedSlotDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private var savedManual = emptyList<String>()

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitFor(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            main { ok = check() }
            if (ok) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out: $what")
    }

    private fun slots(): List<Pair<String?, FavoriteSlots.Kind?>> {
        var s = emptyList<Pair<String?, FavoriteSlots.Kind?>>()
        main { s = homeFavorites(app.prefs, app.apps).map { it?.entry?.key to it?.kind } }
        return s
    }

    private fun setManual(keys: List<String>) = main {
        app.prefs.manualFavorites.forEach { app.prefs.removeFavorite(it) }
        keys.forEach { app.prefs.addFavorite(it) }
    }

    /** Forces a fresh prediction (the 15-minute throttle aside). */
    private fun predictNow() {
        main { app.prefs.savePredictions(emptyList(), 0) }
        main { app.autoFavorites.refreshPrediction() }
        waitFor("prediction") { app.prefs.predictionsAt != 0L }
    }

    private fun text(v: View): String? = when (v) {
        is TextView -> v.text?.toString()
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { text(v.getChildAt(it)) }
        else -> null
    }

    @After fun restore() {
        shell("appops set $pkg GET_USAGE_STATS default")
        main {
            app.prefs.favoritesMode = FavoritesMode.MANUAL
            app.prefs.predictedSlot = false
            app.prefs.savePredictions(emptyList(), 0)
        }
        setManual(savedManual)
    }

    @Test fun predictedSlotEndToEnd() {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        waitFor("apps loaded") { app.apps.apps.isNotEmpty() }
        main { savedManual = app.prefs.manualFavorites; app.prefs.favoritesMode = FavoritesMode.MANUAL }

        fun key(p: String): String { var k = ""; main { k = app.apps.apps.first { it.packageName == p && !it.isSelf }.key }; return k }
        val manual = listOf("com.android.chrome", "com.google.android.gm", "com.google.android.apps.maps",
            "com.google.android.apps.photos", "com.google.android.youtube", "com.google.android.apps.docs").map(::key)
        setManual(manual)

        // Some usage to predict from: the Clock a few times (not one of slots 1-5).
        val clock = key("com.google.android.deskclock")
        repeat(3) {
            shell("monkey -p com.google.android.deskclock -c android.intent.category.LAUNCHER 1")
            Thread.sleep(1500)
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(800)
        }
        shell("appops set $pkg GET_USAGE_STATS allow")

        // Off: the six picks, untouched.
        assertEquals(manual, slots().map { it.first })

        // On: slots 1-5 the same, slot 6 predicted and not one of them.
        main { app.prefs.predictedSlot = true }
        predictNow()
        var s = slots()
        assertEquals(manual.take(5), s.take(5).map { it.first })
        assertEquals("slot 6: $s; predictions ${app.prefs.predictions}", FavoriteSlots.Kind.PREDICTED, s[5].second)
        val predicted = checkNotNull(s[5].first) { "slot 6 is empty: $s" }
        assertFalse("slot 6 repeats slots 1-5", predicted in manual.take(5))
        assertTrue("the Clock was launched but isn't predicted: ${app.prefs.predictions}", clock in app.prefs.predictions)
        assertEquals(manual, app.prefs.manualFavorites) // the 6th pick is kept

        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            home.onActivity { it.findViewById<View>(R.id.searchIcon).performLongClick() }
            waitFor("favorites row") { var ok = false; home.onActivity { ok = it.favoritesPopup.isShowing }; ok }
            Thread.sleep(800)
            // The predicted slot carries the mark; the others don't.
            home.onActivity { a ->
                val cells = a.favoritesPopup.cells
                fun icon(v: View) = v.findViewById<ImageView>(R.id.icon)
                assertNotNull("slot 6 has no mark", icon(cells[5]).foreground)
                assertTrue("slot 1 is marked", icon(cells[0]).foreground == null)
            }
            shell("screencap -p $dir/${prefix}_font1.0_20-predicted-slot.png")

            // Long-press slot 6: Pin prediction comes first.
            home.onActivity { it.favoritesPopup.cells[5].performLongClick() }
            waitFor("menu") { var ok = false; home.onActivity { ok = it.appMenu.isShowing }; ok }
            Thread.sleep(500)
            home.onActivity { a ->
                val first = a.appMenu.rowsView!!.getChildAt(0)
                assertEquals(a.getString(R.string.menu_pin_prediction), text(first))
            }
            shell("screencap -p $dir/${prefix}_font1.0_21-pin-prediction.png")
            home.onActivity { it.appMenu.rowsView!!.getChildAt(0).performClick() }
            waitFor("pinned") { app.prefs.predictionPinned }

            // Pinned: slot 6 is that app, fixed, and it's the manual 6th pick now.
            s = slots()
            assertEquals(predicted to FavoriteSlots.Kind.SLOT6_FIXED, s[5])
            assertEquals(manual.take(5) + predicted, app.prefs.manualFavorites)
            main { app.prefs.savePredictions(listOf(key("com.android.settings")), System.currentTimeMillis()) }
            assertEquals("a pinned slot stays put", predicted, slots()[5].first)

            // Long-press it again: Unpin, and the slot predicts again.
            home.onActivity { it.favoritesPopup.dismiss() }
            home.onActivity { it.findViewById<View>(R.id.searchIcon).performLongClick() }
            waitFor("favorites row again") { var ok = false; home.onActivity { ok = it.favoritesPopup.isShowing }; ok }
            Thread.sleep(500)
            home.onActivity { it.favoritesPopup.cells[5].performLongClick() }
            waitFor("menu again") { var ok = false; home.onActivity { ok = it.appMenu.isShowing }; ok }
            home.onActivity { a ->
                assertEquals(a.getString(R.string.menu_unpin_prediction), text(a.appMenu.rowsView!!.getChildAt(0)))
                a.appMenu.rowsView!!.getChildAt(0).performClick()
            }
            waitFor("unpinned") { !app.prefs.predictionPinned }
            predictNow()
            assertEquals(FavoriteSlots.Kind.PREDICTED, slots()[5].second)
        }

        // Off again: the six picks, the 6th being the pinned app (pinning replaced the old 6th).
        main { app.prefs.predictedSlot = false }
        assertEquals(manual.take(5) + predicted, slots().map { it.first })
        assertTrue(slots().all { it.second == FavoriteSlots.Kind.FAVORITE })

        // No usage access: slot 6 is the 6th pick, never empty.
        setManual(manual)
        main { app.prefs.predictedSlot = true }
        shell("appops set $pkg GET_USAGE_STATS ignore")
        main { app.autoFavorites.refreshPrediction() }
        waitFor("predictions cleared") { app.prefs.predictions.isEmpty() }
        assertEquals(manual[5] to FavoriteSlots.Kind.SLOT6_FIXED, slots()[5])

        // Automatic mode: the setting does nothing there.
        main { app.prefs.favoritesMode = FavoritesMode.AUTOMATIC }
        assertFalse(app.prefs.predictedSlotActive)
        assertTrue(slots().none { it.second == FavoriteSlots.Kind.PREDICTED || it.second == FavoriteSlots.Kind.SLOT6_FIXED })
    }
}
