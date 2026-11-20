package io.github.iamth3doctor.searchlight.weatherapp

import android.content.Context
import io.github.iamth3doctor.searchlight.searchlight
import io.github.iamth3doctor.searchlight.weather.WeatherSkin
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The weather skin in use, app-wide: the weather screen, every living scene and the Weather
 * widgets read it, and follow it when it changes (on the main thread).
 */
object Skins {

    @Volatile var current: WeatherSkin = WeatherSkin.DEFAULT
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(key: String?) {
        current = WeatherSkin.of(key)
    }

    /** Switches the whole app to [skin] (and remembers it). */
    fun set(context: Context, skin: WeatherSkin) {
        if (skin == current) return
        current = skin
        context.searchlight.prefs.weatherSkin = skin.key
        for (l in listeners) l()
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
}
