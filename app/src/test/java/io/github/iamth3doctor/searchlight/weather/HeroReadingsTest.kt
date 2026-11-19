package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroReadingsTest {

    private val hour = 3_600_000L
    private val off = -8 * 3600
    // 2026-11-18 14:20 PST
    private val now = 1_795_550_400_000L + 2 * hour + 20 * 60_000L
    private val today = Moon.dayStart(now, off)
    private val start = now / hour * hour
    private val hours = (-3..30).map { i ->
        HourPoint(start + i * hour, 10.0 + i * 0.5, if (i in 2..4) 61 else 3, true, cloudPct = 80, precipMm = if (i in 2..4) 1.27 else 0.0,
            precipProb = 70, windMph = 12.0, windFromDeg = 200.0, pressureHpa = 1013.0 + i)
    }
    private val days = (0 until 7).map { DayPoint(today + it * 24 * hour, 12.0, 4.0, 3, 20, today + it * 24 * hour + 7 * hour + 25 * 60_000L,
        today + it * 24 * hour + 16 * hour + 40 * 60_000L, uvMax = 2.4) }
    private val data = WeatherData("Tacoma", 47.25, -122.44, 11.1, 3, true, 12.0, 4.0, now,
        WeatherDetail(off, 7.2, 81, 6.7, 1016.6, 24_140.0, 6.0, 200.0, 1.0, hours, days, null, cloudPct = 75, precipMm = 0.0))
    private fun time(ms: Long) = java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.ofTotalSeconds(off)).toLocalTime().toString()

    @Test fun fiveCalloutsWithWhatWasAskedFor() {
        val c = SceneModel.at(data, now, live = true)
        val out = HeroReadings.callouts(data, c, ::time).associateBy { it.key }
        assertEquals(HeroReadings.KEYS, out.keys.toList())
        assertEquals("WIND", out.getValue("wind").label)
        val wind = out.getValue("wind").lines.single()
        assertTrue(wind, wind.contains("6 mph") && wind.contains("SSW") && wind.startsWith(HeroReadings.arrow(20.0)))
        assertEquals(listOf("Cloud 75%", "Rain None"), out.getValue("sky").lines)
        assertEquals("↑ 07:25  ↓ 16:40", out.getValue("sun").lines[0])
        assertTrue(out.getValue("sun").lines[1], out.getValue("sun").lines[1].endsWith("% lit"))
        val temp = out.getValue("temp").lines
        assertEquals("52°F · Feels 45°", temp[0])
        assertEquals("Hum 81% · Dew 44°", temp[1])
        assertEquals("30.02 inHg", temp[2])
        assertEquals(listOf("Max UV 2 Low", "Vis 15 mi"), out.getValue("uv").lines)
        assertTrue("°F only", out.values.flatMap { it.lines }.none { it.contains("°C") })
    }

    @Test fun aScrubbedHourIsThatHours() {
        val t = start + 3 * hour
        val c = SceneModel.at(data, t, live = false)
        val out = HeroReadings.callouts(data, c, ::time).associateBy { it.key }
        assertEquals("Rain 0.05 in/h", out.getValue("sky").lines[1])
        assertEquals("Cloud 80%", out.getValue("sky").lines[0])
        assertEquals("30.00 inHg", out.getValue("temp").lines[2]) // that hour's 1016 hPa
        assertTrue(out.getValue("wind").lines.single().contains("12 mph"))
    }

    @Test fun noReadingDashes() {
        val c = SceneModel.at(data, now, live = true)
        assertTrue(HeroReadings.callouts(null, c, ::time).all { it.lines == listOf("—") })
    }

    @Test fun units() {
        assertEquals("None", HeroReadings.rainRate(0.0))
        assertEquals("< 0.01 in/h", HeroReadings.rainRate(0.1))
        assertEquals("0.10 in/h", HeroReadings.rainRate(2.54))
        assertEquals("29.92 inHg", HeroReadings.pressure(1013.25))
        assertEquals("→", HeroReadings.arrow(90.0))
        assertEquals("↙", HeroReadings.arrow(225.0))
        assertEquals("↑", HeroReadings.arrow(359.0))
    }

    @Test fun theTimelineIsNowThenTheNextHours() {
        val c = SceneModel.at(data, now, live = true)
        val m = HeroReadings.timeline(data, c)
        assertEquals(5, m.size)
        assertEquals(now, m[0].time)
        assertTrue(m[0].now)
        assertEquals(11.1, m[0].tempC!!, 0.0)
        assertEquals((1..4).map { start + it * hour }, m.drop(1).map { it.time })
        assertEquals((1..4).map { 10.0 + it * 0.5 }, m.drop(1).map { it.tempC })
        assertTrue(m.drop(1).none { it.now })
        // Scrubbed: from that hour, not NOW.
        val s = HeroReadings.timeline(data, SceneModel.at(data, start + 5 * hour, live = false))
        assertTrue(s.none { it.now })
        assertEquals(start + 6 * hour, s[1].time)
        // No reading: no temperatures.
        assertNull(HeroReadings.timeline(null, c)[0].tempC)
    }
}
