package io.github.iamth3doctor.searchlight.ui

import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.apps.FavoriteSlots
import io.github.iamth3doctor.searchlight.data.Prefs

/** One filled favorite slot, as the popup and the rail draw it. */
class HomeFavorite(val entry: AppEntry, val kind: FavoriteSlots.Kind) {
    val predicted get() = kind == FavoriteSlots.Kind.PREDICTED
}

/** The six slots (null = empty), from the current mode, setting, pin and predictions. */
fun homeFavorites(prefs: Prefs, repo: AppRepository): List<HomeFavorite?> =
    FavoriteSlots.compose(
        favorites = prefs.favorites,
        predictedSlot = prefs.predictedSlotActive,
        pinned = prefs.predictionPinned,
        predictions = prefs.predictions,
        installed = { key -> repo.find(key)?.isSelf == false },
    ).map { slot -> slot?.let { s -> repo.find(s.key)?.let { HomeFavorite(it, s.kind) } } }

/** Is slot [index] the predicted slot (predicting or pinned) right now? */
fun isPredictedSlot(prefs: Prefs, index: Int) = prefs.predictedSlotActive && index == FavoriteSlots.COUNT - 1
