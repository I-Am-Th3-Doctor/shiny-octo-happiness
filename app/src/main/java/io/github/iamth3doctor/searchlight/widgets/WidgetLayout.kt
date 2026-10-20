package io.github.iamth3doctor.searchlight.widgets

/** One placed widget: the system's appWidgetId and the height you gave it (width fills the column). */
data class WidgetSpec(val id: Int, val heightDp: Int)

/**
 * The widget page as saved in prefs: "12:180,15:240" = widget 12 at 180dp, then widget 15 at
 * 240dp, top to bottom. Pure; JVM-tested. Malformed entries are dropped, never crash.
 */
object WidgetLayout {

    fun parse(raw: String?): List<WidgetSpec> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = HashSet<Int>()
        return raw.split(',').mapNotNull { part ->
            val bits = part.trim().split(':')
            val id = bits.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val h = bits.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            if (id < 0 || h <= 0 || !seen.add(id)) null else WidgetSpec(id, h)
        }
    }

    fun serialize(list: List<WidgetSpec>): String = list.joinToString(",") { "${it.id}:${it.heightDp}" }

    /** New widgets go at the bottom of the page. */
    fun add(list: List<WidgetSpec>, spec: WidgetSpec) = list.filter { it.id != spec.id } + spec

    fun remove(list: List<WidgetSpec>, id: Int) = list.filter { it.id != id }

    fun move(list: List<WidgetSpec>, id: Int, delta: Int): List<WidgetSpec> {
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return list
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun resize(list: List<WidgetSpec>, id: Int, heightDp: Int, minDp: Int, maxDp: Int) =
        list.map { if (it.id == id) it.copy(heightDp = heightDp.coerceIn(minDp, maxDp)) else it }
}
