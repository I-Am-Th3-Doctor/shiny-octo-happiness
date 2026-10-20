package io.github.iamth3doctor.searchlight.widgets

import kotlin.math.ceil

/** A placed widget, in grid cells (never pixels): top-left cell plus how many cells it spans. */
data class GridSpec(val id: Int, val col: Int, val row: Int, val colSpan: Int, val rowSpan: Int) {

    fun overlaps(o: GridSpec) =
        col < o.col + o.colSpan && o.col < col + colSpan && row < o.row + o.rowSpan && o.row < row + rowSpan

    fun inBounds(cols: Int, rows: Int) =
        col >= 0 && row >= 0 && colSpan >= 1 && rowSpan >= 1 && col + colSpan <= cols && row + rowSpan <= rows
}

/**
 * The free-placement widget grid: storage format, collision rules, first-fit placement, and
 * re-fitting when the grid changes size (fold/unfold). Pure; JVM-tested.
 */
object WidgetGrid {

    /** Shown widgets, plus ids that don't fit the current grid at all (kept in storage, just not drawn). */
    data class Fit(val shown: List<GridSpec>, val hidden: List<Int>)

    // ------------------------------------------------------------------ storage: "id:col:row:w:h;…"

    fun parse(raw: String?): List<GridSpec> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = HashSet<Int>()
        return raw.split(';').mapNotNull { part ->
            val n = part.trim().split(':').map { it.toIntOrNull() ?: return@mapNotNull null }
            if (n.size != 5) return@mapNotNull null
            val s = GridSpec(n[0], n[1], n[2], n[3], n[4])
            if (s.id < 0 || s.col < 0 || s.row < 0 || s.colSpan < 1 || s.rowSpan < 1 || !seen.add(s.id)) null else s
        }
    }

    fun serialize(list: List<GridSpec>) = list.joinToString(";") { "${it.id}:${it.col}:${it.row}:${it.colSpan}:${it.rowSpan}" }

    /** Replaces the entry with the same id (or appends it). */
    fun upsert(list: List<GridSpec>, spec: GridSpec): List<GridSpec> =
        if (list.any { it.id == spec.id }) list.map { if (it.id == spec.id) spec else it } else list + spec

    fun remove(list: List<GridSpec>, id: Int) = list.filter { it.id != id }

    // ------------------------------------------------------------------ geometry

    /**
     * Grid dimensions for the area above the home card. A typical phone gives 4 × 6; wider screens
     * (unfolded) get more columns so cells stay roughly the same physical size.
     */
    fun gridSize(widthDp: Float, heightDp: Float): Pair<Int, Int> {
        val cols = (widthDp / 96f).toInt().coerceIn(4, 8)
        val rows = (heightDp / 80f).toInt().coerceIn(3, 8)
        return cols to rows
    }

    /** How many cells a widget of [sizeDp] needs when cells are [cellDp] big. */
    fun spanFor(sizeDp: Float, cellDp: Float, max: Int): Int =
        if (cellDp <= 0f) 1 else ceil((sizeDp / cellDp) - 0.15f).toInt().coerceIn(1, max.coerceAtLeast(1))

    fun fits(candidate: GridSpec, others: List<GridSpec>, cols: Int, rows: Int) =
        candidate.inBounds(cols, rows) && others.none { it.id != candidate.id && it.overlaps(candidate) }

    /** Top-left-most free spot (row by row) for a [colSpan] × [rowSpan] widget, or null. */
    fun firstFree(others: List<GridSpec>, id: Int, colSpan: Int, rowSpan: Int, cols: Int, rows: Int): GridSpec? {
        for (r in 0..rows - rowSpan) for (c in 0..cols - colSpan) {
            val s = GridSpec(id, c, r, colSpan, rowSpan)
            if (fits(s, others, cols, rows)) return s
        }
        return null
    }

    /**
     * Places a widget at its preferred span if there's room, otherwise the biggest smaller span
     * (down to [minColSpan] × [minRowSpan]) that fits anywhere. Null = no room at all.
     */
    fun place(
        others: List<GridSpec>, id: Int, colSpan: Int, rowSpan: Int,
        minColSpan: Int, minRowSpan: Int, cols: Int, rows: Int,
    ): GridSpec? {
        val w0 = colSpan.coerceIn(1, cols)
        val h0 = rowSpan.coerceIn(1, rows)
        val candidates = ArrayList<Pair<Int, Int>>()
        for (w in w0 downTo minColSpan.coerceIn(1, w0)) for (h in h0 downTo minRowSpan.coerceIn(1, h0)) candidates += w to h
        candidates.sortByDescending { it.first * it.second }
        for ((w, h) in candidates) firstFree(others, id, w, h, cols, rows)?.let { return it }
        return null
    }

    /**
     * Fits stored positions into a (possibly smaller) grid without losing them: everything that's
     * still in bounds and unobstructed stays exactly where it was; the rest is nudged inside, then
     * moved to the first free space, then shrunk. Anything that still can't fit is hidden.
     * Storage isn't changed, so unfolding again restores the original layout.
     */
    fun fitToGrid(list: List<GridSpec>, cols: Int, rows: Int): Fit {
        val kept = ArrayList<GridSpec>()
        for (s in list) if (s.inBounds(cols, rows) && kept.none { it.overlaps(s) }) kept += s
        val hidden = ArrayList<Int>()
        for (s in list) {
            if (kept.any { it.id == s.id }) continue
            val w = s.colSpan.coerceIn(1, cols)
            val h = s.rowSpan.coerceIn(1, rows)
            val nudged = GridSpec(s.id, s.col.coerceIn(0, cols - w), s.row.coerceIn(0, rows - h), w, h)
            val spot = if (fits(nudged, kept, cols, rows)) nudged else place(kept, s.id, w, h, 1, 1, cols, rows)
            if (spot != null) kept += spot else hidden += s.id
        }
        val order = list.withIndex().associate { it.value.id to it.index }
        return Fit(kept.sortedBy { order[it.id] }, hidden)
    }

    /** One-time move from the old single-column stack (id → height in dp) to grid cells. */
    fun fromStack(stack: List<Pair<Int, Int>>, cols: Int, rows: Int, cellHeightDp: Float): List<GridSpec> {
        val out = ArrayList<GridSpec>()
        for ((id, heightDp) in stack) {
            val h = spanFor(heightDp.toFloat(), cellHeightDp, rows)
            place(out, id, cols, h, 1, 1, cols, rows)?.let { out += it }
        }
        return out
    }
}
