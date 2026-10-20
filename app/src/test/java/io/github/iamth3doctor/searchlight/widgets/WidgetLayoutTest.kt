package io.github.iamth3doctor.searchlight.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLayoutTest {
    private val a = WidgetSpec(12, 180)
    private val b = WidgetSpec(15, 240)
    private val c = WidgetSpec(3, 100)

    @Test fun roundTrip() {
        val list = listOf(a, b, c)
        assertEquals(list, WidgetLayout.parse(WidgetLayout.serialize(list)))
        assertEquals(emptyList<WidgetSpec>(), WidgetLayout.parse(null))
        assertEquals(emptyList<WidgetSpec>(), WidgetLayout.parse(""))
    }

    @Test fun malformedEntriesDropped() {
        assertEquals(listOf(a, c), WidgetLayout.parse("12:180,garbage,15,x:3,3:100,-1:50,9:0"))
        assertEquals(listOf(a), WidgetLayout.parse("12:180,12:300")) // duplicate id keeps first
    }

    @Test fun addGoesLastAndReplacesSameId() {
        assertEquals(listOf(a, b), WidgetLayout.add(listOf(a), b))
        assertEquals(listOf(b, WidgetSpec(12, 90)), WidgetLayout.add(listOf(a, b), WidgetSpec(12, 90)))
    }

    @Test fun removeAndMove() {
        assertEquals(listOf(a, c), WidgetLayout.remove(listOf(a, b, c), 15))
        assertEquals(listOf(b, a, c), WidgetLayout.move(listOf(a, b, c), 15, -1))
        assertEquals(listOf(a, c, b), WidgetLayout.move(listOf(a, b, c), 15, +1))
        assertEquals(listOf(a, b, c), WidgetLayout.move(listOf(a, b, c), 12, -1)) // already top
        assertEquals(listOf(a, b, c), WidgetLayout.move(listOf(a, b, c), 99, +1)) // unknown id
    }

    @Test fun resizeClamps() {
        assertEquals(listOf(WidgetSpec(12, 400), b), WidgetLayout.resize(listOf(a, b), 12, 999, 40, 400))
        assertEquals(listOf(WidgetSpec(12, 40), b), WidgetLayout.resize(listOf(a, b), 12, 10, 40, 400))
    }
}
