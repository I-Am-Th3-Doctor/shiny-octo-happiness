package com.something.launcher

import com.something.launcher.data.HomeItem
import com.something.launcher.data.HomeState
import com.something.launcher.data.HomeStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStoreTest {
    @Test fun roundTrip() {
        val s = HomeState(
            items = listOf(HomeItem.Weather, HomeItem.App("com.x/.Main#0"), HomeItem.StickyNote, HomeItem.App("com.y/.Main#0")),
            seeded = true,
        )
        assertEquals(s, HomeStore.decode(JSONObject(HomeStore.encode(s).toString())))
        assertEquals(setOf("com.x/.Main#0", "com.y/.Main#0"), s.pinned)
    }

    @Test fun decodeDropsJunkAndDuplicates() {
        val json = """{"items":["widget:weather","app:a","app:a","thing:old","app:","widget:sticky"]}"""
        val s = HomeStore.decode(JSONObject(json))
        assertEquals(listOf("widget:weather", "app:a", "widget:sticky"), s.items.map { it.id })
        assertEquals(true, s.seeded)
    }
}
