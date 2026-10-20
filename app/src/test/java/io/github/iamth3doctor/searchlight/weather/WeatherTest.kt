package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {

    @Test fun conversionIsRealAndRounded() {
        assertEquals(32, Temperature.convert(0.0, TempUnit.FAHRENHEIT))
        assertEquals(212, Temperature.convert(100.0, TempUnit.FAHRENHEIT))
        assertEquals(-40, Temperature.convert(-40.0, TempUnit.FAHRENHEIT))
        assertEquals(70, Temperature.convert(21.3, TempUnit.FAHRENHEIT))
        assertEquals(21, Temperature.convert(21.3, TempUnit.CELSIUS))
        assertEquals(22, Temperature.convert(21.5, TempUnit.CELSIUS))
        assertEquals("70°", Temperature.format(21.3, TempUnit.FAHRENHEIT))
        assertEquals("21°", Temperature.format(21.3, TempUnit.CELSIUS))
    }

    @Test fun sameStoredValueGivesBothUnits() {
        // The bug this app exists to fix: the number must change when the unit changes.
        val w = sample()
        assertEquals("77°", Temperature.format(w.temperatureC, TempUnit.FAHRENHEIT))
        assertEquals("25°", Temperature.format(w.temperatureC, TempUnit.CELSIUS))
    }

    @Test fun forecastUrlAlwaysCelsiusAndRounded() {
        val url = OpenMeteo.forecastUrl(30.267153, -97.743057)
        assertTrue(url, url.contains("latitude=30.27&longitude=-97.74"))
        assertTrue(url.contains("temperature_unit=celsius"))
        assertTrue(url.contains("current=temperature_2m,weather_code,is_day"))
        assertTrue(url.contains("daily=temperature_2m_max,temperature_2m_min"))
    }

    @Test fun parsesForecast() {
        val json = """
            {"latitude":30.27,"longitude":-97.74,"timezone":"America/Chicago",
             "current":{"time":"2026-10-20T14:00","interval":900,"temperature_2m":25.0,"weather_code":2,"is_day":1},
             "daily":{"time":["2026-10-20"],"temperature_2m_max":[28.4],"temperature_2m_min":[16.1]}}
        """.trimIndent()
        val w = OpenMeteo.parseForecast(json, "Austin", 30.27, -97.74, now = 123L)
        assertEquals(25.0, w.temperatureC, 0.0)
        assertEquals(28.4, w.highC, 0.0)
        assertEquals(16.1, w.lowC, 0.0)
        assertEquals("Partly cloudy", w.condition)
        assertTrue(w.isDay)
        assertEquals(123L, w.fetchedAt)
        assertEquals("83°", Temperature.format(w.highC, TempUnit.FAHRENHEIT))
        assertEquals("61°", Temperature.format(w.lowC, TempUnit.FAHRENHEIT))
    }

    @Test fun highLowNeverContradictCurrent() {
        val json = """{"current":{"temperature_2m":30.0,"weather_code":0,"is_day":0},
            "daily":{"temperature_2m_max":[28.0],"temperature_2m_min":[18.0]}}"""
        val w = OpenMeteo.parseForecast(json, "X", 0.0, 0.0, 0)
        assertEquals(30.0, w.highC, 0.0)
        assertFalse(w.isDay)
        assertEquals("🌙", w.emoji)
    }

    @Test fun parsesGeocode() {
        val json = """{"results":[
            {"id":1,"name":"Austin","latitude":30.26715,"longitude":-97.74306,"country_code":"US","admin1":"Texas"},
            {"id":2,"name":"Austin","latitude":43.66,"longitude":-92.97,"country_code":"US","admin1":"Minnesota"}],
            "generationtime_ms":0.5}"""
        val places = OpenMeteo.parseGeocode(json)
        assertEquals(2, places.size)
        assertEquals("Austin, Texas, US", places[0].label)
        assertEquals(43.66, places[1].latitude, 0.0)
        assertTrue(OpenMeteo.parseGeocode("""{"generationtime_ms":0.2}""").isEmpty())
        assertTrue(OpenMeteo.geocodeUrl("New York").contains("name=New+York"))
    }

    @Test fun cacheRoundTrip() {
        val w = sample()
        assertEquals(w, WeatherData.fromJson(w.toJson()))
        assertEquals(null, WeatherData.fromJson("garbage"))
    }

    @Test fun codes() {
        assertEquals("Clear sky", WeatherCodes.describe(0))
        assertEquals("Thunderstorm with hail", WeatherCodes.describe(99))
        assertEquals("☀️", WeatherCodes.emoji(0, true))
    }

    private fun sample() = WeatherData("Austin", 30.27, -97.74, 25.0, 0, true, 28.0, 16.0, 1000L)
}
