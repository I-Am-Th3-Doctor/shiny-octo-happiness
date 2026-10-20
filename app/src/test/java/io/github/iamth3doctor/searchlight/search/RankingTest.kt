package io.github.iamth3doctor.searchlight.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingTest {

    private val apps = listOf(
        "Chrome", "Chess", "Clock", "Calculator", "Calendar", "Camera", "Contacts",
        "Gmail", "Google Maps", "Google", "Maps", "YouTube", "YouTube Music", "WhatsApp",
        "Settings", "Spotify", "Signal", "Slack", "Messages", "Phone", "Photos",
        "Wi-Fi Analyzer", "Café Finder", "Play Store", "Files by Google", "Keep Notes",
    ).map { MatchKey(it) }

    private fun search(q: String, counts: Map<String, Int> = emptyMap()) =
        Ranking.rank(q, apps, { it }, { counts[it.original] ?: 0 }).map { it.original }

    private fun first(q: String, counts: Map<String, Int> = emptyMap()) = search(q, counts).firstOrNull()

    @Test fun twoLetterPrefix() {
        assertEquals("Gmail", first("gm"))
        assertEquals("WhatsApp", first("wh"))
        assertEquals("Spotify", first("sp"))
        assertEquals("Phone", first("ph"))
    }

    @Test fun usageBreaksTiesWithinATier() {
        assertEquals("Chess", first("ch"))
        assertEquals("Chrome", first("ch", mapOf("Chrome" to 3)))
    }

    @Test fun usageNeverBeatsABetterTier() {
        // Google Maps is used a lot, but "gm" is still a prefix of Gmail.
        assertEquals("Gmail", first("gm", mapOf("Google Maps" to 10_000)))
    }

    @Test fun initialsAndCamelCase() {
        assertTrue(search("gm").indexOf("Google Maps") in 1..2)
        assertEquals("YouTube", first("yt"))
        assertEquals("YouTube Music", first("ytm"))
        assertEquals("Play Store", first("ps"))
    }

    @Test fun laterWord() {
        assertEquals("Maps", first("maps"))
        assertEquals("Google Maps", search("maps")[1])
        assertEquals("Keep Notes", first("notes"))
    }

    @Test fun punctuationAccentsAndCase() {
        assertEquals("Wi-Fi Analyzer", first("wifi"))
        assertEquals("Café Finder", first("cafe"))
        assertEquals("Café Finder", first("CAFÉ"))
        assertEquals("Google Maps", first("google  m"))
    }

    @Test fun oneTypo() {
        assertEquals("Settings", first("setings"))
        assertEquals("Spotify", first("spotfy"))
        assertEquals("Calendar", first("calender"))
    }

    @Test fun subsequence() {
        assertTrue("Calculator" in search("clc"))
    }

    @Test fun noMatch() {
        assertTrue(search("xyzq").isEmpty())
        assertTrue(search("   ").isEmpty())
    }

    @Test fun singleLetterOnlyMatchesWordStarts() {
        val results = search("k")
        assertEquals("Keep Notes", results.first())
        assertTrue("Clock" !in results)
    }
}
