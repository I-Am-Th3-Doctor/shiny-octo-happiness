package io.github.iamth3doctor.searchlight.media

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Small thumbnails for photo, video and song results, rendered off the main thread. */
class Thumbnails(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val cache = LruCache<String, Bitmap>(120)
    private val missing = HashSet<String>()
    private val pending = HashMap<String, MutableList<(Bitmap) -> Unit>>()
    private val size = (48 * context.resources.displayMetrics.density).roundToInt().let { Size(it, it) }

    fun forFile(path: String, isVideo: Boolean, onReady: (Bitmap) -> Unit): Bitmap? = get("f:$path", onReady) {
        if (isVideo) ThumbnailUtils.createVideoThumbnail(File(path), size, null)
        else ThumbnailUtils.createImageThumbnail(File(path), size, null)
    }

    fun forUri(uri: Uri, onReady: (Bitmap) -> Unit): Bitmap? = get("u:$uri", onReady) {
        context.contentResolver.loadThumbnail(uri, size, null)
    }

    private fun get(key: String, onReady: (Bitmap) -> Unit, load: () -> Bitmap): Bitmap? {
        cache.get(key)?.let { return it }
        if (key in missing) return null
        pending[key]?.let { it += onReady; return null }
        pending[key] = mutableListOf(onReady)
        executor.execute {
            val bmp = try { load() } catch (e: Exception) { null }
            main.post {
                val waiting = pending.remove(key)
                if (bmp == null) {
                    missing += key
                } else {
                    cache.put(key, bmp)
                    waiting?.forEach { it(bmp) }
                }
            }
        }
        return null
    }

    companion object {
        private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng")
        private val VIDEO = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v")

        /** null = not a visual file; true = video; false = image. */
        fun visualKind(name: String): Boolean? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                in IMAGE -> false
                in VIDEO -> true
                else -> null
            }
        }
    }
}
