package io.github.iamth3doctor.searchlight.widgets

/**
 * Widgets Searchlight draws itself (not app widgets), offered in the widget picker beside every
 * app's. On the grid they're stored like any widget, under negative ids, which Android never
 * hands out for app widgets. Pure; JVM-tested.
 */
enum class BuiltInWidget(val key: String, val defaultCols: Int, val defaultRows: Int) {
    CALENDAR("calendar", 4, 3),
    /** The conditions now, plain, on frosted glass: 2 × 2, and it stays that size. */
    WEATHER("weather", 2, 2),
    /** Today on a paper sticky note: 2 × 2, and it stays that size. */
    STICKY_NOTE("sticky_note", 2, 2);

    val isWeather: Boolean get() = this == WEATHER

    /** Its size is fixed: no resize handles. */
    val fixedSize: Boolean get() = this == WEATHER || this == STICKY_NOTE

    companion object {
        fun isBuiltIn(id: Int) = id < 0

        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: if (key in RETIRED_WEATHER) WEATHER else null

        /** The 3-day and today Weather widgets there used to be: now the one Weather widget. */
        private val RETIRED_WEATHER = setOf("weather_3day", "weather_day")

        /**
         * Built-in widgets placed before the one plain Weather widget: retired kinds become it, and
         * a fixed-size widget at another size goes back to its own, where that doesn't overlap
         * another widget (a smaller one that can't grow in place keeps its size).
         */
        fun migrate(types: Map<Int, String>, placed: List<GridSpec>): Pair<Map<Int, String>, List<GridSpec>> {
            val now = types.mapValues { (_, k) -> fromKey(k)?.key ?: k }
            val specs = placed.toMutableList()
            for ((i, s) in specs.withIndex()) {
                val t = (if (isBuiltIn(s.id)) fromKey(now[s.id]) else null)?.takeIf { it.fixedSize } ?: continue
                if (s.colSpan == t.defaultCols && s.rowSpan == t.defaultRows) continue
                val sized = s.copy(colSpan = t.defaultCols, rowSpan = t.defaultRows)
                if (specs.none { it.id != s.id && it.overlaps(sized) }) specs[i] = sized
            }
            return now to specs
        }

        /** A fresh id for a new built-in widget: below every one already placed (either posture). */
        fun newId(placed: List<GridSpec>): Int = (placed.minOfOrNull { it.id }?.coerceAtMost(0) ?: 0) - 1
    }
}
