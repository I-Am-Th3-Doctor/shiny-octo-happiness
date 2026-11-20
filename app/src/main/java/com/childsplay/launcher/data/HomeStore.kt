package com.childsplay.launcher.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The objects a thing-widget can be. */
enum class ThingShape(val title: String) {
    CLOUD("Cloud"),
    TV("TV"),
    PAINTING("Painting"),
    STATUE("Statue"),
    BALLOON("Balloon"),
    ROCKET("Rocket"),
    ;

    companion object {
        fun parse(s: String?): ThingShape = entries.firstOrNull { it.name == s } ?: CLOUD
    }
}

/** A thing on the home screen: an object holding a group of apps. */
data class Thing(
    val id: String,
    val name: String,
    val shape: ThingShape,
    /** Index into [com.childsplay.launcher.ui.Toy.thingColors]. */
    val color: Int,
    val appKeys: List<String>,
)

/** What sits on the home board, in order. */
sealed interface HomeItem {
    val id: String

    data class ThingItem(val thingId: String) : HomeItem {
        override val id get() = "thing:$thingId"
    }

    data object Weather : HomeItem {
        override val id = "widget:weather"
    }

    data object StickyNote : HomeItem {
        override val id = "widget:sticky"
    }

    companion object {
        fun parse(s: String): HomeItem? = when {
            s == Weather.id -> Weather
            s == StickyNote.id -> StickyNote
            s.startsWith("thing:") -> ThingItem(s.removePrefix("thing:"))
            else -> null
        }
    }
}

data class HomeState(
    val items: List<HomeItem> = emptyList(),
    val things: Map<String, Thing> = emptyMap(),
    /** Index of the home backdrop ("room"). */
    val room: Int = 0,
    /** Sticky note colour index into the note palette. */
    val noteColor: Int = 0,
    val seeded: Boolean = false,
)

/**
 * Persists the home board (things, their apps, order, room) as a small JSON file.
 * All mutations go through [update]; writes are atomic and off the main thread.
 */
class HomeStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "home.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _state = MutableStateFlow(read())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    fun update(transform: (HomeState) -> HomeState) {
        _state.update(transform)
        val snapshot = _state.value
        scope.launch { writeLock.withLock { write(snapshot) } }
    }

    // ---- Mutations -----------------------------------------------------------------------

    fun upsertThing(thing: Thing) = update { s ->
        val items = if (s.items.any { it is HomeItem.ThingItem && it.thingId == thing.id }) s.items
        else s.items + HomeItem.ThingItem(thing.id)
        s.copy(things = s.things + (thing.id to thing), items = items)
    }

    fun deleteThing(id: String) = update { s ->
        s.copy(
            things = s.things - id,
            items = s.items.filterNot { it is HomeItem.ThingItem && it.thingId == id },
        )
    }

    fun removeAppFromThing(thingId: String, appKey: String) = update { s ->
        val t = s.things[thingId] ?: return@update s
        s.copy(things = s.things + (thingId to t.copy(appKeys = t.appKeys - appKey)))
    }

    fun addAppToThing(thingId: String, appKey: String) = update { s ->
        val t = s.things[thingId] ?: return@update s
        if (appKey in t.appKeys) s
        else s.copy(things = s.things + (thingId to t.copy(appKeys = t.appKeys + appKey)))
    }

    fun move(itemId: String, delta: Int) = update { s ->
        val idx = s.items.indexOfFirst { it.id == itemId }
        if (idx < 0) return@update s
        val target = (idx + delta).coerceIn(0, s.items.lastIndex)
        if (target == idx) return@update s
        val list = s.items.toMutableList()
        val item = list.removeAt(idx)
        list.add(target, item)
        s.copy(items = list)
    }

    fun setWidgetVisible(item: HomeItem, visible: Boolean) = update { s ->
        val has = s.items.any { it.id == item.id }
        when {
            visible && !has -> s.copy(items = listOf(item) + s.items)
            !visible && has -> s.copy(items = s.items.filterNot { it.id == item.id })
            else -> s
        }
    }

    fun setRoom(room: Int) = update { it.copy(room = room) }
    fun setNoteColor(c: Int) = update { it.copy(noteColor = c) }

    // ---- Persistence ---------------------------------------------------------------------

    private fun read(): HomeState = try {
        if (!file.baseFile.exists()) HomeState()
        else decode(JSONObject(String(file.readFully(), Charsets.UTF_8)))
    } catch (t: Throwable) {
        Log.e(TAG, "Couldn't read home.json, starting fresh", t)
        HomeState()
    }

    private fun write(state: HomeState) {
        val out = try {
            file.startWrite()
        } catch (t: Throwable) {
            Log.e(TAG, "Couldn't open home.json for write", t)
            return
        }
        try {
            out.write(encode(state).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (t: Throwable) {
            Log.e(TAG, "Couldn't write home.json", t)
            file.failWrite(out)
        }
    }

    companion object {
        private const val TAG = "HomeStore"

        @Volatile private var instance: HomeStore? = null

        fun get(context: Context): HomeStore =
            instance ?: synchronized(this) {
                instance ?: HomeStore(context.applicationContext).also { instance = it }
            }

        fun newId(): String = UUID.randomUUID().toString().take(8)

        fun encode(state: HomeState): JSONObject = JSONObject().apply {
            put("version", 1)
            put("seeded", state.seeded)
            put("room", state.room)
            put("noteColor", state.noteColor)
            put("items", JSONArray(state.items.map { it.id }))
            put("things", JSONArray().apply {
                state.things.values.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id)
                        put("name", t.name)
                        put("shape", t.shape.name)
                        put("color", t.color)
                        put("apps", JSONArray(t.appKeys))
                    })
                }
            })
        }

        fun decode(o: JSONObject): HomeState {
            val things = LinkedHashMap<String, Thing>()
            val arr = o.optJSONArray("things") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val id = t.optString("id")
                if (id.isEmpty()) continue
                val apps = t.optJSONArray("apps") ?: JSONArray()
                things[id] = Thing(
                    id = id,
                    name = t.optString("name", "Thing"),
                    shape = ThingShape.parse(t.optString("shape")),
                    color = t.optInt("color", 0),
                    appKeys = (0 until apps.length()).mapNotNull { apps.optString(it).ifEmpty { null } }.distinct(),
                )
            }
            val itemsArr = o.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArr.length())
                .mapNotNull { HomeItem.parse(itemsArr.optString(it)) }
                .filter { it !is HomeItem.ThingItem || things.containsKey(it.thingId) }
                .distinctBy { it.id }
                .toMutableList()
            // Any thing missing from the order list still gets a spot.
            things.keys.forEach { id -> if (items.none { it is HomeItem.ThingItem && it.thingId == id }) items += HomeItem.ThingItem(id) }
            return HomeState(
                items = items,
                things = things,
                room = o.optInt("room", 0),
                noteColor = o.optInt("noteColor", 0),
                seeded = o.optBoolean("seeded", things.isNotEmpty()),
            )
        }
    }
}
