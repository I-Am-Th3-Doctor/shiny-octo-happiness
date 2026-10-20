package io.github.iamth3doctor.searchlight.search

import android.os.Handler
import android.os.Looper
import io.github.iamth3doctor.searchlight.files.FileSearch
import io.github.iamth3doctor.searchlight.media.MediaRepository
import io.github.iamth3doctor.searchlight.messages.MessageRepository
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The part of search too big for the main thread: songs, photos, messages and the file index.
 * One worker thread; a newer query cancels an older one mid-scan, so typing never queues up work.
 */
class BackgroundSearch(
    private val files: FileSearch,
    private val media: MediaRepository,
    private val messages: MessageRepository,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()

    fun search(query: String, onResult: (List<SearchResult>) -> Unit) {
        val gen = generation.incrementAndGet()
        val index = files.index
        val photosFromMedia = media.searchesPhotos()
        worker.execute {
            val cancelled = { generation.get() != gen }
            if (cancelled()) return@execute
            val out = ArrayList<SearchResult>()

            val songs = media.searchSongs(query, MAX_SONGS)
            songs.forEach { out += SearchResult.Song(it) }
            if (photosFromMedia) media.searchPhotos(query, MAX_PHOTOS).forEach { out += SearchResult.Photo(it) }
            messages.search(query, MAX_MESSAGES, cancelled).forEach { (m, idx) ->
                out += SearchResult.Message(m, messages.senderName(m), idx)
            }
            if (cancelled()) return@execute

            // Songs already listed by title shouldn't show again as files.
            val songPaths = songs.mapNotNullTo(HashSet()) { it.path?.let { p -> File(p).absolutePath } }
            index.search(query, MAX_FILES + songPaths.size, System.currentTimeMillis(), cancelled)
                .asSequence()
                .filter { index.pathOf(it) !in songPaths }
                .take(MAX_FILES)
                .forEach { out += SearchResult.File(index, it) }

            main.post { if (!cancelled()) onResult(out) }
        }
    }

    fun cancel() {
        generation.incrementAndGet()
    }

    private companion object {
        const val MAX_SONGS = 4
        const val MAX_PHOTOS = 8
        const val MAX_MESSAGES = 4
        const val MAX_FILES = 20
    }
}
