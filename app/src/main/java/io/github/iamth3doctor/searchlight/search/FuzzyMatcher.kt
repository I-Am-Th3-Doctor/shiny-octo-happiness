package io.github.iamth3doctor.searchlight.search

import java.text.Normalizer
import kotlin.math.min

/**
 * Pre-processed form of a searchable label. Built once per item (when the app list loads),
 * so each keystroke only does cheap string comparisons.
 *
 * Pure Kotlin, no Android types: unit-tested on the JVM.
 */
class MatchKey(val original: String) {
    /** Lowercased, accent-stripped label. Spaces/punctuation kept so "google m" works. */
    val text: String

    /** [text] with everything but letters and digits removed: "Wi-Fi" -> "wifi". */
    val compact: String

    /** First letter of each word, camelCase-aware: "YouTube Music" -> "ytm". */
    val initials: String

    /** Indices into [text] where a word begins. */
    val wordStarts: IntArray

    private val isStart: BooleanArray

    init {
        val sb = StringBuilder(original.length)
        val starts = ArrayList<Int>()
        var prev: Char? = null
        for (c in original) {
            val boundary = when {
                !c.isLetterOrDigit() -> false
                prev == null || !prev.isLetterOrDigit() -> true
                c.isUpperCase() && prev.isLowerCase() -> true
                c.isDigit() != prev.isDigit() -> true
                else -> false
            }
            val folded = FuzzyMatcher.fold(c)
            if (folded.isNotEmpty()) {
                if (boundary) starts += sb.length
                sb.append(folded)
            }
            prev = c
        }
        text = sb.toString()
        wordStarts = starts.toIntArray()
        isStart = BooleanArray(text.length).also { flags -> starts.forEach { flags[it] = true } }
        compact = FuzzyMatcher.compact(text)
        initials = buildString { starts.forEach { append(text[it]) } }
    }

    fun isWordStart(index: Int) = isStart[index]

    /** The individual words, for typo matching. */
    fun words(): List<String> = wordStarts.map { start ->
        var end = start
        while (end < text.length && text[end].isLetterOrDigit() && (end == start || !isStart[end])) end++
        text.substring(start, end)
    }
}

/**
 * Scores a query against a [MatchKey]. Higher is better; 0 means "no match".
 *
 * Tiers (a better tier always beats a worse one, usage boost never exceeds a tier gap):
 *  1000      exact
 *  900-920   label starts with query            "ch"  -> Chrome
 *  880-900   same, ignoring punctuation         "wifi"-> Wi-Fi Analyzer
 *  750-820   a later word starts with query     "maps"-> Google Maps
 *  740-760   word initials                      "gm"  -> Google Maps, "yt" -> YouTube
 *  550-600   substring anywhere (2+ letters)
 *  300-499   in-order subsequence from a word start, rewarding word boundaries
 *  250       one typo (queries of 4+ letters)   "setings" -> Settings
 */
object FuzzyMatcher {

    fun fold(c: Char): String {
        if (c.code < 128) return c.lowercaseChar().toString()
        val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (d in decomposed) {
            if (Character.getType(d) == Character.NON_SPACING_MARK.toInt()) continue
            sb.append(d.lowercaseChar())
        }
        return sb.toString()
    }

    /** Normalizes user input the same way labels are normalized. */
    fun normalize(query: String): String {
        val sb = StringBuilder(query.length)
        for (c in query.trim()) {
            if (c.isWhitespace()) {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            } else {
                sb.append(fold(c))
            }
        }
        return sb.toString()
    }

    fun compact(s: String): String = buildString(s.length) { s.forEach { if (it.isLetterOrDigit()) append(it) } }

    /** @param q a query already passed through [normalize]. */
    fun score(q: String, key: MatchKey): Int {
        if (q.isEmpty()) return 0
        val t = key.text
        if (t.isEmpty()) return 0
        if (t == q) return 1000
        if (t.startsWith(q)) return 900 + coverage(q.length, t.length)

        val qc = compact(q)
        if (qc.isEmpty()) return if (t.contains(q)) 550 else 0
        if (key.compact.startsWith(qc)) return 880 + coverage(qc.length, key.compact.length)

        for (i in 1 until key.wordStarts.size) {
            val ws = key.wordStarts[i]
            if (t.startsWith(q, ws)) return 800 - min(i, 10) * 5 + coverage(q.length, t.length - ws)
        }

        if (qc.length >= 2 && key.initials.startsWith(qc)) return 740 + coverage(qc.length, key.initials.length)

        // A single letter only matches at word starts; mid-word hits would just be noise.
        if (qc.length < 2) return 0

        val idx = t.indexOf(q)
        if (idx >= 0) return 600 - min(idx, 50)

        val sub = subsequence(qc, key)
        if (sub > 0) return sub

        if (qc.length >= 4 && withinOneTypo(qc, key)) return 250
        return 0
    }

    /** 0..20 bonus: the more of the label the query covers, the better. */
    private fun coverage(matched: Int, total: Int): Int = if (total <= 0) 0 else min(20, 20 * matched / total)

    private fun subsequence(q: String, key: MatchKey): Int {
        val t = key.text
        var best = 0
        for (start in key.wordStarts) {
            if (t[start] != q[0]) continue
            var ti = start + 1
            var qi = 1
            var gaps = 0
            var boundaryHits = 1
            var prevMatch = start
            while (qi < q.length && ti < t.length) {
                if (t[ti] == q[qi]) {
                    if (ti != prevMatch + 1) gaps++
                    if (key.isWordStart(ti)) boundaryHits++
                    prevMatch = ti
                    qi++
                }
                ti++
            }
            if (qi == q.length) {
                val s = (400 + boundaryHits * 15 - gaps * 10 - min(start, 30)).coerceIn(300, 499)
                if (s > best) best = s
            }
        }
        return best
    }

    private fun withinOneTypo(q: String, key: MatchKey): Boolean {
        val candidates = key.words() + key.compact
        for (word in candidates) {
            if (word.length < q.length - 1) continue
            // Compare against prefixes of the same length, one shorter, and one longer,
            // so a missing, extra, or wrong letter all count as one typo.
            for (len in (q.length - 1)..(q.length + 1)) {
                if (len < 1 || len > word.length) continue
                if (osaDistance(q, word.substring(0, len)) <= 1) return true
            }
        }
        return false
    }

    /** Optimal-string-alignment distance (Levenshtein + adjacent transposition). */
    internal fun osaDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
            }
        }
        return d[a.length][b.length]
    }
}
