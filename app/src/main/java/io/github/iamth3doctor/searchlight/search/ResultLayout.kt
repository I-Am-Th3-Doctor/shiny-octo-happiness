package io.github.iamth3doctor.searchlight.search

/** Result sections, in display order: the app grid first, then list sections. */
enum class Section { APPS, ANSWER, CONTACTS, MUSIC, PHOTOS, MESSAGES, FILES, ACTIONS }

/**
 * Turns a flat result list into what the results view shows: the apps as one grid block on top
 * (all of them), then list rows grouped under small section headers. Also owns keyboard/selection movement through that
 * shape. Pure Kotlin (generic over the item type); JVM-tested.
 */
class ResultLayout<T>(items: List<T>, sectionOf: (T) -> Section) {

    sealed interface Row<T> {
        data class Header<T>(val section: Section) : Row<T>
        /** All app results as one grid block; their selection indices are 0 until items.size. */
        data class AppGrid<T>(val items: List<T>) : Row<T>
        /** A full-width list row (contact, file, web search…). */
        data class Line<T>(val item: T, val index: Int) : Row<T>
    }

    /** What the adapter renders, top to bottom. */
    val rows: List<Row<T>>

    /** Every selectable item in selection order: grid cells left-to-right, top-to-bottom, then lines. */
    val selectable: List<T>

    /** How many of [selectable] are grid cells (they come first). */
    val gridCount: Int

    init {
        val grouped = items.groupBy(sectionOf)
        val out = ArrayList<Row<T>>()
        val sel = ArrayList<T>()
        for (section in Section.entries) {
            val group = grouped[section] ?: continue
            out += Row.Header(section)
            if (section == Section.APPS) {
                out += Row.AppGrid(group)
                sel += group
            } else {
                for (item in group) {
                    out += Row.Line(item, sel.size)
                    sel += item
                }
            }
        }
        rows = out
        selectable = sel
        gridCount = grouped[Section.APPS]?.size ?: 0
    }

    /** Adapter position of selection [index]: the grid block for apps, the row otherwise. */
    fun positionOf(index: Int): Int = rows.indexOfFirst {
        (it is Row.AppGrid && index < gridCount) || (it is Row.Line && it.index == index)
    }

    enum class Move { LEFT, RIGHT, UP, DOWN, NEXT, PREVIOUS }

    /** Where the selection goes from [from] with [columns] grid columns. Never leaves the results. */
    fun move(from: Int, move: Move, columns: Int): Int {
        val total = selectable.size
        if (total == 0) return -1
        val sel = from.coerceIn(0, total - 1)
        val inGrid = sel < gridCount
        val cols = columns.coerceAtLeast(1)
        return when (move) {
            Move.NEXT -> (sel + 1).coerceAtMost(total - 1)
            Move.PREVIOUS -> (sel - 1).coerceAtLeast(0)
            Move.RIGHT -> if (inGrid && sel % cols < cols - 1 && sel + 1 < gridCount) sel + 1 else sel
            Move.LEFT -> if (inGrid && sel % cols > 0) sel - 1 else sel
            Move.DOWN -> when {
                !inGrid -> (sel + 1).coerceAtMost(total - 1)
                sel + cols < gridCount -> sel + cols
                // A shorter last row below: land on its last tile.
                sel / cols < (gridCount - 1) / cols -> gridCount - 1
                // Bottom row of the grid: into the list, if there is one.
                gridCount < total -> gridCount
                else -> sel
            }
            Move.UP -> when {
                inGrid -> if (sel - cols >= 0) sel - cols else sel
                // First list row: up into the grid's last row.
                sel == gridCount && gridCount > 0 -> ((gridCount - 1) / cols) * cols
                else -> (sel - 1).coerceAtLeast(0)
            }
        }
    }
}
