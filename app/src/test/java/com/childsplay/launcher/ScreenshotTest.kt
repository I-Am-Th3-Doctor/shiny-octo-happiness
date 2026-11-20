package com.childsplay.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.childsplay.launcher.data.AppEntry
import com.childsplay.launcher.data.Thing
import com.childsplay.launcher.data.ThingShape
import com.childsplay.launcher.things.ThingEditor
import com.childsplay.launcher.things.ThingPanel
import com.childsplay.launcher.things.ThingWidget
import com.childsplay.launcher.ui.RoomBackdrop
import com.childsplay.launcher.ui.Rooms
import com.childsplay.launcher.ui.ToyTheme
import com.childsplay.launcher.ui.toyBlock
import com.childsplay.launcher.weather.Alert
import com.childsplay.launcher.weather.Current
import com.childsplay.launcher.weather.DayPoint
import com.childsplay.launcher.weather.Forecast
import com.childsplay.launcher.weather.HeroWeatherWidget
import com.childsplay.launcher.weather.HourPoint
import com.childsplay.launcher.weather.Place
import com.childsplay.launcher.weather.Skins
import com.childsplay.launcher.weather.Source
import com.childsplay.launcher.weather.WeatherScreen
import com.childsplay.launcher.weather.WeatherState
import com.childsplay.launcher.weather.WeatherStore
import com.childsplay.launcher.widgets.StickyNote
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

/**
 * Renders key screens to PNGs for eyeballing. Skipped unless -PscreenshotsDir=... is passed.
 */
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

    private fun snap(name: String, millis: Long = 1600, content: @Composable () -> Unit) {
        rule.setContent { ToyTheme { content() } }
        rule.mainClock.advanceTimeBy(millis)
        // Infinite animations never let Compose idle, so draw the view tree directly.
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(outDir).mkdirs()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val apps = listOf("Phone", "Messages", "Gmail", "Discord", "YouTube", "Spotify", "Camera", "Photos", "Chrome", "Settings", "Maps", "Clock")
        .mapIndexed { i, n -> AppEntry("k$i", n, "pkg.$i", ComponentName("pkg.$i", "pkg.$i.Main"), Process.myUserHandle(), 0, false) }

    private val things = listOf(
        Thing("1", "Chatter", ThingShape.CLOUD, 6, listOf("k0", "k1", "k2", "k3")),
        Thing("2", "Watch & Listen", ThingShape.TV, 0, listOf("k4", "k5")),
        Thing("3", "Make Stuff", ThingShape.PAINTING, 9, listOf("k6", "k7", "k8", "k9", "k10")),
        Thing("4", "Essentials", ThingShape.STATUE, 8, listOf("k8", "k9", "k10")),
        Thing("5", "Games", ThingShape.BALLOON, 1, listOf("k11")),
        Thing("6", "Space", ThingShape.ROCKET, 3, emptyList()),
    )

    private fun forecast(code: Int, isDay: Boolean, alert: Boolean = false): Forecast {
        val now = LocalDateTime.of(2026, 11, 20, 14, 0)
        val hours = (0 until 48).map { HourPoint(now.plusHours(it.toLong()), 14.0 + (it % 12), 13.0, if (it % 7 == 0) 61 else code, if (it % 7 == 0) 1.2 else 0.0, null, 12.0, (now.plusHours(it.toLong()).hour in 7..16), if (it < 40) Source.HRRR else Source.ECMWF) }
        val days = (0 until 7).map { DayPoint(LocalDate.of(2026, 11, 20).plusDays(it.toLong()), 18.0 + it, 6.0 - it % 3, listOf(code, 61, 95, 3, 0, 71, 2)[it], 2.0, null, LocalDateTime.of(2026, 11, 20, 6, 52), LocalDateTime.of(2026, 11, 20, 16, 33), if (it < 2) Source.HRRR else Source.ECMWF) }
        val alerts = if (alert) listOf(Alert("Tornado Warning", "Extreme", "Tornado Warning issued for Testville", "A tornado was spotted.", "Take cover now.", "2026-11-20T15:45:00-05:00")) else emptyList()
        return Forecast(Place("Brooklyn", 40.6, -73.9), Current(16.4, 14.8, code, isDay, 18.0, 62.0, Source.HRRR), hours, days, alerts, -18000, System.currentTimeMillis())
    }

    @Test fun home_board() = snap("home_board") {
        Box(Modifier.fillMaxSize()) {
            RoomBackdrop(Rooms.get(0), Modifier.fillMaxSize())
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                HeroWeatherWidget(
                    WeatherState(forecast(2, true)), Skins.Popsicle, true,
                    Modifier.fillMaxWidth().aspectRatio(2.1f).toyBlock(Skins.Popsicle.card, corner = 30.dp),
                )
                Row {
                    StickyNote(0, false, Modifier.weight(1f).aspectRatio(1f))
                    ThingWidget(things[0], apps.take(4), emptyMap(), {}, {}, Modifier.weight(1f).padding(4.dp))
                }
                Row {
                    ThingWidget(things[1], apps.subList(4, 6), emptyMap(), {}, {}, Modifier.weight(1f).padding(4.dp))
                    ThingWidget(things[2], apps.subList(6, 11), emptyMap(), {}, {}, Modifier.weight(1f).padding(4.dp))
                }
                Row {
                    ThingWidget(things[3], apps.subList(8, 11), emptyMap(), {}, {}, Modifier.weight(1f).padding(4.dp))
                    ThingWidget(things[4], apps.subList(11, 12), emptyMap(), {}, {}, Modifier.weight(1f).padding(4.dp))
                }
            }
        }
    }

    @Test fun shapes_all() = snap("shapes_all") {
        Box(Modifier.fillMaxSize()) {
            RoomBackdrop(Rooms.get(2), Modifier.fillMaxSize())
            Column(Modifier.padding(12.dp)) {
                things.chunked(2).forEach { row ->
                    Row { row.forEach { t -> ThingWidget(t, apps.filter { it.key in t.appKeys }, emptyMap(), {}, {}, Modifier.weight(1f).padding(6.dp)) } }
                }
            }
        }
    }

    @Test fun panel_open() = snap("panel_open", millis = 2500) {
        Box(Modifier.fillMaxSize()) {
            RoomBackdrop(Rooms.get(3), Modifier.fillMaxSize())
            ThingPanel(things[0], apps.take(7), emptyMap(), Rect(100f, 600f, 500f, 1000f), false, { _, _ -> }, {}, {}, {}, {})
        }
    }

    @Test fun editor() = snap("editor") {
        ThingEditor(things[1], apps, emptyMap(), {}, {}, {})
    }

    private fun weatherScreen(name: String, code: Int, isDay: Boolean, skin: String, alert: Boolean = false) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).edit()
            .putString("cache", WeatherStore.encode(forecast(code, isDay, alert)).toString())
            .putString("skin", skin).putBoolean("fahrenheit", true).commit()
        val ctor = WeatherStore::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val store = ctor.newInstance(ctx)
        snap(name, millis = 2300) { WeatherScreen(store) }
    }

    @Test fun weather_clear_day() = weatherScreen("weather_clear_day", 0, true, "popsicle")
    @Test fun weather_storm_alert() = weatherScreen("weather_storm_alert", 95, true, "bubblegum", alert = true)
    @Test fun weather_snow_night() = weatherScreen("weather_snow_night", 73, false, "grape")
    @Test fun weather_rain_tangerine() = weatherScreen("weather_rain_tangerine", 63, true, "tangerine")

    @Test fun widgets_skins() = snap("widgets_skins") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0 to true, 2 to true, 61 to true, 95 to false, 73 to true, 45 to true).zip(Skins.all).forEach { (c, skin) ->
                HeroWeatherWidget(
                    WeatherState(forecast(c.first, c.second)), skin, true,
                    Modifier.width(376.dp).aspectRatio(2.6f).toyBlock(skin.card, corner = 30.dp),
                )
            }
        }
    }
}
