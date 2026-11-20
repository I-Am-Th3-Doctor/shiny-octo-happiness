package com.something.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.something.launcher.data.AppEntry
import com.something.launcher.data.DotIcons
import com.something.launcher.ui.AppCell
import com.something.launcher.ui.DotText
import com.something.launcher.ui.Glyphs
import com.something.launcher.ui.Ink
import com.something.launcher.ui.PixelGlyph
import com.something.launcher.ui.SomethingTheme
import com.something.launcher.ui.dotGrid
import com.something.launcher.weather.Alert
import com.something.launcher.weather.Current
import com.something.launcher.weather.DayPoint
import com.something.launcher.weather.Forecast
import com.something.launcher.weather.HourPoint
import com.something.launcher.weather.Place
import com.something.launcher.weather.Source
import com.something.launcher.weather.WeatherScreen
import com.something.launcher.weather.WeatherState
import com.something.launcher.weather.WeatherStore
import com.something.launcher.weather.WeatherWidget
import com.something.launcher.widgets.StickyNote
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** Renders key screens to PNGs for eyeballing. Skipped unless -PscreenshotsDir=... is passed. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val outDir = System.getProperty("screenshots.dir").orEmpty()

    @Before fun setUp() {
        assumeTrue("screenshots disabled", outDir.isNotEmpty())
        rule.mainClock.autoAdvance = false
    }

    private fun snap(name: String, millis: Long = 1200, content: @Composable () -> Unit) {
        rule.setContent { SomethingTheme { content() } }
        rule.mainClock.advanceTimeBy(millis)
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(outDir).mkdirs()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val apps = listOf("Phone", "Messages", "Camera", "Chrome", "Gmail", "Maps", "Photos", "Settings")
        .mapIndexed { i, n -> AppEntry("k$i", n, "pkg.$i", ComponentName("pkg.$i", "pkg.$i.Main"), Process.myUserHandle(), 0, i == 7) }

    /** Fake glyphs: draw a few shapes and run them through the real dot-icon pipeline. */
    private fun fakeIcons() = apps.mapIndexed { i, a ->
        val bmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        when (i % 4) {
            0 -> c.drawCircle(54f, 54f, 30f, p)
            1 -> c.drawRoundRect(22f, 30f, 86f, 78f, 12f, 12f, p)
            2 -> { c.drawRect(24f, 36f, 84f, 80f, p); p.color = 0; p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR); c.drawCircle(54f, 58f, 13f, p) }
            else -> { val path = android.graphics.Path().apply { moveTo(54f, 20f); lineTo(88f, 86f); lineTo(20f, 86f); close() }; c.drawPath(path, p) }
        }
        val d = android.graphics.drawable.BitmapDrawable(ApplicationProvider.getApplicationContext<Context>().resources, bmp)
        a.key to DotIcons.render(d, 144).asImageBitmap()
    }.toMap() + ("plate" to DotIcons.render(ColorDrawable(android.graphics.Color.RED), 144).asImageBitmap())

    private fun forecast(code: Int, isDay: Boolean, alert: Boolean = false): Forecast {
        val now = LocalDateTime.of(2026, 11, 20, 14, 0)
        val hours = (0 until 48).map {
            val t = now.plusHours(it.toLong())
            HourPoint(t, 8.0 + 6 * kotlin.math.sin(it / 4.0), 7.0, if (it % 9 == 0) 61 else code, if (it % 9 == 0) 1.2 else 0.0, null, 12.0, t.hour in 7..16, if (it < 40) Source.HRRR else Source.ECMWF)
        }
        val days = (0 until 7).map {
            DayPoint(LocalDate.of(2026, 11, 20).plusDays(it.toLong()), 15.0 + it, 3.0 - it % 3, listOf(code, 61, 95, 3, 0, 71, 2)[it], 2.0, null,
                LocalDateTime.of(2026, 11, 20, 6, 52), LocalDateTime.of(2026, 11, 20, 16, 33), if (it < 2) Source.HRRR else Source.ECMWF)
        }
        val alerts = if (alert) listOf(Alert("Tornado Warning", "Extreme", "Tornado Warning issued", "A tornado was spotted.", "Take cover now.", "2026-11-20T15:45:00-05:00")) else emptyList()
        return Forecast(Place("Brooklyn", 40.6, -73.9), Current(16.4, 14.8, code, isDay, 18.0, 62.0, Source.HRRR), hours, days, alerts, -18000, System.currentTimeMillis())
    }

    @Test fun home() {
        val icons = fakeIcons()
        snap("home") {
            Box(Modifier.fillMaxSize().dotGrid()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    DotText("9:41", 92.sp, letterSpacing = 0.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WeatherWidget(WeatherState(forecast(2, true)), true, Modifier.weight(1f).aspectRatio(1f))
                        StickyNote(false, Modifier.weight(1f).aspectRatio(1f))
                    }
                    apps.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { a -> AppCell(a, icons[a.key], { _, _ -> }, {}, Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    @Test fun glyphs() = snap("glyphs") {
        Column(Modifier.fillMaxSize().dotGrid().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(
                listOf(Glyphs.Sun, Glyphs.Moon, Glyphs.PartlyDay, Glyphs.PartlyNight),
                listOf(Glyphs.Cloud, Glyphs.Cloudy, Glyphs.Fog, Glyphs.Drizzle),
                listOf(Glyphs.Rain, Glyphs.Sleet, Glyphs.Snow, Glyphs.Storm),
                listOf(Glyphs.Alert),
            ).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { row.forEach { PixelGlyph(it, 76.dp, unlit = Ink.Faint) } }
            }
        }
    }

    @Test fun widgets_alert() = snap("widgets_alert") {
        Column(Modifier.fillMaxSize().dotGrid().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WeatherWidget(WeatherState(forecast(95, false, alert = true)), true, Modifier.weight(1f).aspectRatio(1f))
                WeatherWidget(WeatherState(), true, Modifier.weight(1f).aspectRatio(1f))
            }
        }
    }

    private fun weatherScreen(name: String, code: Int, isDay: Boolean, alert: Boolean = false) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).edit()
            .putString("cache", WeatherStore.encode(forecast(code, isDay, alert)).toString())
            .putBoolean("fahrenheit", true).commit()
        val ctor = WeatherStore::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val store = ctor.newInstance(ctx)
        snap(name) { WeatherScreen(store) }
    }

    @Test fun weather_clear() = weatherScreen("weather_clear", 0, true)
    @Test fun weather_storm_alert() = weatherScreen("weather_storm_alert", 95, true, alert = true)
}
