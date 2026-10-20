package com.molido.vpn

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MolidoNavigationDrawer(
    private val activity: Activity,
    private val palette: AppAppearance.Palette,
    version: String,
    private val entries: List<Entry>
) : Dialog(activity) {
    data class Entry(
        val group: String,
        val title: String,
        val detail: String = "",
        val action: () -> Unit
    )

    private fun dp(n: Int) =
        (n * activity.resources.displayMetrics.density + .5f).toInt()

    private fun label(value: String, strong: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = if (strong) 16f else 13f
            setTextColor(if (strong) palette.ink else palette.muted)
            typeface = Typefaces.regular(activity)
        }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (AppLanguage.current() == "fa")
                View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(palette.canvas)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("MOLIDO VPN", true),
            LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(label("×", true).apply {
            gravity = Gravity.CENTER
            contentDescription = Strings.t("Close")
            isFocusable = true
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        val search = EditText(activity).apply {
            hint = Strings.t("Search menus")
            textSize = 16f
            setSingleLine(true)
            setTextColor(palette.ink)
            setHintTextColor(palette.muted)
            typeface = Typefaces.regular(activity)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(52)))
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(ScrollView(activity).apply { addView(list) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("MolidoVPN · $version").apply {
            gravity = Gravity.CENTER
        })

        fun normalize(s: String) = s.lowercase(Locale.ROOT)
            .replace('ي', 'ی').replace('ك', 'ک').trim()

        fun render(query: String) {
            list.removeAllViews()
            val q = normalize(query)
            val matches = entries.filter {
                normalize(it.title + " " + it.detail + " " + it.group).contains(q)
            }
            matches.groupBy { it.group }.forEach { (group, items) ->
                list.addView(label(group).apply {
                    setPadding(dp(8), dp(20), dp(8), dp(8))
                })
                items.forEach { entry ->
                    list.addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        minimumHeight = dp(64)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        isFocusable = true
                        addView(label(entry.title, true))
                        if (entry.detail.isNotEmpty())
                            addView(label(entry.detail))
                        setOnClickListener {
                            dismiss()
                            entry.action()
                        }
                    }, LinearLayout.LayoutParams(-1,
                        ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
            if (matches.isEmpty())
                list.addView(label(Strings.t("No matching menu")))
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        render("")
        setContentView(root)
        window?.setBackgroundDrawable(ColorDrawable(palette.canvas))
        window?.setGravity(Gravity.RIGHT)
        setOnShowListener {
            window?.setLayout(
                minOf(dp(360), (activity.resources.displayMetrics.widthPixels * .9f).toInt()),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            root.isFocusableInTouchMode = true
            root.requestFocus()
        }
    }
}
