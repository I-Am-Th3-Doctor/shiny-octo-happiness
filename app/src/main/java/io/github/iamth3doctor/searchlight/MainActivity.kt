package io.github.iamth3doctor.searchlight

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.cards.BatteryCard
import io.github.iamth3doctor.searchlight.cards.CalendarCard
import io.github.iamth3doctor.searchlight.cards.CardType
import io.github.iamth3doctor.searchlight.cards.CardsAdapter
import io.github.iamth3doctor.searchlight.cards.HomeCard
import io.github.iamth3doctor.searchlight.cards.MusicCard
import io.github.iamth3doctor.searchlight.cards.QuickActionsCard
import io.github.iamth3doctor.searchlight.cards.WeatherCard
import io.github.iamth3doctor.searchlight.databinding.ActivityMainBinding
import io.github.iamth3doctor.searchlight.search.ResultLayout
import io.github.iamth3doctor.searchlight.search.SearchEngine
import io.github.iamth3doctor.searchlight.search.SearchResult
import io.github.iamth3doctor.searchlight.ui.Actions
import io.github.iamth3doctor.searchlight.ui.FavoritesPopup
import io.github.iamth3doctor.searchlight.ui.ResultsAdapter
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.widgets.WidgetController
import java.util.Calendar
import kotlin.math.max

/**
 * The home screen: wallpaper and one frosted home card (tiles with page dots + the search row).
 * No dock. Typing folds the tiles away; results appear above the card: an app grid (2 rows
 * visible, scrolls for more), then contacts / files / actions as rows under section headers.
 */
class MainActivity : Activity(), ResultsAdapter.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: SearchEngine
    private lateinit var adapter: ResultsAdapter
    private lateinit var favoritesPopup: FavoritesPopup
    private lateinit var widgets: WidgetController

    private val app get() = searchlight
    private val prefs get() = app.prefs

    private var openMenu: PopupMenu? = null
    private var launchedSomething = false

    /** Results for the current query: [local] is instant, [slowResults] arrives a moment later. */
    private var local: List<SearchResult> = emptyList()
    private var slowResults: List<SearchResult> = emptyList()

    private var cards: List<HomeCard> = emptyList()
    private var cardsStarted = false

    private val sourcesChanged: () -> Unit = { refreshResults() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = SearchEngine(prefs, app.apps, app.contacts)
        adapter = ResultsAdapter(app.apps, app.contacts, app.thumbnails, this)
        favoritesPopup = FavoritesPopup(this, app.apps, prefs, ::launchApp)
        widgets = WidgetController(this, binding.widgetGrid, prefs) { editing -> binding.editBar.isVisible = editing }
        savedInstanceState?.getInt(KEY_PENDING_WIDGET)?.let { widgets.pendingId = it }
        widgets.cleanUpOrphans()

        setupResultsGrid()

        applyInsets()
        setupPager()
        rebuildCardsIfChanged()
        goToIdleCard()
        setupHomeCard()
        setupWidgetPage()

        app.apps.addListener(sourcesChanged)
        app.contacts.addListener(sourcesChanged)
        app.files.addListener(sourcesChanged)
    }

    override fun onDestroy() {
        app.apps.removeListener(sourcesChanged)
        app.contacts.removeListener(sourcesChanged)
        app.files.removeListener(sourcesChanged)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        rebuildCardsIfChanged()
        startCards()
        widgets.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PENDING_WIDGET, widgets.pendingId)
    }

    /** Widget picker → bind permission → the widget's configure screen all report back here. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been granted in Settings since last time.
        app.startSources()
        app.weather.refreshIfOlderThan(WEATHER_MAX_AGE_MS)
        cards.forEach { it.render() } // e.g. °F/°C changed in the Widget Center
        binding.searchInput.hint = greeting()
        if (prefs.autoOpenKeyboard) binding.searchInput.post { showKeyboard() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The IME can only be shown once the window has focus; onResume is sometimes too early.
        if (hasFocus && prefs.autoOpenKeyboard && openMenu == null) showKeyboard()
    }

    override fun onStop() {
        super.onStop()
        stopCards()
        widgets.stop()
        dismissPopups()
        // Coming back after opening something should start fresh.
        if (launchedSomething) {
            launchedSomething = false
            resetHome()
        }
    }

    /** Pressing Home (from another app, or on the home screen itself) lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            widgets.exitEditMode()
            dismissPopups()
            resetHome()
            if (prefs.autoOpenKeyboard) showKeyboard()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Fold/unfold: views re-measure themselves; popups are anchored to stale coordinates.
        dismissPopups()
    }

    @Deprecated("Launcher: Back never leaves home; it clears the search and drops the keyboard.")
    override fun onBackPressed() {
        when {
            widgets.editing -> widgets.exitEditMode()
            favoritesPopup.isShowing -> favoritesPopup.dismiss()
            else -> resetHome()
        }
    }

    // ------------------------------------------------------------------ setup

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            binding.widgetGrid.imeVisible = ime.bottom > 0
            WindowInsetsCompat.CONSUMED
        }
    }

    private val fitTiles = Runnable { fitTileHeight() }

    private fun scheduleTileFit() {
        binding.cards.removeCallbacks(fitTiles)
        binding.cards.post(fitTiles)
    }

    /**
     * The pager is as tall as its tallest tile needs at the current width (never below the
     * token height), so long text or a large font size wraps instead of being cut off.
     */
    private fun fitTileHeight() {
        val width = binding.cards.width
        if (width == 0 || cards.isEmpty()) return
        val needed = cards.maxOf { it.measureHeight(width, binding.cards) }
        val target = maxOf(px(R.dimen.size_tile_height), needed)
        if (binding.cards.layoutParams.height != target) {
            binding.cards.layoutParams = binding.cards.layoutParams.apply { height = target }
        }
    }

    private fun setupPager() {
        binding.cards.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) scheduleTileFit() }
        binding.cards.offscreenPageLimit = CardType.entries.size
        binding.cards.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateDots(position)
        })
    }

    /** Builds the card pages from the Widget Center settings; no-op if nothing changed. */
    private fun rebuildCardsIfChanged() {
        val types = prefs.enabledCards
        if (types == cards.map { it.type }) return
        val wasStarted = cardsStarted
        stopCards()
        cards = types.map { type ->
            when (type) {
                CardType.WEATHER -> WeatherCard(this)
                CardType.CALENDAR -> CalendarCard(this)
                CardType.BATTERY -> BatteryCard(this)
                CardType.MUSIC -> MusicCard(this).also { it.onStartedPlaying = ::onMusicStarted }
                CardType.QUICK_ACTIONS -> QuickActionsCard(this)
            }.also { it.onDrawn = ::scheduleTileFit }
        }
        binding.cards.adapter = CardsAdapter(cards)
        binding.dots.removeAllViews()
        val dotSize = px(R.dimen.size_dot)
        val dotGap = px(R.dimen.space_xs)
        repeat(cards.size) {
            binding.dots.addView(View(this).apply {
                setBackgroundResource(R.drawable.bg_dot)
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply { marginStart = dotGap; marginEnd = dotGap }
            })
        }
        binding.dots.isVisible = cards.size > 1
        if (wasStarted) startCards()
        scheduleTileFit()
        goToIdleCard()
        refreshResults()
    }

    private fun startCards() {
        if (cardsStarted) return
        cardsStarted = true
        cards.forEach { it.onStart() }
    }

    private fun stopCards() {
        if (!cardsStarted) return
        cardsStarted = false
        cards.forEach { it.onStop() }
    }

    private fun updateDots(position: Int) {
        for (i in 0 until binding.dots.childCount) binding.dots.getChildAt(i).alpha = if (i == position) 1f else 0.35f
    }

    /** Idle card from the Widget Center, unless music is playing and should be in front. */
    private fun goToIdleCard() {
        val music = cards.firstOrNull { it is MusicCard } as MusicCard?
        val target = if (prefs.musicAutoFront && music?.isPlaying == true) CardType.MUSIC else prefs.idleCard
        val index = cards.indexOfFirst { it.type == target }.coerceAtLeast(0)
        if (cards.isNotEmpty()) binding.cards.setCurrentItem(index, false)
        updateDots(index)
    }

    private fun onMusicStarted() {
        if (!prefs.musicAutoFront || query().isNotBlank()) return
        val index = cards.indexOfFirst { it.type == CardType.MUSIC }
        if (index >= 0) binding.cards.setCurrentItem(index, true)
    }

    private fun setupHomeCard() {
        // Tiles open their own app/view; anywhere else on the card means "I want to search".
        binding.homeCard.clipToOutline = true
        val focusSearch = View.OnClickListener { showKeyboard() }
        binding.homeCard.setOnClickListener(focusSearch)
        binding.searchPill.setOnClickListener(focusSearch)
        binding.dots.setOnClickListener(focusSearch)

        val input = binding.searchInput
        input.doAfterTextChanged {
            favoritesPopup.dismiss()
            onQueryChanged()
        }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) {
                adapter.selectedResult()?.let {
                    onClick(it, binding.results.findViewHolderForAdapterPosition(adapter.selectedPosition())?.itemView ?: input)
                }
            }
            enter
        }
        input.setOnKeyListener { _, keyCode, event -> event.action == KeyEvent.ACTION_DOWN && navigateResults(keyCode, event) }
        binding.clearButton.setOnClickListener { binding.searchInput.text = null }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.searchIcon.setOnClickListener { showKeyboard() }
        binding.searchIcon.setOnLongClickListener {
            favoritesPopup.show(binding.homeCard)
            true
        }
    }

    /** Edit mode (long-press a widget or empty space) shows a small bar: add, settings, done. */
    private fun setupWidgetPage() {
        binding.editAdd.setOnClickListener { widgets.beginAdd() }
        binding.editSettings.setOnClickListener {
            widgets.exitEditMode()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.editDone.setOnClickListener { widgets.exitEditMode() }
    }

    /**
     * Results: a vertical list whose first block is the app grid (4 columns folded, 6 unfolded,
     * 2 rows visible). Columns are recomputed whenever the width changes.
     */
    private fun setupResultsGrid() {
        adapter.columns = columnsFor(resources.displayMetrics.widthPixels)
        binding.results.layoutManager = LinearLayoutManager(this)
        binding.results.adapter = adapter
        binding.results.itemAnimator = null
        binding.results.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
            if (r - l != or_ - ol) adapter.columns = columnsFor(v.width)
        }
    }

    private fun columnsFor(widthPx: Int): Int =
        if (widthPx / resources.displayMetrics.density >= UNFOLDED_MIN_WIDTH_DP) 6 else 4

    /**
     * Moves the highlight through the results: ↑/↓ by row (grid rows, then list rows), Tab /
     * Shift+Tab one by one. ←/→ move across the grid only with a physical keyboard, so the
     * on-screen keyboard's cursor keys still move the text cursor.
     */
    private fun navigateResults(keyCode: Int, event: KeyEvent): Boolean {
        if (!binding.results.isVisible) return false
        val hardKeyboard = resources.configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        val move = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> ResultLayout.Move.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> ResultLayout.Move.DOWN
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) ResultLayout.Move.PREVIOUS else ResultLayout.Move.NEXT
            KeyEvent.KEYCODE_DPAD_LEFT -> if (hardKeyboard) ResultLayout.Move.LEFT else return false
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (hardKeyboard) ResultLayout.Move.RIGHT else return false
            else -> return false
        }
        if (adapter.move(move, adapter.columns)) binding.results.scrollToPosition(adapter.selectedPosition())
        return true
    }

    private fun greeting(): String {
        prefs.greeting.takeIf { it.isNotEmpty() }?.let { return it }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return getString(
            when (hour) {
                in 5..11 -> R.string.greeting_morning
                in 12..16 -> R.string.greeting_afternoon
                in 17..21 -> R.string.greeting_evening
                else -> R.string.greeting_night
            }
        )
    }

    // ------------------------------------------------------------------ search

    private fun query() = binding.searchInput.text?.toString().orEmpty()

    private fun onQueryChanged() {
        slowResults = emptyList()
        refreshResults()
    }

    /** Recomputes the instant part, (re)starts the file search, and redraws. */
    private fun refreshResults() {
        val q = query()
        val searching = q.isNotBlank()
        binding.clearButton.isVisible = q.isNotEmpty()
        binding.settingsButton.isVisible = q.isEmpty()
        binding.cardArea.isVisible = !searching && cards.isNotEmpty()
        if (searching) widgets.exitEditMode()
        binding.widgetGrid.isVisible = !searching
        binding.results.isVisible = searching
        if (!searching) {
            app.background.cancel()
            local = emptyList()
            slowResults = emptyList()
            binding.calcPreview.isVisible = false
            adapter.submit(emptyList())
            return
        }
        local = engine.local(q)
        val calc = local.firstOrNull() as? SearchResult.Calc
        binding.calcPreview.isVisible = calc != null
        binding.calcPreview.text = calc?.let { getString(R.string.calc_result, it.value) }
        render()
        app.background.search(q) { results ->
            if (query() == q) {
                slowResults = results
                render()
            }
        }
    }

    private fun render() {
        val q = query().trim()
        val list = ArrayList<SearchResult>(local.size + slowResults.size + 2)
        list += local
        list += slowResults
        // Web search: first when nothing local matched (so Enter goes there), otherwise last.
        list += SearchResult.Web(q, prefs.webEngine)
        if (!prefs.accessHintDismissed && (!app.contacts.hasPermission() || !app.files.hasPermission())) {
            list += SearchResult.AccessHint
        }
        adapter.submit(list)
        binding.results.scrollToPosition(0)
    }

    private fun resetHome() {
        binding.searchInput.text = null
        binding.searchInput.clearFocus()
        if (!prefs.autoOpenKeyboard) hideKeyboard()
        goToIdleCard()
    }

    private fun showKeyboard() {
        val input = binding.searchInput
        input.requestFocus()
        WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime())
    }

    private fun hideKeyboard() {
        WindowCompat.getInsetsController(window, binding.searchInput).hide(WindowInsetsCompat.Type.ime())
    }

    // ------------------------------------------------------------------ result callbacks

    override fun onClick(result: SearchResult, view: View) {
        when (result) {
            is SearchResult.App -> launchApp(result.entry, view)
            is SearchResult.Contact -> opened { Actions.openContact(this, result.entry) }
            is SearchResult.File -> opened {
                val e = result.entry
                if (e.isDir) Actions.openFolder(this, result.index.relativeDirOf(e).let { if (it.isEmpty()) e.name else "$it/${e.name}" })
                else Actions.openFile(this, result.index.pathOf(e))
            }
            is SearchResult.Song -> opened { Actions.openMedia(this, result.entry.uri, "audio/*") }
            is SearchResult.Photo -> opened {
                Actions.openMedia(this, result.entry.uri, result.entry.mime ?: if (result.entry.isVideo) "video/*" else "image/*")
            }
            is SearchResult.Message -> opened { Actions.message(this, result.entry.address) }
            is SearchResult.Calc -> Actions.copy(this, result.value.replace(",", ""))
            is SearchResult.Web -> opened { Actions.webSearch(this, result.engine, result.query) }
            SearchResult.AccessHint -> startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onAction(result: SearchResult, which: Int, view: View) {
        val number = (result as? SearchResult.Contact)?.entry?.number ?: return
        opened { if (which == 1) Actions.dial(this, number) else Actions.message(this, number) }
    }

    override fun onLongClick(result: SearchResult, view: View) {
        when (result) {
            is SearchResult.App -> showAppMenu(result.entry, view)
            is SearchResult.File -> showFileMenu(result, view)
            is SearchResult.Contact -> result.entry.number?.let { Actions.copy(this, it) }
            is SearchResult.Song -> Actions.shareMedia(this, result.entry.uri, "audio/*")
            is SearchResult.Photo -> Actions.shareMedia(this, result.entry.uri, result.entry.mime ?: "image/*")
            is SearchResult.Message -> Actions.copy(this, result.entry.body)
            is SearchResult.Calc -> Actions.copy(this, result.value.replace(",", ""))
            SearchResult.AccessHint -> menu(view) {
                add(R.string.menu_hide_hint) {
                    prefs.accessHintDismissed = true
                    render()
                }
            }
            is SearchResult.Web -> Unit
        }
    }

    private inline fun opened(block: () -> Unit) {
        dismissPopups()
        launchedSomething = true
        block()
    }

    private fun launchApp(entry: AppEntry, source: View?) {
        dismissPopups()
        launchedSomething = true
        app.apps.launch(entry, source, this)
    }

    // ------------------------------------------------------------------ menus

    private class MenuBuilder(val menu: PopupMenu) {
        val actions = HashMap<Int, () -> Unit>()
        fun add(title: Int, action: () -> Unit) {
            val id = actions.size + 1
            menu.menu.add(0, id, id, title)
            actions[id] = action
        }
    }

    private fun menu(anchor: View, build: MenuBuilder.() -> Unit) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        val builder = MenuBuilder(popup).apply(build)
        popup.setOnMenuItemClickListener { item -> builder.actions[item.itemId]?.invoke(); true }
        openMenu?.dismiss()
        openMenu = popup
        popup.setOnDismissListener { if (openMenu === popup) openMenu = null }
        popup.show()
    }

    private fun showAppMenu(entry: AppEntry, anchor: View) = menu(anchor) {
        if (prefs.isFavorite(entry.key)) {
            add(R.string.menu_remove_favorite) { prefs.removeFavorite(entry.key) }
        } else {
            add(R.string.menu_add_favorite) {
                if (prefs.addFavorite(entry.key)) {
                    Toast.makeText(this@MainActivity, getString(R.string.favorite_added, entry.label), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, R.string.favorites_full, Toast.LENGTH_LONG).show()
                }
            }
        }
        add(R.string.menu_app_info) { app.apps.openAppInfo(entry, anchor) }
        if (app.apps.canUninstall(entry)) add(R.string.menu_uninstall) { app.apps.uninstall(entry, this@MainActivity) }
    }

    private fun showFileMenu(result: SearchResult.File, anchor: View) = menu(anchor) {
        val e = result.entry
        val path = result.index.pathOf(e)
        if (!e.isDir) {
            add(R.string.menu_open_with) { opened { Actions.openWith(this@MainActivity, path) } }
            add(R.string.menu_share) { opened { Actions.share(this@MainActivity, path) } }
        }
        add(R.string.menu_open_folder) { opened { Actions.openFolder(this@MainActivity, result.index.relativeDirOf(e)) } }
        add(R.string.menu_copy_path) { Actions.copy(this@MainActivity, path) }
    }

    private fun dismissPopups() {
        favoritesPopup.dismiss()
        openMenu?.dismiss()
        openMenu = null
    }

    private companion object {
        const val WEATHER_MAX_AGE_MS = 30 * 60 * 1000L
        /** Results area at least this wide (the unfolded screen) gets 6 app columns instead of 4. */
        const val UNFOLDED_MIN_WIDTH_DP = 560
        const val KEY_PENDING_WIDGET = "pending_widget_id"
    }
}
