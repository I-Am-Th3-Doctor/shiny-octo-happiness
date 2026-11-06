package io.github.iamth3doctor.searchlight

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.data.FavoritesMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings > Favorites, Manual mode: a removed favorite leaves an "Empty slot" with "+"; "+" picks
 * an app (never one that's already a favorite) for the first free slot; none at six; five at most
 * with the predicted 6th slot on; reordering still works.
 */
@RunWith(AndroidJUnit4::class)
class SettingsFavoriteSlotsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"

    private var savedMode = FavoritesMode.MANUAL
    private var savedFavorites = emptyList<String>()
    private var savedPredicted = false

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).use {
        java.io.FileInputStream(it.fileDescriptor).readBytes()
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out: $what")
    }

    private fun apps(): List<AppEntry> {
        var l = emptyList<AppEntry>()
        main { l = app.apps.apps.filter { !it.isSelf }.distinctBy { it.key } }
        return l
    }

    private fun setFavorites(keys: List<String>) = main {
        app.prefs.manualFavorites.forEach { app.prefs.removeFavorite(it) }
        keys.forEach { app.prefs.addFavorite(it) }
    }

    @Before fun setUp() {
        shell("mkdir -p $dir")
        waitFor("apps loaded") { apps().size > 7 }
        main {
            savedMode = app.prefs.favoritesMode
            savedFavorites = app.prefs.manualFavorites
            savedPredicted = app.prefs.predictedSlot
            app.prefs.favoritesMode = FavoritesMode.MANUAL
            app.prefs.predictedSlot = false
        }
    }

    @After fun restore() = main {
        app.prefs.predictedSlot = false
        app.prefs.manualFavorites.forEach { app.prefs.removeFavorite(it) }
        savedFavorites.forEach { app.prefs.addFavorite(it) }
        app.prefs.predictedSlot = savedPredicted
        app.prefs.favoritesMode = savedMode
    }

    // ---- reading the list ----

    private class Rows(val favorites: List<LinearLayout>, val empty: List<LinearLayout>)

    private fun rows(s: ActivityScenario<SettingsActivity>): Rows {
        var r = Rows(emptyList(), emptyList())
        s.onActivity { a ->
            val list = a.findViewById<ViewGroup>(R.id.favoritesList)
            val all = (0 until list.childCount).map(list::getChildAt).filterIsInstance<LinearLayout>()
            r = Rows(all.filter { it.tag != SettingsActivity.EMPTY_SLOT_TAG }, all.filter { it.tag == SettingsActivity.EMPTY_SLOT_TAG })
        }
        return r
    }

    private fun label(row: LinearLayout) = (0 until row.childCount).map(row::getChildAt).filterIsInstance<TextView>().first().text.toString()
    private fun buttons(row: LinearLayout) = (0 until row.childCount).map(row::getChildAt).filterIsInstance<ImageButton>()
    private fun button(row: LinearLayout, desc: Int) = buttons(row).first { it.contentDescription == app.getString(desc) }

    private fun settings(block: (ActivityScenario<SettingsActivity>) -> Unit) {
        val s = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            Thread.sleep(800)
            block(s)
        } finally {
            s.close()
        }
    }

    // ---- tests ----

    @Test fun removingLeavesAnEmptySlotThatPlusFills() {
        val a = apps()
        setFavorites(a.take(3).map { it.key })
        settings { s ->
            assertEquals(3, rows(s).favorites.size)
            assertEquals(3, rows(s).empty.size)
            // Every empty slot reads "Empty slot" and has a "+".
            for (e in rows(s).empty) {
                assertEquals(app.getString(R.string.settings_empty_slot), label(e))
                assertTrue(buttons(e).any { it.contentDescription == app.getString(R.string.settings_add_favorite) && it.isEnabled })
            }

            // Remove the middle one: its slot shows at once.
            val second = a[1]
            rows(s).let { r -> s.onActivity { button(r.favorites[1], R.string.remove).performClick() } }
            waitFor("removed") { rows(s).favorites.size == 2 && rows(s).empty.size == 4 }
            assertEquals(listOf(a[0].key, a[2].key), app.prefs.manualFavorites)

            // "+": the picker offers no current favorite.
            rows(s).let { r -> s.onActivity { button(r.empty[0], R.string.settings_add_favorite).performClick() } }
            var picker: android.app.AlertDialog? = null
            waitFor("picker") { s.onActivity { picker = it.favoritePicker }; picker?.isShowing == true }
            instrumentation.waitForIdleSync()
            var offered = emptyList<String>()
            s.onActivity { offered = (0 until picker!!.listView.adapter.count).map { i -> picker!!.listView.adapter.getItem(i).toString() } }
            val favoriteKeys = app.prefs.manualFavorites.toSet()
            assertEquals("everything but the favorites", a.count { it.key !in favoriteKeys }, offered.size)
            shell("screencap -p $dir/${prefix}_font1.0_90-favorites-add-picker.png")
            // Pick the one just removed: it fills the first empty slot (the end of the picks).
            val index = a.filter { it.key !in favoriteKeys }.sortedWith { x, y -> java.text.Collator.getInstance().compare(x.label, y.label) }
                .indexOfFirst { it.key == second.key }
            s.onActivity { picker!!.listView.performItemClick(null, index, index.toLong()) }
            waitFor("added") { rows(s).favorites.size == 3 && rows(s).empty.size == 3 }
            assertEquals(listOf(a[0].key, a[2].key, second.key), app.prefs.manualFavorites)
            // The new row is a full favorite row: icon, name, arrows and X.
            val added = rows(s).favorites[2]
            assertEquals(second.label, label(added))
            assertTrue((0 until added.childCount).map(added::getChildAt).any { it is ImageView && it !is ImageButton })
            assertTrue(buttons(added).map { it.contentDescription.toString() }.containsAll(
                listOf(app.getString(R.string.move_up), app.getString(R.string.move_down), app.getString(R.string.remove))))
            shell("screencap -p $dir/${prefix}_font1.0_91-favorites-empty-slots.png")

            // Reordering still works after the add.
            rows(s).let { r -> s.onActivity { button(r.favorites[2], R.string.move_up).performClick() } }
            waitFor("moved up") { app.prefs.manualFavorites == listOf(a[0].key, second.key, a[2].key) }
        }
    }

    @Test fun noneLeftMeansSixEmptySlotsAndSixMeansNoPlus() {
        val a = apps()
        setFavorites(emptyList())
        settings { s ->
            assertEquals(0, rows(s).favorites.size)
            assertEquals(6, rows(s).empty.size)
        }
        setFavorites(a.take(6).map { it.key })
        settings { s ->
            assertEquals(6, rows(s).favorites.size)
            assertEquals("no + at six", 0, rows(s).empty.size)
            var plus = false
            s.onActivity { act ->
                fun walk(v: View) {
                    if (v is ImageButton && v.contentDescription == app.getString(R.string.settings_add_favorite)) plus = true
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(act.findViewById(R.id.favoritesList))
            }
            assertFalse(plus)
        }
    }

    @Test fun thePredictedSixthSlotKeepsItsPlace() {
        val a = apps()
        setFavorites(a.take(4).map { it.key })
        settings { s ->
            assertEquals(2, rows(s).empty.size)
            // Predicted 6th on: slot 6 is the prediction's, so only slot 5 is offered.
            s.onActivity { it.findViewById<android.widget.Switch>(R.id.predictedSlotSwitch).isChecked = true }
            waitFor("predicted on") { app.prefs.predictedSlot && rows(s).empty.size == 1 }
            rows(s).let { r -> s.onActivity { button(r.empty[0], R.string.settings_add_favorite).performClick() } }
            var picker: android.app.AlertDialog? = null
            waitFor("picker") { s.onActivity { picker = it.favoritePicker }; picker?.isShowing == true }
            s.onActivity { picker!!.listView.performItemClick(null, 0, 0) }
            waitFor("filled to five") { app.prefs.manualFavorites.size == 5 && rows(s).empty.isEmpty() }
            // Off again: the sixth slot is free to fill.
            s.onActivity { it.findViewById<android.widget.Switch>(R.id.predictedSlotSwitch).isChecked = false }
            waitFor("predicted off") { !app.prefs.predictedSlot && rows(s).empty.size == 1 }
        }
    }
}
