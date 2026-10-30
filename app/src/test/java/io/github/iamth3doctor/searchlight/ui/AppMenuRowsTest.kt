package io.github.iamth3doctor.searchlight.ui

import io.github.iamth3doctor.searchlight.ui.AppMenuRows.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class AppMenuRowsTest {

    @Test fun fullMenuInOrder() {
        assertEquals(
            listOf(Action.APP_INFO, Action.WIDGETS, Action.UNINSTALL, Action.ADD_FAVORITE),
            AppMenuRows.actions(hasWidgets = true, canUninstall = true, autoFavoritesActive = false, isFavorite = false),
        )
    }

    @Test fun theMenuHasNoPinOrUnpinRow() {
        // The predicted 6th slot's app (not a manual favorite) gets the same rows as anywhere
        // else, and nothing beyond these five kinds exists to add.
        assertEquals(setOf("APP_INFO", "WIDGETS", "UNINSTALL", "ADD_FAVORITE", "REMOVE_FAVORITE"), Action.entries.map { it.name }.toSet())
        assertEquals(
            listOf(Action.APP_INFO, Action.UNINSTALL, Action.ADD_FAVORITE),
            AppMenuRows.actions(hasWidgets = false, canUninstall = true, autoFavoritesActive = false, isFavorite = false),
        )
    }

    @Test fun favoriteToggleFollowsTheModeAndTheApp() {
        assertEquals(Action.REMOVE_FAVORITE, AppMenuRows.actions(false, false, false, isFavorite = true).last())
        assertEquals(listOf(Action.APP_INFO), AppMenuRows.actions(false, false, autoFavoritesActive = true, isFavorite = true))
    }
}
