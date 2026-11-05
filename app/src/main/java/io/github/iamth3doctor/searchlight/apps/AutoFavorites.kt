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
 *
 * The default Phone and Messaging apps (as the system says now, looked up at every ranking) are
 * always among the six, however little they're used; they still sit where their usage puts
 * them (see [rank]). A change of either default re-ranks on the next return home.
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

    /**
     * Re-ranks if Automatic is on and the ranking is missing or older than a day, or the default
     * Phone or Messaging app changed since it was made. Main thread.
     */
    fun refreshIfStale() {
        if (prefs.favoritesMode != FavoritesMode.AUTOMATIC) return
        if (!hasAccess()) {
            if (prefs.autoFavoritesAt != 0L) prefs.saveAutoFavorites(emptyList(), 0)
            return
        }
        val defaultsChanged = defaultPackages() != prefs.autoDefaultPackages
        if (!defaultsChanged && System.currentTimeMillis() - prefs.autoFavoritesAt < MAX_AGE_MS) return
        refresh()
    }

    /** Device tests: stand-ins for the system's default Phone and Messaging apps (null: the real ones). */
    @Volatile internal var defaultsOverride: List<String?>? = null

    /**
     * The default Phone (dialer) and Messaging (SMS) apps' packages, as the system says now; an
     * unset or unreadable one is left out (its slot goes to usage). One app can be both.
     */
    fun defaultPackages(): List<String> {
        defaultsOverride?.let { return it.filterNotNull().distinct() }
        val dialer = runCatching { context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        val sms = runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        return listOfNotNull(dialer, sms).distinct()
    }

    /** Re-ranks now (in the background); the favorites listeners fire when it's saved. Main thread. */
    fun refresh() {
        if (running || prefs.favoritesMode != FavoritesMode.AUTOMATIC || !hasAccess()) return
        running = true
        val apps = repo.apps
        val defaults = defaultPackages() // looked up afresh every time: a changed default follows
        worker.execute {
            val now = System.currentTimeMillis()
            val cands = candidates(apps)
            val keys = try {
                rank(launches(now - WINDOW_MS, now), cands, defaults)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read usage stats", e)
                null
            }
            main.post {
                running = false
                if (keys != null && prefs.favoritesMode == FavoritesMode.AUTOMATIC) {
                    prefs.saveAutoFavorites(keys, now, defaults, defaults.mapNotNull { cands[it] })
                }
            }
        }
    }

    private var predicting = false

    /**
     * The predicted 6th favorite (Manual mode, setting on): worked out again when it's older
     * than [PREDICTION_MAX_AGE_MS], called when the favorites row opens and on returning home,
     * so it tracks what you're using now and is never more than a day old. Without usage access the
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
        /** The prediction follows what you're doing now: worked out again after this long. */
        const val PREDICTION_MAX_AGE_MS = 5L * 60 * 1000
        /** Kept beyond six so an uninstall doesn't leave a gap before the next re-rank. */
        private const val KEEP = 12

        private const val MINUTE_MS = 60L * 1000
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val DAY_MS = 24 * HOUR_MS

        /**
         * The predicted 6th favorite's candidates, best first: what you're using now, then what
         * you've used today, then the long-run habit for this time of day. Slot 6 takes the first
         * that isn't one of slots 1-5, so when the recent apps are all favorites already, it
         * falls through to today's, then to the habit; with nothing at all, the manual 6th pick.
         */
        fun predict(
            foreground: List<Pair<String, Long>>,
            candidates: Map<String, String>,
            now: Long,
            zone: java.util.TimeZone,
        ): List<String> = (
            recentRanking(foreground, candidates, now) +
                todayRanking(foreground, candidates, now, zone) +
                timeOfDayRanking(foreground, candidates, now, zone)
            ).distinct().take(3 * KEEP)

        /**
         * How much a launch [ageMs] ago counts toward "what you're using now": 1 within the last
         * 30 minutes, 0.5 within the hour, 0.25 within two hours, nothing older.
         */
        fun recencyWeight(ageMs: Long): Double = when {
            ageMs <= 30 * MINUTE_MS -> 1.0 // (a launch stamped a moment "ahead" of now counts as now)
            ageMs <= HOUR_MS -> 0.5
            ageMs <= 2 * HOUR_MS -> 0.25
            else -> 0.0
        }

        /** Apps launched in the last two hours, by [recencyWeight] summed (ties: most recent). */
        fun recentRanking(foreground: List<Pair<String, Long>>, candidates: Map<String, String>, now: Long): List<String> {
            val score = HashMap<String, Double>()
            val last = HashMap<String, Long>()
            for ((pkg, time) in launches(foreground)) {
                val w = recencyWeight(now - time)
                if (w == 0.0) continue
                score[pkg] = (score[pkg] ?: 0.0) + w
                last[pkg] = maxOf(last[pkg] ?: 0L, time)
            }
            return best(score, last, candidates)
        }

        /** Apps launched since local midnight, most launches first (ties: most recent). */
        fun todayRanking(foreground: List<Pair<String, Long>>, candidates: Map<String, String>, now: Long, zone: java.util.TimeZone): List<String> {
            val midnight = now - Math.floorMod(now + zone.getOffset(now), DAY_MS)
            val score = HashMap<String, Double>()
            val last = HashMap<String, Long>()
            for ((pkg, time) in launches(foreground)) {
                if (time < midnight) continue
                score[pkg] = (score[pkg] ?: 0.0) + 1
                last[pkg] = maxOf(last[pkg] ?: 0L, time)
            }
            return best(score, last, candidates)
        }

        /**
         * The long-run habit: launches over the whole window, each counting more the closer its
         * time of day is to [now]'s (up to 4x within the hour, back to 1x two hours away), so the
         * app you open every morning ranks high in the morning. Ties: most recent.
         */
        fun timeOfDayRanking(foreground: List<Pair<String, Long>>, candidates: Map<String, String>, now: Long, zone: java.util.TimeZone): List<String> {
            fun hourOfDay(t: Long) = Math.floorMod(t + zone.getOffset(t), DAY_MS).toDouble() / HOUR_MS
            val nowHour = hourOfDay(now)
            val score = HashMap<String, Double>()
            val last = HashMap<String, Long>()
            for ((pkg, time) in launches(foreground)) {
                val apart = Math.abs(hourOfDay(time) - nowHour).let { minOf(it, 24 - it) }
                score[pkg] = (score[pkg] ?: 0.0) + 1 + 3 * maxOf(0.0, 1 - apart / 2)
                last[pkg] = maxOf(last[pkg] ?: 0L, time)
            }
            return best(score, last, candidates)
        }

        /** One (package, time) per launch: coming to the foreground from a different app. */
        private fun launches(foreground: List<Pair<String, Long>>): List<Pair<String, Long>> {
            val out = ArrayList<Pair<String, Long>>()
            var previous: String? = null
            for ((pkg, time) in foreground) {
                if (pkg != previous) out += pkg to time
                previous = pkg
            }
            return out
        }

        private fun best(score: Map<String, Double>, last: Map<String, Long>, candidates: Map<String, String>): List<String> =
            score.keys.filter { it in candidates && (score[it] ?: 0.0) > 0.0 }
                .sortedWith(compareByDescending<String> { score[it] }.thenByDescending { last[it] })
                .take(KEEP)
                .map { candidates.getValue(it) }

        /** The favorites row's length. */
        const val SIX = 6

        /**
         * App keys, most launches first (ties: most recent). A launch is coming to the foreground
         * from a different app, so moving between screens inside one app counts once. Only
         * packages in [candidates] (installed, personal profile, not Searchlight) rank.
         *
         * [defaults] (the default Phone and Messaging apps' packages) are always in the first six:
         * one that isn't takes the place of the lowest-ranked app there that isn't a default, then
         * the six are sorted by usage again, so they float like any other app. One app that is both
         * takes one place; a default that isn't a candidate (not installed) is skipped. Apps pushed
         * out of the six follow it, in order.
         */
        fun rank(foreground: List<Pair<String, Long>>, candidates: Map<String, String>, defaults: Collection<String> = emptyList()): List<String> {
            val counts = HashMap<String, Int>()
            val last = HashMap<String, Long>()
            var previous: String? = null
            for ((pkg, time) in foreground) {
                if (pkg != previous) counts[pkg] = (counts[pkg] ?: 0) + 1
                last[pkg] = time
                previous = pkg
            }
            val byUsage = compareByDescending<String> { counts[it] ?: 0 }.thenByDescending { last[it] ?: 0L }
            val ranked = counts.keys.filter { it in candidates }.sortedWith(byUsage)
            val guaranteed = defaults.filter { it in candidates }.distinct()
            val six = ranked.take(SIX).toMutableList()
            for (d in guaranteed) {
                if (d in six) continue
                if (six.size < SIX) { six += d; continue }
                // The lowest-ranked app in the six that isn't a default gives up its place.
                val out = six.indices.reversed().firstOrNull { six[it] !in guaranteed } ?: continue
                six[out] = d
            }
            val top = six.sortedWith(byUsage)
            return (top + ranked.filter { it !in top }).take(KEEP).map { candidates.getValue(it) }
        }

        /**
         * What the row shows in Automatic: [list] (apps you added, then the ranking, then manual
         * picks to fill gaps) with every [defaults] key kept within the first six. If added apps
         * pushed one past six, it takes the place of the last ranked (not added, not default) app
         * there, and the ranked part keeps its usage order.
         */
        fun keepDefaultsInSix(list: List<String>, added: List<String>, ranking: List<String>, defaults: List<String>): List<String> {
            val six = list.take(SIX).toMutableList()
            for (d in defaults.distinct()) {
                if (d in six || d !in list) continue
                val out = six.indices.reversed().firstOrNull { six[it] !in added && six[it] !in defaults } ?: continue
                six[out] = d
            }
            val (fixed, ranked) = six.partition { it in added }
            val order = { k: String -> ranking.indexOf(k).let { if (it < 0) Int.MAX_VALUE else it } }
            val top = fixed + ranked.sortedBy(order)
            return top + list.filter { it !in top }
        }
    }
}
