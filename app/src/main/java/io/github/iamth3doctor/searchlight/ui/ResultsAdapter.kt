package io.github.iamth3doctor.searchlight.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.contacts.ContactRepository
import io.github.iamth3doctor.searchlight.databinding.ItemResultBinding
import io.github.iamth3doctor.searchlight.media.Thumbnails
import io.github.iamth3doctor.searchlight.search.SearchResult
import io.github.iamth3doctor.searchlight.util.Snippet

/** One row layout for every result type; what differs is the leading badge and the trailing buttons. */
class ResultsAdapter(
    private val apps: AppRepository,
    private val contacts: ContactRepository,
    private val thumbnails: Thumbnails,
    private val callbacks: Callbacks,
) : RecyclerView.Adapter<ResultsAdapter.Holder>() {

    interface Callbacks {
        fun onClick(result: SearchResult, view: View)
        fun onLongClick(result: SearchResult, view: View)
        /** [which] is 1 for the first trailing button (call), 2 for the second (message). */
        fun onAction(result: SearchResult, which: Int, view: View)
    }

    private var items: List<SearchResult> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<SearchResult>) {
        items = list
        notifyDataSetChanged()
    }

    fun first(): SearchResult? = items.firstOrNull()

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val h = Holder(ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        h.b.root.setOnClickListener { v -> h.result?.let { callbacks.onClick(it, v) } }
        h.b.root.setOnLongClickListener { v -> h.result?.let { callbacks.onLongClick(it, v) }; true }
        h.b.action1.setOnClickListener { v -> h.result?.let { callbacks.onAction(it, 1, v) } }
        h.b.action2.setOnClickListener { v -> h.result?.let { callbacks.onAction(it, 2, v) } }
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val r = items[position]
        h.result = r
        val b = h.b
        val ctx = b.root.context
        b.root.isActivated = position == 0
        b.icon.setImageDrawable(null)
        b.icon.imageTintList = null
        b.icon.clipToOutline = false
        b.icon.background = null
        b.badge.isVisible = false
        b.subtitle.isVisible = false
        b.action1.isVisible = false
        b.action2.isVisible = false
        b.title.type(Type.BODY)
        b.subtitle.ellipsize = android.text.TextUtils.TruncateAt.START

        when (r) {
            is SearchResult.App -> {
                b.title.text = r.entry.label
                b.icon.setImageBitmap(apps.icon(r.entry) { bmp -> if (h.result === r) b.icon.setImageBitmap(bmp) })
            }
            is SearchResult.Contact -> {
                val c = r.entry
                b.title.text = c.name
                c.number?.let { subtitle(b, it) }
                showBadge(b, c.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#", ctx.color(avatarColor(c.name)))
                contacts.photo(c) { bmp -> if (h.result === r && bmp != null) showPhoto(b, bmp) }?.let { showPhoto(b, it) }
                if (c.number != null) {
                    b.action1.setImageResource(R.drawable.ic_call)
                    b.action1.contentDescription = ctx.getString(R.string.action_call)
                    b.action1.isVisible = true
                    b.action2.setImageResource(R.drawable.ic_message)
                    b.action2.contentDescription = ctx.getString(R.string.action_message)
                    b.action2.isVisible = true
                }
            }
            is SearchResult.File -> {
                val e = r.entry
                b.title.text = e.name
                val dir = r.index.relativeDirOf(e).ifEmpty { ctx.getString(R.string.storage_root) }
                val parts = mutableListOf(dir)
                if (!e.isDir) parts += Formatter.formatShortFileSize(ctx, e.size)
                parts += DateUtils.getRelativeTimeSpanString(e.modified, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
                subtitle(b, parts.joinToString(" · "))
                if (e.isDir) {
                    b.icon.setImageResource(R.drawable.ic_folder)
                    b.icon.imageTintList = ColorStateList.valueOf(ctx.color(R.color.cat_folder))
                } else {
                    val ext = e.name.substringAfterLast('.', "").take(4).uppercase()
                    showBadge(b, ext.ifEmpty { "FILE" }, ctx.color(categoryColor(ext)))
                    Thumbnails.visualKind(e.name)?.let { isVideo ->
                        thumbnails.forFile(r.index.pathOf(e), isVideo) { bmp -> if (h.result === r) showImage(b, bmp) }
                            ?.let { showImage(b, it) }
                    }
                }
            }
            is SearchResult.Song -> {
                val s = r.entry
                b.title.text = s.title
                subtitle(b, listOfNotNull(s.artist, s.album).joinToString(" · ").ifEmpty { ctx.getString(R.string.result_song) })
                showBadge(b, "♪", ctx.color(R.color.cat_audio))
                thumbnails.forUri(s.uri) { bmp -> if (h.result === r) showImage(b, bmp) }?.let { showImage(b, it) }
            }
            is SearchResult.Photo -> {
                val p = r.entry
                b.title.text = p.name
                val kind = ctx.getString(if (p.isVideo) R.string.result_video else R.string.result_photo)
                val date = DateUtils.getRelativeTimeSpanString(p.date, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
                subtitle(b, listOfNotNull(kind, p.bucket, date.toString()).joinToString(" · "))
                showBadge(b, if (p.isVideo) "VID" else "IMG", ctx.color(if (p.isVideo) R.color.cat_video else R.color.cat_image))
                thumbnails.forUri(p.uri) { bmp -> if (h.result === r) showImage(b, bmp) }?.let { showImage(b, it) }
            }
            is SearchResult.Message -> {
                val m = r.entry
                b.title.text = r.sender
                val when_ = DateUtils.getRelativeTimeSpanString(m.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                subtitle(b, "$when_ · " + Snippet.around(m.body, r.matchIndex, 6))
                b.subtitle.ellipsize = android.text.TextUtils.TruncateAt.END
                showBadge(b, "💬", ctx.color(R.color.cat_message))
            }
            is SearchResult.Calc -> {
                b.title.text = ctx.getString(R.string.calc_result, r.value)
                b.title.type(Type.TILE_TITLE)
                subtitle(b, ctx.getString(R.string.calc_subtitle, r.expression))
                showBadge(b, "=", ctx.color(R.color.accent))
            }
            is SearchResult.Web -> {
                b.title.text = ctx.getString(R.string.web_search_for, r.engine.label, r.query)
                b.icon.setImageResource(R.drawable.ic_search)
            }
            SearchResult.AccessHint -> {
                b.title.setText(R.string.access_hint_title)
                subtitle(b, ctx.getString(R.string.access_hint_subtitle))
                b.icon.setImageResource(R.drawable.ic_add)
            }
        }
    }

    private fun subtitle(b: ItemResultBinding, text: String) {
        b.subtitle.text = text
        b.subtitle.isVisible = true
    }

    private fun showBadge(b: ItemResultBinding, text: String, color: Int) {
        b.badge.text = text
        b.badge.type(if (text.length > 2) Type.CAPTION else Type.BODY)
        b.badge.backgroundTintList = ColorStateList.valueOf(color)
        b.badge.isVisible = true
    }

    private fun showPhoto(b: ItemResultBinding, bmp: android.graphics.Bitmap) {
        b.badge.isVisible = false
        b.icon.setBackgroundResource(R.drawable.bg_badge)
        b.icon.clipToOutline = true
        b.icon.setImageBitmap(bmp)
    }

    /** Album art / photo thumbnail: rounded square, replaces the badge. */
    private fun showImage(b: ItemResultBinding, bmp: android.graphics.Bitmap) {
        b.badge.isVisible = false
        b.icon.imageTintList = null
        b.icon.setBackgroundResource(R.drawable.bg_art)
        b.icon.clipToOutline = true
        b.icon.setImageBitmap(bmp)
    }

    private fun avatarColor(name: String): Int = AVATAR_COLORS[(name.hashCode() and 0x7fffffff) % AVATAR_COLORS.size]

    private fun categoryColor(ext: String): Int = when (ext) {
        "PDF" -> R.color.cat_pdf
        "JPG", "JPEG", "PNG", "GIF", "WEBP", "HEIC", "HEIF", "DNG" -> R.color.cat_image
        "MP4", "MKV", "MOV", "WEBM", "3GP", "AVI" -> R.color.cat_video
        "MP3", "M4A", "FLAC", "WAV", "OGG", "OPUS", "AAC" -> R.color.cat_audio
        "ZIP", "RAR", "7Z", "TAR", "GZ" -> R.color.cat_archive
        "DOC", "DOCX", "ODT", "RTF", "TXT", "MD" -> R.color.cat_document
        "XLS", "XLSX", "CSV", "ODS" -> R.color.cat_sheet
        "PPT", "PPTX", "ODP", "KEY" -> R.color.cat_slides
        "APK", "APKS", "XAPK" -> R.color.cat_apk
        else -> R.color.cat_other
    }

    class Holder(val b: ItemResultBinding) : RecyclerView.ViewHolder(b.root) {
        var result: SearchResult? = null
    }

    private companion object {
        val AVATAR_COLORS = intArrayOf(
            R.color.avatar_1, R.color.avatar_2, R.color.avatar_3, R.color.avatar_4,
            R.color.avatar_5, R.color.avatar_6, R.color.avatar_7, R.color.avatar_8,
        )
    }
}
