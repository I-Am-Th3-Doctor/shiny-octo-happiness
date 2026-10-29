package io.github.iamth3doctor.searchlight.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.data.Prefs

/**
 * The unfolded rail's favorites: the same six as the long-press popup, stacked vertically, one
 * tap each. Empty slots show "+" and explain how to add one, like the popup.
 */
class FavoritesRail(
    private val activity: Activity,
    private val repo: AppRepository,
    private val prefs: Prefs,
    private val container: LinearLayout,
    private val onLaunch: (AppEntry, View) -> Unit,
    /** The last flag: the press was on the predicted (6th) slot, so it can be pinned or unpinned. */
    private val onLongPress: (AppEntry, View, Boolean) -> Unit,
) {
    fun render() {
        container.removeAllViews()
        val favorites = homeFavorites(prefs, repo)
        val emptyHint = if (prefs.autoFavoritesActive) R.string.favorites_auto_empty_hint else R.string.favorites_empty_hint
        val cell = activity.px(R.dimen.size_rail_cell)
        val icon = activity.px(R.dimen.size_rail_icon)
        for (i in 0 until Prefs.MAX_FAVORITES) {
            val fav = favorites.getOrNull(i)
            val image = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val slot = FrameLayout(activity).apply {
                setBackgroundResource(resolveBorderless())
                addView(image, FrameLayout.LayoutParams(icon, icon).apply { gravity = Gravity.CENTER })
            }
            if (fav != null) {
                val entry = fav.entry
                image.setImageBitmap(repo.icon(entry) { image.setImageBitmap(it) })
                // The predicted slot can change: a small mark says so.
                if (fav.predicted) image.foreground = activity.getDrawable(R.drawable.fg_predicted)
                slot.contentDescription =
                    if (fav.predicted) activity.getString(R.string.favorite_predicted, entry.label) else entry.label
                slot.tooltipText = slot.contentDescription
                slot.setOnClickListener { onLaunch(entry, it) }
                slot.setOnLongClickListener {
                    onLongPress(entry, it, isPredictedSlot(prefs, i))
                    true
                }
            } else {
                image.setImageResource(R.drawable.ic_add)
                image.setBackgroundResource(R.drawable.bg_empty_slot)
                image.alpha = 0.6f
                slot.contentDescription = activity.getString(emptyHint)
                slot.setOnClickListener { Toast.makeText(activity, emptyHint, Toast.LENGTH_SHORT).show() }
            }
            container.addView(slot, LinearLayout.LayoutParams(cell, cell))
        }
    }

    private fun resolveBorderless(): Int {
        val a = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        try {
            return a.getResourceId(0, 0)
        } finally {
            a.recycle()
        }
    }

    companion object {
        /** Width the rail and its edge gap take from the screen's right side (for laying out around it). */
        fun reservedWidth(activity: Activity) =
            activity.px(R.dimen.size_rail) + activity.px(R.dimen.inset_home_card)
    }
}
