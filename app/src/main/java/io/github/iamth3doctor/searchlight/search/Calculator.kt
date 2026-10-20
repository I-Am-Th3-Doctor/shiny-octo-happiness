package io.github.iamth3doctor.searchlight.search

import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * Inline calculator: "42 x 18" -> "756". Pure Kotlin.
 *
 * Supports + - * / x × ÷ ^ %, parentheses (a missing closing paren is fine while typing),
 * unary minus, decimals, thousands commas, and π / pi. Only kicks in when the input
 * contains a binary operator, so plain numbers and app names are left alone.
 */
object Calculator {

    /** @return the formatted result, or null if [input] isn't a complete expression. */
    fun evaluate(input: String): String? {
        val tokens = tokenize(input) ?: return null
        if (!hasBinaryOperator(tokens)) return null
        val parser = Parser(tokens)
        val value = parser.parse() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return format(value)
    }

    fun format(v: Double): String {
        if (v == 0.0) return "0"
        val a = abs(v)
        if (a >= 1e15 || a < 1e-9) {
            return String.format(Locale.US, "%.6e", v).replace(Regex("\\.?0+e"), "e").replace("e+", "e")
        }
        val rounded = BigDecimal(v).round(MathContext(12))
        val df = DecimalFormat("#,##0.###########", DecimalFormatSymbols(Locale.US))
        return df.format(rounded)
    }

    private sealed interface Tok {
        data class Num(val v: Double) : Tok
        data class Op(val c: Char) : Tok
        data object LParen : Tok
        data object RParen : Tok
        data object Percent : Tok
    }

    private fun tokenize(input: String): List<Tok>? {
        val s = input.trim()
        if (s.isEmpty() || s.none { it.isDigit() }) return null
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
                    val text = s.substring(start, i).replace(",", "")
                    out += Tok.Num(text.toDoubleOrNull() ?: return null)
                }
                c == '+' || c == '-' || c == '^' -> { out += Tok.Op(c); i++ }
                c == '−' -> { out += Tok.Op('-'); i++ }
                c == '*' || c == 'x' || c == 'X' || c == '×' || c == '·' -> { out += Tok.Op('*'); i++ }
                c == '/' || c == '÷' -> { out += Tok.Op('/'); i++ }
                c == '%' -> { out += Tok.Percent; i++ }
                c == '(' -> { out += Tok.LParen; i++ }
                c == ')' -> { out += Tok.RParen; i++ }
                c == 'π' -> { out += Tok.Num(Math.PI); i++ }
                (c == 'p' || c == 'P') && i + 1 < s.length && s[i + 1].lowercaseChar() == 'i' -> {
                    out += Tok.Num(Math.PI); i += 2
                }
                else -> return null
            }
        }
        return out
    }

    /** A leading sign isn't a binary operator: "-5" is just a number, not a calculation. */
    private fun hasBinaryOperator(tokens: List<Tok>): Boolean = tokens.withIndex().any { (idx, t) ->
        t is Tok.Op && (t.c in "*/^" || (idx > 0 && tokens[idx - 1].let { it is Tok.Num || it == Tok.RParen || it == Tok.Percent }))
    }

    /**
     * expr    = term (('+'|'-') term)*
     * term    = unary (('*'|'/') unary)*
     * unary   = ('-'|'+') unary | power
     * power   = postfix ('^' unary)?          right-associative, so -2^2 = -4 and 2^-1 works
     * postfix = primary '%'*                  50% = 0.5
     * primary = number | '(' expr ')'?
     */
    private class Parser(private val t: List<Tok>) {
        private var pos = 0

        fun parse(): Double? {
            val v = expr() ?: return null
            return if (pos == t.size) v else null
        }

        private fun peek() = t.getOrNull(pos)

        private fun expr(): Double? {
            var v = term() ?: return null
            while (true) {
                val op = peek() as? Tok.Op ?: break
                if (op.c != '+' && op.c != '-') break
                pos++
                val r = term() ?: return null
                v = if (op.c == '+') v + r else v - r
            }
            return v
        }

        private fun term(): Double? {
            var v = unary() ?: return null
            while (true) {
                val op = peek() as? Tok.Op ?: break
                if (op.c != '*' && op.c != '/') break
                pos++
                val r = unary() ?: return null
                v = if (op.c == '*') v * r else v / r
            }
            return v
        }

        private fun unary(): Double? {
            val op = peek() as? Tok.Op
            if (op != null && (op.c == '-' || op.c == '+')) {
                pos++
                val v = unary() ?: return null
                return if (op.c == '-') -v else v
            }
            return power()
        }

        private fun power(): Double? {
            val base = postfix() ?: return null
            val op = peek() as? Tok.Op
            if (op != null && op.c == '^') {
                pos++
                val exp = unary() ?: return null
                return base.pow(exp)
            }
            return base
        }

        private fun postfix(): Double? {
            var v = primary() ?: return null
            while (peek() == Tok.Percent) {
                pos++
                v /= 100
            }
            return v
        }

        private fun primary(): Double? {
            when (val tok = peek()) {
                is Tok.Num -> { pos++; return tok.v }
                Tok.LParen -> {
                    pos++
                    val v = expr() ?: return null
                    // A closing paren may be missing at the very end while you're still typing.
                    if (peek() == Tok.RParen) pos++ else if (pos != t.size) return null
                    return v
                }
                else -> return null
            }
        }
    }
}
