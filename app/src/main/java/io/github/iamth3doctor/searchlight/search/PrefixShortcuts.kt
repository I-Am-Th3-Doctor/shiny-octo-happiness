package io.github.iamth3doctor.searchlight.search

/**
 * "y lofi" searches YouTube for "lofi": a one-letter key, a space, then the query. Pure Kotlin;
 * JVM-tested. The search itself (which app, which intent) is in Actions.prefixSearch.
 */
object PrefixShortcuts {

    enum class Target(val key: String, val label: String) {
        YOUTUBE("y", "YouTube"),
        MAPS("m", "Maps"),
        WIKIPEDIA("w", "Wikipedia"),
    }

    class Match(val target: Target, val query: String)

    /** "y lofi" -> YouTube / "lofi"; null when [input] isn't a key, a space and a query. */
    fun match(input: String): Match? {
        val s = input.trimStart()
        if (s.length < 3 || s[1] != ' ') return null
        val target = Target.entries.firstOrNull { it.key.equals(s.substring(0, 1), ignoreCase = true) } ?: return null
        val q = s.substring(2).trim()
        return if (q.isEmpty()) null else Match(target, q)
    }

    /**
     * The shortcuts to hint at while [input] is just a key ("y", or "y " before the query): the
     * one it starts, so the user sees what typing on will do. Empty otherwise.
     */
    fun hints(input: String): List<Target> {
        val s = input.trimStart()
        if (s.isEmpty() || s.length > 2 || (s.length == 2 && s[1] != ' ')) return emptyList()
        return Target.entries.filter { it.key.equals(s.substring(0, 1), ignoreCase = true) }
    }
}
