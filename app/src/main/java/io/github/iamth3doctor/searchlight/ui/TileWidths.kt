package io.github.iamth3doctor.searchlight.ui

/**
 * Widths for tiles side by side in one row. Tiles share the width equally, except that any
 * tile whose minimum (what its buttons need) is more than an equal share gets its minimum, and
 * the others share what's left. If even the minimums don't fit, they shrink in proportion.
 * Pure; JVM-tested.
 */
object TileWidths {

    /**
     * The unfolded at-a-glance row. Fixed tiles ([flexible] false) get at least their [needs]
     * (what shows them whole: the temperature, the time, the battery, the four toggles);
     * flexible ones (Music) need their floor or they leave the row (0). What's left over is
     * shared by every tile in it. Only when even the fixed tiles can't fit do they shrink, in
     * proportion. Pure; JVM-tested.
     */
    fun fit(total: Int, needs: List<Int>, flexible: List<Boolean>): List<Int> {
        if (needs.isEmpty()) return emptyList()
        val fixed = needs.indices.filter { !flexible[it] }
        val flex = needs.indices.filter { flexible[it] }
        val fixedSum = fixed.sumOf { needs[it] }
        if (fixedSum >= total) {
            val shrunk = split(total, fixed.map { needs[it] })
            return needs.indices.map { i -> if (i in flex) 0 else shrunk[fixed.indexOf(i)] }
        }
        val shown = if (fixedSum + flex.sumOf { needs[it] } <= total) needs.indices.toList() else fixed
        val extra = total - shown.sumOf { needs[it] }
        val each = extra / shown.size
        var left = extra - each * shown.size
        return needs.indices.map { i ->
            if (i !in shown) 0 else needs[i] + each + if (left > 0) { left--; 1 } else 0
        }
    }

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
