package io.github.iamth3doctor.searchlight.ui

/**
 * Widths for tiles side by side in one row. Tiles share the width equally, except that any
 * tile whose minimum (what its buttons need) is more than an equal share gets its minimum, and
 * the others share what's left. If even the minimums don't fit, they shrink in proportion.
 * Pure; JVM-tested.
 */
object TileWidths {
    fun split(total: Int, minimums: List<Int>): List<Int> {
        if (minimums.isEmpty()) return emptyList()
        val sum = minimums.sum()
        if (sum >= total) {
            if (sum == 0) return List(minimums.size) { 0 }
            val scaled = minimums.map { it * total / sum }
            return scaled.mapIndexed { i, w -> if (i == scaled.lastIndex) total - scaled.dropLast(1).sum() else w }
        }
        // Grow the set of tiles held at their minimum until the rest's share covers everyone.
        val atMin = HashSet<Int>()
        while (true) {
            val share = (total - atMin.sumOf { minimums[it] }).toDouble() / (minimums.size - atMin.size)
            val more = minimums.indices.filter { it !in atMin && minimums[it] > share }
            if (more.isEmpty()) break
            atMin += more
        }
        val rest = minimums.indices.filter { it !in atMin }
        val pool = total - atMin.sumOf { minimums[it] }
        val each = pool / rest.size
        var left = pool - each * rest.size
        return minimums.indices.map { i ->
            if (i in atMin) minimums[i] else each + if (left > 0) { left--; 1 } else 0
        }
    }
}
