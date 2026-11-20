package io.github.iamth3doctor.searchlight.weatherapp

import android.app.Activity
import android.view.View
import io.github.iamth3doctor.searchlight.searchlight

/**
 * What the Weather tile and the Weather widget open: your weather app (chosen in settings or from
 * the tile's long-press menu); if none is chosen, it's no longer installed, or it won't open, the
 * conditions screen (the day, precipitation, hours and 7 days, from Searchlight's own forecast).
 */
object OpenWeather {
    fun open(activity: Activity, source: View?) {
        val app = activity.searchlight.prefs.weatherAppKey?.let { activity.searchlight.apps.find(it) }
        if (app != null && activity.searchlight.apps.launch(app, source, activity, quiet = true)) return
        WeatherActivity.open(activity)
    }
}
