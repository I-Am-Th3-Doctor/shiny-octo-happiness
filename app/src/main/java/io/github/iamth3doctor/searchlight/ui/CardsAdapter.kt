package io.github.iamth3doctor.searchlight.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.iamth3doctor.searchlight.R

/**
 * Pages of the swipeable glance-card area above the search pill.
 *
 * Phase 1 ships two simple cards so the pager, dots and swipe can be tried on the device.
 * Phase 2 replaces this list with the Widget Center cards (weather first, then calendar,
 * battery, music, quick actions) and the idle-card picker.
 */
class CardsAdapter : RecyclerView.Adapter<CardsAdapter.Holder>() {

    private val layouts = intArrayOf(R.layout.card_today, R.layout.card_tips)

    override fun getItemCount() = layouts.size
    override fun getItemViewType(position: Int) = layouts[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(viewType, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = Unit

    class Holder(view: View) : RecyclerView.ViewHolder(view)
}
