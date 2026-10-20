package io.github.iamth3doctor.searchlight.data

import android.content.Context

/** All persisted state. Everything stays in this app's private storage. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("searchlight", Context.MODE_PRIVATE)
    private val usageSp = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    // Read once; launch counts are consulted on every keystroke.
    private val usage = HashMap<String, Int>().apply {
        usageSp.all.forEach { (k, v) -> if (v is Int) put(k, v) }
    }

    private val favoriteListeners = mutableListOf<() -> Unit>()

    /** Off by default: the idle home screen shows the glance cards, the keyboard would cover them. */
    var autoOpenKeyboard: Boolean
        get() = sp.getBoolean(KEY_AUTO_KEYBOARD, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_KEYBOARD, value).apply()

    /** Custom pill text; empty means the automatic "Good morning/afternoon/evening". */
    var greeting: String
        get() = sp.getString(KEY_GREETING, "").orEmpty()
        set(value) = sp.edit().putString(KEY_GREETING, value.trim()).apply()

    var webEngine: WebEngine
        get() = WebEngine.entries.firstOrNull { it.name == sp.getString(KEY_WEB_ENGINE, null) } ?: WebEngine.GOOGLE
        set(value) = sp.edit().putString(KEY_WEB_ENGINE, value.name).apply()

    /** The "grant access to search contacts & files" row in results, until dismissed. */
    var accessHintDismissed: Boolean
        get() = sp.getBoolean(KEY_ACCESS_HINT_DISMISSED, false)
        set(value) = sp.edit().putBoolean(KEY_ACCESS_HINT_DISMISSED, value).apply()

    /** Whether we've shown the system contacts prompt once (after a denial, Android stops showing it). */
    var contactsPermissionAsked: Boolean
        get() = sp.getBoolean(KEY_CONTACTS_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_CONTACTS_ASKED, value).apply()

    // ---- Favorites: an ordered list of up to MAX_FAVORITES app keys ----

    val favorites: List<String>
        get() = sp.getString(KEY_FAVORITES, "").orEmpty().split('\n').filter { it.isNotEmpty() }

    fun isFavorite(key: String) = key in favorites

    /** @return false if the list is already full. */
    fun addFavorite(key: String): Boolean {
        val list = favorites
        if (key in list) return true
        if (list.size >= MAX_FAVORITES) return false
        saveFavorites(list + key)
        return true
    }

    fun removeFavorite(key: String) = saveFavorites(favorites - key)

    fun moveFavorite(key: String, delta: Int) {
        val list = favorites.toMutableList()
        val from = list.indexOf(key)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return
        list.add(to, list.removeAt(from))
        saveFavorites(list)
    }

    private fun saveFavorites(list: List<String>) {
        sp.edit().putString(KEY_FAVORITES, list.joinToString("\n")).apply()
        favoriteListeners.toList().forEach { it() }
    }

    fun addFavoritesListener(l: () -> Unit) { favoriteListeners += l }
    fun removeFavoritesListener(l: () -> Unit) { favoriteListeners -= l }

    // ---- Launch counts: feed the ranking so your usual app wins ties ----

    fun launchCount(key: String): Int = usage[key] ?: 0

    fun recordLaunch(key: String) {
        val n = launchCount(key) + 1
        usage[key] = n
        usageSp.edit().putInt(key, n).apply()
    }

    companion object {
        const val MAX_FAVORITES = 6
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_AUTO_KEYBOARD = "auto_keyboard_v2"
        private const val KEY_GREETING = "greeting"
        private const val KEY_WEB_ENGINE = "web_engine"
        private const val KEY_ACCESS_HINT_DISMISSED = "access_hint_dismissed"
        private const val KEY_CONTACTS_ASKED = "contacts_asked"
    }
}

enum class WebEngine(val label: String, private val template: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BRAVE("Brave", "https://search.brave.com/search?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s");

    fun url(query: String): String = template.format(java.net.URLEncoder.encode(query, "UTF-8"))
}
