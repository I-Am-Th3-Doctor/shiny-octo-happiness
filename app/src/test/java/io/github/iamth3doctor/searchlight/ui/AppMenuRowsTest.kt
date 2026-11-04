package io.github.iamth3doctor.searchlight.ui

import io.github.iamth3doctor.searchlight.ui.AppMenuRows.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class AppMenuRowsTest {

    @Test fun fullMenuInOrder() {
        assertEquals(
            listOf(Action.APP_INFO, Action.WIDGETS, Action.UNINSTALL, Action.ADD_FAVORITE),
            AppMenuRows.actions(hasWidgets = true, canUninstall = true, isFavorite = false),
        )
    }

    @Test fun theMenuHasNoPinOrUnpinRow() {
        // The predicted 6th slot's app (not a manual favorite) gets the same rows as anywhere
        // else, and nothing beyond these five kinds exists to add.
        assertEquals(setOf("APP_INFO", "WIDGETS", "UNINSTALL", "ADD_FAVORITE", "REMOVE_FAVORITE"), Action.entries.map { it.name }.toSet())
        assertEquals(
            listOf(Action.APP_INFO, Action.UNINSTALL, Action.ADD_FAVORITE),
            AppMenuRows.actions(hasWidgets = false, canUninstall = true, isFavorite = false),
        )
    }

    @Test fun everyMenuOffersAddOrRemove() {
        // Every surface builds its menu here, in either favorites mode: never without the toggle.
        for (widgets in listOf(true, false)) for (uninstall in listOf(true, false)) {
            assertEquals(Action.ADD_FAVORITE, AppMenuRows.actions(widgets, uninstall, isFavorite = false).last())
            assertEquals(Action.REMOVE_FAVORITE, AppMenuRows.actions(widgets, uninstall, isFavorite = true).last())
            assertEquals(Action.APP_INFO, AppMenuRows.actions(widgets, uninstall, isFavorite = false).first())
        }
    }
}
