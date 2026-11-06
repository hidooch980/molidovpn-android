package com.molido.vpn

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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

/**
 * Searchable premium navigation drawer. Entries still invoke the exact actions
 * supplied by MainActivity; this class only owns presentation and filtering.
 */
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
            textSize = if (strong) 15.5f else 12.5f
            setTextColor(if (strong) palette.ink else palette.muted)
            typeface = if (strong) Typefaces.medium(activity) else Typefaces.regular(activity)
        }

    private fun cardBackground() = Sculpt.sculptedBackground(
        activity.resources.displayMetrics.density,
        palette.surface,
        15,
        stroke = Sculpt.withAlpha(palette.divider, 0.8f),
    )

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (AppLanguage.isRtl())
                View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(palette.canvas)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }

        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(12))
        }
        val mark = TextView(activity).apply {
            text = "M"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typefaces.extraBold(activity)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(palette.primary, palette.violet),
            ).apply { cornerRadius = dp(15).toFloat() }
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(46), dp(46)))
        val brand = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("MOLIDO MESH AI", true).apply {
                textSize = 16f
                letterSpacing = 0.07f
            })
            addView(label("SECURE NETWORK  ·  $version").apply {
                textSize = 9.5f
                letterSpacing = 0.035f
                setPadding(0, dp(2), 0, 0)
            })
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(11)
        })
        header.addView(label("×", true).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            contentDescription = Strings.t("Close")
            isFocusable = true
            background = cardBackground()
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(header)

        val search = EditText(activity).apply {
            hint = Strings.t("Search menus")
            textSize = 14f
            setSingleLine(true)
            setTextColor(palette.ink)
            setHintTextColor(palette.muted)
            typeface = Typefaces.regular(activity)
            background = cardBackground()
            setPadding(dp(14), 0, dp(14), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(50)).apply {
            bottomMargin = dp(10)
        })

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(8))
        }
        root.addView(ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(label("MolidoVPN  ·  $version").apply {
            gravity = Gravity.CENTER
            textSize = 10f
            setPadding(0, dp(10), 0, 0)
        })

        fun normalize(s: String) = s.lowercase(Locale.ROOT)
            .replace('ي', 'ی').replace('ك', 'ک').trim()

        val expandedGroups = mutableSetOf<String>()

        fun render(query: String) {
            list.removeAllViews()
            val q = normalize(query)
            val matches = entries.filter {
                normalize(it.title + " " + it.detail + " " + it.group).contains(q)
            }
            matches.groupBy { it.group }.forEach { (group, items) ->
                val expanded = q.isNotEmpty() || group in expandedGroups
                val section = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    background = cardBackground()
                    isClickable = true
                    isFocusable = true
                    addView(label("✦  $group", true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(label(if (expanded) "−" else "+", true).apply {
                        textSize = 19f
                        gravity = Gravity.CENTER
                        setTextColor(palette.primaryText)
                    }, LinearLayout.LayoutParams(dp(28), dp(32)))
                    setOnClickListener {
                        if (group in expandedGroups) expandedGroups.remove(group)
                        else expandedGroups.add(group)
                        render(query)
                    }
                }
                list.addView(section, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(7)
                })
                if (expanded) items.forEach { entry ->
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(58)
                        setPadding(dp(14), dp(10), dp(12), dp(10))
                        background = cardBackground()
                        isClickable = true
                        isFocusable = true
                        contentDescription = entry.title
                        setOnClickListener {
                            dismiss()
                            entry.action()
                        }
                    }
                    val copy = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(label(entry.title, true))
                        if (entry.detail.isNotEmpty()) {
                            addView(label(entry.detail).apply {
                                setPadding(0, dp(3), 0, 0)
                            })
                        }
                    }
                    row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(label("›", true).apply {
                        textSize = 22f
                        setTextColor(palette.primaryText)
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(dp(24), dp(32)))
                    list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                        bottomMargin = dp(7)
                        marginStart = dp(12)
                    })
                }
            }
            if (matches.isEmpty()) {
                list.addView(label(Strings.t("No matching menu")).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(28), 0, dp(28))
                })
            }
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
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setGravity(Gravity.END)
        setCanceledOnTouchOutside(true)
        setOnShowListener {
            window?.setLayout(
                minOf(dp(380), (activity.resources.displayMetrics.widthPixels * .92f).toInt()),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            root.isFocusableInTouchMode = true
            root.requestFocus()
        }
    }
}
