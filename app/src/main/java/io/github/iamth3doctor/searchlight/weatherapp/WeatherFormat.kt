package io.github.iamth3doctor.searchlight.weatherapp

import android.content.Context
import java.time.format.DateTimeFormatter

/** Time formats for the weather screen, at the place's own time. */
object WeatherFormat {
    fun hour(context: Context): DateTimeFormatter = DateTimeFormatter.ofPattern(
        if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h a", context.resources.configuration.locales[0])

    fun time(context: Context): DateTimeFormatter = DateTimeFormatter.ofPattern(
        if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", context.resources.configuration.locales[0])
}
