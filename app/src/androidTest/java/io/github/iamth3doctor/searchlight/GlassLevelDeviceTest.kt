package io.github.iamth3doctor.searchlight

import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.data.Prefs
import io.github.iamth3doctor.searchlight.ui.CollapsibleSections
import io.github.iamth3doctor.searchlight.ui.FrostedDrawable
import io.github.iamth3doctor.searchlight.ui.GlassTint
import io.github.iamth3doctor.searchlight.ui.WallpaperBlur
import io.github.iamth3doctor.searchlight.ui.isLightTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Glass transparency" in Settings > Appearance: the default is today's look, a real drag on
 * the slider changes the glass live (settings cards at once, the home card and search row on
 * return, the row always lighter), and the value outlives the screen and a fresh start.
 */
@RunWith(AndroidJUnit4::class)
class GlassLevelDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <A : android.app.Activity, T> on(s: ActivityScenario<A>, read: (A) -> T): T {
        var v: T? = null
        s.onActivity { v = read(it) }
        @Suppress("UNCHECKED_CAST") return v as T
    }

    private fun alpha(c: Int) = c ushr 24

    private fun reset() = main {
        app.prefs.glassLevel = GlassTint.LEVEL_DEFAULT
        FrostedDrawable.level = GlassTint.LEVEL_DEFAULT
    }

    @Before fun fresh() {
        app.getSharedPreferences("searchlight", 0).edit().remove("glass_level").commit()
        main { FrostedDrawable.level = app.prefs.glassLevel }
    }

    @After fun clean() = reset()

    /** Opens Appearance and brings the slider on screen. */
    private fun showSlider(s: ActivityScenario<SettingsActivity>) {
        s.onActivity { a ->
            val body = CollapsibleSections.bodyOf(a.window.decorView, "appearance")!!
            if (body.visibility != View.VISIBLE) CollapsibleSections.headerOf(a.window.decorView, "appearance")!!.performClick()
        }
        Thread.sleep(700)
        s.onActivity { a -> a.findViewById<View>(R.id.glassRow).let { it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height), true) } }
        Thread.sleep(700)
        instrumentation.waitForIdleSync()
    }

    /** A real tap on the slider at [fraction] of its track. */
    private fun tapSlider(s: ActivityScenario<SettingsActivity>, fraction: Float) {
        val (x, y) = on(s) { a ->
            val bar = a.findViewById<SeekBar>(R.id.glassSlider)
            val l = IntArray(2)
            bar.getLocationOnScreen(l)
            val track = bar.width - bar.paddingLeft - bar.paddingRight
            (l[0] + bar.paddingLeft + (track * fraction).toInt()) to (l[1] + bar.height / 2)
        }
        shell("input tap $x $y")
        Thread.sleep(600)
        instrumentation.waitForIdleSync()
    }

    /**
     * The glass of the settings card the slider sits in (on screen, so redrawn as it moves) as
     * drawn; always its plain tint (it doesn't sample the wallpaper). Waits for a frame first.
     */
    private fun settingsGlass(s: ActivityScenario<SettingsActivity>): Int {
        Thread.sleep(300)
        instrumentation.waitForIdleSync()
        return on(s) { a -> card(a).appliedFallback }
    }

    private fun card(a: SettingsActivity) = CollapsibleSections.cardOf(a.window.decorView, "appearance")!!.background as FrostedDrawable

    /** What a failure needs to say: the level everywhere it lives, and the card as drawn. */
    private fun state(s: ActivityScenario<SettingsActivity>) = on(s) { a ->
        val c = card(a)
        "level ${FrostedDrawable.level}, prefs ${app.prefs.glassLevel}, slider ${a.findViewById<SeekBar>(R.id.glassSlider).progress}, " +
            "fallback ${hex(c.appliedFallback)}, tint ${hex(c.appliedTint)}, drewBlur ${c.drewBlur}, shown ${CollapsibleSections.cardOf(a.window.decorView, "appearance")!!.isShown}"
    }

    private fun stockSettingsGlass(a: SettingsActivity) =
        a.getColor(if (a.isLightTheme()) R.color.settings_glass_light_fallback else R.color.glass_card_fallback)

    /** The home card's and search row's glass at a level: as drawn, and as expected there. */
    private class Home(val card: Int, val row: Int, val expectedCard: Int, val expectedRow: Int)

    /** Opens home at the current level and reads its glass; [shot] names a screenshot of it. */
    private fun home(shot: String? = null): Home {
        lateinit var h: Home
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            Thread.sleep(2000)
            s.onActivity { a ->
                val level = FrostedDrawable.level
                val c = a.findViewById<View>(R.id.homeCard).background as FrostedDrawable
                val r = a.findViewById<View>(R.id.searchPill).background as FrostedDrawable
                val sample = WallpaperBlur.sampledColor()
                fun expected(d: FrostedDrawable, layer: GlassTint.Layer, tint: Int, fallback: Int) = when {
                    !d.drewBlur -> GlassTint.scale(a.getColor(fallback), level, GlassTint.FLOOR_PLAIN)
                    sample != null -> GlassTint.tint(sample, layer, level)
                    else -> GlassTint.scale(a.getColor(tint), level)
                }
                h = Home(
                    if (c.drewBlur) c.appliedTint else c.appliedFallback,
                    if (r.drewBlur) r.appliedTint else r.appliedFallback,
                    expected(c, GlassTint.Layer.CARD, R.color.glass_card, R.color.glass_card_fallback),
                    expected(r, GlassTint.Layer.ROW, R.color.glass_row, R.color.surface_inset),
                )
            }
            if (shot != null) shell("screencap -p $dir/${prefix}_font1.0_$shot.png")
        }
        return h
    }

    private fun hex(c: Int) = Integer.toHexString(c)

    @Test fun defaultIsTodaysLook() {
        assertEquals(GlassTint.LEVEL_DEFAULT, app.prefs.glassLevel)
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            Thread.sleep(800)
            showSlider(s)
            s.onActivity { a ->
                assertEquals(50, a.findViewById<SeekBar>(R.id.glassSlider).progress)
                assertEquals("50%", a.findViewById<TextView>(R.id.glassValue).text.toString())
                assertEquals("Clear", a.findViewById<TextView>(R.id.glassClear).text.toString())
                assertEquals("Solid", a.findViewById<TextView>(R.id.glassSolid).text.toString())
            }
            val stock = on(s) { stockSettingsGlass(it) }
            assertEquals(state(s), hex(stock), hex(settingsGlass(s)))
        }
        // Home: exactly the stock tints (level 50 leaves every alpha as it was).
        val h = home()
        assertEquals("home card", hex(h.expectedCard), hex(h.card))
        assertEquals("search row", hex(h.expectedRow), hex(h.row))
    }

    @Test fun sliderScalesTheGlassLiveAndPersists() {
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            Thread.sleep(800)
            showSlider(s)
            val stock = settingsGlass(s)

            // Toward Solid: saved, read out, the card on this screen more opaque at once.
            tapSlider(s, 0.95f)
            val solid = app.prefs.glassLevel
            assertTrue("level $solid", solid >= 85)
            assertEquals(solid, FrostedDrawable.level)
            assertEquals("$solid%", on(s) { it.findViewById<TextView>(R.id.glassValue).text.toString() })
            assertTrue("settings card more solid than ${hex(stock)}: ${state(s)}", alpha(settingsGlass(s)) > alpha(stock))
            shell("screencap -p $dir/${prefix}_font1.0_28-glass-slider.png")

            // Toward Clear: clearer than today.
            tapSlider(s, 0.05f)
            val clear = app.prefs.glassLevel
            assertTrue("level $clear", clear <= 15)
            assertTrue("settings card clearer than ${hex(stock)}: ${state(s)}", alpha(settingsGlass(s)) < alpha(stock))
        }

        // Home at Clear vs the stock look: both clearer, as the curve says.
        main { FrostedDrawable.level = GlassTint.LEVEL_DEFAULT }
        val stock50 = home()
        main { FrostedDrawable.level = app.prefs.glassLevel }
        val clear = home("29-glass-clear")
        assertEquals("card", hex(clear.expectedCard), hex(clear.card))
        assertEquals("row", hex(clear.expectedRow), hex(clear.row))
        assertTrue("card clearer", alpha(clear.card) < alpha(stock50.card))
        assertTrue("row clearer", alpha(clear.row) < alpha(stock50.row))

        // Solid: both more opaque, a hint of glass left.
        main { app.prefs.glassLevel = 100; FrostedDrawable.level = 100 }
        val solid = home("30-glass-solid")
        assertEquals("card", hex(solid.expectedCard), hex(solid.card))
        assertEquals("row", hex(solid.expectedRow), hex(solid.row))
        assertTrue("card more solid", alpha(solid.card) > alpha(stock50.card))
        assertTrue("row more solid", alpha(solid.row) > alpha(stock50.row))
        assertTrue("a hint of glass", alpha(solid.card) < 0xF5)

        // A fresh start: storage read anew (a new Prefs, as after a restart), the slider where it was left.
        main { app.prefs.glassLevel = 23 }
        assertEquals(23, Prefs(app).glassLevel)
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            Thread.sleep(800)
            showSlider(s)
            assertEquals(23, on(s) { it.findViewById<SeekBar>(R.id.glassSlider).progress })
            assertEquals("23%", on(s) { it.findViewById<TextView>(R.id.glassValue).text.toString() })
        }
    }
}
