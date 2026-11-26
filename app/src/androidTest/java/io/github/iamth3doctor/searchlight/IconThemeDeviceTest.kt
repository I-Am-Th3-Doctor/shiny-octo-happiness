package io.github.iamth3doctor.searchlight

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.iconpack.IconMotion
import io.github.iamth3doctor.searchlight.iconpack.IconTheme
import io.github.iamth3doctor.searchlight.iconpack.LiveIcons
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/**
 * Searchlight's own icon theme: it ships with no icons (every app its system icon, bar the live
 * clock and calendar); a temporary icon proves the mapping themes its app in the drawer, search
 * and the favorites on home, and goes again; the live clock and calendar; every icon coming alive
 * with its app's move, pausing when asked and never holding up a tap; and an icon pack under the
 * same system.
 */
@RunWith(AndroidJUnit4::class)
class IconThemeDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val testPack = "io.github.iamth3doctor.searchlight.testiconpack"
    private val theme get() = app.iconThemes.theme
    private val generic = setOf(IconTheme.Move.SPIN, IconTheme.Move.SWING, IconTheme.Move.BOUNCE, IconTheme.Move.FLIP)

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

    /** The apps whose icons are on screen. Main thread. */
    private fun shownEntries() = IconMotion.onScreen().mapNotNull { IconMotion.entryOf(it.first) }
    private fun shownApps() = shownEntries().map { it.packageName }

    /** The icon view on screen showing [pkg]. Main thread. */
    private fun shownView(pkg: String): ImageView? = IconMotion.onScreen().map { it.first }.firstOrNull { IconMotion.entryOf(it)?.packageName == pkg }

    private fun ImageView.bitmap() = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap

    /** The favorites row: exactly [apps] (manual mode, nothing else on it). */
    private fun favorites(vararg apps: AppEntry) = main {
        app.prefs.favoritesMode = io.github.iamth3doctor.searchlight.data.FavoritesMode.MANUAL
        app.prefs.predictedSlot = false
        app.prefs.manualFavorites.forEach { app.prefs.removeFavorite(it) }
        apps.forEach { app.prefs.addFavorite(it.key) }
    }

    @Before fun setUp() {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME ${app.packageName}")
        waitFor("apps loaded") { app.apps.apps.isNotEmpty() }
        main { app.iconPacks.select(null) {}; app.iconThemes.useTheme(null) }
        setTheme(true)
    }

    @After fun tearDown() {
        IconMotion.forceMotion = null
        app.iconThemes.powerSaveOverride = null
        main { app.iconPacks.select(null) {}; app.iconThemes.useTheme(null) }
        setTheme(true)
    }

    @Test fun theThemeShipsEmptyAndTheToggleAndCountWork() {
        // No icons of its own: only the mapping in the folder.
        assertEquals(listOf("theme.txt"), app.assets.list("icontheme")!!.toList())
        assertEquals(emptySet<String>(), theme.files)
        // Signature moves for at least three apps (Phone, Camera, Clock), keyed by the app.
        assertEquals(IconTheme.Move.RING, theme.icons["com.google.android.dialer"]!!.move)
        assertEquals(IconTheme.Move.SHUTTER, theme.icons["com.android.camera2"]!!.move)
        assertEquals(IconTheme.Move.RING, theme.icons["com.google.android.deskclock"]!!.move)
        assertTrue(theme.icons.values.map { it.move }.filter { it.signature }.toSet().size >= 3)
        assertEquals(IconTheme.Live.CLOCK, theme.icons["com.google.android.deskclock"]!!.live)
        assertEquals(IconTheme.Live.CALENDAR, theme.icons["com.google.android.calendar"]!!.live)
        // The format: package or exact component, a move with no art, notes, a line that won't read.
        val parsed = IconTheme.parse("""
            # a note
            com.example.a   a.png   spin
            com.example.b/.Main  -  ring  clock   # the exact component, live
            not a line
            com.example.c   c.png   wobble
        """.trimIndent())
        assertEquals(setOf("com.example.a", "com.example.b/com.example.b.Main", "com.example.c"), parsed.keys)
        assertNull(parsed["com.example.b/com.example.b.Main"]!!.file)
        assertEquals(IconTheme.Live.CLOCK, parsed["com.example.b/com.example.b.Main"]!!.live)
        assertEquals("an unknown move: a gentle bounce", IconTheme.Move.BOUNCE, parsed["com.example.c"]!!.move)

        // Every app but the live clock and calendar shows its system icon, theme on or off.
        val apps = main { app.apps.apps.filter { !it.isSelf } }
        val on = apps.associate { it.key to icon(it) }
        val live = apps.filter { theme.find(it.component)?.live != null }
        assertEquals("themed: the live clock and calendar", live.map { it.packageName }.distinct().size, main { app.iconThemes.themedCount() })
        setTheme(false)
        assertEquals("off: nothing themed", 0, main { app.iconThemes.themedCount() })
        for (e in apps) {
            val off = icon(e)
            if (e in live) assertFalse("${e.label}: off, its system icon", off.sameAs(on[e.key]))
            else assertTrue("${e.label}: its system icon either way", off.sameAs(on[e.key]))
            assertTrue("${e.label} isn't blank", (0 until off.height step 4).any { y -> (0 until off.width step 4).any { x -> Color.alpha(off.getPixel(x, y)) > 32 } })
        }
        setTheme(true)
    }

    @Test fun aTemporaryIconThemesItsAppEverywhereThenGoes() {
        // A temporary test icon, as if dropped in the folder with one line added.
        val art = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = 0xFF2E7D5B.toInt()
            c.drawCircle(96f, 96f, 84f, p)
            p.color = 0xFFF2C14E.toInt()
            c.drawCircle(96f, 80f, 30f, p)
        }
        val bundled = app.assets.open("icontheme/theme.txt").bufferedReader().use { it.readText() }
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(2000)
            // The drawer: the app at its top gets the test icon.
            home.onActivity { it.openAppTable() }
            waitFor("the drawer's icons") { shownEntries().any { e -> theme.find(e.component)?.live == null } }
            val target = main { shownEntries().first { e -> theme.find(e.component)?.live == null } }
            val system = icon(target)
            val temp = IconTheme.of("$bundled\n${target.packageName}   test_icon.png   gear\n") { f -> if (f == "test_icon.png") art else null }
            main { app.iconThemes.useTheme(temp) }
            val themed = icon(target)
            assertTrue("the test icon", themed.sameAs(Bitmap.createScaledBitmap(art, themed.width, themed.height, true)))
            assertFalse(themed.sameAs(system))
            assertTrue("counted", main { app.iconThemes.themedCount() } >= 1 + main { app.apps.apps.filter { !it.isSelf && theme.find(it.component)?.live != null }.map { it.packageName }.distinct().size })
            assertEquals("the app's own move", IconTheme.Move.GEAR, main { app.apps.moveFor(target) })
            waitFor("the drawer shows the test icon") { shownView(target.packageName)?.bitmap()?.sameAs(themed) == true }
            // Apps with no icon in the theme: their system icon, right beside it.
            assertTrue(main { IconMotion.onScreen().map { it.first }.filter { IconMotion.entryOf(it)?.packageName != target.packageName }.none { it.bitmap()?.sameAs(themed) == true } })
            Thread.sleep(800)
            shot("150-icon-theme-drawer")
            @Suppress("DEPRECATION") home.onActivity { it.onBackPressed() }
            Thread.sleep(800)
            // Search.
            home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText(target.label) }
            waitFor("in search") { shownView(target.packageName)?.bitmap()?.sameAs(themed) == true }
            Thread.sleep(500)
            shot("151-icon-theme-search")
            home.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("") }
            Thread.sleep(800)
            // The favorites on home.
            favorites(target, entry("com.google.android.youtube"), entry("com.google.android.deskclock"), entry("com.google.android.calendar"))
            home.onActivity { it.findViewById<View>(R.id.searchIcon).performLongClick() }
            waitFor("in the favorites") { shownView(target.packageName)?.bitmap()?.sameAs(themed) == true }
            Thread.sleep(1200)
            shot("152-icon-theme-favorites")

            // Removed again: the theme ships empty, the app back to its system icon.
            main { app.iconThemes.useTheme(null) }
            assertEquals(emptySet<String>(), theme.files)
            assertTrue(icon(target).sameAs(system))
        }
    }

    @Test fun theClockShowsTheTimeAndTheCalendarToday() {
        val clock = entry("com.google.android.deskclock")
        val calendar = entry("com.google.android.calendar")
        val clockLine = theme.find(clock.component)!!
        val calLine = theme.find(calendar.component)!!
        val size = 192
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 40) }
        val at340 = theme.render(clockLine, size, c.timeInMillis, still = false)!!
        val at915 = theme.render(clockLine, size, c.apply { set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 15) }.timeInMillis, still = false)!!
        assertFalse("the hands move", at340.sameAs(at915))
        assertEquals((9 * 30.0 + 7.5) to 90.0, IconTheme.handAngles(9, 15))
        // Held still: 10:10, whatever the time.
        assertTrue(theme.render(clockLine, size, System.currentTimeMillis(), still = true)!!.sameAs(LiveIcons.clock(size, 10 to 10)))
        // Frosted and natural: a pale face, the clay center.
        val face = at340.getPixel(size / 2, (size * 0.3f).toInt())
        assertTrue("a pale frosted face", Color.red(face) > 200 && Color.green(face) > 200 && Color.blue(face) > 200)
        assertEquals("the clay center", LiveIcons.CLAY, at340.getPixel(size / 2, size / 2))
        // What the launcher shows: the time now (or the minute either side, if it turned meanwhile).
        val now = System.currentTimeMillis()
        main { app.apps.forgetIcon(clock) }
        val shown = icon(clock)
        assertTrue("the clock icon shows the time now", listOf(now, now + 60_000, now - 60_000).any { t ->
            theme.render(clockLine, shown.width, t, false)!!.sameAs(shown) || theme.render(clockLine, shown.width, t, true)!!.sameAs(shown)
        })

        // The calendar: today's date, and another day looks different.
        val today = Calendar.getInstance()
        assertEquals(today.get(Calendar.DAY_OF_MONTH).toString(), IconTheme.dateText(today.timeInMillis).second)
        val tomorrow = today.timeInMillis + 24 * 3_600_000L
        assertFalse(theme.render(calLine, size, today.timeInMillis, false)!!.sameAs(theme.render(calLine, size, tomorrow, false)!!))
        main { app.apps.forgetIcon(calendar) }
        val cal = icon(calendar)
        assertTrue("the calendar icon shows today", cal.sameAs(theme.render(calLine, cal.width, System.currentTimeMillis(), false)!!))

        // Battery Saver: the clock holds at 10:10.
        app.iconThemes.powerSaveOverride = true
        main { app.iconThemes.timeMoved(dateMayHaveChanged = false) }
        main { app.apps.forgetIcon(clock) }
        assertTrue("held still at 10:10", icon(clock).sameAs(LiveIcons.clock(shown.width, 10 to 10)))
        // Theme off: the system's clock.
        setTheme(false)
        assertFalse(icon(clock).sameAs(shown))
    }

    @Test fun everyIconComesAliveWithItsAppsMoveAndNeverHoldsUpATap() {
        val settings = entry("com.android.settings")
        val clock = entry("com.google.android.deskclock")
        val youtube = entry("com.google.android.youtube")
        favorites(settings, clock, youtube, entry("com.google.android.calendar"))
        IconMotion.forceMotion = true
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            home.onActivity { it.findViewById<View>(R.id.searchIcon).performLongClick() }
            waitFor("the favorites on screen") { shownApps().containsAll(listOf(settings, clock, youtube).map { it.packageName }) }
            // Home opening: each icon on screen makes its move, system icons included.
            val before = IconMotion.played.size
            assertTrue(main { IconMotion.homeOpened() } >= 4)
            waitFor("they move") { IconMotion.onScreen().any { IconMotion.moving(it.first) } }
            Thread.sleep(250)
            shot("153-icon-theme-alive")
            waitFor("all moved", 6_000) { IconMotion.played.size >= before + 4 }
            val moves = IconMotion.played.drop(before).toMap()
            assertEquals("settings turns a notch", IconTheme.Move.GEAR, moves[settings.key])
            assertEquals("the clock rings", IconTheme.Move.RING, moves[clock.key])
            assertTrue("youtube's system icon: a generic move", moves[youtube.key] in generic)
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
            // Theme off: nothing moves.
            IconMotion.forceMotion = true
            setTheme(false)
            assertEquals(0, main { IconMotion.homeOpened() })
            setTheme(true)

            // A tap mid-move opens the app at once.
            waitFor("settings back") { shownView("com.android.settings") != null }
            val iv = main { shownView("com.android.settings")!! }
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

    @Test fun aPackDrawsItsIconsWhileMovesAndTheLiveIconsStay() {
        val packs = app.iconPacks.installedPacks().map { it.packageName }
        org.junit.Assume.assumeTrue("the test pack isn't installed: $packs", testPack in packs)
        val settings = entry("com.android.settings")
        val clock = entry("com.google.android.deskclock")
        val youtube = entry("com.google.android.youtube")
        val systemSettings = icon(settings)
        val systemYoutube = icon(youtube)
        var ok: Boolean? = null
        main { app.iconPacks.select(testPack) { ok = it } }
        waitFor("the pack applied") { ok != null }
        assertTrue(ok!!)
        // The pack's own art.
        val s = icon(settings)
        assertFalse("the pack's settings icon", s.sameAs(systemSettings))
        val c = s.getPixel(s.width / 2, s.height / 4)
        assertTrue("the pack's orange disc", Color.red(c) > 200 && Color.green(c) in 70..150 && Color.blue(c) < 60)
        // The live clock stays Searchlight's own.
        main { app.apps.forgetIcon(clock) }
        val shown = icon(clock)
        val line = theme.find(clock.component)!!
        val now = System.currentTimeMillis()
        assertTrue("the live clock with a pack applied", listOf(now, now - 60_000, now + 60_000).any { t ->
            theme.render(line, shown.width, t, false)!!.sameAs(shown) || theme.render(line, shown.width, t, true)!!.sameAs(shown)
        })
        // An app the pack doesn't draw: its system icon.
        assertTrue("youtube keeps its system icon", icon(youtube).sameAs(systemYoutube))
        assertTrue(main { app.iconThemes.themedCount() } >= 2)
        // The moves are the apps', whoever drew the icons.
        assertEquals(IconTheme.Move.GEAR, main { app.apps.moveFor(settings) })
        assertEquals(IconTheme.Move.RING, main { app.apps.moveFor(clock) })
        assertTrue(main { app.apps.moveFor(youtube) } in generic)

        favorites(settings, clock, youtube, entry("com.google.android.calendar"))
        IconMotion.forceMotion = true
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            home.onActivity { it.findViewById<View>(R.id.searchIcon).performLongClick() }
            waitFor("pack icons on screen") { shownView("com.android.settings")?.bitmap()?.sameAs(s) == true }
            val before = IconMotion.played.size
            assertTrue(main { IconMotion.homeOpened() } >= 3)
            waitFor("they move", 6_000) { IconMotion.played.size >= before + 3 }
            Thread.sleep(150)
            shot("154-icon-pack-alive")
            val moves = IconMotion.played.drop(before).toMap()
            assertEquals("a signature move on the pack's icon", IconTheme.Move.GEAR, moves[settings.key])
            assertEquals(IconTheme.Move.RING, moves[clock.key])
            assertTrue(moves[youtube.key] in generic)
            waitFor("settled", 5_000) { IconMotion.onScreen().none { IconMotion.moving(it.first) } }
        }
    }
}
