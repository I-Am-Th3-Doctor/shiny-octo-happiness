package io.github.iamth3doctor.searchlight

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.databinding.ActivityMainBinding
import io.github.iamth3doctor.searchlight.search.SearchEngine
import io.github.iamth3doctor.searchlight.ui.FavoritesPopup
import io.github.iamth3doctor.searchlight.ui.ResultsAdapter
import kotlin.math.max

/** The home screen: one search bar, results above it, favorites below it. */
class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: SearchEngine
    private lateinit var adapter: ResultsAdapter
    private lateinit var favoritesPopup: FavoritesPopup

    private val repo get() = searchlight.apps
    private val prefs get() = searchlight.prefs

    private var openMenu: PopupMenu? = null
    private var launchedApp = false

    private val appsChanged: () -> Unit = {
        refreshResults()
        renderFavorites()
    }
    private val favoritesChanged: () -> Unit = { renderFavorites() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = SearchEngine(prefs)
        adapter = ResultsAdapter(repo, ::launch, ::showAppMenu)
        favoritesPopup = FavoritesPopup(this, repo, prefs, ::launch)

        binding.results.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, true)
        binding.results.adapter = adapter
        binding.results.itemAnimator = null

        applyInsets()
        setupSearchBar()
        setupHomeLongPress()

        repo.addListener(appsChanged)
        prefs.addFavoritesListener(favoritesChanged)
        renderFavorites()
    }

    override fun onDestroy() {
        repo.removeListener(appsChanged)
        prefs.removeFavoritesListener(favoritesChanged)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.autoOpenKeyboard) binding.searchInput.post { showKeyboard() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The IME can only be shown once the window has focus; onResume is sometimes too early.
        if (hasFocus && prefs.autoOpenKeyboard && openMenu == null) showKeyboard()
    }

    override fun onStop() {
        super.onStop()
        dismissPopups()
        // Coming back after opening an app should start a fresh search.
        if (launchedApp) {
            launchedApp = false
            clearQuery()
        }
    }

    /** Pressing Home (while in another app, or on the home screen itself) lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            dismissPopups()
            clearQuery()
            binding.results.scrollToPosition(0)
            if (prefs.autoOpenKeyboard) showKeyboard()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Fold/unfold: the layout re-measures itself; popups are anchored to old coordinates.
        dismissPopups()
    }

    @Deprecated("Launcher: Back never leaves home; it just clears the search.")
    override fun onBackPressed() {
        if (favoritesPopup.isShowing) favoritesPopup.dismiss() else clearQuery()
    }

    // ------------------------------------------------------------------ setup

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupSearchBar() {
        val input = binding.searchInput
        input.doAfterTextChanged {
            favoritesPopup.dismiss()
            refreshResults()
        }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) {
                adapter.first()?.let { launch(it, binding.results.findViewHolderForAdapterPosition(0)?.itemView) }
            }
            enter
        }
        binding.clearButton.setOnClickListener { clearQuery() }
        binding.searchIcon.setOnClickListener { showKeyboard() }
        binding.searchIcon.setOnLongClickListener {
            favoritesPopup.show(binding.searchBar)
            true
        }
    }

    /** Long-press on empty space (not on a result) opens the home menu. Phase 3 adds "Add widget". */
    private fun setupHomeLongPress() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onLongPress(e: MotionEvent) {
                if (binding.results.findChildViewUnder(e.x, e.y) == null) showHomeMenu(e.rawX, e.rawY)
            }
        })
        binding.results.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (rv.findChildViewUnder(e.x, e.y) == null) detector.onTouchEvent(e)
                return false
            }
        })
        binding.glance.setOnLongClickListener {
            showHomeMenu(null, null)
            true
        }
    }

    // ------------------------------------------------------------------ search

    private fun refreshResults() {
        val q = binding.searchInput.text?.toString().orEmpty()
        binding.clearButton.isVisible = q.isNotEmpty()
        adapter.submit(if (q.isBlank()) emptyList() else engine.searchApps(q, repo.apps))
        binding.results.scrollToPosition(0)
    }

    private fun clearQuery() {
        if (binding.searchInput.text.isNullOrEmpty()) return
        binding.searchInput.text = null
    }

    private fun showKeyboard() {
        val input = binding.searchInput
        input.requestFocus()
        WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime())
    }

    private fun launch(entry: AppEntry, source: View?) {
        dismissPopups()
        launchedApp = true
        repo.launch(entry, source, this)
    }

    // ------------------------------------------------------------------ favorites row

    private fun renderFavorites() {
        val row = binding.favoritesRow
        row.removeAllViews()
        val favorites = prefs.favorites.mapNotNull(repo::find)
        if (favorites.isEmpty()) {
            row.addView(TextView(this).apply {
                setText(R.string.favorites_empty_hint)
                setTextColor(getColor(R.color.on_wallpaper_dim))
                textSize = 12f
                gravity = Gravity.CENTER
            })
            return
        }
        val size = resources.getDimensionPixelSize(R.dimen.favorite_icon)
        val pad = (8 * resources.displayMetrics.density).toInt()
        for (entry in favorites) {
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, size + pad * 2, 1f)
                setPadding(pad, pad, pad, pad)
                contentDescription = entry.label
                background = getDrawable(resolveAttr(android.R.attr.selectableItemBackgroundBorderless))
                setImageBitmap(repo.icon(entry) { bmp -> setImageBitmap(bmp) })
                setOnClickListener { v -> launch(entry, v) }
                setOnLongClickListener { v -> showFavoriteMenu(entry, v); true }
            }
            row.addView(icon)
        }
    }

    private fun resolveAttr(attr: Int): Int {
        val out = android.util.TypedValue()
        theme.resolveAttribute(attr, out, true)
        return out.resourceId
    }

    // ------------------------------------------------------------------ menus

    private fun showAppMenu(entry: AppEntry, anchor: View) {
        val menu = PopupMenu(this, anchor, Gravity.END)
        val fav = prefs.isFavorite(entry.key)
        menu.menu.add(0, MENU_FAVORITE, 0, if (fav) R.string.menu_remove_favorite else R.string.menu_add_favorite)
        menu.menu.add(0, MENU_INFO, 1, R.string.menu_app_info)
        if (repo.canUninstall(entry)) menu.menu.add(0, MENU_UNINSTALL, 2, R.string.menu_uninstall)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_FAVORITE -> toggleFavorite(entry)
                MENU_INFO -> repo.openAppInfo(entry, anchor)
                MENU_UNINSTALL -> repo.uninstall(entry, this)
            }
            true
        }
        showMenu(menu)
    }

    private fun showFavoriteMenu(entry: AppEntry, anchor: View) {
        val menu = PopupMenu(this, anchor)
        val list = prefs.favorites
        val index = list.indexOf(entry.key)
        menu.menu.add(0, MENU_FAVORITE, 0, R.string.menu_remove_favorite)
        if (index > 0) menu.menu.add(0, MENU_LEFT, 1, R.string.menu_move_left)
        if (index in 0 until list.lastIndex) menu.menu.add(0, MENU_RIGHT, 2, R.string.menu_move_right)
        menu.menu.add(0, MENU_INFO, 3, R.string.menu_app_info)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_FAVORITE -> prefs.removeFavorite(entry.key)
                MENU_LEFT -> prefs.moveFavorite(entry.key, -1)
                MENU_RIGHT -> prefs.moveFavorite(entry.key, +1)
                MENU_INFO -> repo.openAppInfo(entry, anchor)
            }
            true
        }
        showMenu(menu)
    }

    private fun showHomeMenu(rawX: Float?, rawY: Float?) {
        val anchor = binding.menuAnchor
        if (rawX != null && rawY != null) {
            val loc = IntArray(2)
            binding.root.getLocationOnScreen(loc)
            anchor.x = rawX - loc[0]
            anchor.y = rawY - loc[1]
        } else {
            anchor.x = binding.root.width / 2f
            anchor.y = binding.root.height / 3f
        }
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_SETTINGS, 0, R.string.menu_launcher_settings)
        menu.menu.add(0, MENU_HOME_SETTINGS, 1, R.string.menu_home_settings)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
                MENU_HOME_SETTINGS -> startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            }
            true
        }
        showMenu(menu)
    }

    private fun showMenu(menu: PopupMenu) {
        openMenu?.dismiss()
        openMenu = menu
        menu.setOnDismissListener { if (openMenu === menu) openMenu = null }
        menu.show()
    }

    private fun toggleFavorite(entry: AppEntry) {
        if (prefs.isFavorite(entry.key)) {
            prefs.removeFavorite(entry.key)
        } else if (prefs.addFavorite(entry.key)) {
            Toast.makeText(this, getString(R.string.favorite_added, entry.label), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.favorites_full, Toast.LENGTH_LONG).show()
        }
    }

    private fun dismissPopups() {
        favoritesPopup.dismiss()
        openMenu?.dismiss()
        openMenu = null
    }

    private companion object {
        const val MENU_FAVORITE = 1
        const val MENU_INFO = 2
        const val MENU_UNINSTALL = 3
        const val MENU_LEFT = 4
        const val MENU_RIGHT = 5
        const val MENU_SETTINGS = 6
        const val MENU_HOME_SETTINGS = 7
    }
}
