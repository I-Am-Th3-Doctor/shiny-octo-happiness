package io.github.iamth3doctor.searchlight.widgets

/**
 * Widgets Searchlight draws itself (not app widgets), offered in the widget picker beside every
 * app's. On the grid they're stored like any widget, under negative ids, which Android never
 * hands out for app widgets. Pure; JVM-tested.
 */
enum class BuiltInWidget(val key: String, val defaultCols: Int, val defaultRows: Int) {
    CALENDAR("calendar", 4, 3),
    /** The live one: the scene, the temperature now. */
    WEATHER("weather", 2, 2),
    /** Today and the next two days. */
    WEATHER_3DAY("weather_3day", 2, 2),
    /** Now, large, over the next hours' temperature curve. */
    WEATHER_DAY("weather_day", 4, 2);

    val isWeather: Boolean get() = this != CALENDAR

    companion object {
        fun isBuiltIn(id: Int) = id < 0

        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }

        /** A fresh id for a new built-in widget: below every one already placed (either posture). */
        fun newId(placed: List<GridSpec>): Int = (placed.minOfOrNull { it.id }?.coerceAtMost(0) ?: 0) - 1
    }
}
