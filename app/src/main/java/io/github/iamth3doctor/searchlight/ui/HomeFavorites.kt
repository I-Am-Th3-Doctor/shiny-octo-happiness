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
        predictions = prefs.predictions,
        // In Focus only allowed apps fill the row (FocusRules.shows).
        installed = { key ->
            repo.find(key)?.let { e ->
                !e.isSelf && io.github.iamth3doctor.searchlight.focus.FocusRules.shows(e.packageName, prefs.focusMode, prefs.focusAllowlist)
            } == true
        },
    ).map { slot -> slot?.let { s -> repo.find(s.key)?.let { HomeFavorite(it, s.kind) } } }
