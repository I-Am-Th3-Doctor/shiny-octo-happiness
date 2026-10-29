package io.github.iamth3doctor.searchlight.apps

/**
 * What each of the six favorite slots shows. Pure (app keys in, keys out); JVM-tested.
 *
 * Without the predicted slot, the favorites in order, uninstalled ones skipped. With it (Manual
 * mode): slots 1-5 are the first five manual picks; slot 6 is the best prediction that isn't one
 * of those five, or, while pinned or with no prediction to give, the manual 6th pick, which is
 * kept untouched either way.
 */
object FavoriteSlots {

    enum class Kind { FAVORITE, PREDICTED, SLOT6_FIXED }

    class Slot(val key: String, val kind: Kind)

    const val COUNT = 6

    fun compose(
        favorites: List<String>,
        predictedSlot: Boolean,
        pinned: Boolean,
        predictions: List<String>,
        installed: (String) -> Boolean,
    ): List<Slot?> {
        if (!predictedSlot) {
            return favorites.filter(installed).take(COUNT).map { Slot(it, Kind.FAVORITE) }.pad()
        }
        val firstFive = favorites.take(COUNT - 1).filter(installed).map { Slot(it, Kind.FAVORITE) }
        val taken = firstFive.mapTo(HashSet()) { it.key }
        val manualSixth = favorites.getOrNull(COUNT - 1)?.takeIf { installed(it) && it !in taken }
        val sixth = when {
            pinned -> manualSixth?.let { Slot(it, Kind.SLOT6_FIXED) }
            else -> predictions.firstOrNull { it !in taken && installed(it) }?.let { Slot(it, Kind.PREDICTED) }
                ?: manualSixth?.let { Slot(it, Kind.SLOT6_FIXED) }
        }
        return firstFive.pad(COUNT - 1) + sixth
    }

    private fun List<Slot?>.pad(n: Int = COUNT): List<Slot?> = this + List((n - size).coerceAtLeast(0)) { null }
}
