package io.github.iamth3doctor.searchlight.iconpack

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.searchlight
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.edgeToEdge
import io.github.iamth3doctor.searchlight.ui.isLightTheme
import io.github.iamth3doctor.searchlight.ui.padForSystemBars
import io.github.iamth3doctor.searchlight.ui.primaryText
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.secondaryText
import io.github.iamth3doctor.searchlight.ui.type
import java.util.concurrent.Executors

/**
 * Settings > Appearance > Icon pack: "System default" and every installed pack, each with its
 * name and icon. Tapping one applies it straight away; a pack that won't load says so and the
 * previous choice stays.
 */
class IconPackActivity : Activity() {

    private val packs get() = searchlight.iconPacks
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private lateinit var list: LinearLayout
    private var busy = false
    private val packsChanged: () -> Unit = { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = px(R.dimen.space_m)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad)
            addView(TextView(context, null, 0, R.style.PageTitle).apply { setText(R.string.icon_pack_title) })
            addView(TextView(context).apply {
                setText(R.string.icon_pack_help)
                type(Type.CAPTION)
                setTextColor(secondaryText())
            })
            addView(list)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        edgeToEdge(lightBackground = isLightTheme())
        scroll.padForSystemBars()
        packs.addListener(packsChanged)
        load()
    }

    override fun onDestroy() {
        packs.removeListener(packsChanged)
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        loader.execute {
            val installed = packs.installedPacks()
            main.post { if (!isFinishing) render(installed) }
        }
    }

    private fun render(installed: List<IconPacks.Info>) {
        list.removeAllViews()
        list.addView(row(getString(R.string.icon_pack_system), getDrawable(R.drawable.ic_launcher), null))
        installed.forEach { list.addView(row(it.label, it.icon, it.packageName)) }
        if (installed.isEmpty()) list.addView(TextView(this).apply {
            setText(R.string.icon_pack_none_installed)
            type(Type.CAPTION)
            setTextColor(secondaryText())
            setPadding(0, px(R.dimen.space_s), 0, 0)
        })
    }

    private fun row(label: CharSequence, icon: android.graphics.drawable.Drawable?, pkg: String?): View {
        val size = px(R.dimen.size_avatar)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(R.dimen.size_row)
            setBackgroundResource(selectable())
            tag = pkg
            addView(ImageView(context).apply {
                setImageDrawable(icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(size, size))
            addView(TextView(context).apply {
                text = label
                type(Type.BODY)
                setTextColor(primaryText())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(R.dimen.space_m) })
            addView(RadioButton(context).apply {
                isChecked = pkg == packs.selected
                isClickable = false
                isFocusable = false
            })
            contentDescription = label
            setOnClickListener { choose(pkg, label) }
        }
    }

    private fun choose(pkg: String?, label: CharSequence) {
        if (busy || pkg == packs.selected) return
        busy = true
        packs.select(pkg) { ok ->
            busy = false
            if (!ok) Toast.makeText(this, getString(R.string.icon_pack_failed, label), Toast.LENGTH_LONG).show()
            // Tick whichever is in use now (the previous one, if this one failed).
            for (i in 0 until list.childCount) {
                val r = list.getChildAt(i) as? LinearLayout ?: continue
                (r.getChildAt(2) as? RadioButton)?.isChecked = r.tag == packs.selected
            }
        }
    }

    private fun selectable(): Int {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        try {
            return a.getResourceId(0, 0)
        } finally {
            a.recycle()
        }
    }
}
