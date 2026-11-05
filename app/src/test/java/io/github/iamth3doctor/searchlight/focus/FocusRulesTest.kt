package io.github.iamth3doctor.searchlight.focus

import io.github.iamth3doctor.searchlight.focus.FocusRules.Dnd
import io.github.iamth3doctor.searchlight.focus.FocusRules.NONE
import io.github.iamth3doctor.searchlight.focus.FocusRules.Restriction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusRulesTest {
    // NotificationManager's values: ALL = 1, PRIORITY = 2, NONE = 3, ALARMS = 4.
    private val all = 1
    private val priority = 2
    private val alarms = 4
    private val allowed = setOf("dialer", "sms")

    @Test fun onlyAllowedAppsShowInFocus() {
        assertTrue(FocusRules.shows("chrome", focus = false, allowed = allowed))
        assertFalse(FocusRules.shows("chrome", focus = true, allowed = allowed))
        assertTrue(FocusRules.shows("dialer", focus = true, allowed = allowed))
    }

    @Test fun blockedInterceptsHiddenDoesNot() {
        assertTrue(FocusRules.blocks("chrome", true, allowed, Restriction.BLOCKED, "me"))
        assertFalse(FocusRules.blocks("chrome", true, allowed, Restriction.HIDDEN, "me"))
        assertFalse(FocusRules.blocks("dialer", true, allowed, Restriction.BLOCKED, "me"))
        assertFalse(FocusRules.blocks("chrome", false, allowed, Restriction.BLOCKED, "me"))
        assertFalse("Searchlight is never blocked", FocusRules.blocks("me", true, allowed, Restriction.BLOCKED, "me"))
    }

    @Test fun dndOnThenBackToExactlyWhatItWas() {
        val inFocus = FocusRules.dndEnter(all, NONE, true, priority, all)
        assertEquals(Dnd(priority, all), inFocus)
        assertEquals(Dnd(all, NONE), FocusRules.dndExit(inFocus.saved, true))
    }

    @Test fun alreadyInDndStaysAsItWasAndComesBackTheSame() {
        val inFocus = FocusRules.dndEnter(alarms, NONE, true, priority, all)
        assertEquals(Dnd(null, alarms), inFocus)
        assertEquals(Dnd(alarms, NONE), FocusRules.dndExit(inFocus.saved, true))
    }

    @Test fun enteringAgainNeverOverwritesTheSavedFilter() {
        val first = FocusRules.dndEnter(all, NONE, true, priority, all)
        // In Focus now the filter reads priority: entering again must keep "all" saved.
        assertEquals(Dnd(null, all), FocusRules.dndEnter(priority, first.saved, true, priority, all))
    }

    @Test fun withoutAccessNothingChangesAndNothingBreaks() {
        assertEquals(Dnd(null, NONE), FocusRules.dndEnter(all, NONE, false, priority, all))
        assertEquals(Dnd(null, NONE), FocusRules.dndExit(NONE, false))
        // Access revoked mid-focus: keep the saved filter for when it's back.
        assertEquals(Dnd(null, all), FocusRules.dndExit(all, false))
    }
}
