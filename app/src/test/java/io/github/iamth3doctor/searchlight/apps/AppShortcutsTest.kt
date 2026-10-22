package io.github.iamth3doctor.searchlight.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppShortcutsTest {

    /** kind: 0 manifest, 1 dynamic, 2 pinned only. */
    private data class S(val id: String, val kind: Int, val rank: Int, val enabled: Boolean = true)

    private fun pick(vararg s: S) = AppShortcuts.pick(s.toList(), { it.id }, { it.enabled }, { it.kind }, { it.rank }).map { it.id }

    @Test fun manifestThenDynamicThenPinnedEachByRank() {
        assertEquals(
            listOf("portrait", "selfie", "recent", "pinned"),
            pick(S("pinned", 2, 0), S("recent", 1, 0), S("selfie", 0, 1), S("portrait", 0, 0)),
        )
    }

    @Test fun disabledAreLeftOutAndAtMostFourShow() {
        assertEquals(
            listOf("a", "c", "d", "e"),
            pick(S("a", 0, 0), S("b", 0, 1, enabled = false), S("c", 0, 2), S("d", 1, 0), S("e", 1, 1), S("f", 1, 2)),
        )
    }
}
