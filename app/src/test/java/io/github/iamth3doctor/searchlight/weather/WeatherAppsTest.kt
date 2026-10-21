package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherAppsTest {
    @Test fun recognisesWeatherAppsByNameOrPackage() {
        assertTrue(WeatherApps.looksLikeWeather("Weather", "com.motorola.weather"))
        assertTrue(WeatherApps.looksLikeWeather("Carrot", "com.grailr.CARROTweather"))
        assertTrue(WeatherApps.looksLikeWeather("MeteoSwiss", "ch.admin.meteoswiss"))
        assertTrue(WeatherApps.looksLikeWeather("RainViewer", "com.rainviewer.radar"))
        assertFalse(WeatherApps.looksLikeWeather("Chrome", "com.android.chrome"))
    }

    @Test fun weatherAppsFirstThenTheRestAlphabetically() {
        val apps = listOf("Chrome" to "com.android.chrome", "Weather" to "com.x.weather", "Calendar" to "com.x.cal", "AccuWeather" to "com.accuweather")
        val names = WeatherApps.ordered(apps, { it.first }, { it.second }).map { it.first }
        assertEquals(listOf("AccuWeather", "Weather", "Calendar", "Chrome"), names)
    }
}
