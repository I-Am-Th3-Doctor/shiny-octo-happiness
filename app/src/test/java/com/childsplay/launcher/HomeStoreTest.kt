package com.childsplay.launcher

import com.childsplay.launcher.data.HomeItem
import com.childsplay.launcher.data.HomeState
import com.childsplay.launcher.data.HomeStore
import com.childsplay.launcher.data.Thing
import com.childsplay.launcher.data.ThingShape
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStoreTest {
    @Test fun roundTrip() {
        val things = listOf(
            Thing("a", "Chatter", ThingShape.CLOUD, 6, listOf("com.x/.Main#0", "com.y/.Main#0")),
            Thing("b", "Telly", ThingShape.TV, 0, emptyList()),
        )
        val s = HomeState(
            items = listOf(HomeItem.Weather, HomeItem.ThingItem("b"), HomeItem.StickyNote, HomeItem.ThingItem("a")),
            things = things.associateBy { it.id }, room = 3, noteColor = 2, seeded = true,
        )
        assertEquals(s, HomeStore.decode(JSONObject(HomeStore.encode(s).toString())))
    }

    @Test fun decodeRepairsOrderList() {
        val json = """
          {"seeded":true,"items":["widget:weather","thing:gone","thing:a","thing:a"],
           "things":[{"id":"a","name":"A","shape":"STATUE","color":1,"apps":["x","x",""]},
                     {"id":"b","name":"B","shape":"NOPE","color":2,"apps":[]}]}
        """.trimIndent()
        val s = HomeStore.decode(JSONObject(json))
        assertEquals(listOf("widget:weather", "thing:a", "thing:b"), s.items.map { it.id })
        assertEquals(listOf("x"), s.things["a"]!!.appKeys)
        assertEquals(ThingShape.CLOUD, s.things["b"]!!.shape)
    }
}
