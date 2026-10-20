package io.github.iamth3doctor.searchlight.cards

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.searchlight
import io.github.iamth3doctor.searchlight.ui.Permissions

/**
 * Four circular toggles, rendered from the slot config saved in Settings → Quick Actions.
 *
 * Android's limits, handled rather than faked:
 * - Wi-Fi, Bluetooth and airplane mode can't be toggled by normal apps: tapping opens the
 *   system panel / page for them, and the circle shows their real state.
 * - Brightness and rotation lock need "Modify system settings": asked on first use.
 * - Silent ringer mode needs Do Not Disturb access; without it the ringer cycles normal ↔ vibrate.
 */
class QuickActionsCard(activity: Activity) : HomeCard(CardType.QUICK_ACTIONS, activity) {

    private val main = Handler(Looper.getMainLooper())
    private val prefs = activity.searchlight.prefs
    private val camera = activity.getSystemService(CameraManager::class.java)
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val notifications = activity.getSystemService(NotificationManager::class.java)
    private val wifi = activity.applicationContext.getSystemService(WifiManager::class.java)
    private val bluetooth = activity.getSystemService(BluetoothManager::class.java)?.adapter
    private val torchId: String? = try {
        camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (e: Exception) {
        null
    }
    private var torchOn = false

    private class Slot(val circle: View, val icon: ImageView, val label: TextView)
    private lateinit var slots: List<Slot>

    private class State(val on: Boolean, val label: String, val icon: Int, val available: Boolean = true)

    override val layout = R.layout.card_quick_actions

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) {
                torchOn = enabled
                render()
            }
        }
    }

    /** DND, ringer, airplane mode: re-draw when the system says they changed. */
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = render()
    }

    override fun bind(root: View) {
        slots = listOf(R.id.qaSlot0, R.id.qaSlot1, R.id.qaSlot2, R.id.qaSlot3).mapIndexed { index, id ->
            val cell = root.findViewById<LinearLayout>(id)
            val circle = cell.getChildAt(0) as ViewGroup
            Slot(circle, circle.getChildAt(0) as ImageView, cell.getChildAt(1) as TextView).also { slot ->
                // Read the config at tap time, so a change in Settings applies immediately.
                slot.circle.setOnClickListener { perform(prefs.quickActionSlots[index]) }
            }
        }
    }

    override fun onStart() {
        torchId?.let { camera.registerTorchCallback(torchCallback, main) }
        val filter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(activity, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        super.onStart()
    }

    override fun onStop() {
        torchId?.let { camera.unregisterTorchCallback(torchCallback) }
        try { activity.unregisterReceiver(systemReceiver) } catch (e: Exception) { /* not registered */ }
        super.onStop()
    }

    override fun draw() {
        prefs.quickActionSlots.forEachIndexed { i, action ->
            val slot = slots.getOrNull(i) ?: return@forEachIndexed
            val st = stateOf(action)
            slot.icon.setImageResource(st.icon)
            slot.label.text = st.label
            slot.circle.contentDescription = st.label
            slot.circle.backgroundTintList = ColorStateList.valueOf(if (st.on) ON_COLOR else OFF_COLOR)
            slot.icon.imageTintList = ColorStateList.valueOf(if (st.on) ON_ICON else OFF_ICON)
            slot.circle.alpha = if (st.available) 1f else 0.4f
        }
    }

    // ------------------------------------------------------------------ state

    private fun str(id: Int, vararg args: Any) = activity.getString(id, *args)

    private fun stateOf(a: QuickAction): State = when (a) {
        QuickAction.TORCH -> State(torchOn, str(R.string.qa_torch), a.icon, available = torchId != null)
        QuickAction.WIFI -> State(wifi?.isWifiEnabled == true, str(R.string.qa_wifi), a.icon)
        QuickAction.BLUETOOTH -> State(
            try { bluetooth?.isEnabled == true } catch (e: SecurityException) { false },
            str(R.string.qa_bluetooth), a.icon, available = bluetooth != null,
        )
        QuickAction.DND -> State(dndOn(), str(R.string.qa_dnd), a.icon)
        QuickAction.BRIGHTNESS -> {
            val auto = system(Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            val level = system(Settings.System.SCREEN_BRIGHTNESS, 128)
            State(true, if (auto) str(R.string.qa_brightness_auto) else str(R.string.qa_brightness_pct, level * 100 / 255), a.icon)
        }
        QuickAction.ROTATION_LOCK -> {
            val autoRotate = system(Settings.System.ACCELEROMETER_ROTATION, 1) == 1
            State(!autoRotate, str(if (autoRotate) R.string.qa_rotation_auto else R.string.qa_rotation_locked), a.icon)
        }
        QuickAction.RINGER -> when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> State(true, str(R.string.qa_ringer_silent), R.drawable.ic_ringer_silent)
            AudioManager.RINGER_MODE_VIBRATE -> State(true, str(R.string.qa_ringer_vibrate), R.drawable.ic_vibrate)
            else -> State(false, str(R.string.qa_ringer_normal), a.icon)
        }
        QuickAction.AIRPLANE -> State(
            Settings.Global.getInt(activity.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
            str(R.string.qa_airplane), a.icon,
        )
        QuickAction.VOLUME -> {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
            State(vol > 0, str(R.string.qa_volume_pct, vol), a.icon)
        }
    }

    private fun system(key: String, default: Int) = Settings.System.getInt(activity.contentResolver, key, default)

    private fun dndOn() = notifications.currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    // ------------------------------------------------------------------ actions

    private fun perform(a: QuickAction) {
        when (a) {
            QuickAction.TORCH -> toggleTorch()
            // Since Android 10, apps may not switch Wi-Fi themselves: use the system panel.
            QuickAction.WIFI -> open(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS))
            // Toggling Bluetooth directly is restricted too (and needs extra permissions on 12+).
            QuickAction.BLUETOOTH -> open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            QuickAction.DND -> toggleDnd()
            QuickAction.BRIGHTNESS -> withWriteSettings { showBrightness() }
            QuickAction.ROTATION_LOCK -> withWriteSettings {
                val autoRotate = system(Settings.System.ACCELEROMETER_ROTATION, 1) == 1
                Settings.System.putInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (autoRotate) 0 else 1)
                render()
            }
            QuickAction.RINGER -> cycleRinger()
            // Airplane mode is system-only; the connectivity panel has its switch.
            QuickAction.AIRPLANE -> open(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            QuickAction.VOLUME -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                main.postDelayed({ render() }, 1500)
            }
        }
    }

    private fun open(intent: Intent, fallback: Intent? = null) {
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            fallback?.let { runCatching { activity.startActivity(it) } }
        }
    }

    /** Runs [block] if we may modify system settings; otherwise asks for it (first use). */
    private fun withWriteSettings(block: () -> Unit) {
        if (Permissions.hasWriteSettings(activity)) {
            block()
        } else {
            Toast.makeText(activity, R.string.qa_needs_write_settings, Toast.LENGTH_LONG).show()
            Permissions.openWriteSettings(activity)
        }
    }

    private fun toggleTorch() {
        val id = torchId ?: return
        try {
            camera.setTorchMode(id, !torchOn)
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.qa_torch_busy, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleDnd() {
        if (!Permissions.hasDndAccess(activity)) {
            Toast.makeText(activity, R.string.qa_dnd_needs_access, Toast.LENGTH_LONG).show()
            Permissions.openDndAccessSettings(activity)
            return
        }
        notifications.setInterruptionFilter(
            if (dndOn()) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        )
        render()
    }

    /** Normal → vibrate → silent → normal. Silent needs DND access; without it, skip it. */
    private fun cycleRinger() {
        val next = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE ->
                if (Permissions.hasDndAccess(activity)) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        try {
            audio.ringerMode = next
        } catch (e: SecurityException) {
            audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        }
        render()
    }

    private fun showBrightness() {
        val resolver = activity.contentResolver
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val autoSwitch = Switch(activity).apply {
            setText(R.string.qa_brightness_auto)
            isChecked = system(Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }
        val seek = SeekBar(activity).apply {
            max = 254
            progress = system(Settings.System.SCREEN_BRIGHTNESS, 128) - 1
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                autoSwitch.isChecked = false
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value + 1)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            Settings.System.putInt(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (checked) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(seek)
            addView(autoSwitch)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.qa_brightness)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { render() }
            .show()
    }

    private companion object {
        const val ON_COLOR = 0xFFE8EAF6.toInt()
        const val OFF_COLOR = 0x33FFFFFF
        const val ON_ICON = 0xFF1C1C1E.toInt()
        const val OFF_ICON = 0xFFFFFFFF.toInt()
    }
}
