package io.github.iamth3doctor.searchlight.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import io.github.iamth3doctor.searchlight.files.FileMatcher
import io.github.iamth3doctor.searchlight.search.FuzzyMatcher
import io.github.iamth3doctor.searchlight.search.MatchKey
import java.util.concurrent.Executors

class SongEntry(val id: Long, val title: String, val artist: String?, val album: String?, val path: String?) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val titleKey = MatchKey(title)
    val artistKey = artist?.let(::MatchKey)
    val albumKey = album?.let(::MatchKey)
}

class PhotoEntry(val id: Long, val name: String, val bucket: String?, val date: Long, val isVideo: Boolean, val mime: String?) {
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id,
        )
    val foldedName = FileMatcher.fold(name)
    val foldedBucket = bucket?.let(FileMatcher::fold)
}

/**
 * Music (by title, artist, album) and photos & videos (by name or album) from MediaStore.
 *
 * Photos & videos are only searched here when All Files Access is OFF; with it on, the file
 * index already covers them (with thumbnails), and searching both would show duplicates.
 */
class MediaRepository(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var observing = false
    private var loadedFor: Pair<Boolean, Boolean>? = null

    @Volatile var songs: List<SongEntry> = emptyList()
        private set
    @Volatile var photos: List<PhotoEntry> = emptyList()
        private set

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun allFiles() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun hasMusicPermission() = allFiles() ||
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_AUDIO) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasPhotosPermission() = allFiles() ||
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** Whether photo results should come from here (rather than from the file index). */
    fun searchesPhotos() = hasPhotosPermission() && !allFiles()

    fun start() {
        if (!hasMusicPermission() && !hasPhotosPermission()) {
            songs = emptyList()
            photos = emptyList()
            loadedFor = null
            return
        }
        val wanted = hasMusicPermission() to searchesPhotos()
        if (wanted == loadedFor) return
        loadedFor = wanted
        if (!observing) {
            observing = true
            val r = context.contentResolver
            r.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            r.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            r.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        }
        reload()
    }

    private val reloadRunnable = Runnable { reload() }

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(reloadRunnable)
            main.postDelayed(reloadRunnable, 10_000)
        }
    }

    private fun reload() {
        val music = hasMusicPermission()
        val visual = searchesPhotos()
        loader.execute {
            try {
                songs = if (music) querySongs() else emptyList()
                photos = if (visual) queryVisual() else emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore query failed", e)
            }
        }
    }

    @Suppress("DEPRECATION") // DATA is deprecated but still filled in; we only use it to de-duplicate file results.
    private fun querySongs(): List<SongEntry> {
        val out = ArrayList<SongEntry>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DATA),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                out += SongEntry(c.getLong(0), title, c.getString(2)?.takeIf { it != "<unknown>" }, c.getString(3), c.getString(4))
            }
        }
        return out
    }

    private fun queryVisual(): List<PhotoEntry> {
        val out = ArrayList<PhotoEntry>()
        for (video in listOf(false, true)) {
            val uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE),
                null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    out += PhotoEntry(c.getLong(0), name, c.getString(2), c.getLong(3) * 1000, video, c.getString(4))
                }
            }
        }
        out.sortByDescending { it.date }
        return out
    }

    /** Title beats artist beats album. */
    fun searchSongs(rawQuery: String, limit: Int): List<SongEntry> {
        val q = FuzzyMatcher.normalize(rawQuery)
        if (q.length < 2) return emptyList()
        return songs.asSequence()
            .map { s ->
                val score = maxOf(
                    FuzzyMatcher.score(q, s.titleKey),
                    s.artistKey?.let { FuzzyMatcher.score(q, it) - 40 } ?: 0,
                    s.albumKey?.let { FuzzyMatcher.score(q, it) - 60 } ?: 0,
                )
                s to score
            }
            .filter { it.second >= 500 } // no loose subsequence/typo hits across thousands of songs
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** Name or album ("Screenshots", "WhatsApp Images") contains the query. Newest first. */
    fun searchPhotos(rawQuery: String, limit: Int): List<PhotoEntry> {
        val q = FuzzyMatcher.normalize(rawQuery)
        if (q.length < 2) return emptyList()
        return photos.asSequence()
            .filter { it.foldedName.contains(q) || it.foldedBucket?.contains(q) == true }
            .take(limit)
            .toList()
    }

    private companion object {
        const val TAG = "Media"
    }
}
