package io.github.iamth3doctor.searchlight.ui

/**
 * Which rows an app's long-press menu has, in order, wherever it's opened (search results, the
 * app drawer, the favorites row or rail, including the predicted 6th slot: it has no rows of its
 * own). Pure; JVM-tested. The app's shortcuts come first, then these.
 */
object AppMenuRows {

    enum class Action { APP_INFO, WIDGETS, UNINSTALL, ADD_FAVORITE, REMOVE_FAVORITE }

    /**
     * App info always; Widgets when the app has any; Uninstall when it can be; then Add to or
     * Remove from favorites, always (in either favorites mode: see Prefs.addFavorite).
     */
    fun actions(hasWidgets: Boolean, canUninstall: Boolean, isFavorite: Boolean): List<Action> =
        listOfNotNull(
            Action.APP_INFO,
            Action.WIDGETS.takeIf { hasWidgets },
            Action.UNINSTALL.takeIf { canUninstall },
            if (isFavorite) Action.REMOVE_FAVORITE else Action.ADD_FAVORITE,
        )
}
