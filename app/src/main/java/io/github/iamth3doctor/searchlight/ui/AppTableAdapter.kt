package io.github.iamth3doctor.searchlight.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.databinding.ItemAppCellBinding

/** Every app, A–Z, in the same cells as the search results' app grid. */
class AppTableAdapter(
    private val repo: AppRepository,
    private val onLaunch: (AppEntry, View) -> Unit,
) : RecyclerView.Adapter<AppTableAdapter.Holder>() {

    private var apps: List<AppEntry> = emptyList()

    @SuppressLint("NotifyDataSetChanged") // the whole list is replaced
    fun submit(list: List<AppEntry>) {
        apps = list
        notifyDataSetChanged()
    }

    inner class Holder(val b: ItemAppCellBinding) : RecyclerView.ViewHolder(b.root) {
        var entry: AppEntry? = null
    }

    override fun getItemCount() = apps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAppCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)).also { h ->
            h.b.root.setOnClickListener { v -> h.entry?.let { onLaunch(it, v) } }
        }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val entry = apps[position]
        h.entry = entry
        h.b.label.text = entry.label
        h.b.root.contentDescription = entry.label
        h.b.icon.setImageBitmap(repo.icon(entry) { bmp -> if (h.entry === entry) h.b.icon.setImageBitmap(bmp) })
    }
}
