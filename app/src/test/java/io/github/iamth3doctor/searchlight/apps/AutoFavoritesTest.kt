package io.github.iamth3doctor.searchlight.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoFavoritesTest {

    private val apps = mapOf("mail" to "0/mail/.Main", "maps" to "0/maps/.Main", "chat" to "0/chat/.Main", "music" to "0/music/.Main")

    @Test fun ranksByLaunchesNotScreens() {
        // Three screens inside maps in a row are one launch; mail is launched twice.
        val events = listOf("maps" to 1L, "maps" to 2L, "maps" to 3L, "home" to 4L, "mail" to 5L, "home" to 6L, "mail" to 7L)
        assertEquals(listOf("0/mail/.Main", "0/maps/.Main"), AutoFavorites.rank(events, apps))
    }

    @Test fun tiesGoToTheMostRecent() {
        val events = listOf("chat" to 1L, "home" to 2L, "music" to 3L)
        assertEquals(listOf("0/music/.Main", "0/chat/.Main"), AutoFavorites.rank(events, apps))
    }

    @Test fun uninstalledAndExcludedAppsNeverRank() {
        // "gone" was uninstalled and "home" is Searchlight: neither is a candidate.
        val events = listOf("gone" to 1L, "home" to 2L, "gone" to 3L, "home" to 4L, "chat" to 5L)
        assertEquals(listOf("0/chat/.Main"), AutoFavorites.rank(events, apps))
    }

    @Test fun keepsAFewSpares() {
        val many = (1..20).associate { "p$it" to "k$it" }
        val events = (1..20).flatMap { i -> List(i) { listOf("p$i" to i.toLong(), "home" to 0L) }.flatten() }
        assertEquals((20 downTo 9).map { "k$it" }, AutoFavorites.rank(events, many))
    }

    @Test fun predictionFavoursAppsUsedAtThisTimeOfDay() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val h = 60L * 60 * 1000
        val day = 24 * h
        // Maps: three launches a day at 8:00. Music: five launches, all at 20:00.
        val events = (0 until 3).flatMap { d -> listOf("maps" to d * day + 8 * h, "home" to d * day + 8 * h + 1) } +
            (0 until 5).flatMap { d -> listOf("music" to d * day + 20 * h, "home" to d * day + 20 * h + 1) }
        val sorted = events.sortedBy { it.second }
        val morning = AutoFavorites.predict(sorted, apps, now = 10 * day + 8 * h, zone = utc)
        val evening = AutoFavorites.predict(sorted, apps, now = 10 * day + 20 * h, zone = utc)
        assertEquals("0/maps/.Main", morning.first())
        assertEquals("0/music/.Main", evening.first())
    }

    @Test fun predictionCountsLaunchesNotScreensAndSkipsNonCandidates() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val events = listOf("chat" to 1L, "chat" to 2L, "chat" to 3L, "home" to 4L, "gone" to 5L, "home" to 6L, "gone" to 7L)
        assertEquals(listOf("0/chat/.Main"), AutoFavorites.predict(events, apps, now = 10L, zone = utc))
    }
}
