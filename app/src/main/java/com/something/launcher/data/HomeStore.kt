package com.something.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
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

/** What sits on the home grid, in order. Widgets span two columns; apps take one cell. */
sealed interface HomeItem {
    val id: String

    data object Weather : HomeItem { override val id = "widget:weather" }
    data object StickyNote : HomeItem { override val id = "widget:sticky" }
    data class App(val key: String) : HomeItem { override val id = "app:$key" }

    companion object {
        fun parse(s: String): HomeItem? = when {
            s == Weather.id -> Weather
            s == StickyNote.id -> StickyNote
            s.startsWith("app:") -> App(s.removePrefix("app:")).takeIf { it.key.isNotEmpty() }
            else -> null
        }
    }
}

data class HomeState(
    val items: List<HomeItem> = emptyList(),
    val seeded: Boolean = false,
) {
    val pinned: Set<String> get() = items.mapNotNullTo(HashSet()) { (it as? HomeItem.App)?.key }
}

/** Persists the home grid as a small JSON file; atomic writes, off the main thread. */
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

    fun pin(appKey: String) = update { s ->
        if (appKey in s.pinned) s else s.copy(items = s.items + HomeItem.App(appKey))
    }

    fun unpin(appKey: String) = update { s -> s.copy(items = s.items.filterNot { it == HomeItem.App(appKey) }) }

    fun move(itemId: String, delta: Int) = update { s ->
        val idx = s.items.indexOfFirst { it.id == itemId }
        if (idx < 0) return@update s
        val target = (idx + delta).coerceIn(0, s.items.lastIndex)
        if (target == idx) return@update s
        val list = s.items.toMutableList()
        list.add(target, list.removeAt(idx))
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

    private fun read(): HomeState = try {
        if (!file.baseFile.exists()) HomeState() else decode(JSONObject(String(file.readFully(), Charsets.UTF_8)))
    } catch (t: Throwable) {
        Log.e(TAG, "Couldn't read home.json, starting fresh", t)
        HomeState()
    }

    private fun write(state: HomeState) {
        val out = try { file.startWrite() } catch (t: Throwable) { Log.e(TAG, "open failed", t); return }
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
            instance ?: synchronized(this) { instance ?: HomeStore(context.applicationContext).also { instance = it } }

        fun encode(state: HomeState): JSONObject = JSONObject()
            .put("version", 2)
            .put("seeded", state.seeded)
            .put("items", JSONArray(state.items.map { it.id }))

        fun decode(o: JSONObject): HomeState {
            val arr = o.optJSONArray("items") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { HomeItem.parse(arr.optString(it)) }.distinctBy { it.id }
            return HomeState(items = items, seeded = o.optBoolean("seeded", items.isNotEmpty()))
        }

        /**
         * First-run grid: both widgets, then the essentials (phone, messages, camera, browser,
         * mail, maps, photos, settings) resolved from the phone's own defaults.
         */
        fun seed(context: Context, apps: List<AppEntry>): HomeState {
            val pm = context.packageManager
            fun def(intent: Intent): String? = runCatching {
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            }.getOrNull()?.takeIf { it != "android" }
            val wanted = listOfNotNull(
                def(Intent(Intent.ACTION_DIAL)),
                def(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))),
                def(Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
                def(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
                def(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))),
                def(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=coffee"))),
                "com.google.android.apps.photos",
                "com.android.settings",
            ).distinct()
            val main = apps.filter { !it.isWorkProfile }
            val keys = wanted.mapNotNull { pkg -> main.firstOrNull { it.packageName == pkg }?.key }.distinct().take(8)
            return HomeState(
                items = listOf(HomeItem.Weather, HomeItem.StickyNote) + keys.map { HomeItem.App(it) },
                seeded = true,
            )
        }
    }
}
