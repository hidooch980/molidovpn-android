package com.molido.vpn.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.molido.vpn.AppAppearance
import com.molido.vpn.Strings
import com.molido.vpn.ui.state.SmartConnectState
import com.molido.vpn.ui.state.SmartConnectState.Phase
import com.molido.vpn.ui.state.SmartConnectState.Status

/**
 * Smart Connect's live view on the home screen (v2 design): methods light up while they are
 * tested, the leader is named, then a winner card with the reason and the warm runner-up, and
 * live speed / ping once connected. Pure rendering of [SmartConnectState.UiState]; holds no logic.
 */
class SmartConnectPanel(
    context: Context,
    private val palette: AppAppearance.Palette,
    private val regular: Typeface,
    private val bold: Typeface,
    private val numeric: Typeface,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density + 0.5f).toInt()

    private val title = text(15f, palette.ink, bold)
    private val subtitle = text(12.5f, palette.muted, regular)
    private val progressTrack = LinearLayout(context)
    private val progressFill = View(context)
    private val progressRest = View(context)
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }
    private val leader = text(12f, palette.muted, regular)
    private val winnerCard = LinearLayout(context).apply { orientation = VERTICAL }
    private val winnerTitle = text(15f, palette.ink, bold)
    private val winnerReason = text(12f, palette.muted, regular)
    private val runnerLine = text(12f, palette.muted, regular)
    private val stats = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val statDown = stat()
    private val statUp = stat()
    private val statPing = stat()
    private val pulses = ArrayList<ValueAnimator>()

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        visibility = View.GONE
        addView(title)
        addView(subtitle, lp().apply { topMargin = dp(2) })
        progressTrack.background = round(palette.divider, 99)
        progressFill.background = round(palette.primary, 99)
        progressTrack.orientation = HORIZONTAL
        progressTrack.addView(progressFill, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
        progressTrack.addView(progressRest, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100f))
        addView(progressTrack, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(10) })
        addView(grid, lp().apply { topMargin = dp(12) })
        addView(leader, lp().apply { topMargin = dp(10) })

        winnerCard.background = card()
        winnerCard.setPadding(dp(16), dp(14), dp(16), dp(14))
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = text(20f, palette.primaryContainer, bold).apply {
            text = "✓"
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(palette.primary, palette.connected))
                .apply { cornerRadius = dp(15).toFloat() }
        }
        head.addView(badge, LayoutParams(dp(46), dp(46)))
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(winnerTitle)
        col.addView(winnerReason, lp().apply { topMargin = dp(2) })
        head.addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        winnerCard.addView(head)
        val sep = View(context).apply { setBackgroundColor(palette.divider) }
        winnerCard.addView(sep, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(12) })
        winnerCard.addView(runnerLine, lp().apply { topMargin = dp(10) })
        addView(winnerCard, lp().apply { topMargin = dp(14) })

        listOf(statDown, statUp, statPing).forEachIndexed { i, v ->
            stats.addView(v, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        addView(stats, lp().apply { topMargin = dp(10) })
    }

    fun render(s: SmartConnectState.UiState) {
        pulses.forEach { it.cancel() }
        pulses.clear()
        if (s.phase == Phase.IDLE || s.methods.isEmpty()) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        title.text = Strings.t("Smart connect")
        subtitle.text = when (s.phase) {
            Phase.PROBING -> Strings.t("Checking which methods can reach out…")
            Phase.TESTING -> Strings.tf("%s of %s methods tested", s.tested, s.methods.size)
            Phase.CONNECTING -> Strings.t("Connecting with the best…")
            Phase.CONNECTED -> Strings.t("Connected with the best method")
            Phase.FAILED -> Strings.t("No method worked on this network")
            Phase.IDLE -> ""
        } + if (s.network.isNotEmpty()) "  ·  " + s.network else ""
        val testing = s.phase == Phase.PROBING || s.phase == Phase.TESTING
        progressTrack.visibility = if (testing) View.VISIBLE else View.GONE
        (progressFill.layoutParams as LayoutParams).weight = s.progress.toFloat()
        (progressRest.layoutParams as LayoutParams).weight = (100 - s.progress).toFloat()
        progressTrack.requestLayout()

        grid.removeAllViews()
        grid.visibility = if (s.winner == null || testing) View.VISIBLE else View.GONE
        s.methods.chunked(3).forEach { row ->
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            row.forEachIndexed { i, m ->
                line.addView(chip(m, m.id == s.leader), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                })
            }
            repeat(3 - row.size) { line.addView(View(context), LayoutParams(0, 1, 1f).apply { marginStart = dp(8) }) }
            grid.addView(line, lp().apply { topMargin = if (grid.childCount > 0) dp(8) else 0 })
        }

        val lead = s.label(s.leader)
        leader.visibility = if (testing && lead != null) View.VISIBLE else View.GONE
        leader.text = Strings.tf("Leading: %s", lead ?: "") +
            (s.methods.firstOrNull { it.id == s.leader }?.score?.let { "  ·  " + Strings.tf("score %s", it) } ?: "")

        val win = s.label(s.winner)
        winnerCard.visibility = if (win != null && !testing) View.VISIBLE else View.GONE
        winnerTitle.text = "${win.orEmpty()}  ·  ${Strings.t("Winner")}"
        winnerReason.text = s.winnerReason + "  ·  " + Strings.tf("out of %s methods", s.methods.size)
        val runner = s.label(s.runnerUp)
        runnerLine.text = if (runner != null) {
            Strings.tf("Ready as backup: %s", runner) + (s.runnerUpScore?.let { " ($it)" } ?: "") + "  ·  " + Strings.t("instant failover")
        } else {
            Strings.t("No backup method on this network")
        }

        stats.visibility = if (s.phase == Phase.CONNECTED) View.VISIBLE else View.GONE
        setStat(statDown, s.downKbps?.let { fmtMbps(it) } ?: "—", Strings.t("Download Mb/s"))
        setStat(statUp, s.upKbps?.let { fmtMbps(it) } ?: "—", Strings.t("Upload Mb/s"))
        setStat(statPing, s.pingMs?.toString() ?: "—", Strings.t("Ping ms"))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulses.forEach { it.cancel() }
        pulses.clear()
    }

    // ------------------------------------------------------------------ pieces

    private fun chip(m: SmartConnectState.MethodState, leading: Boolean): View {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        val active = m.status == Status.TESTING || m.status == Status.PROBING
        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (leading) blend(palette.primary, 0.16f) else palette.surface)
            setStroke(dp(1), if (active || leading) palette.primary else palette.divider)
        }
        box.background = bg
        val name = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(when (m.status) {
                    Status.OK, Status.TESTING, Status.PROBING -> palette.primary
                    Status.FAILED -> palette.danger
                    else -> palette.faint
                })
            }
        }
        name.addView(dot, LayoutParams(dp(7), dp(7)))
        name.addView(text(12f, palette.ink, bold).apply { text = m.label; ellipsize = TextUtils.TruncateAt.END; maxLines = 2 },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        box.addView(name)
        val detail = when (m.status) {
            Status.QUEUED -> m.detail.ifEmpty { Strings.t("queued") }
            Status.PROBING -> Strings.t("probing…")
            Status.TESTING -> Strings.t("testing…")
            Status.OK -> listOfNotNull(m.latencyMs?.let { "${it}ms" }, m.score?.let { Strings.tf("score %s", it) }).joinToString(" · ")
            Status.FAILED -> Strings.t("failed")
            Status.SKIPPED -> Strings.t("not needed")
        }
        box.addView(text(11f, if (m.status == Status.OK) palette.primaryText else palette.muted, numeric).apply {
            text = detail
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp().apply { topMargin = dp(3) })
        box.alpha = when (m.status) {
            Status.FAILED, Status.SKIPPED -> 0.45f
            else -> 1f
        }
        if (active) {
            // The "lighting up" effect: the border glow breathes while the method is under test.
            pulses += ValueAnimator.ofFloat(0.35f, 1f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    val a = it.animatedValue as Float
                    bg.setStroke(dp(2), blend(palette.primary, a))
                    bg.setColor(blend(palette.primary, 0.06f + 0.10f * a))
                }
                start()
            }
        }
        return box
    }

    private fun stat(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(10), dp(8), dp(10))
        background = card()
        addView(text(19f, palette.ink, numeric).apply { gravity = Gravity.CENTER })
        addView(text(10.5f, palette.muted, regular).apply { gravity = Gravity.CENTER })
    }

    private fun setStat(v: LinearLayout, value: String, label: String) {
        (v.getChildAt(0) as TextView).text = value
        (v.getChildAt(1) as TextView).text = label
    }

    private fun fmtMbps(kbps: Long) = String.format(java.util.Locale.US, "%.1f", kbps / 1000.0)

    private fun card() = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(palette.surface)
        setStroke(dp(1), palette.divider)
    }

    private fun round(color: Int, r: Int) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat()
        setColor(color)
    }

    /** [color] at [alpha] (0..1). */
    private fun blend(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun text(size: Float, color: Int, face: Typeface) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = face
        includeFontPadding = false
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun lp() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
