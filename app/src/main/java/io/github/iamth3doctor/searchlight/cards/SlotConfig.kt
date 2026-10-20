package io.github.iamth3doctor.searchlight.cards

/**
 * Parses the saved Quick Actions slot list ("TORCH,WIFI,BLUETOOTH,DND"). Pure; JVM-tested.
 *
 * Always returns exactly `defaults.size` slots. Anything unreadable (missing pref, an action
 * removed in a later version, a short list) falls back to that slot's default, so an app
 * update can never leave the tile with a blank or crashing slot.
 */
object SlotConfig {
    fun parse(raw: String?, valid: Set<String>, defaults: List<String>): List<String> {
        val saved = raw?.split(',')?.map { it.trim() }.orEmpty()
        return defaults.indices.map { i -> saved.getOrNull(i)?.takeIf { it in valid } ?: defaults[i] }
    }

    fun serialize(slots: List<String>): String = slots.joinToString(",")
}
