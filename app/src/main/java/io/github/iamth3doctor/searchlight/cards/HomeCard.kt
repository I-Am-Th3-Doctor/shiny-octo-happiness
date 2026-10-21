package io.github.iamth3doctor.searchlight.cards

import android.app.Activity
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DimenRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import io.github.iamth3doctor.searchlight.R

/** The built-in glance cards, in default order. Toggled and ordered in the Widget Center. */
enum class CardType(@StringRes val title: Int, @StringRes val description: Int) {
    WEATHER(R.string.card_weather, R.string.card_weather_desc),
    CALENDAR(R.string.card_calendar, R.string.card_calendar_desc),
    BATTERY(R.string.card_battery, R.string.card_battery_desc),
    MUSIC(R.string.card_music, R.string.card_music_desc),
    QUICK_ACTIONS(R.string.card_quick_actions, R.string.card_quick_actions_desc),
}

/**
 * One page of the card pager. Each card owns its view (created once) and only listens for
 * updates between [onStart] and [onStop], i.e. while the home screen is visible.
 */
abstract class HomeCard(val type: CardType, protected val activity: Activity) {

    private var view: View? = null
    protected var started = false
        private set

    @get:LayoutRes protected abstract val layout: Int

    /** Narrowest this tile can be when tiles sit side by side (unfolded): room for its buttons. */
    @get:DimenRes open val minRowWidth: Int = 0

    protected abstract fun bind(root: View)

    /** Draw current state into the views. Only ever called once [bind] has run. */
    protected abstract fun draw()

    /**
     * Safe to call any time (callbacks often arrive before the pager has created the page):
     * does nothing until the view exists.
     */
    fun render() {
        if (view != null) draw()
    }

    private fun ensureView(parent: ViewGroup): View = view ?: LayoutInflater.from(activity).inflate(layout, parent, false).also {
        view = it
        bind(it)
        draw()
    }

    private var sidePadding: Pair<Int, Int>? = null

    /**
     * Unfolded, tiles sit side by side: tighter side padding, and [onRowChanged] lets a tile
     * slim down (Music drops prev/next). Folded restores the original padding.
     */
    fun setInRow(inRow: Boolean) {
        val card = view?.findViewById<View>(R.id.card) ?: return
        val (start, end) = sidePadding ?: (card.paddingStart to card.paddingEnd).also { sidePadding = it }
        val p = activity.resources.getDimensionPixelSize(R.dimen.space_s)
        card.setPaddingRelative(if (inRow) minOf(p, start) else start, card.paddingTop, if (inRow) minOf(p, end) else end, card.paddingBottom)
        onRowChanged(inRow)
    }

    protected open fun onRowChanged(inRow: Boolean) = Unit

    fun viewFor(parent: ViewGroup): View {
        val v = ensureView(parent)
        (v.parent as? ViewGroup)?.removeView(v)
        return v
    }

    open fun onStart() {
        started = true
        render()
    }

    open fun onStop() {
        started = false
    }
}

/** Non-blank [parts] joined with " · ", keeping their spans: one line of tile text. */
fun joinedLine(vararg parts: CharSequence): CharSequence = SpannableStringBuilder().apply {
    parts.filter { it.isNotBlank() }.forEachIndexed { i, part ->
        if (i > 0) append(" · ")
        append(part)
    }
}

class CardsAdapter(private val cards: List<HomeCard>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = cards.size
    // One view type per card: each page keeps its own long-lived view.
    override fun getItemViewType(position: Int) = position
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        object : RecyclerView.ViewHolder(cards[viewType].viewFor(parent)) {}
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
}
