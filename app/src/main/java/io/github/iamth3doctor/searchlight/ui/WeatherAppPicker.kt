package io.github.iamth3doctor.searchlight.ui

import android.app.Activity
import android.app.AlertDialog
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.searchlight
import io.github.iamth3doctor.searchlight.weather.WeatherApps

/**
 * Choose the app the weather tile opens: "None" plus every app, weather-looking ones first
 * ([WeatherApps]). Saves the choice and hands it to [onPicked] (null for None).
 */
fun Activity.pickWeatherApp(onPicked: (AppEntry?) -> Unit = {}) {
    val app = searchlight
    val apps = WeatherApps.ordered(app.apps.apps.filter { !it.isSelf }, { it.label }, { it.component.packageName })
    val labels = listOf(getString(R.string.weather_app_none)) + apps.map { it.label }
    val checked = apps.indexOfFirst { it.key == app.prefs.weatherAppKey }.let { if (it < 0) 0 else it + 1 }
    AlertDialog.Builder(this)
        .setTitle(R.string.weather_app)
        .wrappingSingleChoice(labels, checked) { d, which ->
            val entry = if (which == 0) null else apps[which - 1]
            app.prefs.weatherAppKey = entry?.key
            d.dismiss()
            onPicked(entry)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
