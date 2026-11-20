package io.github.iamth3doctor.searchlight

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.wrappingItems
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.SeekBar
import io.github.iamth3doctor.searchlight.ui.FrostedDrawable
import io.github.iamth3doctor.searchlight.ui.GlassTint
import android.widget.TextView
import androidx.core.view.isVisible
import io.github.iamth3doctor.searchlight.cards.QuickAction
import io.github.iamth3doctor.searchlight.data.FavoritesMode
import io.github.iamth3doctor.searchlight.databinding.ItemGestureRowBinding
import io.github.iamth3doctor.searchlight.gestures.GestureAction
import io.github.iamth3doctor.searchlight.gestures.GestureService
import io.github.iamth3doctor.searchlight.gestures.HomeGesture
import io.github.iamth3doctor.searchlight.iconpack.IconPackActivity
import io.github.iamth3doctor.searchlight.data.Prefs
import io.github.iamth3doctor.searchlight.data.WebEngine
import io.github.iamth3doctor.searchlight.data.WebSearchUrl
import io.github.iamth3doctor.searchlight.media.MusicApps
import io.github.iamth3doctor.searchlight.ui.CollapsibleSections
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.edgeToEdge
import io.github.iamth3doctor.searchlight.ui.isLightTheme
import io.github.iamth3doctor.searchlight.ui.padForSystemBars
import io.github.iamth3doctor.searchlight.ui.wrappingSingleChoice
import io.github.iamth3doctor.searchlight.ui.primaryText
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.secondaryText
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.databinding.ActivitySettingsBinding
import io.github.iamth3doctor.searchlight.ui.Permissions
import io.github.iamth3doctor.searchlight.focus.FocusRules
import java.text.NumberFormat

/** Tags each empty favorite slot row in Settings (tests). */
internal const val FAVORITE_EMPTY_SLOT_TAG = "favorite_empty_slot"

/** Settings + the permissions hub. Phase 4 adds looks and gestures. */
class SettingsActivity : Activity() {

    private lateinit var binding: ActivitySettingsBinding
    private val app get() = searchlight
    private val prefs get() = app.prefs
    private val favoritesChanged: () -> Unit = { renderFavorites() }
    private val filesChanged: () -> Unit = { renderIndexStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        edgeToEdge(lightBackground = isLightTheme())
        binding.scroll.padForSystemBars()
        // Each section (Home, Appearance, Gestures, ...) folds open and shut under its title.
        // The six top-level entries are frosted cards: an icon chip, the title over a subtitle.
        CollapsibleSections.apply(binding.scroll.getChildAt(0) as LinearLayout, SCREEN, prefs, MENU)
        binding.scroll.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (isLightTheme()) intArrayOf(getColor(R.color.settings_page_light_top), getColor(R.color.settings_page_light_bottom))
            else intArrayOf(getColor(R.color.settings_page_dark_top), getColor(R.color.settings_page_dark_bottom)))

        binding.setDefault.setOnClickListener { requestDefaultHome() }
        setupFocusApps()
        binding.focusSwitch.isChecked = prefs.focusMode
        binding.focusSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.focusMode = checked
            // Focus clears the glass, leaving it restores it: show (and draw) where it is now.
            binding.glassSlider.progress = prefs.glassLevel
            showGlassLevel(prefs.glassLevel)
            FrostedDrawable.setGlassLevel(prefs.glassLevel)
        }
        binding.autoKeyboard.isChecked = prefs.autoOpenKeyboard
        binding.autoKeyboard.setOnCheckedChangeListener { _, checked -> prefs.autoOpenKeyboard = checked }
        binding.greetingRow.setOnClickListener { editGreeting() }
        binding.webEngineRow.setOnClickListener { pickWebEngine() }
        binding.iconPackRow.setOnClickListener { startActivity(Intent(this, IconPackActivity::class.java)) }
        setupGlassSlider()
        binding.favoritesModeRow.setOnClickListener { pickFavoritesMode() }
        binding.predictedSlotSwitch.setOnCheckedChangeListener { _, on ->
            if (on == prefs.predictedSlot) return@setOnCheckedChangeListener
            prefs.predictedSlot = on
            if (on) {
                if (app.autoFavorites.hasAccess()) app.autoFavorites.refreshPrediction() else askUsageAccess(R.string.usage_access_prompt_predicted)
            }
        }
        binding.usageAccessRow.setOnClickListener { Permissions.openUsageAccessSettings(this) }
        binding.widgetCenterRow.setOnClickListener { startActivity(Intent(this, WidgetCenterActivity::class.java)) }
        binding.reindex.setOnClickListener { app.files.rescan() }
        binding.about.text = getString(R.string.settings_about, BuildConfig.VERSION_NAME)
        binding.calendarTargetRow.setOnClickListener { pickCalendarTarget() }
        binding.calendarMoveLocal.setOnClickListener { confirmMoveLocal() }

        prefs.addFavoritesListener(favoritesChanged)
        app.apps.addListener(favoritesChanged)
        app.files.addListener(filesChanged)
    }

    override fun onDestroy() {
        prefs.removeFavoritesListener(favoritesChanged)
        app.apps.removeListener(favoritesChanged)
        app.files.removeListener(filesChanged)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        app.startSources()
        app.autoFavorites.refreshIfStale() // usage access may have just been granted
        render()
        renderCalendarWidget()
    }

    // ------------------------------------------------------------------ Calendar widget

    private val calendarWriter by lazy { io.github.iamth3doctor.searchlight.calendar.CalendarWriter(this) }
    private val calendarWorker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    /** Where new events go, and any events stranded on phone-only calendars (with the move). */
    private fun renderCalendarWidget() {
        calendarWorker.execute {
            val target = calendarWriter.target(prefs.calendarTarget)
            val chosen = calendarWriter.writable().firstOrNull { it.id == prefs.calendarTarget }
            val local = calendarWriter.localEventCount()
            val readable = calendarWriter.canRead()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.calendarTargetValue.text = when {
                    !readable -> getString(R.string.settings_calendar_needs_access)
                    chosen != null -> chosen.name
                    target != null -> getString(R.string.settings_calendar_target_default) + " (" + target.name + ")"
                    else -> getString(R.string.cal_no_google)
                }
                binding.calendarLocalInfo.text = if (local > 0) resources.getQuantityString(R.plurals.settings_calendar_move_count, local, local)
                else getString(R.string.settings_calendar_move_none)
                binding.calendarLocalInfo.visibility = if (readable) android.view.View.VISIBLE else android.view.View.GONE
                binding.calendarMoveLocal.visibility = if (local > 0 && target != null) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    /** Synced calendars only: a phone-only calendar is never offered. */
    private fun pickCalendarTarget() {
        calendarWorker.execute {
            val cals = calendarWriter.writable()
            runOnUiThread {
                if (cals.isEmpty()) {
                    Toast.makeText(this, if (calendarWriter.canRead()) R.string.cal_no_google else R.string.settings_calendar_needs_access, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val labels = listOf(getString(R.string.settings_calendar_target_default)) +
                    cals.map { if (it.account.isNotEmpty() && it.account != it.name) "${it.name} · ${it.account}" else it.name }
                val checked = cals.indexOfFirst { it.id == prefs.calendarTarget }.let { if (it < 0) 0 else it + 1 }
                AlertDialog.Builder(this).setTitle(R.string.settings_calendar_target)
                    .wrappingSingleChoice(labels, checked) { d, which ->
                        d.dismiss()
                        prefs.calendarTarget = if (which == 0) -1L else cals[which - 1].id
                        renderCalendarWidget()
                    }.show()
            }
        }
    }

    /** One time: events on phone-only calendars to the Google calendar (copied, then the originals removed). */
    private fun confirmMoveLocal() {
        if (!calendarWriter.canWrite()) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR), REQUEST_RUNTIME)
            return
        }
        calendarWorker.execute {
            val target = calendarWriter.target(prefs.calendarTarget)
            runOnUiThread {
                if (target == null) return@runOnUiThread
                moveDialog = AlertDialog.Builder(this)
                    .setTitle(R.string.settings_calendar_move)
                    .setMessage(getString(R.string.settings_calendar_move_confirm, target.name))
                    .setPositiveButton(R.string.settings_calendar_move_go) { _, _ ->
                        calendarWorker.execute {
                            val result = calendarWriter.moveLocalEvents(target.id)
                            runOnUiThread {
                                var msg = resources.getQuantityString(R.plurals.settings_calendar_moved, result.moved, result.moved, target.name)
                                if (result.failed > 0) msg += " " + getString(R.string.settings_calendar_move_failed, result.failed)
                                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                                renderCalendarWidget()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** The move confirmation last shown (tests). */
    internal var moveDialog: AlertDialog? = null

    private fun render() {
        binding.setDefault.setText(if (isDefaultHome()) R.string.settings_is_default else R.string.settings_set_default)
        binding.greetingValue.text = prefs.greeting.ifEmpty { getString(R.string.settings_greeting_auto) }
        binding.webEngineValue.text = prefs.webTarget.label
        binding.iconPackValue.text = app.iconPacks.selected
            ?.let { pkg -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)) }.getOrNull() }
            ?: getString(R.string.icon_pack_system)

        renderTileSettings()
        renderGestures()
        renderPermissions()
        renderIndexStatus()
        renderFavorites()
    }

    // ------------------------------------------------------------------ home

    private fun roleManager(): RoleManager? =
        getSystemService(RoleManager::class.java)?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }

    private fun isDefaultHome() = roleManager()?.isRoleHeld(RoleManager.ROLE_HOME) == true

    private fun requestDefaultHome() {
        val rm = roleManager()
        if (rm != null && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            @Suppress("DEPRECATION")
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE)
        } else {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }

    private fun editGreeting() {
        val input = EditText(this).apply {
            setText(prefs.greeting)
            hint = getString(R.string.settings_greeting_hint)
            setSingleLine()
        }
        val pad = px(R.dimen.space_l)
        val container = LinearLayout(this).apply {
            setPadding(pad, px(R.dimen.space_s), pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_greeting)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.greeting = input.text.toString()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The engine list: the presets, then Custom (with its saved name). A preset is picked at
     * once; Custom opens its two fields, filled with what was saved (and is picked at once if
     * there is a saved one). Nothing touches the network: the URL only opens on a search.
     */
    private fun pickWebEngine() {
        val engines = WebEngine.entries
        val labels = engines.map {
            if (it != WebEngine.CUSTOM) it.label
            else if (prefs.hasCustomEngine) getString(R.string.custom_engine_named, prefs.customEngineName) else getString(R.string.custom_engine)
        }
        engineDialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_web_engine)
            .wrappingSingleChoice(labels, engines.indexOf(prefs.webEngine)) { d, which ->
                val e = engines[which]
                if (e == WebEngine.CUSTOM) {
                    if (prefs.hasCustomEngine) prefs.webEngine = WebEngine.CUSTOM
                    showCustomEngineForm()
                } else {
                    prefs.webEngine = e // the saved custom engine stays, for switching back
                    binding.customEngineForm.isVisible = false
                }
                render()
                d.dismiss()
            }
            .show()
    }

    /** The open engine list (device tests). */
    internal var engineDialog: AlertDialog? = null

    private fun showCustomEngineForm() {
        binding.customEngineName.setText(prefs.customEngineName)
        binding.customEngineUrl.setText(prefs.customEngineUrl)
        binding.customEngineNameError.isVisible = false
        binding.customEngineUrlError.isVisible = false
        binding.customEngineForm.isVisible = true
        binding.customEngineSave.setOnClickListener { saveCustomEngine() }
    }

    /** Checked first: a wrong field gets its error underneath and nothing is saved. */
    private fun saveCustomEngine() {
        val name = binding.customEngineName.text.toString()
        val url = binding.customEngineUrl.text.toString()
        val problem = WebSearchUrl.check(name, url)
        binding.customEngineNameError.isVisible = problem == WebSearchUrl.Problem.NAME_MISSING
        binding.customEngineNameError.setText(R.string.custom_engine_error_name)
        val urlError = when (WebSearchUrl.checkUrl(url)) {
            WebSearchUrl.Problem.NOT_HTTPS -> R.string.custom_engine_error_https
            WebSearchUrl.Problem.NO_PLACEHOLDER -> R.string.custom_engine_error_none
            WebSearchUrl.Problem.MANY_PLACEHOLDERS -> R.string.custom_engine_error_many
            else -> null
        }
        binding.customEngineUrlError.isVisible = urlError != null
        urlError?.let { binding.customEngineUrlError.setText(it) }
        if (problem != null || urlError != null) return
        prefs.saveCustomEngine(name, url)
        binding.customEngineForm.isVisible = false
        render()
    }

    // ------------------------------------------------------------------ Music Player + Quick Actions

    private fun renderTileSettings() {
        val box = binding.tileSettings
        box.removeAllViews()

        box.addView(sectionHeader(R.string.settings_section_music))
        val pkg = prefs.defaultMusicPackage
        val current = when {
            pkg == null -> getString(R.string.music_default_auto)
            MusicApps.isInstalled(this, pkg) -> MusicApps.label(this, pkg)
            else -> getString(R.string.music_default_missing, pkg)
        }
        box.addView(settingsRow(getString(R.string.music_default_player), current) { pickMusicPlayer() })

        box.addView(sectionHeader(R.string.settings_section_quick_actions))
        prefs.quickActionSlots.forEachIndexed { i, action ->
            box.addView(settingsRow(getString(R.string.qa_slot, i + 1), getString(action.label)) { pickQuickAction(i) })
        }
    }

    /** "Automatic" + every installed media app found on the device. */
    private fun pickMusicPlayer() {
        val apps = MusicApps.installed(this)
        val labels = listOf(getString(R.string.music_default_auto)) + apps.map { it.label }
        val checked = apps.indexOfFirst { it.packageName == prefs.defaultMusicPackage }.let { if (it < 0) 0 else it + 1 }
        AlertDialog.Builder(this)
            .setTitle(R.string.music_default_player)
            .wrappingSingleChoice(labels, checked) { d, which ->
                prefs.defaultMusicPackage = if (which == 0) null else apps[which - 1].packageName
                d.dismiss()
                render()
            }
            .apply { if (apps.isEmpty()) setMessage(R.string.music_no_apps) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickQuickAction(slot: Int) {
        val actions = QuickAction.entries
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.qa_slot, slot + 1))
            .wrappingSingleChoice(actions.map { getString(it.label) }, actions.indexOf(prefs.quickActionSlots[slot])) { d, which ->
                prefs.setQuickActionSlot(slot, actions[which])
                d.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sectionHeader(text: Int) = TextView(this, null, 0, R.style.SettingsSection).apply {
        setText(text)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun settingsRow(title: String, summary: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = px(R.dimen.size_row)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(R.dimen.space_s), 0, px(R.dimen.space_s))
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
        addView(TextView(context).apply { text = title; type(Type.BODY); setTextColor(primaryText()) })
        addView(TextView(context).apply { text = summary; type(Type.CAPTION); setTextColor(secondaryText()) })
    }

    // ------------------------------------------------------------------ permissions hub

    /**
     * One row per thing Searchlight can be allowed to do. The switch always shows the real
     * state; tapping it starts the grant flow (or opens Android's page to revoke it).
     */
    private inner class Perm(
        val title: Int,
        val description: Int,
        val granted: () -> Boolean,
        val runtime: Array<String>? = null,
        val openSpecial: (() -> Unit)? = null,
        val comingLater: Boolean = false,
    )

    private fun permissions(): List<Perm> {
        val t33 = Build.VERSION.SDK_INT >= 33
        return listOf(
            Perm(R.string.perm_contacts, R.string.perm_contacts_desc, app.contacts::hasPermission,
                runtime = arrayOf(Manifest.permission.READ_CONTACTS)),
            Perm(R.string.perm_calendar, R.string.perm_calendar_desc, app.calendar::hasPermission,
                runtime = arrayOf(Manifest.permission.READ_CALENDAR)),
            Perm(R.string.perm_messages, R.string.perm_messages_desc, app.messages::hasPermission,
                runtime = arrayOf(Manifest.permission.READ_SMS)),
            Perm(R.string.perm_music, R.string.perm_music_desc, app.media::hasMusicPermission,
                runtime = arrayOf(if (t33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)),
            Perm(R.string.perm_photos, R.string.perm_photos_desc, app.media::hasPhotosPermission,
                runtime = if (t33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)),
            Perm(R.string.perm_files, R.string.perm_files_desc, app.files::hasPermission,
                runtime = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE) else null,
                openSpecial = { Permissions.openAllFilesSettings(this) }),
            Perm(R.string.perm_location, R.string.perm_location_desc, app.weather::hasLocationPermission,
                runtime = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)),
            Perm(R.string.perm_media_controls, R.string.perm_media_controls_desc, { Permissions.hasMediaAccess(this) },
                openSpecial = { Permissions.openNotificationListenerSettings(this) }),
            Perm(R.string.perm_dnd, R.string.perm_dnd_desc, { Permissions.hasDndAccess(this) },
                openSpecial = { Permissions.openDndAccessSettings(this) }),
            Perm(R.string.perm_brightness, R.string.perm_brightness_desc, { Permissions.hasWriteSettings(this) },
                openSpecial = { Permissions.openWriteSettings(this) }),
            Perm(R.string.perm_accessibility, R.string.perm_accessibility_desc, { GestureService.isEnabled(this) },
                openSpecial = { Permissions.openAccessibilitySettings(this) }),
        )
    }

    private fun renderPermissions() {
        val list = binding.permissionsList
        list.removeAllViews()
        for (p in permissions()) {
            val granted = p.granted()
            list.addView(Switch(this).apply {
                setText(p.title)
                type(Type.BODY)
                minHeight = px(R.dimen.size_touch)
                isChecked = granted
                isEnabled = !p.comingLater
                setPadding(0, px(R.dimen.space_s), 0, 0)
                setOnClickListener {
                    isChecked = p.granted() // the switch mirrors reality, not the tap
                    request(p)
                }
            })
            list.addView(TextView(this).apply {
                setText(if (granted && !p.comingLater) R.string.perm_granted_tap_to_change else p.description)
                type(Type.CAPTION)
                setTextColor(secondaryText())
                alpha = if (p.comingLater) 0.5f else 1f
            })
        }
    }

    private fun request(p: Perm) {
        val runtime = p.runtime
        when {
            p.openSpecial != null && runtime == null -> p.openSpecial.invoke()
            runtime == null -> Unit
            p.granted() -> Permissions.openAppDetails(this) // apps can't revoke their own permissions
            runtime.any { prefs.wasAsked(it) } && runtime.none { shouldShowRequestPermissionRationale(it) } ->
                // Denied before: Android won't show the prompt again, so go to the app's page.
                Permissions.openAppDetails(this)
            else -> {
                prefs.markAsked(runtime)
                requestPermissions(runtime, REQUEST_RUNTIME)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        app.startSources()
        render()
    }

    private fun renderIndexStatus() {
        val files = app.files
        val nf = NumberFormat.getIntegerInstance()
        val scanning = files.scanning
        val index = files.index
        binding.indexStatus.text = when {
            !files.hasPermission() -> ""
            scanning != null -> getString(R.string.index_status_scanning, nf.format(scanning))
            index.builtAt == 0L -> getString(R.string.index_status_none)
            else -> getString(
                R.string.index_status_done,
                nf.format(index.entries.size),
                DateUtils.getRelativeTimeSpanString(index.builtAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        }
        binding.indexStatus.visibility = if (files.hasPermission()) android.view.View.VISIBLE else android.view.View.GONE
        binding.reindex.visibility = binding.indexStatus.visibility
        binding.reindex.isEnabled = scanning == null
    }

    // ------------------------------------------------------------------ gestures

    private fun renderGestures() {
        val list = binding.gesturesList
        list.removeAllViews()
        val accessibility = GestureService.isEnabled(this)
        for (g in HomeGesture.entries) {
            val row = ItemGestureRowBinding.inflate(layoutInflater, list, false)
            val action = prefs.gestureAction(g)
            val appPackage = prefs.gestureApp(g)
            val target = appPackage?.let(app.apps::findByPackage)
            row.title.setText(g.title)
            row.summary.text = when {
                action != GestureAction.LAUNCH_APP -> getString(action.label)
                target != null -> getString(R.string.gesture_launch_named, target.label)
                else -> getString(action.label)
            }
            val problem = when {
                action.needsAccessibility && !accessibility -> R.string.gesture_needs_access
                action == GestureAction.LAUNCH_APP && target == null -> R.string.gesture_app_missing
                else -> 0
            }
            row.problem.visibility = if (problem != 0) android.view.View.VISIBLE else android.view.View.GONE
            if (problem != 0) row.problemText.setText(problem)
            row.fix.visibility = if (problem == R.string.gesture_needs_access) android.view.View.VISIBLE else android.view.View.GONE
            row.fix.setOnClickListener { Permissions.openAccessibilitySettings(this) }
            row.root.setOnClickListener { pickGestureAction(g) }
            list.addView(row.root)
        }
    }

    private fun pickGestureAction(g: HomeGesture) {
        val actions = GestureAction.entries
        AlertDialog.Builder(this)
            .setTitle(g.title)
            .wrappingSingleChoice(actions.map { getString(it.label) }, actions.indexOf(prefs.gestureAction(g))) { d, which ->
                d.dismiss()
                val action = actions[which]
                if (action == GestureAction.LAUNCH_APP) {
                    pickGestureApp(g)
                } else {
                    prefs.setGesture(g, action)
                    renderGestures()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Launch App: every app, A–Z; the gesture keeps the package so it survives app updates. */
    private fun pickGestureApp(g: HomeGesture) {
        val apps = app.apps.apps.filter { !it.isSelf }.distinctBy { it.packageName }
        val current = prefs.gestureApp(g)
        AlertDialog.Builder(this)
            .setTitle(R.string.gesture_pick_app)
            .wrappingSingleChoice(apps.map { it.label }, apps.indexOfFirst { it.packageName == current }) { d, which ->
                d.dismiss()
                prefs.setGesture(g, GestureAction.LAUNCH_APP, apps[which].packageName)
                renderGestures()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ favorites

    private fun pickFavoritesMode() {
        val modes = FavoritesMode.entries
        val labels = listOf(
            getString(R.string.favorites_mode_manual) + " — " + getString(R.string.favorites_mode_manual_desc),
            getString(R.string.favorites_mode_automatic) + " — " + getString(R.string.favorites_mode_automatic_desc),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_favorites_mode)
            .wrappingSingleChoice(labels, modes.indexOf(prefs.favoritesMode)) { d, which ->
                d.dismiss()
                val mode = modes[which]
                if (mode == prefs.favoritesMode) return@wrappingSingleChoice
                prefs.favoritesMode = mode
                if (mode == FavoritesMode.AUTOMATIC) {
                    if (app.autoFavorites.hasAccess()) app.autoFavorites.refresh() else askUsageAccess()
                }
            }
            .show()
    }

    private fun askUsageAccess(message: Int = R.string.usage_access_prompt) {
        AlertDialog.Builder(this)
            .setTitle(R.string.usage_access_prompt_title)
            .setMessage(message)
            .setPositiveButton(R.string.usage_access_open) { _, _ -> Permissions.openUsageAccessSettings(this) }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    private fun renderFavorites() {
        val automatic = prefs.favoritesMode == FavoritesMode.AUTOMATIC
        binding.favoritesModeValue.setText(if (automatic) R.string.favorites_mode_automatic else R.string.favorites_mode_manual)
        // The predicted slot is Manual mode's: hidden in Automatic, where all six come from usage.
        val predictedRow = if (automatic) android.view.View.GONE else android.view.View.VISIBLE
        binding.predictedSlotSwitch.visibility = predictedRow
        binding.predictedSlotDesc.visibility = predictedRow
        binding.predictedSlotSwitch.isChecked = prefs.predictedSlot
        val needsUsage = automatic || prefs.predictedSlotActive
        binding.usageAccessRow.visibility =
            if (needsUsage && !app.autoFavorites.hasAccess()) android.view.View.VISIBLE else android.view.View.GONE
        binding.favoritesHelp.setText(if (prefs.autoFavoritesActive) R.string.settings_favorites_help_auto else R.string.settings_favorites_help)

        val list = binding.favoritesList
        list.removeAllViews()
        if (prefs.autoFavoritesActive) {
            // Read-only: the six the home screen shows (installed only), best first.
            prefs.favorites.mapNotNull(app.apps::find).take(Prefs.MAX_FAVORITES).forEach { entry ->
                list.addView(favoriteRow(entry.key))
            }
            return
        }
        val keys = prefs.manualFavorites
        keys.forEachIndexed { index, key ->
            val row = favoriteRow(key)
            val label = row.getChildAt(1) as TextView
            row.addView(button(R.drawable.ic_arrow_up, R.string.move_up, label, enabled = index > 0) {
                prefs.moveFavorite(key, -1)
            })
            row.addView(button(R.drawable.ic_arrow_down, R.string.move_down, label, enabled = index < keys.lastIndex) {
                prefs.moveFavorite(key, +1)
            })
            row.addView(button(R.drawable.ic_close, R.string.remove, label, enabled = true) {
                prefs.removeFavorite(key)
            })
            list.addView(row)
        }
        // A free slot for each favorite missing, each with "+". With the predicted 6th slot on,
        // slot 6 is the prediction's (a manual pick can't go there), so five are offered.
        val capacity = io.github.iamth3doctor.searchlight.apps.FavoriteSlots.capacity(prefs.predictedSlotActive)
        repeat((capacity - keys.size).coerceAtLeast(0)) { list.addView(emptySlotRow()) }
    }

    /** "Empty slot" with a "+": tap either to pick an app for the first free slot. */
    private fun emptySlotRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(R.dimen.size_row)
            tag = FAVORITE_EMPTY_SLOT_TAG
        }
        val dim = secondaryText()
        row.addView(View(this).apply {
            // Dashed ring in the page's own secondary text color (readable light or dark).
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(px(R.dimen.stroke_hairline), dim.defaultColor, pxF(R.dimen.stroke_dash), pxF(R.dimen.stroke_dash))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(px(R.dimen.size_avatar), px(R.dimen.size_avatar))
        })
        val label = TextView(this).apply {
            setText(R.string.settings_empty_slot)
            type(Type.BODY)
            setTextColor(dim)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = px(R.dimen.space_m)
            }
        }
        row.addView(label)
        val add = button(R.drawable.ic_add, R.string.settings_add_favorite, label, enabled = true) { pickFavorite() }
        add.imageTintList = primaryText()
        row.addView(add)
        row.setOnClickListener { pickFavorite() }
        return row
    }

    /** The installed apps that aren't favorites yet; the pick fills the first empty slot. */
    private fun pickFavorite() {
        val collator = java.text.Collator.getInstance()
        val taken = prefs.manualFavorites.toSet()
        val apps = app.apps.apps.filter { !it.isSelf && it.key !in taken }.distinctBy { it.key }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
        favoritePicker = AlertDialog.Builder(this)
            .setTitle(R.string.settings_add_favorite)
            .wrappingItems(apps.map { it.label }) { d, which ->
                d.dismiss()
                prefs.addFavorite(apps[which].key) // the listener re-renders the list
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The app picker last opened from an empty slot (tests). */
    internal var favoritePicker: AlertDialog? = null

    /** Icon + label; the manual list adds its reorder/remove buttons after. */
    private fun favoriteRow(key: String): LinearLayout {
        val entry = app.apps.find(key)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(R.dimen.size_row)
        }
        val label = TextView(this).apply {
            text = entry?.label ?: key.substringAfter('/').substringBefore('/')
            type(Type.BODY)
            setTextColor(primaryText())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = px(R.dimen.space_m)
            }
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(px(R.dimen.size_avatar), px(R.dimen.size_avatar))
            if (entry != null) setImageBitmap(app.apps.icon(entry) { setImageBitmap(it) })
        }
        row.addView(icon)
        row.addView(label)
        return row
    }

    private fun button(icon: Int, desc: Int, tintFrom: TextView, enabled: Boolean, onClick: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = getString(desc)
            imageTintList = tintFrom.textColors
            background = null
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.3f
            layoutParams = LinearLayout.LayoutParams(px(R.dimen.size_touch), px(R.dimen.size_touch))
            setOnClickListener { onClick() }
        }


    // ------------------------------------------------------------------ Focus: allowed and restricted apps

    private fun setupFocusApps() {
        renderFocusApps()
        binding.focusAllowedRow.setOnClickListener { pickFocusApps() }
        binding.focusRestrictedRow.setOnClickListener { pickFocusRestriction() }
    }

    private fun renderFocusApps() {
        val names = prefs.focusAllowlist.mapNotNull { app.apps.findByPackage(it)?.label }.sorted()
        binding.focusAllowedValue.text = if (names.isEmpty()) getString(R.string.focus_allowed_none) else names.joinToString(", ")
        val blocked = prefs.focusRestriction == FocusRules.Restriction.BLOCKED
        binding.focusRestrictedValue.text = getString(if (blocked) R.string.focus_restricted_blocked else R.string.focus_restricted_hidden) +
            " · " + getString(if (blocked) R.string.focus_restricted_blocked_desc else R.string.focus_restricted_hidden_desc)
    }

    /** Which apps stay available in Focus: every app, ticked if allowed. Applies at once (mid-focus too). */
    private fun pickFocusApps() {
        val collator = java.text.Collator.getInstance()
        val apps = app.apps.apps.filter { !it.isSelf }.distinctBy { it.packageName }.sortedWith { a, b -> collator.compare(a.label, b.label) }
        val checked = BooleanArray(apps.size) { apps[it].packageName in prefs.focusAllowlist }
        focusAppsDialog = AlertDialog.Builder(this)
            .setTitle(R.string.focus_allowed_apps)
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.focusAllowlist = apps.filterIndexed { i, _ -> checked[i] }.map { it.packageName }.toSet()
                renderFocusApps()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The open allowlist dialog (device tests). */
    internal var focusAppsDialog: AlertDialog? = null

    private fun pickFocusRestriction() {
        val choices = FocusRules.Restriction.entries
        val labels = choices.map { r ->
            if (r == FocusRules.Restriction.BLOCKED) getString(R.string.focus_restricted_blocked) + "\n" + getString(R.string.focus_restricted_blocked_desc)
            else getString(R.string.focus_restricted_hidden) + "\n" + getString(R.string.focus_restricted_hidden_desc)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.focus_restricted_apps)
            .wrappingSingleChoice(labels, choices.indexOf(prefs.focusRestriction)) { d, which ->
                prefs.focusRestriction = choices[which] // applies at once, mid-focus too
                d.dismiss()
                renderFocusApps()
            }
            .show()
    }

    /** "Glass transparency": moving it redraws the glass here at once; home redraws on return. */
    private fun setupGlassSlider() {
        val slider = binding.glassSlider
        FrostedDrawable.setGlassLevel(prefs.glassLevel)
        slider.max = GlassTint.LEVEL_MAX
        slider.progress = prefs.glassLevel
        showGlassLevel(prefs.glassLevel)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.setGlassLevelByUser(value) // (in Focus, this level then stays after leaving it)
                FrostedDrawable.setGlassLevel(value) // every glass surface redraws, here and on home
                showGlassLevel(value)
            }
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
    }

    private fun showGlassLevel(value: Int) {
        val text = getString(R.string.glass_value, value)
        binding.glassValue.text = text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.glassSlider.stateDescription = "$text, ${getString(R.string.glass_clear)} to ${getString(R.string.glass_solid)}"
        }
    }

    private companion object {
        const val SCREEN = "settings"
        private val MENU = mapOf(
            "home" to CollapsibleSections.Menu(R.drawable.ic_home, R.string.settings_sub_home),
            "appearance" to CollapsibleSections.Menu(R.drawable.ic_palette, R.string.settings_sub_appearance),
            "gestures" to CollapsibleSections.Menu(R.drawable.ic_gesture_hand, R.string.settings_sub_gestures),
            "permissions" to CollapsibleSections.Menu(R.drawable.ic_shield, R.string.settings_sub_permissions),
            "favorites" to CollapsibleSections.Menu(R.drawable.ic_star, R.string.settings_sub_favorites),
            "calendar" to CollapsibleSections.Menu(R.drawable.ic_calendar, R.string.settings_sub_calendar),
            "about" to CollapsibleSections.Menu(R.drawable.ic_info, R.string.settings_sub_about),
        )
        const val REQUEST_HOME_ROLE = 1
        const val REQUEST_RUNTIME = 2
    }
}
