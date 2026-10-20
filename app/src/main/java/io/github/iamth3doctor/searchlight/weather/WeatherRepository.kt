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

    fun refreshIfOlderThan(maxAgeMs: Long) {
        val age = System.currentTimeMillis() - (state.data?.fetchedAt ?: 0)
        if (age > maxAgeMs || state.problem != null) refresh()
    }

    /** Call after the location setting changes (and from "Refresh" on the card). */
    fun refresh() {
        if (state.loading) return
        setState(state.copy(loading = true))
        val manual = prefs.manualPlace
        val previous = state.data
        val canUseDevice = hasLocationPermission()
        worker.execute {
            val result: State = try {
                val target: Triple<String, Double, Double>? = when {
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
                    val json = httpGet(OpenMeteo.forecastUrl(lat, lon))
                    State(OpenMeteo.parseForecast(json, name, lat, lon, System.currentTimeMillis()), false, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Weather refresh failed", e)
                State(previous, false, Problem.NETWORK)
            }
            main.post {
                result.data?.let { if (it !== previous) prefs.lastWeather = it }
                setState(result)
            }
        }
    }

    /** ZIP/city lookup for the manual location picker. [onResult] gets null on network failure. */
    fun searchPlaces(query: String, onResult: (List<GeoPlace>?) -> Unit) {
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

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Searchlight-Launcher")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "Weather"
        const val PERIOD_MS = 30 * 60 * 1000L
        const val UNLOCK_MIN_AGE_MS = 2 * 60 * 1000L
        const val FRESH_FIX_MS = 30 * 60 * 1000L
    }
}
