package io.github.iamth3doctor.searchlight.apps

import io.github.iamth3doctor.searchlight.apps.BadgeCounts.Posted
import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeCountsTest {
    @Test fun countsPostedNotificationsPerApp() {
        val posted = listOf(
            Posted("chat", ongoing = false, groupSummary = false),
            Posted("chat", ongoing = false, groupSummary = false),
            Posted("chat", ongoing = false, groupSummary = true), // the group's summary: not another one
            Posted("music", ongoing = true, groupSummary = false), // now playing: not unread
            Posted("mail", ongoing = false, groupSummary = false),
        )
        assertEquals(mapOf("chat" to 2, "mail" to 1), BadgeCounts.count(posted))
    }

    @Test fun nothingPostedNoBadges() = assertEquals(emptyMap<String, Int>(), BadgeCounts.count(emptyList()))
}
