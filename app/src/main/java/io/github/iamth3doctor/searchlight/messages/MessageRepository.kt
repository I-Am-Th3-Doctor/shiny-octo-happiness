package io.github.iamth3doctor.searchlight.messages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import io.github.iamth3doctor.searchlight.contacts.ContactRepository
import io.github.iamth3doctor.searchlight.files.FileMatcher
import io.github.iamth3doctor.searchlight.search.FuzzyMatcher
import java.util.concurrent.Executors

class MessageEntry(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val incoming: Boolean,
) {
    val folded: String = FileMatcher.fold(body)
}

/**
 * The most recent [MAX_MESSAGES] SMS kept in memory (read-only; needs READ_SMS) so their text
 * is searchable. Searched on the background search thread together with files.
 * Public methods: main thread only, except [search] which works on an immutable snapshot.
 */
class MessageRepository(private val context: Context, private val contacts: ContactRepository) {

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var observing = false

    @Volatile var messages: List<MessageEntry> = emptyList()
        private set

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!hasPermission()) {
            messages = emptyList()
            return
        }
        if (!observing) {
            observing = true
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            reload()
        }
    }

    private val reloadRunnable = Runnable { reload() }

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(reloadRunnable)
            main.postDelayed(reloadRunnable, 5_000)
        }
    }

    private fun reload() {
        loader.execute {
            val list = ArrayList<MessageEntry>()
            try {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                    null, null, "${Telephony.Sms.DATE} DESC",
                )?.use { c ->
                    while (c.moveToNext() && list.size < MAX_MESSAGES) {
                        val body = c.getString(2) ?: continue
                        val address = c.getString(1) ?: continue
                        list += MessageEntry(c.getLong(0), address, body, c.getLong(3), c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "SMS query failed", e)
                return@execute
            }
            messages = list
        }
    }

    fun senderName(m: MessageEntry): String = contacts.nameForNumber(m.address) ?: m.address

    /**
     * Messages whose text contains the query (3+ letters), or whose sender's name starts with it.
     * Newest first. Returns pairs of (message, index of the match in its body or -1).
     */
    fun search(rawQuery: String, limit: Int, cancelled: () -> Boolean): List<Pair<MessageEntry, Int>> {
        val q = FuzzyMatcher.normalize(rawQuery)
        if (q.length < 3) return emptyList()
        val out = ArrayList<Pair<MessageEntry, Int>>()
        for ((i, m) in messages.withIndex()) {
            if (i and 1023 == 0 && cancelled()) return emptyList()
            val idx = m.folded.indexOf(q)
            if (idx >= 0) {
                out += m to idx
            } else {
                val name = contacts.nameForNumber(m.address)
                if (name != null && FileMatcher.fold(name).let { it.startsWith(q) || it.contains(" $q") }) out += m to -1
            }
            if (out.size >= limit) break
        }
        return out
    }

    private companion object {
        const val TAG = "Messages"
        const val MAX_MESSAGES = 5000
    }
}
