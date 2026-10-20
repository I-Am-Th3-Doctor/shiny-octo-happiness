package io.github.iamth3doctor.searchlight.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background index of every file in shared storage (needs All Files Access).
 *
 * - The last index is loaded from disk at startup, so file results work right after a cold start.
 * - A fresh scan runs at startup, when it's more than [STALE_MS] old on resume, and (debounced)
 *   whenever MediaStore reports changes.
 * - Searches run on their own thread; a newer query cancels an older one mid-scan.
 *
 * Public methods: main thread only.
 */
class FileSearch(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val indexer = Executors.newSingleThreadExecutor()
    private val searcher = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val cacheFile = File(context.filesDir, "files.index")
    private val listeners = mutableListOf<() -> Unit>()

    @Volatile var index: FileIndex = FileIndex.EMPTY
        private set

    /** Non-null while a scan is running: entries seen so far. */
    @Volatile var scanning: Int? = null
        private set

    private var loadedCache = false
    private var observing = false
    private var scanQueued = false

    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun notifyListeners() = listeners.toList().forEach { it() }

    /** Call on app start and on every resume; cheap when nothing needs doing. */
    fun start() {
        if (!hasPermission()) {
            if (index !== FileIndex.EMPTY) {
                index = FileIndex.EMPTY
                notifyListeners()
            }
            return
        }
        if (!loadedCache) {
            loadedCache = true
            indexer.execute {
                val cached = FileIndex.read(cacheFile)
                if (cached != null) main.post { if (index === FileIndex.EMPTY) { index = cached; notifyListeners() } }
            }
        }
        if (!observing) {
            observing = true
            context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
        }
        if (System.currentTimeMillis() - index.builtAt > STALE_MS) rescan()
    }

    private val rescanRunnable = Runnable { rescan() }

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(rescanRunnable)
            main.postDelayed(rescanRunnable, 30_000)
        }
    }

    fun rescan() {
        if (scanQueued || !hasPermission()) return
        scanQueued = true
        scanning = 0
        notifyListeners()
        indexer.execute {
            val root = Environment.getExternalStorageDirectory()
            val fresh = try {
                FileIndex.scan(root, System.currentTimeMillis(), progress = { n ->
                    scanning = n
                    main.post(::notifyListeners)
                })
            } catch (e: Exception) {
                Log.w(TAG, "File scan failed", e)
                null
            }
            if (fresh != null) {
                try {
                    fresh.write(cacheFile)
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't save file index", e)
                }
            }
            main.post {
                scanQueued = false
                scanning = null
                if (fresh != null) index = fresh
                notifyListeners()
            }
        }
    }

    /** Runs [rawQuery] off the main thread; [onResult] is called on the main thread unless superseded. */
    fun search(rawQuery: String, limit: Int, onResult: (FileIndex, List<FileEntry>) -> Unit) {
        val gen = generation.incrementAndGet()
        val snapshot = index
        if (snapshot.entries.isEmpty()) return
        searcher.execute {
            if (generation.get() != gen) return@execute
            val hits = snapshot.search(rawQuery, limit, System.currentTimeMillis()) { generation.get() != gen }
            main.post { if (generation.get() == gen) onResult(snapshot, hits) }
        }
    }

    /** Drops any in-flight search (e.g. the query was cleared). */
    fun cancel() {
        generation.incrementAndGet()
    }

    private companion object {
        const val TAG = "FileSearch"
        const val STALE_MS = 15 * 60 * 1000L
    }
}
