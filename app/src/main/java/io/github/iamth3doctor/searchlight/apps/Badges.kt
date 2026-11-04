package io.github.iamth3doctor.searchlight.apps

import android.os.Handler
import android.os.Looper

/**
 * The unread counts the icons show: how many notifications each app has posted right now
 * (see [BadgeCounts]), fed by MediaListenerService while notification access is granted. Only
 * counts: no notification text is read or kept. Empty without access, so no badges show.
 */
object Badges {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    @Volatile var counts: Map<String, Int> = emptyMap()
        private set

    fun countFor(packageName: String): Int = counts[packageName] ?: 0

    /** New counts (any thread): listeners hear on the main thread if anything changed. */
    fun update(newCounts: Map<String, Int>) {
        if (newCounts == counts) return
        counts = newCounts
        main.post { listeners.toList().forEach { it() } }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
}
