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

    var autoOpenKeyboard: Boolean
        get() = sp.getBoolean(KEY_AUTO_KEYBOARD, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_KEYBOARD, value).apply()

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
        private const val KEY_AUTO_KEYBOARD = "auto_keyboard"
    }
}
