package io.github.iamth3doctor.searchlight.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.databinding.ItemAppBinding

class ResultsAdapter(
    private val repo: AppRepository,
    private val onClick: (AppEntry, View) -> Unit,
    private val onLongClick: (AppEntry, View) -> Unit,
) : RecyclerView.Adapter<ResultsAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()

    init {
        setHasStableIds(true)
    }

    /** Lists are tiny and replaced on every keystroke; a full rebind is the fastest option. */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<AppEntry>) {
        items = list
        notifyDataSetChanged()
    }

    fun first(): AppEntry? = items.firstOrNull()

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) = items[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val holder = Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        holder.b.root.setOnClickListener { v -> holder.entry?.let { onClick(it, v) } }
        holder.b.root.setOnLongClickListener { v -> holder.entry?.let { onLongClick(it, v) }; true }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        holder.entry = entry
        holder.b.label.text = entry.label
        holder.b.root.isActivated = position == 0
        holder.b.icon.setImageBitmap(repo.icon(entry) { bmp ->
            if (holder.entry?.key == entry.key) holder.b.icon.setImageBitmap(bmp)
        })
    }

    class Holder(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root) {
        var entry: AppEntry? = null
    }
}
