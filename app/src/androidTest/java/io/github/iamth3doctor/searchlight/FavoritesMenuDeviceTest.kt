package io.github.iamth3doctor.searchlight

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.apps.FavoriteSlots
import io.github.iamth3doctor.searchlight.data.FavoritesMode
import io.github.iamth3doctor.searchlight.ui.homeFavorites
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Add to favorites" / "Remove from favorites" in the app long-press menu, opened from the search
 * results and from the app drawer, in Manual and Automatic mode, with the predicted 6th slot left
 * alone; the app's own rows (App info, Widgets) stay.
 */
@RunWith(AndroidJUnit4::class)
class FavoritesMenuDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val sp get() = app.getSharedPreferences("searchlight", 0)
    private var saved = emptyMap<String, Any?>()

    /** Runs [cmd] and waits for it to finish (a screenshot must be taken before the menu closes). */
    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitFor(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out: $what")
    }

    private val favoriteKeys = listOf("favorites", "favorites_mode", "predicted_slot", "auto_favorites", "auto_favorites_at", "auto_favorites_added")

    @Before fun setUp() {
        saved = favoriteKeys.associateWith { sp.all[it] }
        sp.edit().apply { favoriteKeys.forEach(::remove) }.commit()
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        waitFor("apps loaded") { var n = 0; main { n = app.apps.apps.size }; n > 3 }
    }

    @After fun restore() {
        sp.edit().apply {
            for ((k, v) in saved) when (v) {
                null -> remove(k)
                is String -> putString(k, v)
                is Boolean -> putBoolean(k, v)
                is Long -> putLong(k, v)
                is Int -> putInt(k, v)
            }
        }.commit()
    }

    private fun entry(pkgName: String): AppEntry {
        var e: AppEntry? = null
        main { e = app.apps.apps.firstOrNull { it.packageName == pkgName && !it.isSelf } }
        return e ?: throw AssertionError("$pkgName not installed")
    }

    /** Apps other than these to fill slots with. */
    private fun others(vararg not: AppEntry): List<AppEntry> {
        var l = emptyList<AppEntry>()
        main { l = app.apps.apps.filter { e -> !e.isSelf && not.none { it.key == e.key } } }
        return l
    }

    private fun find(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun longPress(from: View, label: String) {
        var v: View? = find(from, label) ?: throw AssertionError("'$label' not on screen")
        while (v != null && !v.isLongClickable) v = v.parent as? View
        v!!.performLongClick()
    }

    /** Long-presses [target] in the search results. */
    private fun menuFromSearch(home: ActivityScenario<MainActivity>, target: AppEntry) {
        home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText(target.label) }
        Thread.sleep(1200)
        instrumentation.waitForIdleSync()
        home.onActivity { a -> longPress(a.findViewById(R.id.resultsPanel), target.label) }
        waitFor("menu") { var open = false; home.onActivity { open = it.appMenu.isShowing }; open }
        Thread.sleep(500)
    }

    /** Long-presses [target] in the app drawer. */
    private fun menuFromDrawer(home: ActivityScenario<MainActivity>, target: AppEntry) {
        home.onActivity { it.openAppTable() }
        Thread.sleep(1500)
        // Scroll the drawer until the app's cell is on screen.
        for (i in 0 until 30) {
            var found = false
            home.onActivity { a ->
                val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.appTableList)
                found = find(list, target.label) != null
                if (!found) list.scrollBy(0, list.height / 2)
            }
            instrumentation.waitForIdleSync()
            if (found) break
            Thread.sleep(200)
        }
        home.onActivity { a -> longPress(a.findViewById(R.id.appTableList), target.label) }
        waitFor("menu") { var open = false; home.onActivity { open = it.appMenu.isShowing }; open }
        Thread.sleep(500)
    }

    private fun rows(home: ActivityScenario<MainActivity>): List<String> {
        var texts = emptyList<String>()
        home.onActivity { a ->
            val rows = a.appMenu.rowsView!!
            texts = (0 until rows.childCount).mapNotNull { i ->
                val row = rows.getChildAt(i) as? ViewGroup ?: return@mapNotNull null
                (0 until row.childCount).map(row::getChildAt).filterIsInstance<TextView>().firstOrNull()?.text?.toString()
            }
        }
        return texts
    }

    private fun tapRow(home: ActivityScenario<MainActivity>, label: String) {
        home.onActivity { a -> (find(a.appMenu.rowsView!!, label)!!.parent as View).performClick() }
        Thread.sleep(500)
        home.onActivity { it.appMenu.dismiss() }
        Thread.sleep(300)
    }

    private val add get() = app.getString(R.string.menu_add_favorite)
    private val remove get() = app.getString(R.string.menu_remove_favorite)

    /** The keys the favorites row shows, slot by slot (null = empty), and slot 6's kind. */
    private fun row(): List<Pair<String?, FavoriteSlots.Kind?>> {
        var s = emptyList<Pair<String?, FavoriteSlots.Kind?>>()
        main { s = homeFavorites(app.prefs, app.apps).map { it?.entry?.key to it?.kind } }
        return s
    }

    @Test fun addFromSearchThenRemove() {
        val target = entry("com.android.settings")
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            menuFromSearch(home, target)
            val before = rows(home)
            shell("screencap -p $dir/${prefix}_font1.0_36-menu-add-favorite.png")
            assertTrue("rows: $before", add in before)
            assertTrue("rows: $before", app.getString(R.string.menu_app_info) in before)
            tapRow(home, add)
            assertTrue(row().toString(), row().any { it.first == target.key })
            assertEquals("first free slot", target.key, row()[0].first)

            // Already a favorite: Remove instead, and it takes it off.
            home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("") }
            Thread.sleep(500)
            menuFromSearch(home, target)
            val after = rows(home)
            assertTrue("rows: $after", remove in after && add !in after)
            assertEquals("same other rows", before - add, after - remove)
            tapRow(home, remove)
            assertFalse(row().toString(), row().any { it.first == target.key })
        }
    }

    @Test fun addFromTheDrawerKeepsTheAppsOwnRows() {
        val target = entry("com.android.chrome")
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            menuFromDrawer(home, target)
            val texts = rows(home)
            shell("screencap -p $dir/${prefix}_font1.0_37-drawer-menu-favorite.png")
            assertTrue("rows: $texts", add in texts && app.getString(R.string.menu_app_info) in texts)
            // Chrome's own shortcuts (when it publishes any) come before App info, untouched.
            var shortcuts = emptyList<String>()
            main { shortcuts = app.shortcuts.menuFor(target).shortcuts.map { it.label.toString() } }
            assertEquals("rows: $texts", shortcuts, texts.take(shortcuts.size))
            var hasWidgets = false
            main { hasWidgets = app.shortcuts.menuFor(target).hasWidgets }
            if (hasWidgets) assertTrue("rows: $texts", app.getString(R.string.menu_widgets) in texts)
            tapRow(home, add)
            assertEquals(target.key, row()[0].first)
        }
    }

    @Test fun aManualAddNeverTakesThePredictedSlot() {
        val target = entry("com.android.settings")
        val rest = others(target).map { it.key } // (not inside main {}: it runs on the main thread itself)
        val five = rest.take(5)
        main {
            app.prefs.predictedSlot = true
            five.forEach { app.prefs.addFavorite(it) }
            app.prefs.savePredictions(listOf(target.key) + rest.drop(5), System.currentTimeMillis())
        }
        val beforeRow = row()
        assertEquals(FavoriteSlots.Kind.PREDICTED, beforeRow[5].second)
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            menuFromSearch(home, target)
            assertTrue(add in rows(home))
            tapRow(home, add) // five manual picks + the prediction: full
        }
        assertEquals("not added: the five manual slots are full", five, app.prefs.manualFavorites)
        assertEquals(beforeRow.take(5), row().take(5))
        // Slot 6 is still the prediction's (never a manual pick), whatever it predicts now.
        assertTrue(row()[5].toString(), row()[5].second == FavoriteSlots.Kind.PREDICTED || row()[5].second == null)
    }

    @Test fun addWorksInAutomaticMode() {
        val target = entry("com.android.settings")
        val ranked = others(target).take(6).map { it.key }
        shell("appops set $pkg GET_USAGE_STATS allow") // Automatic needs usage access to show
        main {
            app.prefs.favoritesMode = FavoritesMode.AUTOMATIC
            app.prefs.saveAutoFavorites(ranked, System.currentTimeMillis())
        }
        assertTrue(app.prefs.autoFavoritesActive)
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            menuFromSearch(home, target)
            assertTrue("rows: ${rows(home)}", add in rows(home))
            tapRow(home, add)
            assertEquals("added first, ahead of the ranking", target.key, row()[0].first)
            assertEquals("manual picks untouched", emptyList<String>(), app.prefs.manualFavorites)

            home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("") }
            Thread.sleep(500)
            menuFromSearch(home, target)
            assertTrue("rows: ${rows(home)}", remove in rows(home))
            tapRow(home, remove)
            assertFalse(app.prefs.autoAdded.toString(), target.key in app.prefs.autoAdded)
        }
        shell("appops set $pkg GET_USAGE_STATS default")
    }
}
