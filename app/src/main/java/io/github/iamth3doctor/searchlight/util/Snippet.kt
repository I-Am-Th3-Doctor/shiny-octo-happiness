package io.github.iamth3doctor.searchlight.util

/** A short excerpt of [text] around a match, for message results. Pure; JVM-tested. */
object Snippet {
    fun around(text: String, matchIndex: Int, matchLength: Int, max: Int = 70): String {
        val clean = text.replace('\n', ' ')
        if (clean.length <= max || matchIndex < 0) return clean.take(max).let { if (clean.length > max) "$it…" else it }
        val lead = (max - matchLength) / 3
        var start = (matchIndex - lead).coerceAtLeast(0)
        val end = (start + max).coerceAtMost(clean.length)
        start = (end - max).coerceAtLeast(0)
        return (if (start > 0) "…" else "") + clean.substring(start, end).trim() + (if (end < clean.length) "…" else "")
    }
}
