package io.github.iamth3doctor.searchlight.weather

/**
 * Android has no "default weather app", so the weather tile opens one you pick. To make that
 * quick, apps that look like weather apps (by name or package, in a few languages) come first;
 * every other app follows, A–Z, in case yours is named differently. Pure; JVM-tested.
 */
object WeatherApps {

    private val HINTS = listOf("weather", "wetter", "meteo", "météo", "clima", "tiempo", "forecast", "radar")

    fun looksLikeWeather(label: String, packageName: String): Boolean {
        val text = "$label $packageName".lowercase()
        return HINTS.any { it in text }
    }

    /** Weather-looking apps first, then the rest; each group A–Z by label. */
    fun <T> ordered(apps: List<T>, label: (T) -> String, packageName: (T) -> String): List<T> =
        apps.sortedWith(
            compareBy<T> { !looksLikeWeather(label(it), packageName(it)) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { label(it) }
        )
}
