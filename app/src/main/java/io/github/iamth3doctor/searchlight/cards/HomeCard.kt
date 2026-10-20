package io.github.iamth3doctor.searchlight.cards

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

class CardsAdapter(private val cards: List<HomeCard>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = cards.size
    // One view type per card: each page keeps its own long-lived view.
    override fun getItemViewType(position: Int) = position
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        object : RecyclerView.ViewHolder(cards[viewType].viewFor(parent)) {}
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
}
