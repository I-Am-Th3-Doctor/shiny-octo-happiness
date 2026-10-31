package io.github.iamth3doctor.searchlight

import android.graphics.Bitmap
import android.graphics.Canvas
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
 * "Glass transparency" in Settings > Appearance, end to end: the slider writes the very key the
 * glass reads; what every surface actually paints follows the value live (blurred or not, the
 * popup and settings cards too); every screen picks the saved value up when it starts; the
 * default paints today's tints; and 0 / 50 / 100 look plainly different (screenshots).
 */
@RunWith(AndroidJUnit4::class)
class GlassLevelDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val sp get() = app.getSharedPreferences("searchlight", 0)

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <A : android.app.Activity, T> on(s: ActivityScenario<A>, read: (A) -> T): T {
        var v: T? = null
        s.onActivity { v = read(it) }
        @Suppress("UNCHECKED_CAST") return v as T
    }

    private fun alpha(c: Int) = c ushr 24
    private fun hex(c: Int) = Integer.toHexString(c)

    /** Lets a frame or two draw. */
    private fun frame() {
        Thread.sleep(400)
        instrumentation.waitForIdleSync()
    }

    /** Sets the level the way the slider does: stored, then handed to the glass. */
    private fun setLevel(value: Int) = main {
        app.prefs.glassLevel = value
        FrostedDrawable.setLevel(value)
    }

    @Before fun fresh() {
        sp.edit().remove(Prefs.KEY_GLASS_LEVEL).commit()
        main { FrostedDrawable.setLevel(app.prefs.glassLevel) }
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
    }

    @After fun clean() {
        shell("cmd uimode night no")
        setLevel(GlassTint.LEVEL_DEFAULT)
    }

    // ---- Settings: the slider ----

    /** Opens Appearance and brings the slider on screen. */
    private fun showSlider(s: ActivityScenario<SettingsActivity>) {
        s.onActivity { a ->
            if (CollapsibleSections.bodyOf(a.window.decorView, "appearance")!!.visibility != View.VISIBLE) {
                CollapsibleSections.headerOf(a.window.decorView, "appearance")!!.performClick()
            }
        }
        Thread.sleep(700)
        s.onActivity { a -> a.findViewById<View>(R.id.glassRow).let { it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height), true) } }
        frame()
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
        frame()
    }

    /** The glass of the slider's own card (on screen, so it repaints as the slider moves). */
    private fun settingsCard(a: SettingsActivity) = CollapsibleSections.cardOf(a.window.decorView, "appearance")!!.background as FrostedDrawable

    private fun stockSettingsGlass(a: SettingsActivity) =
        a.getColor(if (a.isLightTheme()) R.color.settings_glass_light_fallback else R.color.glass_card_fallback)

    private fun state(s: ActivityScenario<SettingsActivity>) = on(s) { a ->
        val c = settingsCard(a)
        "level ${FrostedDrawable.level}, stored ${sp.getInt(Prefs.KEY_GLASS_LEVEL, -1)}, slider ${a.findViewById<SeekBar>(R.id.glassSlider).progress}, " +
            "drawn ${hex(c.drawnColor)}, fallback ${hex(c.appliedFallback)}"
    }

    @Test fun sliderWritesTheKeyTheGlassReads() {
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
            assertEquals("default paints today's tint: ${state(s)}", hex(stock), hex(on(s) { settingsCard(it).drawnColor }))

            for (fraction in listOf(0.97f, 0.03f, 0.5f)) {
                tapSlider(s, fraction)
                val shown = on(s) { it.findViewById<SeekBar>(R.id.glassSlider).progress }
                // Written under the key the glass is read from, handed to the glass, read out.
                assertEquals(state(s), shown, sp.getInt(Prefs.KEY_GLASS_LEVEL, -1))
                assertEquals(state(s), shown, Prefs(app).glassLevel)
                assertEquals(state(s), shown, FrostedDrawable.level)
                assertEquals("$shown%", on(s) { it.findViewById<TextView>(R.id.glassValue).text.toString() })
                // And this screen's own card repainted with it, live.
                assertEquals(state(s), hex(GlassTint.scale(stock, shown)), hex(on(s) { settingsCard(it).drawnColor }))
                if (fraction > 0.9f) shell("screencap -p $dir/${prefix}_font1.0_28-glass-slider.png")
            }
        }
    }

    // ---- Home: every surface ----

    private class Surface(val name: String, val glass: FrostedDrawable, val shown: Boolean)

    private fun surfaces(a: MainActivity): List<Surface> {
        fun s(name: String, id: Int) = a.findViewById<View>(id).let { Surface(name, it.background as FrostedDrawable, it.isShown) }
        return listOf(
            s("home card", R.id.homeCard), s("search row", R.id.searchPill), s("results panel", R.id.resultsPanel),
            s("web pill", R.id.webPill), s("rail", R.id.rail), s("all apps", R.id.allApps),
        ) + listOfNotNull(a.favoritesPopup.contentView?.let { Surface("favorites popup", it.background as FrostedDrawable, it.isShown) })
    }

    /** What a surface paints at the current level: the tint over the blur, or the fallback without one. */
    private fun painted(s: Surface) = if (s.glass.drewBlur) s.glass.appliedTint else s.glass.appliedFallback

    @Test fun everySurfaceFollowsTheLevelLive() {
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(2000)
            home.onActivity { it.favoritesPopup.show(it.findViewById(R.id.homeCard)) }
            frame()

            // Default: today's tints, exactly, on the card and row as drawn.
            home.onActivity { a ->
                val sample = WallpaperBlur.sampledColor()
                val card = surfaces(a)[0]
                val row = surfaces(a)[1]
                val stockCard = if (!card.glass.drewBlur) a.getColor(R.color.glass_card_fallback)
                    else sample?.let { GlassTint.tint(it, GlassTint.Layer.CARD) } ?: a.getColor(R.color.glass_card)
                val stockRow = if (!row.glass.drewBlur) a.getColor(R.color.surface_inset)
                    else sample?.let { GlassTint.tint(it, GlassTint.Layer.ROW) } ?: a.getColor(R.color.glass_row)
                assertEquals("home card at the default", hex(stockCard), hex(card.glass.drawnColor))
                assertEquals("search row at the default", hex(stockRow), hex(row.glass.drawnColor))
            }
            shell("screencap -p $dir/${prefix}_font1.0_29-glass-default.png")

            val at = mutableMapOf<Int, List<Int>>()
            for (level in listOf(0, 100, 50)) {
                setLevel(level)
                frame()
                home.onActivity { a ->
                    val all = surfaces(a)
                    assertTrue("popup open", all.any { it.name == "favorites popup" })
                    // Every shown surface repainted with the new value (hidden ones paint it when shown).
                    for (s in all) {
                        if (s.shown) assertEquals("${s.name} at $level", hex(painted(s)), hex(s.glass.drawnColor))
                    }
                    at[level] = all.map { painted(it) }
                    // The row lighter than the card at every level (with the blur; without, it's a light inset).
                    if (all[0].glass.drewBlur && all[1].glass.drewBlur) {
                        val card = painted(all[0])
                        val row = painted(all[1])
                        assertTrue("row ${hex(row)} vs card ${hex(card)} at $level",
                            alpha(row) < alpha(card) || GlassTint.hsl(row).third > GlassTint.hsl(card).third)
                    }
                }
                if (level != 50) shell("screencap -p $dir/${prefix}_font1.0_${if (level == 0) "30-glass-clear" else "31-glass-solid"}.png")
            }
            // Clear < default < Solid on every surface, and far apart: 0 nearly invisible, 100 nearly opaque.
            val names = on(home) { a -> surfaces(a).map { it.name } }
            for (i in names.indices) {
                val (c, d, s) = listOf(at[0]!![i], at[50]!![i], at[100]!![i]).map(::alpha)
                assertTrue("${names[i]}: clear $c default $d solid $s", c < d && d < s)
                assertTrue("${names[i]} at clear: $c", c <= 0.15f * 255)
            }
            home.onActivity { it.favoritesPopup.dismiss() }
        }
    }

    @Test fun noBlurFallbackPaintsTheScaledTint() {
        // A glass surface with no wallpaper under it, drawn for real at each level.
        val pixels = mutableMapOf<Int, Int>()
        main {
            val v = View(app)
            val glass = FrostedDrawable(v, 20f, 0x800B0B0D.toInt(), 0xBF0B0B0D.toInt(), wallpaper = false)
            val bmp = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            for (level in listOf(0, 50, 100)) {
                FrostedDrawable.setLevel(level)
                bmp.eraseColor(0)
                glass.setBounds(0, 0, 40, 40)
                glass.draw(Canvas(bmp))
                assertTrue(!glass.drewBlur)
                assertEquals(hex(GlassTint.scale(0xBF0B0B0D.toInt(), level)), hex(glass.drawnColor))
                pixels[level] = alpha(bmp.getPixel(20, 20))
            }
        }
        assertTrue("pixels: $pixels", kotlin.math.abs(pixels[50]!! - 0xBF) <= 1)
        assertTrue("pixels: $pixels", pixels[0]!! < 0x20 && pixels[100]!! > 0xF0)
    }

    // ---- Restart, and the dark theme ----

    @Test fun savedValueIsReadWhenScreensStart() {
        setLevel(17)
        // As after a restart: the glass forgets, the screens read storage when they start.
        main { FrostedDrawable.setLevel(GlassTint.LEVEL_DEFAULT) }
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            Thread.sleep(1500)
            assertEquals(17, FrostedDrawable.level)
            home.onActivity { a ->
                val card = surfaces(a)[0]
                assertEquals(hex(painted(card)), hex(card.glass.drawnColor))
                assertTrue(hex(card.glass.drawnColor), alpha(card.glass.drawnColor) < 0x40)
            }
        }
        main { FrostedDrawable.setLevel(GlassTint.LEVEL_DEFAULT) }
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            Thread.sleep(800)
            showSlider(s)
            assertEquals(17, FrostedDrawable.level)
            assertEquals(17, on(s) { it.findViewById<SeekBar>(R.id.glassSlider).progress })
            assertEquals("17%", on(s) { it.findViewById<TextView>(R.id.glassValue).text.toString() })
        }
    }

    @Test fun darkThemeSettingsCardsScaleToo() {
        shell("cmd uimode night yes")
        Thread.sleep(1500)
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            Thread.sleep(1000)
            showSlider(s)
            assertTrue("dark", on(s) { !it.isLightTheme() })
            val stock = on(s) { it.getColor(R.color.glass_card_fallback) }
            assertEquals(hex(stock), hex(on(s) { settingsCard(it).drawnColor }))
            setLevel(0)
            frame()
            assertEquals(hex(GlassTint.scale(stock, 0)), hex(on(s) { settingsCard(it).drawnColor }))
            shell("screencap -p $dir/${prefix}_font1.0_32-glass-dark-clear.png")
            setLevel(100)
            frame()
            assertEquals(hex(GlassTint.scale(stock, 100)), hex(on(s) { settingsCard(it).drawnColor }))
        }
    }
}
