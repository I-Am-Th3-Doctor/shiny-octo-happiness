package io.github.iamth3doctor.searchlight

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.View
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.iconpack.IconMotion
import io.github.iamth3doctor.searchlight.iconpack.IconTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/**
 * Searchlight's own icon theme: its mapping and art, its icons on home, in the drawer, in the
 * favorites and in search (and the system's icon for apps it doesn't map), off and on again, the
 * live clock and calendar, and the icons coming alive without ever holding up a tap.
 */
@RunWith(AndroidJUnit4::class)
class IconThemeDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val theme get() = app.iconThemes.theme

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    private fun shot(name: String) = shell("screencap -p $dir/${prefix}_font1.0_$name.png")

    private fun <T> main(block: () -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST") return out as T
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (main(check)) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out: $what")
    }

    private fun entry(pkg: String): AppEntry =
        checkNotNull(main { app.apps.apps.firstOrNull { it.packageName == pkg && !it.isSelf } }) { "$pkg isn't installed" }

    /** The icon the launcher draws for [e] right now. */
    private fun icon(e: AppEntry): Bitmap {
        var bmp: Bitmap? = null
        instrumentation.runOnMainSync { bmp = app.apps.icon(e) { bmp = it } }
        waitFor("icon for ${e.label}") { bmp != null }
        return bmp!!
    }

    private fun setTheme(on: Boolean) = main { app.iconThemes.enabled = on }

    @Before fun setUp() {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME ${app.packageName}")
        waitFor("apps loaded") { app.apps.apps.isNotEmpty() }
        main { app.iconPacks.select(null) {} }
        setTheme(true)
    }

    @After fun tearDown() {
        IconMotion.forceMotion = null
        app.iconThemes.powerSaveOverride = null
        setTheme(true)
    }

    @Test fun theMappingNamesEveryStarterIconAndEachIsA192pxPng() {
        val files = theme.files
        assertEquals(setOf("phone.png", "messages.png", "gmail.png", "brave.png", "quetta.png", "calendar.png", "clock.png", "camera.png",
            "photos.png", "maps.png", "spotify.png", "youtube.png", "canva.png", "settings.png"), files)
        for (f in files) {
            val b = app.assets.open("icontheme/$f").use { BitmapFactory.decodeStream(it) }
            assertNotNull(f, b)
            assertEquals("$f width", 192, b.width)
            assertEquals("$f height", 192, b.height)
            assertEquals("$f has a clear background", 0, Color.alpha(b.getPixel(1, 1)))
            assertTrue("$f is drawn", (0 until 192 step 8).sumOf { y -> (0 until 192 step 8).count { x -> Color.alpha(b.getPixel(x, y)) > 200 } } > 100)
        }
        // At least three icons have a signature move of their own.
        val signatures = theme.icons.values.filter { it.move.signature }.map { it.file }.toSet()
        assertTrue("signature moves: $signatures", signatures.size >= 3)
        assertEquals(IconTheme.Live.CLOCK, theme.icons["com.google.android.deskclock"]!!.live)
        assertEquals(IconTheme.Live.CALENDAR, theme.icons["com.google.android.calendar"]!!.live)
        // The format: package or exact component, notes, a line that doesn't read is skipped.
        val parsed = IconTheme.parse("""
            # a note
            com.example.a   a.png   spin
            com.example.b/.Main  b.png  ring  clock   # the exact component
            not a line
            com.example.c   c.png   wobble
        """.trimIndent())
        assertEquals(setOf("com.example.a", "com.example.b/com.example.b.Main", "com.example.c"), parsed.keys)
        assertEquals(IconTheme.Move.RING, parsed["com.example.b/com.example.b.Main"]!!.move)
        assertEquals(IconTheme.Live.CLOCK, parsed["com.example.b/com.example.b.Main"]!!.live)
        assertEquals("an unknown move: a gentle bounce", IconTheme.Move.BOUNCE, parsed["com.example.c"]!!.move)
    }

    @Test fun themedIconsEverywhereUnmappedKeepTheirOwnAndOffRestoresThemAll() {
        val settings = entry("com.android.settings")
        val youtube = entry("com.google.android.youtube")
        val unmapped = checkNotNull(main { app.apps.apps.firstOrNull { !it.isSelf && theme.find(it.component) == null } }) { "every app is themed" }
        assertNotNull(main { app.apps.themedIcon(settings) })
        assertNull(main { app.apps.themedIcon(unmapped) })
        val themedSettings = icon(settings)
        val unmappedOn = icon(unmapped)
        assertTrue("the theme's settings art", themedSettings.sameAs(theme.render(theme.find(settings.component)!!, themedSettings.width, 0L, false)))

        // Off: every icon the system's.
        setTheme(false)
        assertNull(main { app.apps.themedIcon(settings) })
        val systemSettings = icon(settings)
        assertFalse("off: the system's settings icon", systemSettings.sameAs(themedSettings))
        assertTrue("an unmapped app keeps its own icon either way", icon(unmapped).sameAs(unmappedOn))
        assertTrue(main { app.iconThemes.themedCount() } >= 4)

        // On again: in the drawer, the favorites and search results.
        setTheme(true)
        favorites(settings, youtube, entry("com.google.android.deskclock"), entry("com.google.android.calendar"))
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(2000)
            home.onActivity { it.openAppTable() }
            waitFor("themed icons in the drawer") { IconMotion.onScreen().isNotEmpty() }
            // Each themed icon there is the theme's own art.
            assertTrue("the drawer shows the theme's art", main {
                IconMotion.onScreen().all { (iv, ic) ->
                    val shown = (iv.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    shown == null || ic.live != null || shown.sameAs(theme.render(ic, shown.width, 0L, false))
                }
            })
            Thread.sleep(1500)
            shot("150-icon-theme-drawer")
            @Suppress("DEPRECATION") home.onActivity { it.onBackPressed() }
            Thread.sleep(800)
            home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("Settings") }
            waitFor("themed settings in search") { IconMotion.onScreen().any { it.second.file == "settings.png" } }
            Thread.sleep(800)
            shot("151-icon-theme-search")
            home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("") }
            Thread.sleep(800)
            home.onActivity { it.findViewById<View>(R.id.searchIcon).performLongClick() }
            waitFor("themed favorites") { IconMotion.onScreen().map { it.second.file }.containsAll(listOf("settings.png", "clock.png", "calendar.png")) }
            Thread.sleep(1500)
            shot("152-icon-theme-favorites")
        }
    }

    @Test fun theClockShowsTheTimeAndTheCalendarToday() {
        val clock = entry("com.google.android.deskclock")
        val calendar = entry("com.google.android.calendar")
        val clockIcon = theme.find(clock.component)!!
        val calIcon = theme.find(calendar.component)!!
        val size = 192
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 40) }
        val at340 = theme.render(clockIcon, size, c.timeInMillis, still = false)!!
        val at915 = theme.render(clockIcon, size, c.apply { set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 15) }.timeInMillis, still = false)!!
        assertFalse("the hands move", at340.sameAs(at915))
        assertEquals(IconTheme.handAngles(9, 15), (9 * 30.0 + 7.5) to 90.0)
        // Held still: 10:10, whatever the time.
        val at1010 = theme.render(clockIcon, size, c.apply { set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 10) }.timeInMillis, still = false)!!
        assertTrue(theme.render(clockIcon, size, System.currentTimeMillis(), still = true)!!.sameAs(at1010))
        // What the launcher shows: the time now (this minute or the one before, if it turned meanwhile).
        val now = System.currentTimeMillis()
        main { IconMotion.refresh { it.live != null } }
        main { app.apps.forgetIcon(clock) }
        val shown = icon(clock)
        assertTrue("the clock icon shows the time now", listOf(now, now + 60_000, now - 60_000).any { t ->
            theme.render(clockIcon, shown.width, t, still = false)!!.sameAs(shown) || theme.render(clockIcon, shown.width, t, still = true)!!.sameAs(shown)
        })

        // The calendar: today's date, and a different day looks different.
        val today = Calendar.getInstance()
        assertEquals(today.get(Calendar.DAY_OF_MONTH).toString(), IconTheme.dateText(today.timeInMillis).second)
        val tomorrow = today.timeInMillis + 24 * 3_600_000L
        assertFalse(theme.render(calIcon, size, today.timeInMillis, false)!!.sameAs(theme.render(calIcon, size, tomorrow, false)!!))
        main { app.apps.forgetIcon(calendar) }
        val cal = icon(calendar)
        assertTrue("the calendar icon shows today", cal.sameAs(theme.render(calIcon, cal.width, System.currentTimeMillis(), false)!!))

        // Battery Saver: the clock holds at 10:10.
        app.iconThemes.powerSaveOverride = true
        main { app.iconThemes.timeMoved(dateMayHaveChanged = false) }
        main { app.apps.forgetIcon(clock) }
        assertTrue("held still at 10:10", icon(clock).sameAs(theme.render(clockIcon, shown.width, 0L, still = true)!!))
    }

    @Test fun iconsComeAliveWithoutHoldingUpATapAndPauseWhenAsked() {
        val settings = entry("com.android.settings")
        favorites(settings, entry("com.google.android.deskclock"), entry("com.google.android.youtube"), entry("com.google.android.calendar"))
        IconMotion.forceMotion = true
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            home.onActivity { it.findViewById<View>(R.id.searchIcon).performLongClick() }
            waitFor("themed favorites on screen") { IconMotion.onScreen().size >= 3 }
            // Home opening: each themed icon on screen makes its move.
            val before = IconMotion.played.size
            assertTrue(main { IconMotion.homeOpened() } >= 3)
            waitFor("they move") { IconMotion.onScreen().any { IconMotion.moving(it.first) } }
            Thread.sleep(250)
            shot("153-icon-theme-alive")
            waitFor("all moved", 6_000) { IconMotion.played.size >= before + 3 }
            // Their own moves, from the theme: the clock rings, settings turns a notch.
            val moves = IconMotion.played.drop(before).toMap()
            assertEquals(IconTheme.Move.GEAR, moves[settings.key])
            assertEquals(IconTheme.Move.RING, moves[entry("com.google.android.deskclock").key])
            waitFor("settled", 5_000) { IconMotion.onScreen().none { IconMotion.moving(it.first) } }
            assertTrue("in place again", main { IconMotion.onScreen().all { (iv, _) -> iv.rotation == 0f && iv.translationY == 0f && iv.scaleX == 1f && iv.rotationY == 0f } })

            // Now and then: one or two at a time, never all.
            val n = main { IconMotion.nudge() }
            assertTrue("$n", n in 1..2)
            waitFor("settled", 5_000) { IconMotion.onScreen().none { IconMotion.moving(it.first) } }

            // Paused: Battery Saver, or the system's animations off.
            IconMotion.forceMotion = null
            app.iconThemes.powerSaveOverride = true
            assertTrue(IconMotion.paused)
            assertEquals(0, main { IconMotion.homeOpened() })
            assertEquals(0, main { IconMotion.nudge() })
            app.iconThemes.powerSaveOverride = false
            assertEquals(!android.animation.ValueAnimator.areAnimatorsEnabled(), IconMotion.paused)

            // A tap mid-move opens the app at once.
            IconMotion.forceMotion = true
            val (iv, _) = main { IconMotion.onScreen().first { it.second.file == "settings.png" } }
            main { IconMotion.play(iv, IconTheme.Move.RECORD) }
            assertTrue(main { IconMotion.moving(iv) })
            val target = main { generateSequence(iv as View) { it.parent as? View }.first { it.hasOnClickListeners() } }
            val t0 = System.currentTimeMillis()
            main { target.performClick() }
            waitFor("the app opens", 3_000) { home.state != Lifecycle.State.RESUMED }
            assertTrue("opened in ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2_000)
        }
        shell("input keyevent KEYCODE_HOME")
    }

    /** The favorites row: exactly [apps] (manual mode, nothing else on it). */
    private fun favorites(vararg apps: AppEntry) = main {
        app.prefs.favoritesMode = io.github.iamth3doctor.searchlight.data.FavoritesMode.MANUAL
        app.prefs.predictedSlot = false
        app.prefs.manualFavorites.forEach { app.prefs.removeFavorite(it) }
        apps.forEach { app.prefs.addFavorite(it.key) }
    }
}
