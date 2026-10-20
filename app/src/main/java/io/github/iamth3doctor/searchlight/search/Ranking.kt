package io.github.iamth3doctor.searchlight.search

import kotlin.math.ln
import kotlin.math.min

/** Ranks items for a query: match quality first, then how often you launch them. Pure Kotlin. */
object Ranking {

    /** Up to +80 for frequently launched items; always smaller than the gap between match tiers. */
    fun usageBoost(launchCount: Int): Int = if (launchCount <= 0) 0 else min(80, (20 * ln(1.0 + launchCount)).toInt())

    fun <T> rank(
        rawQuery: String,
        items: List<T>,
        key: (T) -> MatchKey,
        launchCount: (T) -> Int,
        limit: Int = 40,
    ): List<T> = rankScored(rawQuery, items, key, launchCount, limit).map { it.first }

    /** Like [rank], keeping each item's final score so different sources can be merged. */
    fun <T> rankScored(
        rawQuery: String,
        items: List<T>,
        key: (T) -> MatchKey,
        launchCount: (T) -> Int,
        limit: Int = 40,
    ): List<Pair<T, Int>> {
        val q = FuzzyMatcher.normalize(rawQuery)
        if (q.isEmpty()) return emptyList()
        val scored = ArrayList<Scored<T>>()
        for (item in items) {
            val k = key(item)
            val s = FuzzyMatcher.score(q, k)
            if (s > 0) scored += Scored(item, s + usageBoost(launchCount(item)), k.original)
        }
        scored.sortWith(
            compareByDescending<Scored<T>> { it.score }
                .thenBy { it.label.length }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        )
        return scored.asSequence().take(limit).map { it.item to it.score }.toList()
    }

    private class Scored<T>(val item: T, val score: Int, val label: String)
}
