package io.github.iamth3doctor.searchlight.search

import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.contacts.ContactEntry
import io.github.iamth3doctor.searchlight.contacts.ContactRepository
import io.github.iamth3doctor.searchlight.data.Prefs

/**
 * The synchronous part of search: calculator, apps and (from 2 characters) contacts. Everything here is in memory and
 * pre-normalized, so it runs on the main thread in well under a millisecond per keystroke.
 * Files are searched separately (see FileSearch) and appended when they arrive.
 */
class SearchEngine(
    private val prefs: Prefs,
    private val apps: AppRepository,
    private val contacts: ContactRepository,
    private val currency: CurrencyRates,
    private val format: Format,
) {
    /** The wording of answer rows (from string resources). */
    interface Format {
        fun unitDetail(query: String): String
        fun currencyDetail(rate: String, from: String, to: String, date: String): String
        fun currencyPending(query: String): String
    }

    /** Which apps may show (Focus: only allowed ones); everything else in search is unaffected. */
    var appVisible: (io.github.iamth3doctor.searchlight.apps.AppEntry) -> Boolean = { true }

    /** Which packages a prefix shortcut may open (Focus: a restricted app's search doesn't show). */
    var packageVisible: (String) -> Boolean = { true }

    fun local(query: String): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        Calculator.evaluate(query)?.let { out += SearchResult.Calc(query.trim(), it) }
        answers(query, out)

        val scored = ArrayList<Pair<SearchResult, Int>>()
        Ranking.rankScored(query, apps.apps.filter(appVisible), { it.matchKey }, { prefs.launchCount(it.key) }, limit = Int.MAX_VALUE)
            .forEach { (app, score) -> scored += SearchResult.App(app) to score }
        if (searchesContacts(query)) {
            searchContacts(query).forEach { (c, score) -> scored += SearchResult.Contact(c) to score - CONTACT_PENALTY }
        }
        scored.sortByDescending { it.second }
        scored.mapTo(out) { it.first }
        return out
    }

    /** Conversions, a prefix shortcut, or shortcut hints: rows above the rest, in Answer. */
    private fun answers(query: String, out: MutableList<SearchResult>) {
        UnitConverter.convert(query)?.let { a ->
            out += SearchResult.Convert(a.text, format.unitDetail(query.trim()), a.text.substringBefore(' ').replace(",", ""))
        }
        CurrencyConverter.parse(query)?.let { ask ->
            currency.refreshIfStale() // new rates re-run the search (MainActivity)
            val rates = currency.cached
            val a = rates?.let { CurrencyConverter.convert(ask, it) }
            out += if (a != null) {
                val rate = String.format(java.util.Locale.US, "%.4f", a.rate)
                SearchResult.Convert(a.text, format.currencyDetail(rate, ask.from, ask.to, a.date), a.text.substringBefore(' ').replace(",", ""))
            } else {
                SearchResult.Convert("${ask.from} → ${ask.to}", format.currencyPending(query.trim()), "")
            }
        }
        PrefixShortcuts.match(query)?.takeIf { m -> m.target.packageName?.let(packageVisible) ?: true }?.let { out += SearchResult.Shortcut(it) }
        PrefixShortcuts.hints(query).filter { t -> t.packageName?.let(packageVisible) ?: true }.forEach { out += SearchResult.ShortcutHint(it) }
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

    companion object {
        /** Contacts join in from the second character; one letter matches too many of them. Apps start at one, and every matching app is shown. */
        fun searchesContacts(query: String) = query.trim().length >= MIN_CONTACT_QUERY

        private const val MIN_CONTACT_QUERY = 2
        private const val MAX_CONTACTS = 5
        /** Apps are what you usually want from a launcher; a contact wins only on a clearly better match. */
        private const val CONTACT_PENALTY = 30
        private const val STARRED_BOOST = 3
        private const val NUMBER_SCORE = 700
    }
}
