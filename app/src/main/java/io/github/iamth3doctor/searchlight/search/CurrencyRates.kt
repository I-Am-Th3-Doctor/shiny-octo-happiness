package io.github.iamth3doctor.searchlight.search

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Exchange rates for [CurrencyConverter]: the European Central Bank's daily reference rates
 * (via frankfurter.app: free, no key). Cached with their date, so a currency question answers
 * at once (offline too) with the date it's from; refreshed in the background when over
 * [MAX_AGE_MS] old, and [onUpdated] runs once new rates are in.
 */
class CurrencyRates(context: Context) {

    private val sp = context.getSharedPreferences("currency_rates", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var fetching = false

    /** Runs on the main thread when fresh rates have been saved. */
    var onUpdated: (() -> Unit)? = null

    /** The cached rates (null until the first fetch succeeds). */
    val cached: CurrencyConverter.Rates?
        get() {
            val json = sp.getString(KEY_RATES, null) ?: return null
            return runCatching { parse(JSONObject(json)) }.getOrNull()
        }

    /** Fetches new rates if there are none or they're old; does nothing while a fetch runs. */
    fun refreshIfStale() {
        if (fetching) return
        if (cached != null && System.currentTimeMillis() - sp.getLong(KEY_FETCHED_AT, 0) < MAX_AGE_MS) return
        fetching = true
        worker.execute {
            try {
                val conn = URL(URL_LATEST).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parse(JSONObject(body)) // only keep what reads back
                sp.edit().putString(KEY_RATES, body).putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply()
                main.post { onUpdated?.invoke() }
            } catch (e: Exception) {
                // Offline or the service is down: keep the cached rates (they show their date).
            } finally {
                fetching = false
            }
        }
    }

    /** Saves rates as if fetched (tests, without the network). */
    fun save(json: String) = sp.edit().putString(KEY_RATES, json).putLong(KEY_FETCHED_AT, System.currentTimeMillis()).commit()

    private fun parse(o: JSONObject): CurrencyConverter.Rates {
        val r = o.getJSONObject("rates")
        val map = HashMap<String, Double>()
        r.keys().forEach { map[it.uppercase()] = r.getDouble(it) }
        return CurrencyConverter.Rates(o.getString("date"), map)
    }

    private companion object {
        const val URL_LATEST = "https://api.frankfurter.app/latest"
        const val KEY_RATES = "rates_json"
        const val KEY_FETCHED_AT = "fetched_at"
        const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
    }
}
