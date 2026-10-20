package io.github.iamth3doctor.searchlight.data

import android.content.Context
import io.github.iamth3doctor.searchlight.cards.CardType
import io.github.iamth3doctor.searchlight.cards.QuickAction
import io.github.iamth3doctor.searchlight.cards.SlotConfig
import io.github.iamth3doctor.searchlight.weather.GeoPlace
import io.github.iamth3doctor.searchlight.weather.TempUnit
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.widgets.WidgetLayout
import io.github.iamth3doctor.searchlight.widgets.WidgetSpec

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

    /** Runtime permissions we've asked for at least once (Android stops showing the prompt after a denial). */
    fun wasAsked(permission: String) = permission in sp.getStringSet(KEY_ASKED, emptySet()).orEmpty()
    fun markAsked(permissions: Array<String>) =
        sp.edit().putStringSet(KEY_ASKED, sp.getStringSet(KEY_ASKED, emptySet()).orEmpty() + permissions).apply()

    // ---- Widget Center ----

    /** All built-in cards in display order (new card types get appended). */
    var cardOrder: List<CardType>
        get() {
            val saved = sp.getString(KEY_CARDS, null).orEmpty().split(',')
                .mapNotNull { n -> CardType.entries.firstOrNull { it.name == n } }.distinct()
            return saved + CardType.entries.filter { it !in saved }
        }
        set(value) = sp.edit().putString(KEY_CARDS, value.joinToString(",") { it.name }).apply()

    var disabledCards: Set<CardType>
        get() = sp.getStringSet(KEY_DISABLED_CARDS, emptySet()).orEmpty()
            .mapNotNullTo(HashSet()) { n -> CardType.entries.firstOrNull { it.name == n } }
        set(value) = sp.edit().putStringSet(KEY_DISABLED_CARDS, value.mapTo(HashSet()) { it.name }).apply()

    /** What the pager shows, in order. */
    val enabledCards: List<CardType> get() = disabledCards.let { off -> cardOrder.filter { it !in off } }

    /** The card the home screen returns to. Weather by default. */
    var idleCard: CardType
        get() = CardType.entries.firstOrNull { it.name == sp.getString(KEY_IDLE_CARD, null) } ?: CardType.WEATHER
        set(value) = sp.edit().putString(KEY_IDLE_CARD, value.name).apply()

    var musicAutoFront: Boolean
        get() = sp.getBoolean(KEY_MUSIC_FRONT, true)
        set(value) = sp.edit().putBoolean(KEY_MUSIC_FRONT, value).apply()

    /** Package name of the music app the Music tile binds to; null = whatever is playing. */
    var defaultMusicPackage: String?
        get() = sp.getString(KEY_MUSIC_PACKAGE, null)
        set(value) = sp.edit().putString(KEY_MUSIC_PACKAGE, value).apply()

    /** The four Quick Actions slots, in order. Unknown/missing entries fall back per slot. */
    var quickActionSlots: List<QuickAction>
        get() = SlotConfig.parse(
            sp.getString(KEY_QA_SLOTS, null),
            QuickAction.entries.mapTo(HashSet()) { it.name },
            QuickAction.DEFAULTS.map { it.name },
        ).map { QuickAction.valueOf(it) }
        set(value) = sp.edit().putString(KEY_QA_SLOTS, SlotConfig.serialize(value.map { it.name })).apply()

    fun setQuickActionSlot(index: Int, action: QuickAction) {
        quickActionSlots = quickActionSlots.toMutableList().also { it[index] = action }
    }

    /** Real Android widgets on the home page, top to bottom. */
    var widgets: List<WidgetSpec>
        get() = WidgetLayout.parse(sp.getString(KEY_WIDGETS, null))
        set(value) = sp.edit().putString(KEY_WIDGETS, WidgetLayout.serialize(value)).apply()

    // ---- Weather ----

    /** Default °F. Stored as a name so it survives app updates. */
    var tempUnit: TempUnit
        get() = TempUnit.entries.firstOrNull { it.name == sp.getString(KEY_TEMP_UNIT, null) } ?: TempUnit.FAHRENHEIT
        set(value) = sp.edit().putString(KEY_TEMP_UNIT, value.name).apply()

    /** null = use device location; otherwise the place picked by ZIP/city search. */
    var manualPlace: GeoPlace?
        get() {
            val name = sp.getString(KEY_PLACE_NAME, null) ?: return null
            return GeoPlace(
                name,
                sp.getString(KEY_PLACE_ADMIN, null),
                sp.getString(KEY_PLACE_COUNTRY, null),
                java.lang.Double.longBitsToDouble(sp.getLong(KEY_PLACE_LAT, 0)),
                java.lang.Double.longBitsToDouble(sp.getLong(KEY_PLACE_LON, 0)),
            )
        }
        set(value) {
            val e = sp.edit()
            if (value == null) {
                e.remove(KEY_PLACE_NAME).remove(KEY_PLACE_ADMIN).remove(KEY_PLACE_COUNTRY).remove(KEY_PLACE_LAT).remove(KEY_PLACE_LON)
            } else {
                e.putString(KEY_PLACE_NAME, value.name).putString(KEY_PLACE_ADMIN, value.admin)
                    .putString(KEY_PLACE_COUNTRY, value.countryCode)
                    .putLong(KEY_PLACE_LAT, java.lang.Double.doubleToRawLongBits(value.latitude))
                    .putLong(KEY_PLACE_LON, java.lang.Double.doubleToRawLongBits(value.longitude))
            }
            e.apply()
        }

    var lastWeather: WeatherData?
        get() = sp.getString(KEY_WEATHER, null)?.let(WeatherData::fromJson)
        set(value) = sp.edit().putString(KEY_WEATHER, value?.toJson()).apply()

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
        private const val KEY_ASKED = "permissions_asked"
        private const val KEY_CARDS = "cards"
        private const val KEY_DISABLED_CARDS = "cards_disabled"
        private const val KEY_IDLE_CARD = "idle_card"
        private const val KEY_MUSIC_FRONT = "music_auto_front"
        private const val KEY_MUSIC_PACKAGE = "music_default_package"
        private const val KEY_QA_SLOTS = "quick_action_slots"
        private const val KEY_WIDGETS = "widgets"
        private const val KEY_TEMP_UNIT = "temp_unit"
        private const val KEY_PLACE_NAME = "place_name"
        private const val KEY_PLACE_ADMIN = "place_admin"
        private const val KEY_PLACE_COUNTRY = "place_country"
        private const val KEY_PLACE_LAT = "place_lat"
        private const val KEY_PLACE_LON = "place_lon"
        private const val KEY_WEATHER = "last_weather"
    }
}

enum class WebEngine(val label: String, private val template: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BRAVE("Brave", "https://search.brave.com/search?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s");

    fun url(query: String): String = template.format(java.net.URLEncoder.encode(query, "UTF-8"))
}
