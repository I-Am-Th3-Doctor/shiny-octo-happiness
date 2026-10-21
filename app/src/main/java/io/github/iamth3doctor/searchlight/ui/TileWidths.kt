package io.github.iamth3doctor.searchlight.ui

/**
 * Widths for tiles side by side in one row: each gets its minimum (what its controls need),
 * then the rest is shared equally. If even the minimums don't fit, they shrink in proportion.
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
        val extra = total - sum
        val each = extra / minimums.size
        val left = extra - each * minimums.size
        return minimums.mapIndexed { i, m -> m + each + if (i < left) 1 else 0 }
    }
}
