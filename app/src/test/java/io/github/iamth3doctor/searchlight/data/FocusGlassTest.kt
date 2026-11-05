package io.github.iamth3doctor.searchlight.data

import io.github.iamth3doctor.searchlight.data.FocusGlass.State
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusGlassTest {
    private val out = State(70, null, false)

    @Test fun enterClearsAndExitRestoresExactly() {
        for (level in listOf(0, 50, 70, 100)) {
            val inFocus = FocusGlass.enter(State(level, null, false))
            assertEquals(0, inFocus.level)
            assertEquals(level, FocusGlass.exit(inFocus).level)
            assertEquals(null, FocusGlass.exit(inFocus).saved)
        }
    }

    @Test fun enteringAgainWhileInFocusKeepsTheSavedLevel() {
        val once = FocusGlass.enter(out)
        val twice = FocusGlass.enter(once)
        assertEquals(70, twice.saved)
        assertEquals(70, FocusGlass.exit(twice).level)
    }

    @Test fun inOutInOutStillRestoresTheOriginal() {
        val s = FocusGlass.exit(FocusGlass.enter(FocusGlass.exit(FocusGlass.enter(out))))
        assertEquals(70, s.level)
    }

    @Test fun sliderMovedInFocusWins() {
        val moved = FocusGlass.userSet(FocusGlass.enter(out), 35)
        assertEquals(35, moved.level)
        assertEquals(35, FocusGlass.exit(moved).level)
    }

    @Test fun sliderOutsideFocusIsJustTheSetting() {
        val s = FocusGlass.userSet(out, 20)
        assertEquals(State(20, null, false), s)
        // And a later Focus round trip restores that.
        assertEquals(20, FocusGlass.exit(FocusGlass.enter(s)).level)
    }
}
