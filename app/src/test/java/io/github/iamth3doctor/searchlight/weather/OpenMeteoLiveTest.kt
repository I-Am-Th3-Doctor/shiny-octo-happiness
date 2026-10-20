package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hits the real Open-Meteo API with the exact URLs the app builds and runs the responses
 * through the app's parsers. Skipped unless OPEN_METEO_LIVE=1 (CI runs it as a non-blocking step).
 */
class OpenMeteoLiveTest {

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        try {
            assertTrue("HTTP ${c.responseCode} for $url", c.responseCode == 200)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    @Test fun forecastAndGeocodingContract() {
        assumeTrue(System.getenv("OPEN_METEO_LIVE") == "1")

        val zip = OpenMeteo.parseGeocode(get(OpenMeteo.geocodeUrl("78701")))
        println("ZIP 78701 -> ${zip.map { it.label }}")
        assertTrue("US ZIP lookup returned nothing", zip.isNotEmpty())

        val city = OpenMeteo.parseGeocode(get(OpenMeteo.geocodeUrl("Chicago")))
        println("Chicago -> ${city.take(3).map { it.label }}")
        assertTrue(city.any { it.countryCode == "US" })

        val place = city.first { it.countryCode == "US" }
        val w = OpenMeteo.parseForecast(get(OpenMeteo.forecastUrl(place.latitude, place.longitude)), place.name, place.latitude, place.longitude, 0)
        println("Chicago now: ${Temperature.format(w.temperatureC, TempUnit.FAHRENHEIT)}F / ${Temperature.format(w.temperatureC, TempUnit.CELSIUS)}C, ${w.condition}")
        assertTrue(w.temperatureC in -60.0..60.0)
        assertTrue(w.lowC <= w.temperatureC && w.temperatureC <= w.highC)
        assertTrue(w.condition != "Unknown")
    }
}
