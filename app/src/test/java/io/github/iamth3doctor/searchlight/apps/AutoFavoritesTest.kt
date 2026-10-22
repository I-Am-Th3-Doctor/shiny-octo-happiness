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
}
