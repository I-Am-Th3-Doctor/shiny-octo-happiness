package io.github.iamth3doctor.searchlight

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Outline
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import io.github.iamth3doctor.searchlight.ui.AppTableAdapter
import io.github.iamth3doctor.searchlight.ui.AppMenu
import io.github.iamth3doctor.searchlight.ui.FavoritesPopup
import io.github.iamth3doctor.searchlight.ui.FavoritesRail
import io.github.iamth3doctor.searchlight.ui.FrostedDrawable
import io.github.iamth3doctor.searchlight.ui.GlassTint
import io.github.iamth3doctor.searchlight.ui.Posture
import io.github.iamth3doctor.searchlight.ui.ResultsAdapter
import io.github.iamth3doctor.searchlight.ui.Swipe
import io.github.iamth3doctor.searchlight.ui.SwipeTracker
import io.github.iamth3doctor.searchlight.gestures.GestureAction
import io.github.iamth3doctor.searchlight.gestures.GestureService
import io.github.iamth3doctor.searchlight.gestures.HomeGesture
import io.github.iamth3doctor.searchlight.ui.WallpaperBlur
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.setOnHoldListener
import io.github.iamth3doctor.searchlight.widgets.WidgetController
import java.text.Collator
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max

/**
 * The home screen: wallpaper and one frosted home card (tiles with page dots + the search row).
 * No dock. Typing folds the tiles away; results appear above the card: a grid of every matching
 * app, then contacts / files / actions as rows under section headers. The list scrolls only
 * when it's taller than the space above the card.
 */
class MainActivity : Activity(), ResultsAdapter.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: SearchEngine
    private lateinit var adapter: ResultsAdapter
    internal lateinit var favoritesPopup: FavoritesPopup // internal: the device tests long-press its cells
    private lateinit var widgets: WidgetController
    private lateinit var appTable: AppTableAdapter

    private val app get() = searchlight
    private val prefs get() = app.prefs

    private var openMenu: PopupMenu? = null
    internal val appMenu by lazy { AppMenu(this) } // internal: the device tests tap its rows
    private var launchedSomething = false

    /** Results for the current query: [local] is instant, [slowResults] arrives a moment later. */
    private var local: List<SearchResult> = emptyList()
    private var slowResults: List<SearchResult> = emptyList()

    private var cards: List<HomeCard> = emptyList()
    private var posture = Posture.FOLDED
    private lateinit var rail: FavoritesRail
    private lateinit var allApps: AppTableAdapter
    /** Unfolded the search row is hidden until the rail's magnifier (or Home + auto-keyboard) asks for it. */
    private var searchOpen = false
    private val favoritesChanged: () -> Unit = {
        rail.render()
        favoritesPopup.rerenderIfShowing() // e.g. a fresh automatic ranking landed while it's open
    }
    private var cardsStarted = false

    private val sourcesChanged: () -> Unit = {
        refreshResults()
        if (posture == Posture.UNFOLDED) rail.render()
        if (binding.appTable.isVisible) appTable.submit(sortedApps())
        if (binding.allApps.isVisible) allApps.submit(sortedApps()) // e.g. a new icon pack
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = SearchEngine(prefs, app.apps, app.contacts)
        adapter = ResultsAdapter(app.apps, app.contacts, app.thumbnails, this)
        favoritesPopup = FavoritesPopup(this, app.apps, prefs, ::launchApp) { e, v, slot6 -> showAppMenu(e, v, slot6) }
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
        setupAppTable()
        setupRail()
        setupHomeGestures()
        applyPosture(Posture.of(resources.configuration))

        placeRequestedWidget(intent)
        app.apps.addListener(sourcesChanged)
        prefs.addFavoritesListener(favoritesChanged)
        app.contacts.addListener(sourcesChanged)
        app.files.addListener(sourcesChanged)
        WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperChanged, android.os.Handler(mainLooper))
    }

    override fun onDestroy() {
        app.apps.removeListener(sourcesChanged)
        prefs.removeFavoritesListener(favoritesChanged)
        app.contacts.removeListener(sourcesChanged)
        app.files.removeListener(sourcesChanged)
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperChanged)
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
        app.shortcuts.refreshIfAccessChanged() // e.g. just made the default home: shortcuts become readable
        app.autoFavorites.refreshIfStale() // daily, and picks up usage access granted (or revoked) in Settings
        app.autoFavorites.refreshPrediction() // the predicted 6th favorite follows the time of day
        cards.forEach { it.render() } // e.g. °F/°C changed in the Widget Center
        WallpaperBlur.retryIfUnavailable() // photo access may have been granted in Settings
        binding.searchInput.hint = greeting()
        if (prefs.autoOpenKeyboard && !binding.appTable.isVisible) binding.searchInput.post { showKeyboard() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The IME can only be shown once the window has focus; onResume is sometimes too early.
        if (hasFocus && prefs.autoOpenKeyboard && openMenu == null && !appMenu.isShowing && !binding.appTable.isVisible) showKeyboard()
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
        placeRequestedWidget(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            widgets.exitEditMode()
            dismissPopups()
            resetHome()
            if (prefs.autoOpenKeyboard) showKeyboard()
        }
    }

    /** A widget an app pinned ("Add to home screen"), handed over by PinWidgetActivity. */
    private fun placeRequestedWidget(intent: Intent?) {
        val id = intent?.getIntExtra(EXTRA_PLACE_WIDGET, AppWidgetManager.INVALID_APPWIDGET_ID) ?: return
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        intent.removeExtra(EXTRA_PLACE_WIDGET) // once, not again on recreate
        widgets.placePinned(id)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Fold/unfold: views re-measure themselves; popups are anchored to stale coordinates.
        dismissPopups()
        applyPosture(Posture.of(newConfig))
    }

    /**
     * Folded: one tile at a time (swipe, dots), 4 app columns, the folded widget layout.
     * Unfolded: every tile side by side, 7 app columns, the unfolded widget layout.
     */
    private fun applyPosture(p: Posture) {
        val changed = p != posture
        posture = p
        widgets.posture = p
        adapter.columns = appColumns()
        (binding.appTableList.layoutManager as? GridLayoutManager)?.spanCount = appColumns()
        if (changed) applyTileMode()
        val unfolded = p == Posture.UNFOLDED
        binding.rail.isVisible = unfolded && !binding.appTable.isVisible
        if (unfolded) rail.render() else closeAllApps(animate = false)
        updateSearchRow()
        layoutColumn()
    }

    /** Folded: always there. Unfolded: once search is asked for (the rail's magnifier), or there's a query. */
    private fun updateSearchRow() {
        binding.searchPill.isVisible = posture == Posture.FOLDED || searchOpen || query().isNotEmpty()
    }

    /**
     * Unfolded, the home column (card, widgets, results) sits beside the rail: capped at
     * home_max_width and centered in the space left of it. Folded: centered on screen as before.
     */
    private fun layoutColumn() {
        val root = binding.root
        val lp = binding.column.layoutParams as FrameLayout.LayoutParams
        val gravity: Int
        val width: Int
        val left: Int
        if (posture == Posture.UNFOLDED && root.width > 0) {
            val avail = root.width - root.paddingLeft - root.paddingRight - FavoritesRail.reservedWidth(this)
            width = minOf(px(R.dimen.home_max_width), avail)
            gravity = Gravity.START
            left = (avail - width) / 2
        } else {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER_HORIZONTAL
            left = 0
        }
        if (lp.gravity != gravity || lp.width != width || lp.leftMargin != left) {
            lp.gravity = gravity
            lp.width = width
            lp.leftMargin = left
            binding.column.layoutParams = lp
        }
    }

    private fun appColumns() = if (posture == Posture.UNFOLDED) UNFOLDED_APP_COLUMNS else FOLDED_APP_COLUMNS

    /** Puts the tile views in the pager (folded) or the side-by-side row (unfolded). */
    private fun applyTileMode() {
        val row = binding.tileRow
        row.removeAllViews()
        if (posture == Posture.UNFOLDED) {
            binding.cards.adapter = null // releases the tile views to the row
            for (card in cards) {
                row.addView(card.viewFor(row).apply { minimumWidth = if (card.minRowWidth != 0) px(card.minRowWidth) else 0 })
                card.setInRow(true)
            }
            binding.cards.isVisible = false
            binding.dots.isVisible = false
            row.isVisible = true
        } else {
            row.isVisible = false
            cards.forEach { it.setInRow(false) }
            binding.cards.isVisible = true
            binding.cards.adapter = CardsAdapter(cards)
            binding.dots.isVisible = cards.size > 1
            goToIdleCard()
        }
    }

    @Deprecated("Launcher: Back never leaves home; it clears the search and drops the keyboard.")
    override fun onBackPressed() {
        when {
            binding.allApps.isVisible -> closeAllApps()
            binding.appTable.isVisible -> closeAppTable()
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

    private fun setupPager() {
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
            }
        }
        binding.dots.removeAllViews()
        val dotSize = px(R.dimen.size_dot)
        val dotGap = px(R.dimen.space_xs)
        repeat(cards.size) {
            binding.dots.addView(View(this).apply {
                setBackgroundResource(R.drawable.bg_dot)
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply { marginStart = dotGap; marginEnd = dotGap }
            })
        }
        applyTileMode()
        if (wasStarted) startCards()
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
        // Frosted glass (see FrostedDrawable) tinted with the wallpaper's own hue (GlassTint):
        // dark enough for white text, the search row one step lighter than the card, both with
        // a light hairline. Color not sampled: near-black at 50% / 35% over the blur. No blur at
        // all: a 75% card, and the row a faint light inset on it.
        val border = color(R.color.glass_border)
        val borderWidth = pxF(R.dimen.stroke_glass)
        binding.homeCard.background = FrostedDrawable(
            binding.homeCard, pxF(R.dimen.radius_home_card),
            color(R.color.glass_card), color(R.color.glass_card_fallback), border, borderWidth,
            adaptive = GlassTint.Layer.CARD,
        )
        binding.searchPill.background = FrostedDrawable(
            binding.searchPill, pxF(R.dimen.radius_pill), // a full pill: half its height
            color(R.color.glass_row), color(R.color.surface_inset), border, borderWidth,
            adaptive = GlassTint.Layer.ROW,
        )
        binding.homeCard.clipToOutline = true
        // The tile strip's tap ripples follow the card's corners too.
        val rowRadius = pxF(R.dimen.radius_home_row)
        binding.cardArea.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, rowRadius)
        }
        binding.cardArea.clipToOutline = true
        val focusSearch = View.OnClickListener { showKeyboard() }
        binding.homeCard.setOnClickListener(focusSearch)
        binding.searchPill.setOnClickListener(focusSearch)

        val input = binding.searchInput
        input.doAfterTextChanged {
            favoritesPopup.dismiss()
            onQueryChanged()
        }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) {
                // The highlighted result, or the web search when nothing else matched.
                val result = adapter.selectedResult() ?: SearchResult.Web(query().trim(), prefs.webEngine)
                onClick(result, binding.results.findViewHolderForAdapterPosition(adapter.selectedPosition())?.itemView ?: input)
            }
            enter
        }
        input.setOnKeyListener { _, keyCode, event -> event.action == KeyEvent.ACTION_DOWN && navigateResults(keyCode, event) }
        binding.clearButton.setOnClickListener { binding.searchInput.text = null }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.searchIcon.setOnClickListener { showKeyboard() }
        // Hold the magnifier for the favorites: 250ms, quicker than the system long-press.
        binding.searchIcon.setOnHoldListener(FAVORITES_HOLD_MS) {
            app.autoFavorites.refreshIfStale()
            app.autoFavorites.refreshPrediction()
            favoritesPopup.show(binding.homeCard)
        }
    }

    /** Edit mode (long-press a widget or empty space) shows a small bar: add, settings, done. */
    // ------------------------------------------------------------------ app table

    /**
     * Swipe up on empty home space opens every app, A–Z, in the search grid's cells. Swipe down
     * from the top of the list, or Back, closes it. Launching an app closes it on the way out.
     */
    private fun setupAppTable() {
        appTable = AppTableAdapter(app.apps, ::launchApp, ::showAppMenu)
        val list = binding.appTableList
        list.layoutManager = GridLayoutManager(this, appColumns())
        // The table is clear glass over the wallpaper: it must never take focus (hiding the home
        // card moves focus off the search field), or Android paints its focus highlight over it.
        for (v in listOf(binding.appTable, list)) {
            v.isFocusable = false
            v.isFocusableInTouchMode = false
            v.defaultFocusHighlightEnabled = false
        }
        list.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        list.adapter = appTable
        binding.widgetGrid.onSwipeUp = ::openAppTable

        // Swipe down closes, but only from the top: otherwise it's an ordinary scroll back up.
        val distance = pxF(R.dimen.size_swipe)
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downX = 0f
            private var downY = 0f
            private var atTop = false
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        atTop = !rv.canScrollVertically(-1)
                    }
                    MotionEvent.ACTION_MOVE ->
                        if (atTop && Swipe.vertical(e.x - downX, e.y - downY, distance) == Swipe.Direction.DOWN) {
                            closeAppTable()
                            return true
                        }
                }
                return false
            }
        })
    }

    fun openAppTable() {
        if (binding.appTable.isVisible) return
        hideKeyboard()
        dismissPopups()
        appTable.submit(sortedApps())
        binding.appTableList.scrollToPosition(0)
        binding.appTable.isVisible = true
        binding.appTable.translationY = binding.root.height.toFloat()
        binding.appTable.animate().translationY(0f).setDuration(TABLE_ANIM_MS).start()
        showHome(false)
    }

    private fun closeAppTable(animate: Boolean = true) {
        if (!binding.appTable.isVisible) return
        binding.appTable.animate().cancel()
        showHome(true, animate)
        if (!animate) {
            binding.appTable.isVisible = false
            return
        }
        binding.appTable.animate().translationY(binding.root.height.toFloat()).setDuration(TABLE_ANIM_MS)
            .withEndAction { binding.appTable.isVisible = false }.start()
    }

    /**
     * The app table has no background of its own: while it's open the home card and widgets
     * fade out, leaving just the wallpaper and the apps. They come back exactly as they were.
     */
    private fun showHome(show: Boolean, animate: Boolean = true) {
        val views = listOfNotNull(binding.column, binding.rail.takeIf { posture == Posture.UNFOLDED })
        for (home in views) {
            home.animate().cancel()
            if (show) home.isVisible = true
            if (!animate) {
                home.alpha = if (show) 1f else 0f
                home.isInvisible = !show
                continue
            }
            home.animate().alpha(if (show) 1f else 0f).setDuration(TABLE_ANIM_MS)
                .withEndAction { home.isInvisible = !show }.start()
        }
    }

    // ------------------------------------------------------------------ unfolded rail

    /**
     * Unfolded: a frosted rail on the right with search, the six favorites (one tap each, no
     * long-press) and all apps, which slides in a two-column panel of every app.
     */
    private fun setupRail() {
        rail = FavoritesRail(this, app.apps, prefs, binding.railFavorites, ::launchApp) { e, v, slot6 -> showAppMenu(e, v, slot6) }
        binding.rail.background = frosted(binding.rail, R.dimen.radius_pill)
        binding.rail.clipToOutline = true
        binding.railSearch.setOnClickListener { showKeyboard() }
        binding.railAllApps.setOnClickListener { openAllApps() }
        binding.root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) layoutColumn() }

        // Hold: the app menu, with a drag armed; moving the held finger closes it and drags instead.
        allApps = AppTableAdapter(app.apps, ::launchApp) { entry, view ->
            heldApp = entry to view
            showAppMenu(entry, view)
        }
        val list = binding.allAppsList
        list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = allApps
        list.isFocusable = false
        list.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.allApps.background = frosted(binding.allApps, R.dimen.radius_card)
        binding.allApps.clipToOutline = true

        // Swipe toward the right edge closes; once an app is held, the same finger drags it.
        val distance = pxF(R.dimen.size_swipe)
        list.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            private var downX = 0f
            private var downY = 0f
            private val slop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop * 2
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (dragged != null) return true
                heldApp?.let { (entry, view) ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                            heldApp = null
                            dismissPopups() // the app menu: the finger is dragging, not choosing
                            startDrag(entry, view)
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> heldApp = null // keep the menu
                    }
                }
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        heldApp = null
                        downX = e.x
                        downY = e.y
                    }
                    MotionEvent.ACTION_MOVE ->
                        if (Swipe.horizontal(e.x - downX, e.y - downY, distance) == Swipe.Direction.RIGHT) {
                            closeAllApps()
                            return true
                        }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (dragged == null) return
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> moveDrag(e.rawX, e.rawY)
                    MotionEvent.ACTION_UP -> endDrag(e.rawX)
                    MotionEvent.ACTION_CANCEL -> endDrag(null)
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
        })
    }

    fun openAllApps() {
        if (binding.allApps.isVisible) return
        hideKeyboard()
        dismissPopups()
        allApps.submit(sortedApps())
        binding.allAppsList.scrollToPosition(0)
        binding.allApps.isVisible = true
        binding.allApps.translationX = binding.allApps.width.takeIf { it > 0 }?.toFloat() ?: px(R.dimen.size_all_apps_panel).toFloat()
        binding.allApps.animate().translationX(0f).setDuration(TABLE_ANIM_MS).start()
    }

    private fun closeAllApps(animate: Boolean = true) {
        if (!binding.allApps.isVisible) return
        endDrag(null)
        binding.allApps.animate().cancel()
        if (!animate) {
            binding.allApps.isVisible = false
            return
        }
        binding.allApps.animate().translationX(binding.allApps.width + px(R.dimen.inset_home_card).toFloat())
            .setDuration(TABLE_ANIM_MS).withEndAction { binding.allApps.isVisible = false }.start()
    }

    // Dragging an app out of the all-apps panel onto a screen edge opens it side by side.
    /** An app being held (its menu is showing) whose drag starts if the finger moves. */
    private var heldApp: Pair<AppEntry, View>? = null
    private var dragged: AppEntry? = null
    private var dragGhost: ImageView? = null

    private fun startDrag(entry: AppEntry, from: View) {
        dragged = entry
        val size = px(R.dimen.size_grid_icon)
        dragGhost = ImageView(this).apply {
            setImageBitmap(app.apps.icon(entry) { setImageBitmap(it) })
            elevation = pxF(R.dimen.elevation_raised) * 2
            alpha = 0.9f
            binding.root.addView(this, FrameLayout.LayoutParams(size, size))
        }
        val loc = IntArray(2)
        from.getLocationOnScreen(loc)
        moveDrag(loc[0] + from.width / 2f, loc[1] + from.height / 2f)
        binding.dropLeft.isVisible = true
        binding.dropRight.isVisible = true
    }

    private fun moveDrag(rawX: Float, rawY: Float) {
        val ghost = dragGhost ?: return
        val root = IntArray(2).also { binding.root.getLocationOnScreen(it) }
        ghost.x = rawX - root[0] - ghost.layoutParams.width / 2f
        ghost.y = rawY - root[1] - ghost.layoutParams.height / 2f
        val side = edgeAt(rawX)
        binding.dropLeft.alpha = if (side == Gravity.START) 1f else 0.4f
        binding.dropRight.alpha = if (side == Gravity.END) 1f else 0.4f
    }

    /** Ends a drag: dropped on an edge ([rawX] inside a drop zone) opens the app side by side. */
    private fun endDrag(rawX: Float?) {
        val entry = dragged ?: return
        dragged = null
        dragGhost?.let { binding.root.removeView(it) }
        dragGhost = null
        binding.dropLeft.isVisible = false
        binding.dropRight.isVisible = false
        if (rawX != null && edgeAt(rawX) != null) {
            dismissPopups()
            launchedSomething = true
            app.apps.launchAdjacent(entry, this)
        }
    }

    /** Gravity.START / END if [rawX] is over the left / right drop zone, else null. */
    private fun edgeAt(rawX: Float): Int? {
        val loc = IntArray(2).also { binding.root.getLocationOnScreen(it) }
        val zone = px(R.dimen.size_drop_zone)
        return when {
            rawX < loc[0] + zone -> Gravity.START
            rawX > loc[0] + binding.root.width - zone -> Gravity.END
            else -> null
        }
    }

    private fun sortedApps(): List<AppEntry> {
        val collator = Collator.getInstance()
        return app.apps.apps.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    // ------------------------------------------------------------------ home gestures

    private var gestureToast: Toast? = null
    /** What the last home swipe did, for the device tests. */
    internal var lastHomeSwipe: String? = null

    /**
     * Swipe down / left / right on empty home space runs the action chosen in Settings. Empty
     * space is the widget canvas (it reports its own swipes: a touch on a widget is the widget's)
     * plus any wallpaper outside it, e.g. beside the card when unfolded. Touches that start on the
     * card, the search row, the results, the edit bar or the rail are never gestures.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility") // swipes only; taps fall through
    private fun setupHomeGestures() {
        binding.widgetGrid.onSwipe = ::onHomeSwipe
        val tracker = SwipeTracker(pxF(R.dimen.size_swipe), pxF(R.dimen.size_gesture))
        binding.root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (onHomeChrome(e.rawX, e.rawY)) return@setOnTouchListener false
                    tracker.down(e.x, e.y)
                }
                // Up stays the widget canvas's (the app table); out here only the home gestures.
                MotionEvent.ACTION_MOVE -> tracker.move(e.x, e.y)?.takeIf { it != Swipe.Direction.UP }?.let(::onHomeSwipe)
            }
            true
        }
    }

    /** Does a touch at this screen point start on the card, results, edit bar, rail or a panel? */
    private fun onHomeChrome(x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        return listOf(binding.homeCard, binding.resultsStack, binding.editBar, binding.rail, binding.allApps, binding.appTable)
            .any { v ->
                v.isShown && run {
                    v.getLocationOnScreen(loc)
                    x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height
                }
            }
    }

    private fun onHomeSwipe(direction: Swipe.Direction) {
        val gesture = HomeGesture.of(direction) ?: return
        // The app table and all-apps panel keep their own swipes; edit mode is for arranging.
        if (binding.appTable.isVisible || binding.allApps.isVisible || binding.widgetGrid.editMode) {
            lastHomeSwipe = "$gesture ignored"
            return
        }
        val action = prefs.gestureAction(gesture)
        lastHomeSwipe = "$gesture -> $action"
        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.NOTIFICATIONS -> globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            GestureAction.QUICK_SETTINGS -> globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            GestureAction.LOCK_SCREEN -> globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            GestureAction.APP_DRAWER -> openAppTable()
            GestureAction.SEARCH -> showKeyboard()
            GestureAction.WIDGET_CENTER -> binding.widgetGrid.enterEditModeFromSwipe()
            // Uninstalled since it was chosen: nothing to open (Settings says "App not installed").
            GestureAction.LAUNCH_APP -> prefs.gestureApp(gesture)?.let(app.apps::findByPackage)?.let { launchApp(it, null) }
        }
    }

    /** Shade, quick settings, lock: only through our accessibility service. Says so when it's off. */
    private fun globalAction(action: Int) {
        if (GestureService.perform(action)) return
        gestureToast?.cancel() // one message per swipe, never a stack of them
        gestureToast = Toast.makeText(this, R.string.gesture_needs_accessibility_toast, Toast.LENGTH_LONG).also { it.show() }
    }

    private fun setupWidgetPage() {
        binding.editAdd.setOnClickListener { widgets.beginAdd() }
        binding.editSettings.setOnClickListener {
            widgets.exitEditMode()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.editDone.setOnClickListener { widgets.exitEditMode() }
    }

    /**
     * Results: a vertical list whose first block is the app grid (every matching app; 4 columns
     * folded, 7 unfolded, see applyPosture).
     */
    private fun setupResultsGrid() {
        adapter.columns = appColumns()
        binding.results.layoutManager = LinearLayoutManager(this)
        // Panel and pill are glass like the home card, each with its own shadow (see frosted()).
        binding.resultsPanel.background = frosted(binding.resultsPanel, R.dimen.radius_card)
        binding.resultsPanel.clipToOutline = true // scrolled results slide under the rounded corners
        binding.webPill.background = frosted(binding.webPill, R.dimen.radius_pill)
        binding.webPill.clipToOutline = true
        binding.webPill.setOnClickListener { onClick(SearchResult.Web(query().trim(), prefs.webEngine), it) }
        // Nothing in the results may take focus: key events (Backspace from the on-screen
        // keyboard, arrows from a hardware one) go to the focused view, and that must stay the
        // search field; if the field ever loses focus, this keeps the results from catching it.
        // Arrow keys move the highlight from the field (navigateResults). Set in code because
        // RecyclerView makes itself focusable in its constructor, over the XML.
        binding.results.isFocusable = false
        binding.results.isFocusableInTouchMode = false
        binding.results.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.results.adapter = adapter
        binding.results.itemAnimator = null
    }

    /** The results panel and web pill: the same glass as the home card (wallpaper-tinted, hairline). */
    private fun frosted(view: View, radius: Int) = FrostedDrawable(
        view, pxF(radius), color(R.color.glass_card), color(R.color.glass_card_fallback),
        color(R.color.glass_border), pxF(R.dimen.stroke_glass), adaptive = GlassTint.Layer.CARD,
    )

    /** A new wallpaper means a new blur behind every glass surface. */
    private val wallpaperChanged = WallpaperManager.OnColorsChangedListener { _, _ ->
        WallpaperBlur.invalidate()
        binding.homeCard.invalidate()
        binding.searchPill.invalidate()
        binding.resultsPanel.invalidate()
        binding.webPill.invalidate()
    }

    /**
     * Moves the highlight through the results: ↑/↓ by row (grid rows, then list rows), Tab /
     * Shift+Tab one by one. ←/→ move across the grid only with a physical keyboard, so the
     * on-screen keyboard's cursor keys still move the text cursor.
     */
    private fun navigateResults(keyCode: Int, event: KeyEvent): Boolean {
        if (!binding.resultsStack.isVisible) return false
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
        updateSearchRow()
        binding.clearButton.isVisible = q.isNotEmpty()
        binding.settingsButton.isVisible = q.isEmpty()
        binding.cardArea.isVisible = !searching && cards.isNotEmpty()
        if (searching) widgets.exitEditMode()
        binding.widgetGrid.isVisible = !searching
        binding.resultsStack.isVisible = searching
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
        if (!prefs.accessHintDismissed && (!app.contacts.hasPermission() || !app.files.hasPermission())) {
            list += SearchResult.AccessHint
        }
        adapter.submit(list)
        binding.results.scrollToPosition(0)
        binding.resultsPanel.isVisible = list.isNotEmpty()
        // Web search has its own pill under the panel (and Enter goes there when nothing matched).
        binding.webPillText.text = getString(R.string.web_search_for, prefs.webEngine.label, q)
    }

    private fun resetHome() {
        closeAppTable(animate = false)
        closeAllApps(animate = false)
        searchOpen = false
        updateSearchRow()
        binding.searchInput.text = null
        binding.searchInput.clearFocus()
        if (!prefs.autoOpenKeyboard) hideKeyboard()
        goToIdleCard()
    }

    private fun showKeyboard() {
        if (!searchOpen && posture == Posture.UNFOLDED) {
            searchOpen = true
            updateSearchRow()
        }
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

    /**
     * Long-press on any app icon (search results, app table, favorites popup, rail, all-apps):
     * the stock-style menu. The app's own shortcuts first (from the cache, so it opens at once),
     * then App info, Widgets (only if the app has any), Uninstall (not for Searchlight or work
     * apps), and add/remove favorite, the only way to set favorites (not while automatic
     * favorites are showing: the manual six are hidden then, so editing them would look like a no-op).
     */
    private fun showAppMenu(entry: AppEntry, anchor: View, predictedSlot: Boolean = false) {
        val cached = app.shortcuts.menuFor(entry)
        // The predicted 6th favorite: Pin prediction (or Unpin, once pinned) before everything else.
        val pin = if (!predictedSlot || !prefs.predictedSlotActive) null
        else if (prefs.predictionPinned) AppMenu.Item(getString(R.string.menu_unpin_prediction), iconRes = R.drawable.ic_pin) {
            prefs.unpinPrediction()
            app.autoFavorites.refreshPrediction()
        } else AppMenu.Item(getString(R.string.menu_pin_prediction), iconRes = R.drawable.ic_pin) {
            prefs.pinPrediction(entry.key)
        }
        val shortcuts = listOfNotNull(pin) + cached.shortcuts.map { s ->
            AppMenu.Item(s.label, bitmap = s.icon ?: app.apps.icon(entry) {}) {
                favoritesPopup.dismiss()
                launchedSomething = true
                app.shortcuts.launch(entry, s, anchor)
            }
        }
        val actions = ArrayList<AppMenu.Item>()
        actions += AppMenu.Item(getString(R.string.menu_app_info), iconRes = R.drawable.ic_info) {
            favoritesPopup.dismiss()
            app.apps.openAppInfo(entry, anchor)
        }
        if (cached.hasWidgets) actions += AppMenu.Item(getString(R.string.menu_widgets), iconRes = R.drawable.ic_widgets) {
            favoritesPopup.dismiss()
            resetHome() // the widget lands on the home grid, so that's where you come back to
            widgets.beginAdd(entry.packageName, entry.user)
        }
        if (app.apps.canUninstall(entry)) actions += AppMenu.Item(getString(R.string.menu_uninstall), iconRes = R.drawable.ic_delete) {
            favoritesPopup.dismiss()
            app.apps.uninstall(entry, this@MainActivity)
        }
        if (!prefs.autoFavoritesActive) {
            actions += if (prefs.isFavorite(entry.key)) {
                AppMenu.Item(getString(R.string.menu_remove_favorite), iconRes = R.drawable.ic_close) {
                    favoritesPopup.dismiss()
                    prefs.removeFavorite(entry.key)
                }
            } else {
                AppMenu.Item(getString(R.string.menu_add_favorite), iconRes = R.drawable.ic_star) {
                    if (prefs.addFavorite(entry.key)) {
                        Toast.makeText(this, getString(R.string.favorite_added, entry.label), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, R.string.favorites_full, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        openMenu?.dismiss()
        openMenu = null
        appMenu.show(anchor, shortcuts, actions) {}
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
        appMenu.dismiss()
        openMenu?.dismiss()
        openMenu = null
    }

    companion object {
        /** A widget id (already bound, e.g. from a pin request) to place on the home grid. */
        const val EXTRA_PLACE_WIDGET = "place_widget"

        private const val WEATHER_MAX_AGE_MS = 30 * 60 * 1000L
        private const val FOLDED_APP_COLUMNS = 4
        private const val UNFOLDED_APP_COLUMNS = 7
        private const val KEY_PENDING_WIDGET = "pending_widget_id"
        private const val TABLE_ANIM_MS = 220L
        private const val FAVORITES_HOLD_MS = 250L
    }
}
