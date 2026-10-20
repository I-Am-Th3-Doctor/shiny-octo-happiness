package io.github.iamth3doctor.searchlight.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.contacts.ContactRepository
import io.github.iamth3doctor.searchlight.databinding.ItemAppCellBinding
import io.github.iamth3doctor.searchlight.databinding.ItemAppGridBinding
import io.github.iamth3doctor.searchlight.databinding.ItemResultBinding
import io.github.iamth3doctor.searchlight.databinding.ItemSectionHeaderBinding
import io.github.iamth3doctor.searchlight.media.Thumbnails
import io.github.iamth3doctor.searchlight.search.ResultLayout
import io.github.iamth3doctor.searchlight.search.SearchResult
import io.github.iamth3doctor.searchlight.search.Section
import io.github.iamth3doctor.searchlight.util.Snippet

/**
 * Search results: apps as an icon grid block on top (2 rows visible, scrolls for more), then
 * everything else as list rows under small section headers (see [ResultLayout]). One selection runs through grid and rows; Enter opens it.
 * Every list row uses one layout; what differs is the leading badge and the trailing buttons.
 */
class ResultsAdapter(
    private val apps: AppRepository,
    private val contacts: ContactRepository,
    private val thumbnails: Thumbnails,
    private val callbacks: Callbacks,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Callbacks {
        fun onClick(result: SearchResult, view: View)
        fun onLongClick(result: SearchResult, view: View)
        /** [which] is 1 for the first trailing button (call), 2 for the second (message). */
        fun onAction(result: SearchResult, which: Int, view: View)
    }

    private var layout = ResultLayout<SearchResult>(emptyList(), ::sectionOf)

    /** Grid columns: 4 folded, 6 unfolded (set by the screen from the available width). */
    var columns = 4
        set(value) {
            if (field == value) return
            field = value
            val pos = layout.rows.indexOfFirst { it is ResultLayout.Row.AppGrid }
            if (pos >= 0) notifyItemChanged(pos)
        }

    /** Selection index (into [ResultLayout.selectable]); -1 when there are no results. */
    var selected = -1
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<SearchResult>) {
        layout = ResultLayout(list, ::sectionOf)
        selected = if (layout.selectable.isEmpty()) -1 else 0
        notifyDataSetChanged()
    }

    /** What Enter opens: the highlighted result. */
    fun selectedResult(): SearchResult? = layout.selectable.getOrNull(selected)

    fun selectedPosition(): Int = if (selected < 0) -1 else layout.positionOf(selected)

    /** Moves the highlight; returns false if it couldn't move that way. */
    fun move(move: ResultLayout.Move, columns: Int): Boolean {
        val next = layout.move(selected, move, columns)
        if (next < 0 || next == selected) return false
        val old = selectedPosition()
        selected = next
        if (old >= 0) notifyItemChanged(old, SELECTION)
        notifyItemChanged(selectedPosition(), SELECTION)
        return true
    }

    override fun getItemCount() = layout.rows.size

    override fun getItemViewType(position: Int) = when (layout.rows[position]) {
        is ResultLayout.Row.Header -> TYPE_HEADER
        is ResultLayout.Row.AppGrid -> TYPE_GRID
        is ResultLayout.Row.Line -> TYPE_LINE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
            TYPE_GRID -> GridHolder(ItemAppGridBinding.inflate(inflater, parent, false)).also { h ->
                h.b.appGrid.layoutManager = GridLayoutManager(parent.context, columns)
                h.b.appGrid.adapter = h.cells
                h.b.appGrid.itemAnimator = null
            }
            else -> Holder(ItemResultBinding.inflate(inflater, parent, false)).also { h ->
                h.b.root.setOnClickListener { v -> h.result?.let { callbacks.onClick(it, v) } }
                h.b.root.setOnLongClickListener { v -> h.result?.let { callbacks.onLongClick(it, v) }; true }
                h.b.action1.setOnClickListener { v -> h.result?.let { callbacks.onAction(it, 1, v) } }
                h.b.action2.setOnClickListener { v -> h.result?.let { callbacks.onAction(it, 2, v) } }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = layout.rows[position]) {
            is ResultLayout.Row.Header -> (holder as HeaderHolder).b.header.setText(headerText(row.section))
            is ResultLayout.Row.AppGrid -> bindGrid(holder as GridHolder, row.items)
            is ResultLayout.Row.Line -> bindLine(holder as Holder, row.item, row.index == selected)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        // Selection moved inside the app grid: re-highlight, no rebind.
        if (payloads.contains(SELECTION) && holder is GridHolder) {
            holder.cells.setSelected(selected)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun bindGrid(h: GridHolder, items: List<SearchResult>) {
        (h.b.appGrid.layoutManager as GridLayoutManager).spanCount = columns
        h.cells.submit(items, selected)
    }

    /** The tiles inside the app grid block. */
    inner class CellsAdapter : RecyclerView.Adapter<CellHolder>() {
        private var items: List<SearchResult> = emptyList()
        private var selectedIndex = -1

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<SearchResult>, selected: Int) {
            items = list
            selectedIndex = selected
            notifyDataSetChanged()
        }

        fun setSelected(index: Int) {
            val old = selectedIndex
            selectedIndex = index
            if (old in items.indices) notifyItemChanged(old)
            if (index in items.indices) notifyItemChanged(index)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CellHolder(ItemAppCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)).also { h ->
                h.b.root.setOnClickListener { v -> h.result?.let { callbacks.onClick(it, v) } }
                h.b.root.setOnLongClickListener { v -> h.result?.let { callbacks.onLongClick(it, v) }; true }
            }

        override fun onBindViewHolder(h: CellHolder, position: Int) = bindCell(h, items[position], position == selectedIndex)
    }

    private fun bindCell(h: CellHolder, r: SearchResult, activated: Boolean) {
        h.result = r
        h.b.root.isActivated = activated
        val entry = (r as SearchResult.App).entry
        h.b.label.text = entry.label
        h.b.root.contentDescription = entry.label
        h.b.icon.setImageBitmap(apps.icon(entry) { bmp -> if (h.result === r) h.b.icon.setImageBitmap(bmp) })
    }

    private fun bindLine(h: Holder, r: SearchResult, activated: Boolean) {
        h.result = r
        val b = h.b
        val ctx = b.root.context
        b.root.isActivated = activated
        b.icon.setImageDrawable(null)
        b.icon.imageTintList = null
        b.icon.clipToOutline = false
        b.icon.background = null
        b.badge.isVisible = false
        b.subtitle.isVisible = false
        b.action1.isVisible = false
        b.action2.isVisible = false
        b.title.type(Type.BODY)

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

    class CellHolder(val b: ItemAppCellBinding) : RecyclerView.ViewHolder(b.root) {
        var result: SearchResult? = null
    }

    inner class GridHolder(val b: ItemAppGridBinding) : RecyclerView.ViewHolder(b.root) {
        val cells = CellsAdapter()
    }

    class HeaderHolder(val b: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(b.root)

    private fun headerText(section: Section) = when (section) {
        Section.APPS -> R.string.section_apps
        Section.ANSWER -> R.string.section_answer
        Section.CONTACTS -> R.string.section_contacts
        Section.MUSIC -> R.string.section_music
        Section.PHOTOS -> R.string.section_photos
        Section.MESSAGES -> R.string.section_messages
        Section.FILES -> R.string.section_files
        Section.ACTIONS -> R.string.section_actions
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_GRID = 1
        const val TYPE_LINE = 2
        /** Payload for "only the highlight moved". */
        val SELECTION = Any()

        fun sectionOf(r: SearchResult): Section = when (r) {
            is SearchResult.App -> Section.APPS
            is SearchResult.Calc -> Section.ANSWER
            is SearchResult.Contact -> Section.CONTACTS
            is SearchResult.Song -> Section.MUSIC
            is SearchResult.Photo -> Section.PHOTOS
            is SearchResult.Message -> Section.MESSAGES
            is SearchResult.File -> Section.FILES
            is SearchResult.Web, SearchResult.AccessHint -> Section.ACTIONS
        }

        val AVATAR_COLORS = intArrayOf(
            R.color.avatar_1, R.color.avatar_2, R.color.avatar_3, R.color.avatar_4,
            R.color.avatar_5, R.color.avatar_6, R.color.avatar_7, R.color.avatar_8,
        )
    }
}
