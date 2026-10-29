package io.github.iamth3doctor.searchlight.apps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import io.github.iamth3doctor.searchlight.data.FavoritesMode
import io.github.iamth3doctor.searchlight.data.Prefs
import java.util.concurrent.Executors

/**
 * Automatic favorites: ranks apps by how often you launched them over the last 30 days
 * (UsageStatsManager, which needs the usage-access special permission) and saves the ranking in
 * [Prefs]. Re-ranked when it's more than a day old: on returning home, and when the favorites
 * popup opens. Without usage access the ranking is cleared, so the manual six show.
 *
 * Usage stats only cover the personal profile, so work apps never rank.
 */
class AutoFavorites(private val context: Context, private val prefs: Prefs, private val repo: AppRepository) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var running = false

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** Re-ranks if Automatic is on and the ranking is missing or older than a day. Main thread. */
    fun refreshIfStale() {
        if (prefs.favoritesMode != FavoritesMode.AUTOMATIC) return
        if (!hasAccess()) {
            if (prefs.autoFavoritesAt != 0L) prefs.saveAutoFavorites(emptyList(), 0)
            return
        }
        if (System.currentTimeMillis() - prefs.autoFavoritesAt < MAX_AGE_MS) return
        refresh()
    }

    /** Re-ranks now (in the background); the favorites listeners fire when it's saved. Main thread. */
    fun refresh() {
        if (running || prefs.favoritesMode != FavoritesMode.AUTOMATIC || !hasAccess()) return
        running = true
        val apps = repo.apps
        worker.execute {
            val now = System.currentTimeMillis()
            val keys = try {
                rank(launches(now - WINDOW_MS, now), candidates(apps))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read usage stats", e)
                null
            }
            main.post {
                running = false
                if (keys != null && prefs.favoritesMode == FavoritesMode.AUTOMATIC) prefs.saveAutoFavorites(keys, now)
            }
        }
    }

    private var predicting = false

    /**
     * The predicted 6th favorite (Manual mode, setting on): worked out again when it's older
     * than [PREDICTION_MAX_AGE_MS], called when the favorites row opens and on returning home,
     * so it follows the time of day and is never more than a day old. Without usage access the
     * predictions are cleared and slot 6 shows the manual 6th pick. Main thread.
     */
    fun refreshPrediction() {
        if (!prefs.predictedSlotActive || predicting) return
        if (!hasAccess()) {
            if (prefs.predictionsAt != 0L || prefs.predictions.isNotEmpty()) prefs.savePredictions(emptyList(), 0)
            return
        }
        if (System.currentTimeMillis() - prefs.predictionsAt < PREDICTION_MAX_AGE_MS) return
        predicting = true
        val apps = repo.apps
        worker.execute {
            val now = System.currentTimeMillis()
            val keys = try {
                predict(launches(now - WINDOW_MS, now), candidates(apps), now, java.util.TimeZone.getDefault())
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read usage stats", e)
                null
            }
            main.post {
                predicting = false
                if (keys != null && prefs.predictedSlotActive) prefs.savePredictions(keys, now)
            }
        }
    }

    /** package -> app key: the first launcher activity of each personal-profile app, never Searchlight. */
    private fun candidates(apps: List<AppEntry>): Map<String, String> {
        val me = Process.myUserHandle()
        val own = context.packageName
        val out = HashMap<String, String>()
        for (a in apps) if (a.user == me && !a.isSelf && a.packageName != own) out.putIfAbsent(a.packageName, a.key)
        return out
    }

    /** Foreground events (package, time) in order. Android may keep less than [from] goes back. */
    private fun launches(from: Long, to: Long): List<Pair<String, Long>> {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        val out = ArrayList<Pair<String, Long>>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) out += e.packageName to e.timeStamp
        }
        return out
    }

    companion object {
        private const val TAG = "AutoFavorites"
        const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        /** The prediction follows the time of day: worked out again after this long. */
        const val PREDICTION_MAX_AGE_MS = 15L * 60 * 1000
        /** Kept beyond six so an uninstall doesn't leave a gap before the next re-rank. */
        private const val KEEP = 12

        private const val HOUR_MS = 60L * 60 * 1000
        private const val DAY_MS = 24 * HOUR_MS

        /**
         * The predicted 6th favorite, best first: launches like [rank], but each launch counts more
         * the closer its time of day is to [now]'s (up to 4x within the hour, back to 1x two hours
         * away), so the app you open every morning wins in the morning. Ties: most recent.
         */
        fun predict(
            foreground: List<Pair<String, Long>>,
            candidates: Map<String, String>,
            now: Long,
            zone: java.util.TimeZone,
        ): List<String> {
            fun hourOfDay(t: Long) = Math.floorMod(t + zone.getOffset(t), DAY_MS).toDouble() / HOUR_MS
            val nowHour = hourOfDay(now)
            val score = HashMap<String, Double>()
            val last = HashMap<String, Long>()
            var previous: String? = null
            for ((pkg, time) in foreground) {
                if (pkg != previous) {
                    val apart = Math.abs(hourOfDay(time) - nowHour).let { minOf(it, 24 - it) }
                    score[pkg] = (score[pkg] ?: 0.0) + 1 + 3 * maxOf(0.0, 1 - apart / 2)
                }
                last[pkg] = time
                previous = pkg
            }
            return score.keys.filter { it in candidates }
                .sortedWith(compareByDescending<String> { score[it] }.thenByDescending { last[it] })
                .take(KEEP)
                .map { candidates.getValue(it) }
        }

        /**
         * App keys, most launches first (ties: most recent). A launch is coming to the foreground
         * from a different app, so moving between screens inside one app counts once. Only
         * packages in [candidates] (installed, personal profile, not Searchlight) rank.
         */
        fun rank(foreground: List<Pair<String, Long>>, candidates: Map<String, String>): List<String> {
            val counts = HashMap<String, Int>()
            val last = HashMap<String, Long>()
            var previous: String? = null
            for ((pkg, time) in foreground) {
                if (pkg != previous) counts[pkg] = (counts[pkg] ?: 0) + 1
                last[pkg] = time
                previous = pkg
            }
            return counts.keys.filter { it in candidates }
                .sortedWith(compareByDescending<String> { counts[it] }.thenByDescending { last[it] })
                .take(KEEP)
                .map { candidates.getValue(it) }
        }
    }
}
