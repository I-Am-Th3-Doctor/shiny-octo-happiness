package com.childsplay.launcher.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.coroutines.resume

data class WeatherState(
    val forecast: Forecast? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** True when we have neither a location permission nor a picked city. */
    val needsPlace: Boolean = false,
)

/**
 * Shared by the Hero Weather app and the home-screen widget: one forecast, one skin, one unit
 * setting. Forecasts are cached on disk so both paint instantly, and refreshed at most every
 * 15 minutes unless forced.
 */
class WeatherStore private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(WeatherState(forecast = readCache()))
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    private val _skin = MutableStateFlow(Skins.byId(prefs.getString("skin", null)))
    val skin: StateFlow<Skin> = _skin.asStateFlow()

    private val _fahrenheit = MutableStateFlow(prefs.getBoolean("fahrenheit", defaultFahrenheit()))
    val fahrenheit: StateFlow<Boolean> = _fahrenheit.asStateFlow()

    /** A city the user picked; null means "use my location". */
    private val _manualPlace = MutableStateFlow(readManualPlace())
    val manualPlace: StateFlow<Place?> = _manualPlace.asStateFlow()

    fun setSkin(skin: Skin) {
        _skin.value = skin
        prefs.edit().putString("skin", skin.id).apply()
    }

    fun setFahrenheit(f: Boolean) {
        _fahrenheit.value = f
        prefs.edit().putBoolean("fahrenheit", f).apply()
    }

    fun setManualPlace(place: Place?) {
        _manualPlace.value = place
        prefs.edit().apply {
            if (place == null) remove("manual_place")
            else putString("manual_place", JSONObject().put("name", place.name).put("lat", place.lat).put("lon", place.lon).toString())
        }.apply()
        refresh(force = true)
    }

    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Refresh if stale (or [force]). Safe to call often, e.g. from every onResume. */
    fun refresh(force: Boolean = false) {
        val cur = _state.value
        if (job?.isActive == true) return
        val fresh = cur.forecast?.let { System.currentTimeMillis() - it.fetchedAt < STALE_MS } == true
        if (!force && fresh) return
        job = scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val place = _manualPlace.value ?: currentPlace()
                if (place == null) {
                    _state.update { it.copy(loading = false, needsPlace = true) }
                    return@launch
                }
                val forecast = WeatherApi.fetch(place)
                writeCache(forecast)
                _state.value = WeatherState(forecast = forecast)
            } catch (t: Throwable) {
                Log.w(TAG, "Weather refresh failed", t)
                _state.update { it.copy(loading = false, error = "Couldn't reach the weather. Pull to try again.") }
            }
        }
    }

    // ---- Location ------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun currentPlace(): Place? {
        if (!hasLocationPermission()) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        val loc = if (last != null && System.currentTimeMillis() - last.time < 60 * 60 * 1000L) last
        else freshLocation(lm, providers) ?: last
        loc ?: return null
        return Place(placeName(loc), loc.latitude, loc.longitude)
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshLocation(lm: LocationManager, providers: List<String>): Location? {
        val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in providers } ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                runCatching {
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
                }.onFailure { cont.resume(null) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun placeName(loc: Location): String = withContext(Dispatchers.IO) {
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            val a = Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea
        }.getOrNull() ?: "Right here"
    }

    // ---- Cache ---------------------------------------------------------------------------

    private fun readManualPlace(): Place? = runCatching {
        val s = prefs.getString("manual_place", null) ?: return null
        val o = JSONObject(s)
        Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
    }.getOrNull()

    private fun defaultFahrenheit(): Boolean =
        Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")

    private fun readCache(): Forecast? = runCatching {
        prefs.getString("cache", null)?.let { decode(JSONObject(it)) }
    }.getOrNull()

    private fun writeCache(f: Forecast) {
        runCatching { prefs.edit().putString("cache", encode(f).toString()).apply() }
    }

    companion object {
        private const val TAG = "WeatherStore"
        const val STALE_MS = 15 * 60 * 1000L

        @Volatile private var instance: WeatherStore? = null

        fun get(context: Context): WeatherStore =
            instance ?: synchronized(this) {
                instance ?: WeatherStore(context.applicationContext).also { instance = it }
            }

        private fun JSONObject.optD(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
        private fun JSONObject.putOpt2(k: String, v: Any?): JSONObject = if (v == null) this else put(k, v)

        fun encode(f: Forecast): JSONObject = JSONObject().apply {
            put("place", JSONObject().put("name", f.place.name).put("lat", f.place.lat).put("lon", f.place.lon))
            put("current", JSONObject().apply {
                put("t", f.current.tempC); putOpt2("f", f.current.feelsC); put("c", f.current.code)
                put("d", f.current.isDay); putOpt2("w", f.current.windKmh); putOpt2("h", f.current.humidity)
                put("s", f.current.source.name)
            })
            put("hours", JSONArray().apply {
                f.hours.forEach { h ->
                    put(JSONObject().apply {
                        put("time", h.time.toString()); put("t", h.tempC); putOpt2("f", h.feelsC); put("c", h.code)
                        putOpt2("p", h.precipMm); putOpt2("w", h.windKmh); put("d", h.isDay); put("s", h.source.name)
                    })
                }
            })
            put("days", JSONArray().apply {
                f.days.forEach { d ->
                    put(JSONObject().apply {
                        put("date", d.date.toString()); put("hi", d.hiC); put("lo", d.loC); put("c", d.code)
                        putOpt2("p", d.precipMm); putOpt2("sr", d.sunrise?.toString()); putOpt2("ss", d.sunset?.toString())
                        put("s", d.source.name)
                    })
                }
            })
            put("alerts", JSONArray().apply {
                f.alerts.forEach { a ->
                    put(JSONObject().put("e", a.event).put("sv", a.severity).put("hl", a.headline)
                        .put("ds", a.description).put("in", a.instruction).putOpt2("end", a.ends))
                }
            })
            put("off", f.utcOffsetSeconds)
            put("at", f.fetchedAt)
        }

        fun decode(o: JSONObject): Forecast {
            val p = o.getJSONObject("place")
            val c = o.getJSONObject("current")
            val hours = o.getJSONArray("hours").let { a ->
                (0 until a.length()).map { i ->
                    val h = a.getJSONObject(i)
                    HourPoint(
                        LocalDateTime.parse(h.getString("time")), h.getDouble("t"), h.optD("f"), h.getInt("c"),
                        h.optD("p"), null, h.optD("w"), h.optBoolean("d", true), Source.valueOf(h.getString("s")),
                    )
                }
            }
            val days = o.getJSONArray("days").let { a ->
                (0 until a.length()).map { i ->
                    val d = a.getJSONObject(i)
                    DayPoint(
                        LocalDate.parse(d.getString("date")), d.getDouble("hi"), d.getDouble("lo"), d.getInt("c"),
                        d.optD("p"), null,
                        d.optString("sr").takeIf { d.has("sr") }?.let { LocalDateTime.parse(it) },
                        d.optString("ss").takeIf { d.has("ss") }?.let { LocalDateTime.parse(it) },
                        Source.valueOf(d.getString("s")),
                    )
                }
            }
            val alerts = o.optJSONArray("alerts")?.let { a ->
                (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    Alert(x.optString("e"), x.optString("sv"), x.optString("hl"), x.optString("ds"), x.optString("in"),
                        if (x.has("end")) x.optString("end") else null)
                }
            }.orEmpty()
            return Forecast(
                place = Place(p.getString("name"), p.getDouble("lat"), p.getDouble("lon")),
                current = Current(
                    c.getDouble("t"), c.optD("f"), c.getInt("c"), c.optBoolean("d", true), c.optD("w"), c.optD("h"),
                    Source.valueOf(c.optString("s", "HRRR")),
                ),
                hours = hours, days = days, alerts = alerts,
                utcOffsetSeconds = o.optInt("off", 0), fetchedAt = o.optLong("at", 0L),
            )
        }
    }
}

/** Display helpers shared by the app and the widget. */
object Units {
    fun temp(c: Double, f: Boolean): Int = Math.round(if (f) c * 9.0 / 5.0 + 32.0 else c).toInt()
    fun wind(kmh: Double, f: Boolean): String =
        if (f) "${Math.round(kmh * 0.621371)} mph" else "${Math.round(kmh)} km/h"
    fun precip(mm: Double, f: Boolean): String =
        if (f) String.format(Locale.US, "%.2f in", mm / 25.4) else String.format(Locale.US, "%.1f mm", mm)
}
