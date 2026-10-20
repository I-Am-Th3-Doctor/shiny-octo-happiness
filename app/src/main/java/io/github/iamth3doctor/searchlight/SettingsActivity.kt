package io.github.iamth3doctor.searchlight

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.iamth3doctor.searchlight.databinding.ActivitySettingsBinding

class SettingsActivity : Activity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs get() = searchlight.prefs
    private val repo get() = searchlight.apps
    private val favoritesChanged: () -> Unit = { renderFavorites() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.setDefault.setOnClickListener { requestDefaultHome() }
        binding.autoKeyboard.isChecked = prefs.autoOpenKeyboard
        binding.autoKeyboard.setOnCheckedChangeListener { _, checked -> prefs.autoOpenKeyboard = checked }
        binding.about.text = getString(R.string.settings_about, BuildConfig.VERSION_NAME)

        prefs.addFavoritesListener(favoritesChanged)
        repo.addListener(favoritesChanged)
    }

    override fun onDestroy() {
        prefs.removeFavoritesListener(favoritesChanged)
        repo.removeListener(favoritesChanged)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        binding.setDefault.setText(if (isDefaultHome()) R.string.settings_is_default else R.string.settings_set_default)
        renderFavorites()
    }

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
            val entry = repo.find(key)
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
                if (entry != null) setImageBitmap(repo.icon(entry) { setImageBitmap(it) })
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
    }
}
