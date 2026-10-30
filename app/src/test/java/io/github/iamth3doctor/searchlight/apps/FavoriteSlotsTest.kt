package io.github.iamth3doctor.searchlight.apps

import io.github.iamth3doctor.searchlight.apps.FavoriteSlots.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteSlotsTest {

    private val manual = listOf("a", "b", "c", "d", "e", "f")
    private val all: (String) -> Boolean = { true }

    private fun keys(s: List<FavoriteSlots.Slot?>) = s.map { it?.key }
    private fun kinds(s: List<FavoriteSlots.Slot?>) = s.map { it?.kind }

    @Test fun offIsTheSixManualPicks() {
        val s = FavoriteSlots.compose(manual, predictedSlot = false, predictions = listOf("x"), installed = all)
        assertEquals(manual, keys(s))
        assertEquals(List(6) { Kind.FAVORITE }, kinds(s))
    }

    @Test fun onPredictsTheSixthNeverRepeatingTheFirstFive() {
        val s = FavoriteSlots.compose(manual, true, predictions = listOf("b", "e", "x", "y"), installed = all)
        assertEquals(listOf("a", "b", "c", "d", "e", "x"), keys(s))
        assertEquals(Kind.PREDICTED, s[5]!!.kind)
    }

    @Test fun theManualSixthCanBePredicted() {
        // Predicting the user's own 6th pick is fine: it isn't in slots 1-5.
        val s = FavoriteSlots.compose(manual, true, predictions = listOf("f"), installed = all)
        assertEquals("f", s[5]!!.key)
        assertEquals(Kind.PREDICTED, s[5]!!.kind)
    }

    @Test fun noPredictionFallsBackToTheManualSixth() {
        val s = FavoriteSlots.compose(manual, true, predictions = emptyList(), installed = all)
        assertEquals("f", s[5]!!.key)
        assertEquals(Kind.MANUAL_SIXTH, s[5]!!.kind)
        // Only predictions that are taken or uninstalled: same.
        val t = FavoriteSlots.compose(manual, true, predictions = listOf("a", "gone"), installed = { it != "gone" })
        assertEquals("f", t[5]!!.key)
    }

    @Test fun slotSixAlwaysPredictsWhileOnEvenWhereAPinUsedToBe() {
        // An app pinned before (stored as the manual 6th pick) no longer holds the slot.
        val s = FavoriteSlots.compose(manual, true, predictions = listOf("x"), installed = all)
        assertEquals("x", s[5]!!.key)
        assertEquals(Kind.PREDICTED, s[5]!!.kind)
    }

    @Test fun turningItOffRestoresTheManualSixth() {
        val on = FavoriteSlots.compose(manual, true, predictions = listOf("x"), installed = all)
        assertEquals("x", on[5]!!.key)
        val off = FavoriteSlots.compose(manual, false, predictions = listOf("x"), installed = all)
        assertEquals(manual, keys(off))
        assertEquals(Kind.FAVORITE, off[5]!!.kind)
    }

    @Test fun fewerThanFiveManualKeepsSlotSixForThePrediction() {
        val s = FavoriteSlots.compose(listOf("a", "b"), true, predictions = listOf("a", "x"), installed = all)
        assertEquals(listOf("a", "b", null, null, null, "x"), keys(s))
    }

    @Test fun nothingAnywhereLeavesTheUsualEmptySlot() {
        val s = FavoriteSlots.compose(listOf("a"), true, predictions = emptyList(), installed = all)
        assertNull(s[5])
    }
}
