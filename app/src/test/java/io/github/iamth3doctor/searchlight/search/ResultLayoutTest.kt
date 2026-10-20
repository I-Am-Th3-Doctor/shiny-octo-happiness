package io.github.iamth3doctor.searchlight.search

import io.github.iamth3doctor.searchlight.search.ResultLayout.Move
import org.junit.Assert.assertEquals
import org.junit.Test

class ResultLayoutTest {

    // "a1".."a6" are apps; "c" contact, "f" file, "w" web search.
    private fun section(s: String) = when (s[0]) {
        'a' -> Section.APPS
        'c' -> Section.CONTACTS
        'f' -> Section.FILES
        '=' -> Section.ANSWER
        else -> Section.ACTIONS
    }

    private fun layout(vararg items: String) = ResultLayout(items.toList(), ::section)

    @Test fun appsFirstThenSectionsWithHeaders() {
        val l = layout("w", "f1", "a1", "c1", "a2", "=")
        assertEquals(listOf("a1", "a2", "=", "c1", "f1", "w"), l.selectable)
        assertEquals(2, l.gridCount)
        val shape = l.rows.map {
            when (it) {
                is ResultLayout.Row.Header -> "#${it.section}"
                is ResultLayout.Row.Cell -> "cell:${it.item}"
                is ResultLayout.Row.Line -> "line:${it.item}"
            }
        }
        assertEquals(
            listOf("#APPS", "cell:a1", "cell:a2", "#ANSWER", "line:=", "#CONTACTS", "line:c1", "#FILES", "line:f1", "#ACTIONS", "line:w"),
            shape,
        )
        assertEquals(4, l.positionOf(2)) // "=" sits after its header
    }

    @Test fun gridMovement4Columns() {
        // a1 a2 a3 a4
        // a5 a6
        // c1
        // w
        val l = layout("a1", "a2", "a3", "a4", "a5", "a6", "c1", "w")
        assertEquals(1, l.move(0, Move.RIGHT, 4))
        assertEquals(3, l.move(3, Move.RIGHT, 4))   // row end: stays
        assertEquals(5, l.move(5, Move.RIGHT, 4))   // last tile: stays
        assertEquals(0, l.move(0, Move.LEFT, 4))
        assertEquals(4, l.move(0, Move.DOWN, 4))
        assertEquals(5, l.move(3, Move.DOWN, 4))    // nothing under a4: last tile of the shorter row
        assertEquals(6, l.move(4, Move.DOWN, 4))    // bottom grid row: into the list
        assertEquals(7, l.move(6, Move.DOWN, 4))
        assertEquals(7, l.move(7, Move.DOWN, 4))    // never leaves the results
        assertEquals(4, l.move(6, Move.UP, 4))      // list → first tile of the last grid row
        assertEquals(1, l.move(5, Move.UP, 4))
        assertEquals(1, l.move(1, Move.UP, 4))
        assertEquals(6, l.move(5, Move.NEXT, 4))    // tab walks everything in order
        assertEquals(5, l.move(6, Move.PREVIOUS, 4))
    }

    @Test fun sixColumnsUnfolded() {
        val l = layout("a1", "a2", "a3", "a4", "a5", "a6", "a7", "c1")
        assertEquals(6, l.move(0, Move.DOWN, 6))
        assertEquals(6, l.move(5, Move.DOWN, 6))    // shorter last row
        assertEquals(7, l.move(6, Move.DOWN, 6))
        assertEquals(5, l.move(4, Move.RIGHT, 6))
    }

    @Test fun listOnlyAndEmpty() {
        val l = layout("c1", "f1", "w")
        assertEquals(0, l.gridCount)
        assertEquals(1, l.move(0, Move.DOWN, 4))
        assertEquals(0, l.move(1, Move.UP, 4))
        assertEquals(0, l.move(0, Move.RIGHT, 4))
        assertEquals(-1, layout().move(0, Move.DOWN, 4))
    }
}
