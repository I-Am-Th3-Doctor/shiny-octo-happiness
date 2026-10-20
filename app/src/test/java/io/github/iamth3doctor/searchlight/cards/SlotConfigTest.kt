package io.github.iamth3doctor.searchlight.cards

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotConfigTest {
    private val valid = setOf("TORCH", "WIFI", "BLUETOOTH", "DND", "BRIGHTNESS", "AIRPLANE")
    private val defaults = listOf("TORCH", "WIFI", "BLUETOOTH", "DND")

    private fun parse(raw: String?) = SlotConfig.parse(raw, valid, defaults)

    @Test fun nothingSavedGivesDefaults() = assertEquals(defaults, parse(null))

    @Test fun savedOrderIsKept() =
        assertEquals(listOf("AIRPLANE", "DND", "TORCH", "BRIGHTNESS"), parse("AIRPLANE,DND,TORCH,BRIGHTNESS"))

    @Test fun duplicatesAllowed() = assertEquals(listOf("DND", "DND", "DND", "DND"), parse("DND,DND,DND,DND"))

    @Test fun unknownOrMissingSlotsFallBackPerSlot() {
        assertEquals(listOf("AIRPLANE", "WIFI", "BLUETOOTH", "DND"), parse("AIRPLANE,REMOVED_IN_V9"))
        assertEquals(defaults, parse(""))
        assertEquals(listOf("BRIGHTNESS", "WIFI", "BLUETOOTH", "DND"), parse("BRIGHTNESS,,BOGUS"))
    }

    @Test fun extraSlotsIgnored() = assertEquals(4, parse("TORCH,WIFI,BLUETOOTH,DND,AIRPLANE").size)

    @Test fun roundTrip() {
        val slots = listOf("WIFI", "TORCH", "AIRPLANE", "DND")
        assertEquals(slots, parse(SlotConfig.serialize(slots)))
    }
}
