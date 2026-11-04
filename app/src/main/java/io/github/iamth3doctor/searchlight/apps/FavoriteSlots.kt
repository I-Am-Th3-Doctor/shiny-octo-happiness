package io.github.iamth3doctor.searchlight.apps

/**
 * What each of the six favorite slots shows. Pure (app keys in, keys out); JVM-tested.
 *
 * Without the predicted slot, the favorites in order, uninstalled ones skipped. With it (Manual
 * mode): slots 1-5 are the first five manual picks; slot 6 is always the live prediction (the
 * best one that isn't in slots 1-5), or, with no prediction to give, the manual 6th pick, which
 * is kept untouched underneath and comes back when the setting is turned off.
 */
object FavoriteSlots {

    enum class Kind { FAVORITE, PREDICTED, MANUAL_SIXTH }

    class Slot(val key: String, val kind: Kind)

    const val COUNT = 6

    fun compose(
        favorites: List<String>,
        predictedSlot: Boolean,
        predictions: List<String>,
        installed: (String) -> Boolean,
    ): List<Slot?> {
        if (!predictedSlot) {
            return favorites.filter(installed).take(COUNT).map { Slot(it, Kind.FAVORITE) }.pad()
        }
        val firstFive = favorites.take(COUNT - 1).filter(installed).map { Slot(it, Kind.FAVORITE) }
        val taken = firstFive.mapTo(HashSet()) { it.key }
        val manualSixth = favorites.getOrNull(COUNT - 1)?.takeIf { installed(it) && it !in taken }
        val sixth = predictions.firstOrNull { it !in taken && installed(it) }?.let { Slot(it, Kind.PREDICTED) }
            ?: manualSixth?.let { Slot(it, Kind.MANUAL_SIXTH) }
        return firstFive.pad(COUNT - 1) + sixth
    }

    enum class AddResult { ADDED, ALREADY, FULL }

    /**
     * How many picks the row has room for: six, or five while the predicted slot is on (slot 6
     * always shows the live prediction, so a manual add never lands there).
     */
    fun capacity(predictedSlot: Boolean) = if (predictedSlot) COUNT - 1 else COUNT

    /**
     * [key] added to [picks] in the first free slot (the end: picks have no gaps), within
     * [capacity]. The list back unchanged when it's already there or there's no room.
     */
    fun add(picks: List<String>, key: String, capacity: Int): Pair<List<String>, AddResult> = when {
        key in picks -> picks to AddResult.ALREADY
        picks.size >= capacity -> picks to AddResult.FULL
        else -> (picks + key) to AddResult.ADDED
    }

    private fun List<Slot?>.pad(n: Int = COUNT): List<Slot?> = this + List((n - size).coerceAtLeast(0)) { null }
}
