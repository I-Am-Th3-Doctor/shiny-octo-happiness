package io.github.iamth3doctor.searchlight.search

import io.github.iamth3doctor.searchlight.search.PrefixShortcuts.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefixShortcutsTest {
    @Test fun keySpaceQuery() {
        assertEquals(Target.YOUTUBE, PrefixShortcuts.match("y lofi")!!.target)
        assertEquals("lofi", PrefixShortcuts.match("y lofi")!!.query)
        assertEquals(Target.MAPS, PrefixShortcuts.match("m tacos")!!.target)
        assertEquals("tacos near me", PrefixShortcuts.match("M  tacos near me ")!!.query)
        assertEquals(Target.WIKIPEDIA, PrefixShortcuts.match("w alan turing")!!.target)
    }

    @Test fun notShortcuts() {
        assertNull(PrefixShortcuts.match("youtube"))
        assertNull(PrefixShortcuts.match("y "))
        assertNull(PrefixShortcuts.match("x lofi"))
        assertNull(PrefixShortcuts.match("maps"))
    }

    @Test fun hintsWhileTypingAKey() {
        assertEquals(listOf(Target.YOUTUBE), PrefixShortcuts.hints("y"))
        assertEquals(listOf(Target.MAPS), PrefixShortcuts.hints("m "))
        assertEquals(emptyList<Target>(), PrefixShortcuts.hints("ma"))
        assertEquals(emptyList<Target>(), PrefixShortcuts.hints("y lofi"))
        assertEquals(emptyList<Target>(), PrefixShortcuts.hints(""))
    }
}
