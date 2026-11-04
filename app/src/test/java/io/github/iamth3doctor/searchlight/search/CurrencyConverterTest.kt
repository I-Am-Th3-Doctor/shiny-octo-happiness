package io.github.iamth3doctor.searchlight.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrencyConverterTest {
    private val rates = CurrencyConverter.Rates("2026-11-03", mapOf("USD" to 1.08, "GBP" to 0.84, "JPY" to 160.0))

    private fun answer(q: String) = CurrencyConverter.parse(q)?.let { CurrencyConverter.convert(it, rates) }

    @Test fun usdToEur() {
        val a = answer("25 USD to EUR")!!
        assertEquals("23.15 EUR", a.text)
        assertEquals("2026-11-03", a.date)
    }

    @Test fun crossRatesAndAliases() {
        assertEquals("19.44 GBP", answer("25 usd in gbp")!!.text)
        assertEquals("148.15 JPY", answer("\$1 to yen")!!.text)
        assertEquals("1,000.00 EUR", answer("1,080 dollars to euros")!!.text)
    }

    @Test fun notCurrency() {
        assertNull(CurrencyConverter.parse("10 miles to km"))
        assertNull(CurrencyConverter.parse("25 USD"))
        assertNull(CurrencyConverter.parse("25 USD to USD"))
        assertNull(CurrencyConverter.parse("25 abc to eur"))
        // A currency the cached rates don't have: no answer rather than a wrong one.
        assertNull(CurrencyConverter.convert(CurrencyConverter.parse("1 INR to EUR")!!, rates))
    }
}
