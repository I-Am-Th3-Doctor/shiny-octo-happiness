package io.github.iamth3doctor.searchlight

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.iamth3doctor.searchlight.data.WebEngine
import io.github.iamth3doctor.searchlight.databinding.ActivitySettingsBinding
import java.text.NumberFormat

/** Settings + the permissions hub. Phase 2 adds the Widget Center; Phase 4 adds looks and gestures. */
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
        // Switches reflect real permission state; tapping one starts the grant/revoke flow.
        binding.permContacts.setOnClickListener { toggleContacts() }
        binding.permFiles.setOnClickListener { toggleFiles() }
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
        app.contacts.start()
        app.files.start()
        render()
    }

    private fun render() {
        binding.setDefault.setText(if (isDefaultHome()) R.string.settings_is_default else R.string.settings_set_default)
        binding.greetingValue.text = prefs.greeting.ifEmpty { getString(R.string.settings_greeting_auto) }
        binding.webEngineValue.text = prefs.webEngine.label

        val contacts = app.contacts.hasPermission()
        binding.permContacts.isChecked = contacts
        binding.permContactsDesc.text = getString(if (contacts) R.string.perm_granted_tap_to_change else R.string.perm_contacts_desc)

        val files = app.files.hasPermission()
        binding.permFiles.isChecked = files
        binding.permFilesDesc.text = getString(if (files) R.string.perm_granted_tap_to_change else R.string.perm_files_desc)
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

    private fun openAppDetails() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun toggleContacts() {
        binding.permContacts.isChecked = app.contacts.hasPermission()
        if (app.contacts.hasPermission()) {
            // Apps can't revoke their own permissions; send you to the place where you can.
            openAppDetails()
            return
        }
        val permanentlyDenied = prefs.contactsPermissionAsked &&
            !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)
        if (permanentlyDenied) {
            openAppDetails()
        } else {
            prefs.contactsPermissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
        }
    }

    private fun toggleFiles() {
        binding.permFiles.isChecked = app.files.hasPermission()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else if (app.files.hasPermission()) {
            openAppDetails()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_FILES)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        app.contacts.start()
        app.files.start()
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
        const val REQUEST_CONTACTS = 2
        const val REQUEST_FILES = 3
    }
}
