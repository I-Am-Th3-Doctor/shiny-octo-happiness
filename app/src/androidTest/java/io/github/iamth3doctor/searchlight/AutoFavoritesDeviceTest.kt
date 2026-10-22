package io.github.iamth3doctor.searchlight

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.data.FavoritesMode
import io.github.iamth3doctor.searchlight.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Automatic favorites against the real UsageStatsManager: apps you open more rank higher, the
 * manual six come back without usage access, and switching back to Manual restores them.
 */
@RunWith(AndroidJUnit4::class)
class AutoFavoritesDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"

    private fun shell(cmd: String) =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { java.io.FileInputStream(it.fileDescriptor).readBytes() }

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            instrumentation.runOnMainSync { ok = check() }
            if (ok) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out: $what")
    }

    @After fun manualAgain() {
        shell("appops set $pkg GET_USAGE_STATS default")
        instrumentation.runOnMainSync {
            app.prefs.favoritesMode = FavoritesMode.MANUAL
            app.prefs.saveAutoFavorites(emptyList(), 0)
        }
    }

    @Test fun ranksByLaunchesAndFallsBackToManual() {
        shell("cmd package set-home-activity $pkg/.MainActivity")
        waitFor("apps loaded") { app.apps.apps.isNotEmpty() }
        val settings = app.apps.apps.first { it.packageName == "com.android.settings" }
        val clock = app.apps.apps.firstOrNull { it.packageName == "com.google.android.deskclock" }
        var manual = emptyList<String>()
        instrumentation.runOnMainSync {
            if (app.prefs.manualFavorites.isEmpty()) app.apps.apps.filter { !it.isSelf }.takeLast(3).forEach { app.prefs.addFavorite(it.key) }
            manual = app.prefs.manualFavorites
        }

        // Settings three times, the clock once, going home in between (each is a launch).
        repeat(3) {
            shell("am start -W -n ${settings.component.flattenToShortString()}")
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(500)
        }
        clock?.let {
            shell("am start -W -n ${it.component.flattenToShortString()}")
            shell("input keyevent KEYCODE_HOME")
        }
        Thread.sleep(1000)

        // Automatic without usage access: nothing to rank, the manual six stay.
        shell("appops set $pkg GET_USAGE_STATS ignore")
        instrumentation.runOnMainSync {
            app.prefs.favoritesMode = FavoritesMode.AUTOMATIC
            app.autoFavorites.refreshIfStale()
            assertFalse(app.autoFavorites.hasAccess())
            assertFalse(app.prefs.autoFavoritesActive)
            assertEquals(manual, app.prefs.favorites)
        }

        // Granted: ranked from usage, Settings above the clock; manual picks fill any gaps.
        shell("appops set $pkg GET_USAGE_STATS allow")
        instrumentation.runOnMainSync { app.autoFavorites.refreshIfStale() }
        waitFor("ranking") { app.prefs.autoFavoritesActive }
        instrumentation.runOnMainSync {
            val shown = app.prefs.favorites.mapNotNull(app.apps::find).take(Prefs.MAX_FAVORITES).map { it.key }
            assertTrue("settings in $shown", settings.key in shown)
            if (clock != null && clock.key in shown) assertTrue(shown.indexOf(settings.key) < shown.indexOf(clock.key))
            assertFalse("Searchlight itself in $shown", shown.any { app.apps.find(it)?.isSelf == true })
            assertEquals(manual, app.prefs.manualFavorites) // untouched
        }

        // What the popup shows in Automatic.
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            home.onActivity { it.findViewById<View>(R.id.searchIcon).performLongClick() }
            Thread.sleep(800)
            instrumentation.waitForIdleSync()
            shell("mkdir -p /sdcard/Download/searchlight-shots")
            shell("screencap -p /sdcard/Download/searchlight-shots/${prefix}_font1.0_12-favorites-automatic.png")
        }

        // Usage access revoked later: back to the manual six on the next check.
        shell("appops set $pkg GET_USAGE_STATS ignore")
        instrumentation.runOnMainSync {
            app.autoFavorites.refreshIfStale()
            assertFalse(app.prefs.autoFavoritesActive)
            assertEquals(manual, app.prefs.favorites)
        }

        // Manual again: the picks are exactly as they were.
        instrumentation.runOnMainSync {
            app.prefs.favoritesMode = FavoritesMode.MANUAL
            assertEquals(manual, app.prefs.favorites)
        }
    }
}
