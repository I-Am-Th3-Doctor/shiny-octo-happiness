package io.github.iamth3doctor.searchlight.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import android.util.LruCache
import io.github.iamth3doctor.searchlight.search.MatchKey
import java.util.concurrent.Executors

class ContactEntry(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val number: String?,
    val photoUri: String?,
    val starred: Boolean,
) {
    val key = "contact/$lookupKey"
    val matchKey = MatchKey(name)
    /** Digits only, for "555 12" style number searches. */
    val digits: String = number?.filter { it.isDigit() }.orEmpty()
    val lookupUri: Uri get() = ContactsContract.Contacts.getLookupUri(id, lookupKey)
}

/**
 * Keeps every contact (name, best phone number, photo) in memory so name search is as instant
 * as app search. Reloads in the background when the contacts database changes.
 * Public methods: main thread only.
 */
class ContactRepository(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val photoCache = LruCache<String, Bitmap>(100)
    private val pendingPhotos = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val listeners = mutableListOf<() -> Unit>()
    private var observing = false

    var contacts: List<ContactEntry> = emptyList()
        private set

    /** Last 7 digits of every number we know -> contact name. Used to label message senders. */
    @Volatile var namesByNumber: Map<String, String> = emptyMap()
        private set

    fun nameForNumber(address: String): String? {
        val d = address.filter { it.isDigit() }
        if (d.length < 5) return null
        return namesByNumber[d.takeLast(7)]
    }

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Safe to call repeatedly (e.g. on every resume): loads once permission appears. */
    fun start() {
        if (!hasPermission()) {
            if (contacts.isNotEmpty()) setContacts(emptyList())
            return
        }
        if (!observing) {
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            observing = true
            reload()
        }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private val reloadRunnable = Runnable { reload() }

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(reloadRunnable)
            main.postDelayed(reloadRunnable, 2000)
        }
    }

    private fun reload() {
        loader.execute {
            val (list, names) = try {
                query()
            } catch (e: Exception) {
                Log.w(TAG, "Contacts query failed", e)
                return@execute
            }
            main.post {
                namesByNumber = names
                setContacts(list)
            }
        }
    }

    private fun setContacts(list: List<ContactEntry>) {
        contacts = list
        listeners.toList().forEach { it() }
    }

    private fun query(): Pair<List<ContactEntry>, Map<String, String>> {
        val resolver = context.contentResolver

        // Best number per contact: the primary one if set, else the first.
        val numbers = HashMap<Long, String>()
        val allNumbers = HashMap<Long, MutableList<String>>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(1) ?: continue
                if (c.getInt(2) != 0 || id !in numbers) numbers[id] = number
                allNumbers.getOrPut(id) { mutableListOf() } += number
            }
        }

        val result = ArrayList<ContactEntry>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ContactsContract.Contacts.STARRED,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(2)?.trim().orEmpty()
                val lookup = c.getString(1) ?: continue
                if (name.isEmpty()) continue
                val id = c.getLong(0)
                result += ContactEntry(id, lookup, name, numbers[id], c.getString(3), c.getInt(4) != 0)
            }
        }
        val names = HashMap<String, String>()
        for (c in result) {
            allNumbers[c.id]?.forEach { n ->
                val d = n.filter { it.isDigit() }
                if (d.length >= 5) names[d.takeLast(7)] = c.name
            }
        }
        return result to names
    }

    /** Same contract as AppRepository.icon: cached bitmap now, or null + callback later. */
    fun photo(entry: ContactEntry, onReady: (Bitmap?) -> Unit): Bitmap? {
        val uri = entry.photoUri ?: return null
        photoCache.get(uri)?.let { return it }
        pendingPhotos[uri]?.let { it += onReady; return null }
        pendingPhotos[uri] = mutableListOf(onReady)
        loader.execute {
            val bmp = try {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (bmp != null) photoCache.put(uri, bmp)
                pendingPhotos.remove(uri)?.forEach { it(bmp) }
            }
        }
        return null
    }

    private companion object {
        const val TAG = "ContactRepository"
    }
}
