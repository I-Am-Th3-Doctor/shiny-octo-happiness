package io.github.iamth3doctor.searchlight.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.databinding.ItemWidgetProviderBinding
import io.github.iamth3doctor.searchlight.ui.edgeToEdge
import io.github.iamth3doctor.searchlight.ui.isLightTheme
import io.github.iamth3doctor.searchlight.ui.padForSystemBars
import io.github.iamth3doctor.searchlight.ui.px
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Every widget from every installed app (personal and work profile), with its preview; or only
 * one app's, when opened from that app's long-press menu ([EXTRA_FILTER_PACKAGE] and
 * [EXTRA_FILTER_USER]). Plain on purpose; visual polish is Phase 4. Returns the chosen provider
 * to the home screen.
 */
class WidgetPickerActivity : Activity() {

    private class Row(val info: AppWidgetProviderInfo, val appLabel: String, val label: String, val size: String) {
        val key = "${info.provider.flattenToString()}#${info.profile.hashCode()}"
        val haystack = "$appLabel $label".lowercase()
    }

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)
    private val previews = HashMap<String, Drawable?>()
    private var all: List<Row> = emptyList()
    private val adapter = Adapter()
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = px(R.dimen.space_m)
        val filter = EditText(this).apply {
            setHint(R.string.widget_picker_filter)
            setSingleLine()
        }
        empty = TextView(this).apply {
            setText(R.string.widget_picker_loading)
            setPadding(0, pad, 0, pad)
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@WidgetPickerActivity)
            adapter = this@WidgetPickerActivity.adapter
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(
                TextView(context, null, 0, R.style.PageTitle).apply { setText(R.string.widget_picker_title) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(filter)
            addView(empty)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        edgeToEdge(lightBackground = isLightTheme())
        root.padForSystemBars()
        filter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        loadProviders()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun loadProviders() {
        loader.execute {
            val manager = AppWidgetManager.getInstance(this)
            val pm = packageManager
            val density = resources.displayMetrics.density
            val onlyPackage = intent.getStringExtra(EXTRA_FILTER_PACKAGE)
            val onlyUser = intent.getParcelableExtraCompat<UserHandle>(EXTRA_FILTER_USER)
            val rows = getSystemService(UserManager::class.java).userProfiles.flatMap { profile ->
                if (onlyPackage == null) manager.getInstalledProvidersForProfile(profile)
                else if (onlyUser == null || onlyUser == profile) manager.getInstalledProvidersForPackage(onlyPackage, profile)
                else emptyList()
            }.map { info ->
                val appLabel = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
                } catch (e: Exception) {
                    info.provider.packageName
                }
                Row(info, appLabel, info.loadLabel(pm) ?: appLabel, sizeLabel(info, density))
            }.sortedWith(compareBy<Row, String>(String.CASE_INSENSITIVE_ORDER) { it.appLabel }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            main.post {
                if (isFinishing) return@post
                all = rows
                applyFilter("")
            }
        }
    }

    private fun applyFilter(q: String) {
        val needle = q.trim().lowercase()
        val shown = if (needle.isEmpty()) all else all.filter { it.haystack.contains(needle) }
        adapter.submit(shown)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(if (all.isEmpty()) R.string.widget_picker_none else R.string.widget_picker_no_match)
    }

    /** "3 × 2" in launcher cells (as the widget declares it, or estimated from its minimum size). */
    private fun sizeLabel(info: AppWidgetProviderInfo, density: Float): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return "${info.targetCellWidth} × ${info.targetCellHeight}"
        }
        fun cells(px: Int) = (((px / density) + 30) / 70).roundToInt().coerceAtLeast(1)
        return "${cells(info.minWidth)} × ${cells(info.minHeight)}"
    }

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(key)

    private fun choose(row: Row) {
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_PROVIDER, row.info.provider)
            .putExtra(EXTRA_PROFILE, row.info.profile))
        finish()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        private var rows: List<Row> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Row>) {
            rows = list
            notifyDataSetChanged()
        }

        inner class Holder(val b: ItemWidgetProviderBinding) : RecyclerView.ViewHolder(b.root) {
            var row: Row? = null
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val h = Holder(ItemWidgetProviderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            h.b.root.setOnClickListener { h.row?.let(::choose) }
            return h
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val row = rows[position]
            h.row = row
            h.b.title.text = row.label
            h.b.subtitle.text = getString(R.string.widget_picker_row, row.appLabel, row.size)
            if (previews.containsKey(row.key)) {
                h.b.preview.setImageDrawable(previews[row.key])
                return
            }
            h.b.preview.setImageDrawable(null)
            val dpi = resources.displayMetrics.densityDpi
            loader.execute {
                val d = try {
                    row.info.loadPreviewImage(this@WidgetPickerActivity, dpi) ?: row.info.loadIcon(this@WidgetPickerActivity, dpi)
                } catch (e: Exception) {
                    null
                }
                main.post {
                    previews[row.key] = d
                    if (h.row === row) h.b.preview.setImageDrawable(d)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_FILTER_PACKAGE = "filter_package"
        const val EXTRA_FILTER_USER = "filter_user"
    }
}
