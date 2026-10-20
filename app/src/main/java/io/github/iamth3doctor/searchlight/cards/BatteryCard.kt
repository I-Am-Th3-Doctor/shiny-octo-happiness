package io.github.iamth3doctor.searchlight.cards

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.color

/** Battery %, charging state, Wi-Fi and cellular signal. Signals are polled while visible. */
class BatteryCard(activity: Activity) : HomeCard(CardType.BATTERY, activity) {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var percent: TextView
    private lateinit var status: TextView
    private lateinit var wifi: TextView
    private lateinit var cell: TextView
    private var batteryIntent: Intent? = null

    override val layout = R.layout.card_battery

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            batteryIntent = intent
            render()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 10_000)
        }
    }

    override fun bind(root: View) {
        percent = root.findViewById(R.id.batteryPercent)
        status = root.findViewById(R.id.batteryStatus)
        wifi = root.findViewById(R.id.wifiSignal)
        cell = root.findViewById(R.id.cellSignal)
        root.findViewById<View>(R.id.card).setOnClickListener {
            try {
                activity.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            } catch (e: Exception) { /* no battery screen on this build */ }
        }
    }

    override fun onStart() {
        batteryIntent = ContextCompat.registerReceiver(
            activity, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        main.post(poll)
        super.onStart()
    }

    override fun onStop() {
        try { activity.unregisterReceiver(batteryReceiver) } catch (e: Exception) { /* not registered */ }
        main.removeCallbacks(poll)
        super.onStop()
    }

    override fun draw() {
        val i = batteryIntent
        if (i != null) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            percent.text = if (pct >= 0) "$pct%" else "--"
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val state = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            status.text = when {
                state == BatteryManager.BATTERY_STATUS_FULL || (plugged && pct >= 100) -> activity.getString(R.string.battery_full)
                plugged -> chargingText()
                else -> activity.getString(R.string.battery_discharging)
            }
        }
        renderSignals()
    }

    private fun chargingText(): String {
        val bm = activity.getSystemService(BatteryManager::class.java)
        val remaining = bm?.computeChargeTimeRemaining() ?: -1
        if (remaining <= 0) return activity.getString(R.string.battery_charging)
        val minutes = (remaining / 60_000).toInt()
        val pretty = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        return activity.getString(R.string.battery_charging_eta, pretty)
    }

    @Suppress("DEPRECATION") // allNetworks / calculateSignalLevel: simplest way to read Wi-Fi RSSI on API 29+.
    private fun renderSignals() {
        val cm = activity.getSystemService(ConnectivityManager::class.java)
        val wm = activity.applicationContext.getSystemService(WifiManager::class.java)
        val wifiCaps = cm?.allNetworks?.mapNotNull { cm.getNetworkCapabilities(it) }
            ?.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        wifi.text = when {
            wifiCaps != null -> {
                val rssi = wifiCaps.signalStrength
                val lvl = if (rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) 4 else WifiManager.calculateSignalLevel(rssi, 5)
                labelWithBars(activity.getString(R.string.signal_wifi), lvl)
            }
            wm?.isWifiEnabled == true -> activity.getString(R.string.signal_wifi_disconnected)
            else -> activity.getString(R.string.signal_wifi_off)
        }

        val tm = activity.getSystemService(TelephonyManager::class.java)
        cell.text = try {
            when {
                tm == null || tm.simState != TelephonyManager.SIM_STATE_READY -> activity.getString(R.string.signal_no_sim)
                else -> {
                    val name = tm.networkOperatorName.ifBlank { activity.getString(R.string.signal_cell) }
                    labelWithBars(name, tm.signalStrength?.level ?: 0)
                }
            }
        } catch (e: SecurityException) {
            activity.getString(R.string.signal_cell)
        }
    }

    /** "Wi-Fi ▂▄▆█" with unfilled bars dimmed. Levels are 0..4. */
    private fun labelWithBars(label: String, level: Int): CharSequence {
        val bars = "▂▄▆█"
        val sb = SpannableStringBuilder("$label  ")
        val start = sb.length
        sb.append(bars)
        val filled = level.coerceIn(0, 4)
        if (filled < 4) {
            sb.setSpan(ForegroundColorSpan(activity.color(R.color.on_surface_faint)), start + filled, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
}
