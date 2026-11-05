package io.github.iamth3doctor.searchlight

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.data.FavoritesMode
import io.github.iamth3doctor.searchlight.search.SearchEngine
import io.github.iamth3doctor.searchlight.search.SearchResult
import io.github.iamth3doctor.searchlight.ui.homeFavorites
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Automatic favorites always include the default Phone and Messaging apps, wherever their usage
 * puts them among the six; a changed default follows on the next return home; Manual is untouched.
 */
@RunWith(AndroidJUnit4::class)
class AutoDefaultsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private var savedMode = FavoritesMode.MANUAL

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitFor(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(250)
        }
        throw AssertionError("timed out: $what")
    }

    @Before fun setUp() {
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        shell("appops set $pkg GET_USAGE_STATS allow")
        waitFor("apps loaded") { var n = 0; main { n = app.apps.apps.size }; n > 6 }
        main { savedMode = app.prefs.favoritesMode }
    }

    @After fun restore() {
        shell("input keyevent KEYCODE_HOME")
        app.autoFavorites.defaultsOverride = null
        main {
            app.prefs.saveAutoFavorites(emptyList(), 0)
            app.prefs.favoritesMode = savedMode
        }
        shell("appops set $pkg GET_USAGE_STATS default")
    }

    private fun personalApps(): List<AppEntry> {
        var l = emptyList<AppEntry>()
        main { l = app.apps.apps.filter { !it.isSelf && it.user == android.os.Process.myUserHandle() } }
        return l
    }

    /** Several apps opened a few times each (none of them a dialer or messaging app), so seven-plus rank. */
    private fun useOtherApps(avoid: Set<String>) {
        // Not Settings or the Clock: AutoFavoritesDeviceTest compares their launches (30 days of history).
        val keepOut = avoid + setOf("com.android.settings", "com.google.android.deskclock")
        // Plain launchable apps only (not the SIM toolkit or an icon pack), started with am start:
        // monkey would also inject a random event, a system key included.
        val skip = setOf("com.android.stk", "com.donnnno.arcticons", "io.github.iamth3doctor.testiconpack")
        val picks = personalApps().filter { it.packageName !in keepOut && it.packageName !in skip }.distinctBy { it.packageName }.take(8)
        repeat(2) {
            for (p in picks) {
                shell("am start -W -n ${p.component.flattenToShortString()}")
                Thread.sleep(1200)
                shell("input keyevent KEYCODE_HOME")
                Thread.sleep(400)
            }
        }
    }

    /** Ranks now, in Automatic, and waits for it. */
    private fun rankNow() {
        main {
            app.prefs.favoritesMode = FavoritesMode.AUTOMATIC
            app.prefs.saveAutoFavorites(emptyList(), 0)
            app.autoFavorites.refresh()
        }
        waitFor("ranking") { var at = 0L; main { at = app.prefs.autoFavoritesAt }; at > 0 }
    }

    /** What the favorites row shows, app by app. */
    private fun row(): List<String> {
        var keys = emptyList<String>()
        main { keys = homeFavorites(app.prefs, app.apps).mapNotNull { it?.entry?.packageName } }
        return keys
    }

    @Test fun theRealDefaultPhoneAndMessagingAppsAreInTheSix() {
        val defaults = app.autoFavorites.defaultPackages()
        val installed = defaults.filter { d -> personalApps().any { it.packageName == d } }
        assertTrue("no default phone or messaging app on this device: $defaults", installed.isNotEmpty())
        useOtherApps(avoid = defaults.toSet())
        rankNow()
        val six = row()
        assertEquals("a full row", 6, six.size)
        for (d in installed) assertTrue("$d not in the row: $six", d in six)
        // Each once, and no marker of any kind: they're plain favorites.
        assertEquals(six, six.distinct())
    }

    @Test fun rarelyUsedDefaultsJoinFloatAndFollowAChange() {
        // The plain usage ranking first (no defaults), to find apps the six leave out.
        app.autoFavorites.defaultsOverride = emptyList()
        useOtherApps(avoid = app.autoFavorites.defaultPackages().toSet())
        rankNow()
        val plain = row()
        assertEquals(6, plain.size)
        val outside = personalApps().map { it.packageName }.distinct().filter { it !in plain }
        assertTrue("need three apps outside the six: $outside", outside.size >= 3)
        val (dialer, sms, other) = Triple(outside[0], outside[1], outside[2])

        // Rarely used "default dialer" and "default SMS app": both join the six, nothing is pinned.
        app.autoFavorites.defaultsOverride = listOf(dialer, sms)
        rankNow()
        var six = row()
        assertTrue("$dialer and $sms in $six", dialer in six && sms in six)
        // The rest are the plain ranking's best four, in usage order (the defaults, unused, last).
        assertEquals(plain.take(4), six.filter { it != dialer && it != sms })
        // Displaced apps are still apps: in the list, and found by search.
        val displaced = plain.filter { it !in six }
        assertEquals(2, displaced.size)
        for (p in displaced) {
            val label = personalApps().first { it.packageName == p }.label
            var found = false
            main {
                found = SearchEngine(app.prefs, app.apps, app.contacts, app.currency, object : SearchEngine.Format {
                    override fun unitDetail(query: String) = query
                    override fun currencyDetail(rate: String, from: String, to: String, date: String) = rate
                    override fun currencyPending(query: String) = query
                }).local(label).any { it is SearchResult.App && it.entry.packageName == p }
            }
            assertTrue("$label searchable", found)
        }

        // The default dialer changes: on the next return home the row follows it.
        app.autoFavorites.defaultsOverride = listOf(other, sms)
        main { app.autoFavorites.refreshIfStale() } // what returning home does
        waitFor("re-ranked for the new default") { other in row() }
        six = row()
        assertTrue("$other in $six", other in six && sms in six)
        assertFalse("old default $dialer dropped: $six", dialer in six)

        // One app the default for both: one place, the other five by usage.
        app.autoFavorites.defaultsOverride = listOf(dialer, dialer)
        rankNow()
        six = row()
        assertEquals(1, six.count { it == dialer })
        assertEquals(plain.take(5), six.filter { it != dialer })

        // No default at all: plain usage.
        app.autoFavorites.defaultsOverride = emptyList()
        rankNow()
        assertEquals(plain, row())
    }

    @Test fun manualModeIsUntouched() {
        val picks = personalApps().take(3).map { it.key }
        main {
            app.prefs.favoritesMode = FavoritesMode.MANUAL
            app.prefs.manualFavorites.forEach { app.prefs.removeFavorite(it) }
            picks.forEach { app.prefs.addFavorite(it) }
        }
        app.autoFavorites.defaultsOverride = listOf(personalApps().last().packageName, null)
        main { app.autoFavorites.refreshIfStale() }
        Thread.sleep(1500)
        var shown = emptyList<String>()
        main { shown = app.prefs.favorites }
        assertEquals("manual picks only", picks, shown)
        main { picks.forEach { app.prefs.removeFavorite(it) } }
    }
}
