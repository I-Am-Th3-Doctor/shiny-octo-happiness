package io.github.iamth3doctor.searchlight.cards

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Permissions

/** Circular toggles: torch, volume, Do Not Disturb, brightness. */
class QuickActionsCard(activity: Activity) : HomeCard(CardType.QUICK_ACTIONS, activity) {

    private val main = Handler(Looper.getMainLooper())
    private val camera = activity.getSystemService(CameraManager::class.java)
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val notifications = activity.getSystemService(NotificationManager::class.java)
    private val torchId: String? = try {
        camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (e: Exception) {
        null
    }
    private var torchOn = false

    private class Toggle(val circle: View, val icon: ImageView, val label: TextView)
    private lateinit var torch: Toggle
    private lateinit var volume: Toggle
    private lateinit var dnd: Toggle
    private lateinit var brightness: Toggle

    override val layout = R.layout.card_quick_actions

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) {
                torchOn = enabled
                render()
            }
        }
    }

    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = render()
    }

    override fun bind(root: View) {
        fun toggle(id: Int, iconRes: Int): Toggle {
            val cell = root.findViewById<LinearLayout>(id)
            val t = Toggle(cell.getChildAt(0), (cell.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as ImageView, cell.getChildAt(1) as TextView)
            t.icon.setImageResource(iconRes)
            return t
        }
        torch = toggle(R.id.qaTorch, R.drawable.ic_flashlight)
        volume = toggle(R.id.qaVolume, R.drawable.ic_volume)
        dnd = toggle(R.id.qaDnd, R.drawable.ic_dnd)
        brightness = toggle(R.id.qaBrightness, R.drawable.ic_brightness)

        torch.circle.setOnClickListener { toggleTorch() }
        volume.circle.setOnClickListener {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            main.postDelayed({ render() }, 1500)
        }
        dnd.circle.setOnClickListener { toggleDnd() }
        brightness.circle.setOnClickListener { showBrightness() }
    }

    override fun onStart() {
        torchId?.let { camera.registerTorchCallback(torchCallback, main) }
        ContextCompat.registerReceiver(
            activity, dndReceiver, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        super.onStart()
    }

    override fun onStop() {
        torchId?.let { camera.unregisterTorchCallback(torchCallback) }
        try { activity.unregisterReceiver(dndReceiver) } catch (e: Exception) { /* not registered */ }
        super.onStop()
    }

    override fun render() {
        setState(torch, torchOn, activity.getString(R.string.qa_torch))
        torch.circle.alpha = if (torchId == null) 0.4f else 1f

        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        setState(volume, vol > 0, activity.getString(R.string.qa_volume_pct, vol))

        val dndOn = notifications.currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
        setState(dnd, dndOn, activity.getString(R.string.qa_dnd))

        val auto = Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val level = Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        setState(
            brightness, true,
            if (auto) activity.getString(R.string.qa_brightness_auto) else activity.getString(R.string.qa_brightness_pct, level * 100 / 255),
        )
    }

    private fun setState(t: Toggle, on: Boolean, label: String) {
        t.circle.backgroundTintList = ColorStateList.valueOf(if (on) ON_COLOR else OFF_COLOR)
        t.icon.imageTintList = ColorStateList.valueOf(if (on) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt())
        t.label.text = label
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
        if (!notifications.isNotificationPolicyAccessGranted) {
            Toast.makeText(activity, R.string.qa_dnd_needs_access, Toast.LENGTH_LONG).show()
            Permissions.openDndAccessSettings(activity)
            return
        }
        val on = notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        notifications.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        )
        render()
    }

    private fun showBrightness() {
        if (!Settings.System.canWrite(activity)) {
            Toast.makeText(activity, R.string.qa_brightness_needs_access, Toast.LENGTH_LONG).show()
            Permissions.openWriteSettings(activity)
            return
        }
        val resolver = activity.contentResolver
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val autoSwitch = Switch(activity).apply {
            setText(R.string.qa_brightness_auto)
            isChecked = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }
        val seek = SeekBar(activity).apply {
            max = 254
            progress = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128) - 1
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
    }
}
