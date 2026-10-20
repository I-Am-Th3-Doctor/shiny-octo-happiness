package io.github.iamth3doctor.searchlight.search

import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.contacts.ContactEntry
import io.github.iamth3doctor.searchlight.contacts.ContactRepository
import io.github.iamth3doctor.searchlight.data.Prefs

/**
 * The synchronous part of search: calculator, apps and contacts. Everything here is in memory and
 * pre-normalized, so it runs on the main thread in well under a millisecond per keystroke.
 * Files are searched separately (see FileSearch) and appended when they arrive.
 */
class SearchEngine(
    private val prefs: Prefs,
    private val apps: AppRepository,
    private val contacts: ContactRepository,
) {
    fun local(query: String): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        Calculator.evaluate(query)?.let { out += SearchResult.Calc(query.trim(), it) }

        val scored = ArrayList<Pair<SearchResult, Int>>()
        Ranking.rankScored(query, apps.apps, { it.matchKey }, { prefs.launchCount(it.key) }, MAX_APPS)
            .forEach { (app, score) -> scored += SearchResult.App(app) to score }
        searchContacts(query).forEach { (c, score) -> scored += SearchResult.Contact(c) to score - CONTACT_PENALTY }
        scored.sortByDescending { it.second }
        scored.mapTo(out) { it.first }
        return out
    }

    private fun searchContacts(query: String): List<Pair<ContactEntry, Int>> {
        val list = contacts.contacts
        if (list.isEmpty()) return emptyList()
        val byName = Ranking.rankScored(query, list, { it.matchKey }, { if (it.starred) STARRED_BOOST else 0 }, MAX_CONTACTS)

        // "555 01" finds a number: digits only, at least three of them.
        val digits = query.filter { it.isDigit() }
        val isNumberQuery = digits.length >= 3 && query.all { it.isDigit() || it in " +-()." }
        if (!isNumberQuery) return byName
        val byNumber = list.filter { it.digits.contains(digits) }.take(MAX_CONTACTS).map { it to NUMBER_SCORE }
        return (byName + byNumber).distinctBy { it.first.key }.take(MAX_CONTACTS)
    }

    private companion object {
        const val MAX_APPS = 8
        const val MAX_CONTACTS = 5
        /** Apps are what you usually want from a launcher; a contact wins only on a clearly better match. */
        const val CONTACT_PENALTY = 30
        const val STARRED_BOOST = 3
        const val NUMBER_SCORE = 700
    }
}
