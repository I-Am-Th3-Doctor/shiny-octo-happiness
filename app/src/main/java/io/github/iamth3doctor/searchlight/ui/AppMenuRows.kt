package io.github.iamth3doctor.searchlight.ui

/**
 * Which rows an app's long-press menu has, in order, wherever it's opened (search results, the
 * app drawer, the favorites row or rail, including the predicted 6th slot: it has no rows of its
 * own). Pure; JVM-tested. The app's shortcuts come first, then these.
 */
object AppMenuRows {

    enum class Action { APP_INFO, WIDGETS, UNINSTALL, ADD_FAVORITE, REMOVE_FAVORITE }

    /**
     * App info always; Widgets when the app has any; Uninstall when it can be; then add or
     * remove favorite, except while automatic favorites show (the manual picks are hidden then).
     */
    fun actions(hasWidgets: Boolean, canUninstall: Boolean, autoFavoritesActive: Boolean, isFavorite: Boolean): List<Action> =
        listOfNotNull(
            Action.APP_INFO,
            Action.WIDGETS.takeIf { hasWidgets },
            Action.UNINSTALL.takeIf { canUninstall },
            when {
                autoFavoritesActive -> null
                isFavorite -> Action.REMOVE_FAVORITE
                else -> Action.ADD_FAVORITE
            },
        )
}
