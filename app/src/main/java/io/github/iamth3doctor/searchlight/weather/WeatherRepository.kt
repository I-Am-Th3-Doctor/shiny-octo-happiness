package io.github.iamth3doctor.searchlight.weather

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.iamth3doctor.searchlight.data.Prefs
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Current conditions + today's high/low, straight from Open-Meteo (free, no key). No other
 * weather app is involved, so units can't get crossed: we store Celsius, the card converts.
 *
 * Refreshes: on unlock, every 30 minutes while the screen is on, and when the home screen
 * resumes with data older than 30 minutes. The last reading is cached for instant cold starts.
 * Public methods: main thread only.
 */
class WeatherRepository(private val context: Context, private val prefs: Prefs) {

    enum class Problem { NEEDS_LOCATION, LOCATION_UNAVAILABLE, NETWORK }

    data class State(val data: WeatherData?, val loading: Boolean, val problem: Problem?)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<() -> Unit>()
    private var started = false

    var state = State(prefs.lastWeather, loading = false, problem = null)
        private set

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun setState(s: State) {
        state = s
        listeners.toList().forEach { it() }
    }

    fun hasLocationPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(context, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        main.postDelayed(periodic, PERIOD_MS)
        refreshIfOlderThan(PERIOD_MS)
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refreshIfOlderThan(UNLOCK_MIN_AGE_MS)
    }

    private val periodic: Runnable = object : Runnable {
        override fun run() {
            val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive != false
            // Screen off: skip; the next unlock refreshes anyway.
            if (interactive) refreshIfOlderThan(PERIOD_MS - 60_000)
            main.postDelayed(this, PERIOD_MS)
        }
    }

    /** Tests: hold off real fetches ([refresh] only counts them in [refreshRequests]). */
    @Volatile var holdForTest = false

    /** Every [refresh] asked for, held or not (tests). */
    var refreshRequests = 0
        private set

    /** Tests: what the home screen and widgets show, as if fetched (offline = the last fetch failed). */
    fun showForTest(data: WeatherData?, offline: Boolean) {
        // A real fetch still under way is dropped when it lands (as on switching cities), so it can't replace this.
        generation++
        setState(State(data, loading = false, problem = if (offline) Problem.NETWORK else null))
    }

    fun refreshIfOlderThan(maxAgeMs: Long) {
        val age = System.currentTimeMillis() - (state.data?.fetchedAt ?: 0)
        if (age > maxAgeMs || state.problem != null) refresh()
    }

    /** Call after the location setting changes (and from "Refresh" on the card). */
    fun refresh() {
        refreshRequests++
        if (holdForTest) return
        if (state.loading) return
        setState(state.copy(loading = true))
        val gen = generation
        val city = prefs.weatherCity
        val key = cityKey(city)
        val manual = prefs.manualPlace
        val previous = state.data
        val canUseDevice = hasLocationPermission()
        worker.execute {
            val result: State = try {
                val target: Triple<String, Double, Double>? = when {
                    city != null -> Triple(city.name, city.latitude, city.longitude)
                    manual != null -> Triple(manual.name, manual.latitude, manual.longitude)
                    canUseDevice -> deviceLocation()?.let { loc ->
                        val name = if (previous != null && distanceKm(previous, loc) < 3) previous.locationName else placeName(loc)
                        Triple(name, loc.latitude, loc.longitude)
                    } ?: previous?.let { Triple(it.locationName, it.latitude, it.longitude) }
                    else -> null
                }
                if (target == null) {
                    State(previous, false, if (canUseDevice) Problem.LOCATION_UNAVAILABLE else Problem.NEEDS_LOCATION)
                } else {
                    val (name, lat, lon) = target
                    // Each stretch from its best model, in one request (HRRR, ECMWF IFS, and best_match
                    // for any hour a model has no forecast for). Never best_match alone: if Open-Meteo
                    // turns the request down, the last good forecast stays up, marked offline.
                    val json = httpGet(OpenMeteo.forecastUrl(lat, lon))
                    var data = OpenMeteo.parseForecast(json, name, lat, lon, System.currentTimeMillis())
                    // Air quality is its own (free) call; without it the rest still shows.
                    val aqi = try {
                        OpenMeteo.parseAqi(httpGet(OpenMeteo.airQualityUrl(lat, lon)))
                    } catch (e: Exception) {
                        Log.w(TAG, "Air quality failed", e)
                        null
                    }
                    data.detail?.let { d -> data = data.copy(detail = d.copy(aqi = aqi)) }
                    // Severe-weather alerts from the National Weather Service (US; free, no key). If that call
                    // fails, the last known alerts stay; outside the US there are none.
                    val alerts = try {
                        NwsAlerts.parse(httpGet(NwsAlerts.url(lat, lon), accept = "application/geo+json"), System.currentTimeMillis())
                    } catch (e: HttpStatusException) {
                        if (e.code in 400..499) emptyList() else previous?.alerts
                    } catch (e: Exception) {
                        Log.w(TAG, "Alerts failed", e)
                        previous?.alerts
                    }
                    data = data.copy(alerts = alerts)
                    State(data, false, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Weather refresh failed", e)
                State(previous, false, Problem.NETWORK)
            }
            main.post {
                // Switched cities meanwhile: this answer is for the old one.
                if (gen != generation) return@post
                result.data?.let { if (it !== previous) { prefs.lastWeather = it; prefs.cacheWeather(key, it) } }
                setState(result)
            }
        }
    }

    // ------------------------------------------------------------------ cities

    private var generation = 0

    /** The city shown (null: home, the device's location or the place set in settings). */
    val city: GeoPlace? get() = prefs.weatherCity

    /** Cities saved on the weather screen, besides home. */
    val savedCities: List<GeoPlace> get() = prefs.weatherCities

    /** Home's name as last fetched (e.g. "Tacoma"), or null before any reading there. */
    fun homeName(): String? =
        (if (city == null) state.data?.locationName else null) ?: prefs.cachedWeather(HOME)?.locationName ?: prefs.manualPlace?.name

    /**
     * Shows [place] (null: home) everywhere: the weather screen, the widget and the Weather tile all
     * follow, so they always agree. Its last good reading shows at once (offline too), then it refreshes.
     */
    fun selectCity(place: GeoPlace?) {
        val from = cityKey(city)
        val to = cityKey(place)
        if (from == to && (place == null) == (city == null)) return
        state.data?.let { prefs.cacheWeather(from, it) }
        prefs.weatherCity = place
        generation++
        val cached = prefs.cachedWeather(to)
        prefs.lastWeather = cached
        setState(State(cached, loading = false, problem = null))
        refresh()
    }

    /** Saves [place] to the list (once). */
    fun saveCity(place: GeoPlace) {
        val list = prefs.weatherCities
        if (list.none { cityKey(it) == cityKey(place) }) prefs.weatherCities = list + place
    }

    /** Takes [place] off the list (back to home if it was showing). */
    fun removeCity(place: GeoPlace) {
        prefs.weatherCities = prefs.weatherCities.filter { cityKey(it) != cityKey(place) }
        if (city?.let(::cityKey) == cityKey(place)) selectCity(null)
    }

    /** Tests: answers city searches instead of the network. */
    @Volatile var searchForTest: ((String) -> List<GeoPlace>?)? = null

    /** ZIP/city lookup for the manual location picker. [onResult] gets null on network failure. */
    fun searchPlaces(query: String, onResult: (List<GeoPlace>?) -> Unit) {
        searchForTest?.let { fake -> main.post { onResult(fake(query)) }; return }
        worker.execute {
            val places = try {
                OpenMeteo.parseGeocode(httpGet(OpenMeteo.geocodeUrl(query)))
            } catch (e: Exception) {
                Log.w(TAG, "Geocoding failed", e)
                null
            }
            main.post { onResult(places) }
        }
    }

    // ------------------------------------------------------------------ location (worker thread)

    private fun deviceLocation(): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val last = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            return null
        }
        if (last != null && System.currentTimeMillis() - last.time < FRESH_FIX_MS) return last
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return last

        val provider = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).firstOrNull { lm.isProviderEnabled(it) } ?: return last

        var fix: Location? = null
        val latch = CountDownLatch(1)
        val cancel = CancellationSignal()
        try {
            lm.getCurrentLocation(provider, cancel, context.mainExecutor) { loc ->
                fix = loc
                latch.countDown()
            }
            if (!latch.await(15, TimeUnit.SECONDS)) cancel.cancel()
        } catch (e: SecurityException) {
            return last
        }
        return fix ?: last
    }

    /** Uses Android's own geocoder for the name; falls back to coordinates if it has none. */
    @Suppress("DEPRECATION")
    private fun placeName(loc: Location): String {
        if (Geocoder.isPresent()) {
            try {
                val a = Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                val name = a?.locality ?: a?.subAdminArea ?: a?.adminArea
                if (!name.isNullOrBlank()) return name
            } catch (e: Exception) {
                Log.w(TAG, "Reverse geocoding failed", e)
            }
        }
        return String.format(Locale.US, "%.2f, %.2f", loc.latitude, loc.longitude)
    }

    private fun distanceKm(w: WeatherData, loc: Location): Float {
        val out = FloatArray(1)
        Location.distanceBetween(w.latitude, w.longitude, loc.latitude, loc.longitude, out)
        return out[0] / 1000f
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    private fun httpGet(url: String, accept: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Searchlight-Launcher")
        // Always a fresh answer (a refresh must show the latest run), never a stored copy.
        conn.useCaches = false
        conn.setRequestProperty("Cache-Control", "no-cache")
        if (accept != null) conn.setRequestProperty("Accept", accept)
        try {
            if (conn.responseCode != 200) throw HttpStatusException(conn.responseCode)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** The cache key for [place] (null: home). */
        fun cityKey(place: GeoPlace?): String =
            if (place == null) HOME else String.format(Locale.US, "%.3f,%.3f", place.latitude, place.longitude)

        const val HOME = "home"
        private const val TAG = "Weather"
        private const val PERIOD_MS = 30 * 60 * 1000L
        private const val UNLOCK_MIN_AGE_MS = 2 * 60 * 1000L
        private const val FRESH_FIX_MS = 30 * 60 * 1000L
    }
}
