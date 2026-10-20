package io.github.iamth3doctor.searchlight

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.TextView
import io.github.iamth3doctor.searchlight.data.WebEngine
import io.github.iamth3doctor.searchlight.databinding.ActivitySettingsBinding
import io.github.iamth3doctor.searchlight.ui.Permissions
import java.text.NumberFormat

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

        binding.setDefault.setOnClickListener { requestDefaultHome() }
        binding.autoKeyboard.isChecked = prefs.autoOpenKeyboard
        binding.autoKeyboard.setOnCheckedChangeListener { _, checked -> prefs.autoOpenKeyboard = checked }
        binding.greetingRow.setOnClickListener { editGreeting() }
        binding.webEngineRow.setOnClickListener { pickWebEngine() }
        binding.widgetCenterRow.setOnClickListener { startActivity(Intent(this, WidgetCenterActivity::class.java)) }
        binding.reindex.setOnClickListener { app.files.rescan() }
        binding.about.text = getString(R.string.settings_about, BuildConfig.VERSION_NAME)

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
        render()
    }

    private fun render() {
        binding.setDefault.setText(if (isDefaultHome()) R.string.settings_is_default else R.string.settings_set_default)
        binding.greetingValue.text = prefs.greeting.ifEmpty { getString(R.string.settings_greeting_auto) }
        binding.webEngineValue.text = prefs.webEngine.label

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
        val pad = dp(20)
        val container = LinearLayout(this).apply {
            setPadding(pad, dp(8), pad, 0)
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

    private fun pickWebEngine() {
        val engines = WebEngine.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_web_engine)
            .setSingleChoiceItems(engines.map { it.label }.toTypedArray(), engines.indexOf(prefs.webEngine)) { d, which ->
                prefs.webEngine = engines[which]
                render()
                d.dismiss()
            }
            .show()
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
            Perm(R.string.perm_accessibility, R.string.perm_accessibility_desc, { false }, comingLater = true),
        )
    }

    private fun renderPermissions() {
        val list = binding.permissionsList
        list.removeAllViews()
        for (p in permissions()) {
            val granted = p.granted()
            list.addView(Switch(this).apply {
                setText(p.title)
                textSize = 16f
                minHeight = dp(48)
                isChecked = granted
                isEnabled = !p.comingLater
                setPadding(0, dp(8), 0, 0)
                setOnClickListener {
                    isChecked = p.granted() // the switch mirrors reality, not the tap
                    request(p)
                }
            })
            list.addView(TextView(this).apply {
                setText(if (granted && !p.comingLater) R.string.perm_granted_tap_to_change else p.description)
                textSize = 13f
                alpha = if (p.comingLater) 0.5f else 0.75f
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

    // ------------------------------------------------------------------ favorites

    private fun renderFavorites() {
        val list = binding.favoritesList
        list.removeAllViews()
        val keys = prefs.favorites
        if (keys.isEmpty()) {
            list.addView(TextView(this).apply {
                setText(R.string.settings_no_favorites)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        keys.forEachIndexed { index, key ->
            val entry = app.apps.find(key)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
            }
            val label = TextView(this).apply {
                text = entry?.label ?: key.substringAfter('/').substringBefore('/')
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(16)
                }
            }
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                if (entry != null) setImageBitmap(app.apps.icon(entry) { setImageBitmap(it) })
            }
            row.addView(icon)
            row.addView(label)
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
    }

    private fun button(icon: Int, desc: Int, tintFrom: TextView, enabled: Boolean, onClick: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = getString(desc)
            imageTintList = tintFrom.textColors
            background = null
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.3f
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_HOME_ROLE = 1
        const val REQUEST_RUNTIME = 2
    }
}
