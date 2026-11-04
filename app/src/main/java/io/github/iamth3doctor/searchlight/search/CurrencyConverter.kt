package io.github.iamth3doctor.searchlight.search

/**
 * "25 USD to EUR" -> "23.12 EUR". Pure Kotlin; JVM-tested. The rates come from [Rates] (the
 * European Central Bank's daily reference rates, per euro); see CurrencyRates for fetching and
 * caching them.
 */
object CurrencyConverter {

    /** Units of each currency per 1 EUR, as published on [date] (yyyy-MM-dd). */
    class Rates(val date: String, val perEuro: Map<String, Double>) {
        fun has(code: String) = code == "EUR" || code in perEuro
        fun per(code: String) = if (code == "EUR") 1.0 else perEuro.getValue(code)
    }

    /** What a query asks: [amount] of [from] in [to] (ISO codes, upper case). */
    class Ask(val amount: Double, val from: String, val to: String)

    class Answer(val value: Double, val text: String, val rate: Double, val date: String)

    /** The ECB's currencies (and EUR): only these make a query a currency question. */
    val CODES = setOf(
        "EUR", "USD", "JPY", "BGN", "CZK", "DKK", "GBP", "HUF", "PLN", "RON", "SEK", "CHF", "ISK", "NOK", "TRY",
        "AUD", "BRL", "CAD", "CNY", "HKD", "IDR", "ILS", "INR", "KRW", "MXN", "MYR", "NZD", "PHP", "SGD", "THB", "ZAR",
    )

    /** Everyday names and symbols for the common ones. */
    private val ALIASES = mapOf(
        "$" to "USD", "dollar" to "USD", "dollars" to "USD", "us$" to "USD",
        "€" to "EUR", "euro" to "EUR", "euros" to "EUR",
        "£" to "GBP", "pound sterling" to "GBP", "pounds sterling" to "GBP", "quid" to "GBP",
        "¥" to "JPY", "yen" to "JPY", "yuan" to "CNY", "rupee" to "INR", "rupees" to "INR",
    )

    private val pattern = Regex(
        """^\s*([$€£¥]?)\s*(-?[\d,]*\.?\d+)\s*([a-z$€£¥ ]*?)\s+(?:to|in|into|as|=)\s+([a-z$€£¥ ]+?)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun code(word: String): String? {
        val w = word.trim()
        if (w.isEmpty()) return null
        ALIASES[w.lowercase()]?.let { return it }
        return w.uppercase().takeIf { it.length == 3 && it in CODES }
    }

    /** The currency conversion [query] asks for, or null if it isn't one. */
    fun parse(query: String): Ask? {
        val m = pattern.matchEntire(query) ?: return null
        val amount = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        val from = code(m.groupValues[3].ifBlank { m.groupValues[1] }) ?: return null
        val to = code(m.groupValues[4]) ?: return null
        if (from == to) return null
        return Ask(amount, from, to)
    }

    fun convert(ask: Ask, rates: Rates): Answer? {
        if (!rates.has(ask.from) || !rates.has(ask.to)) return null
        val rate = rates.per(ask.to) / rates.per(ask.from)
        val value = ask.amount * rate
        return Answer(value, "${String.format(java.util.Locale.US, "%,.2f", value)} ${ask.to}", rate, rates.date)
    }
}
