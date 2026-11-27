package io.github.iamth3doctor.searchlight.iconpack

import java.util.Locale

/**
 * Every app's move on Home, from the theme's searchlight-icon-actions.md: its "App | Component |
 * Action | Fit" table. An app is found by its component (the exact package/activity, else its
 * package), then by its label, then by the start of a label cut short ("Moto ..."). Plain Kotlin,
 * no Android, so it reads the same in tests. Safe from any thread once built.
 */
class IconActions private constructor(
    private val byComponent: Map<String, IconTheme.Move>,
    private val byLabel: Map<String, IconTheme.Move>,
    private val byPrefix: List<Pair<String, IconTheme.Move>>,
    /** The table's rows, in order (label, its components, its action as written). */
    val rows: List<Row>,
    /** Rows whose action isn't one of the moves (they fall back to a generic move). */
    val skipped: List<String>,
) {

    class Row(val label: String, val components: List<String>, val action: String, val move: IconTheme.Move?)

    /**
     * The move for the app at [component] ("package/activity", flattened) labelled [label], or
     * null when the table doesn't have it.
     */
    fun moveFor(component: String, label: String?): IconTheme.Move? {
        byComponent[component]?.let { return it }
        byComponent[component.substringBefore('/')]?.let { return it }
        val l = label?.let(::normal) ?: return null
        byLabel[l]?.let { return it }
        return byPrefix.firstOrNull { (p, _) -> l.startsWith(p) }?.second
    }

    companion object {
        const val FILE = "searchlight-icon-actions.md"

        val EMPTY = IconActions(emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList())

        /** Shortest stub a label cut short ("Moto ...") may match by. */
        const val MIN_PREFIX = 4

        /** A label as compared: no "(?)", "(1)", lowercase, plain apostrophes, single spaces. */
        fun normal(label: String): String = label
            .replace('’', '\'').replace('‘', '\'')
            .replace(Regex("\\((\\?|\\d+)\\)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)

        /** The move a table's Action names (case aside), or null. */
        fun moveNamed(action: String): IconTheme.Move? =
            IconTheme.Move.entries.firstOrNull { it.name.equals(action.trim(), ignoreCase = true) }

        /** Reads the table out of [markdown]; anything else in the file is left alone. */
        fun parse(markdown: String): IconActions {
            val byComponent = LinkedHashMap<String, IconTheme.Move>()
            val byLabel = LinkedHashMap<String, IconTheme.Move>()
            val byPrefix = ArrayList<Pair<String, IconTheme.Move>>()
            val rows = ArrayList<Row>()
            val skipped = ArrayList<String>()
            var cols: Map<String, Int>? = null
            for (raw in markdown.lineSequence()) {
                val line = raw.trim()
                if (!line.startsWith("|")) { cols = null; continue }
                val cells = line.trim('|').split('|').map { it.trim() }
                val head = cols
                if (head == null) {
                    val names = cells.map { it.lowercase(Locale.ROOT) }
                    if ("app" in names && "action" in names) cols = names.withIndex().associate { it.value to it.index }
                    continue
                }
                if (cells.all { it.isEmpty() || it.all { c -> c == '-' || c == ':' } }) continue
                val label = cells.getOrNull(head.getValue("app")) ?: continue
                val action = cells.getOrNull(head.getValue("action")) ?: ""
                val components = head["component"]?.let { i -> cells.getOrNull(i) }.orEmpty()
                    .split(',', ' ').map { it.trim().trim('`') }.filter { it.isNotEmpty() }
                    .map { expand(it) }
                val move = moveNamed(action)
                rows += Row(label, components, action, move)
                if (move == null) { skipped += "$label: \"$action\""; continue }
                for (c in components) byComponent.putIfAbsent(c, move)
                if (label.isBlank()) continue
                val n = normal(label)
                val cut = n.endsWith("...") || n.endsWith("…")
                if (cut) {
                    val stub = n.trimEnd('.', '…', ' ')
                    if (stub.length >= MIN_PREFIX) byPrefix += stub to move
                } else byLabel.putIfAbsent(n, move)
            }
            return IconActions(byComponent, byLabel, byPrefix, rows, skipped)
        }

        /** "com.a/.Main" spelled out to "com.a/com.a.Main", as components flatten. */
        private fun expand(c: String): String {
            val pkg = c.substringBefore('/', "")
            if (pkg.isEmpty()) return c
            val cls = c.substringAfter('/')
            return "$pkg/${if (cls.startsWith(".")) pkg + cls else cls}"
        }
    }
}
