package io.github.iamth3doctor.searchlight.search

import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.data.Prefs

/**
 * Phase 1: apps only. Phase 2 adds contacts, files, calculator and the web fallback as
 * further result types merged here.
 *
 * Runs synchronously on the main thread: scoring a few hundred pre-normalized labels takes well
 * under a millisecond, which is faster than any thread hop.
 */
class SearchEngine(private val prefs: Prefs) {
    fun searchApps(query: String, apps: List<AppEntry>): List<AppEntry> =
        Ranking.rank(query, apps, { it.matchKey }, { prefs.launchCount(it.key) })
}
