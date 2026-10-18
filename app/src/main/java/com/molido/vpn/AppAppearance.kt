package com.molido.vpn

import android.content.Context
import android.graphics.Color

/**
 * The Orbit palettes — one dark, one light, chosen by the user.
 *
 * History matters here. The colour picker used to offer five palettes plus a
 * "Dynamic" mode that inherited the phone's Material colours, and that was
 * removed on purpose: the approved design is a specific look, and letting the OS
 * repaint it produced washed-out greys and light surfaces the layout was never
 * designed for.
 *
 * This is not that. There are exactly two palettes, both drawn from an approved
 * mock, and nothing about them is derived from the phone's wallpaper:
 *   - [ORBIT]     — the original dark glass + neon console.
 *   - [PORCELAIN] — a light palette: grey canvas, white raised cards.
 *
 * ## Why a light palette is more than swapping ink for canvas
 *
 * The dark look builds depth out of a white specular highlight on the top edge
 * of every surface. On a white card that highlight is invisible, so a naive
 * inversion produces flat white rectangles floating on flat white. Depth on
 * light has to come from the opposite direction: a shadow below the surface, and
 * a card that is *lighter* than the page rather than darker.
 *
 * That inversion lives in [Sculpt.Lighting], carried on the palette, so every
 * surface in the app changes lighting model together instead of each call site
 * guessing. [load] installs it before any drawing happens.
 *
 * ## Text vs graphics accents
 *
 * `#4FE3C1` mint reads at 11:1 on the dark card and 3.4:1 on white — fine for a
 * dial arc, below the 4.5:1 floor for the letters of a label. So every accent
 * has a `…Text` sibling. On [ORBIT] the two are the same colour; on [PORCELAIN]
 * the text sibling is darkened until it clears 4.5:1 on both the card and the
 * canvas. Shapes keep the vivid colour, which is what makes the light theme
 * still look like MolidoVPN instead of a generic white app.
 */
object AppAppearance {

    /** Which palette the user picked. Persisted in the shared "settings" store. */
    enum class Mode(val key: String, val enLabel: String, val description: String) {
        DARK("dark", "Dark", "Night — turquoise on deep navy"),
        LIGHT("light", "Light — theme", "Turquoise on mist — white cards"),
        ;

        /** Localized at call time so a language switch refreshes the label. */
        val label: String get() = Strings.t(enLabel)

        companion object {
            fun from(key: String?): Mode = entries.firstOrNull { it.key == key } ?: LIGHT
        }
    }

    const val PREF_KEY = "theme_mode"

    data class Palette(
        /** page background — mock `--void` */
        val canvas: Int,
        /** raised card fill */
        val surface: Int,
        /** recessed / secondary card fill */
        val surfaceVariant: Int,
        /** primary text — mock `--ink` */
        val ink: Int,
        /** secondary text — mock `--dim` */
        val muted: Int,
        /** hairline borders — mock `--line` */
        val divider: Int,
        /** the brand accent — mock `--mint` */
        val primary: Int,
        /**
         * Text and glyphs drawn on top of a surface filled with [primary] — the
         * Save/Done/Reset buttons.
         *
         * Dark on both palettes, which is not an accident: [primary] is a mid-to-
         * bright teal in both, so a light label on it never reaches 4.5:1 while a
         * dark one clears it comfortably.
         */
        val primaryContainer: Int,
        /**
         * Background of a row that is currently selected — a protocol option, a
         * split-tunnel app, a picker entry.
         *
         * Split out from [primaryContainer] because the dark palette could use one
         * value for both and the light palette cannot. Dark: `#04070B`, the canvas,
         * so a selected row reads as a recess with a mint border. Light: a pale
         * mint wash, because filling a selected row with near-black ink on a white
         * page would hide the ink-coloured label sitting on it.
         */
        val selectedSurface: Int,
        /** the "tunnel is up" accent — mock `--neon` */
        val connected: Int,
        val connectedContainer: Int,
        /** tertiary text — mock `--faint` */
        val faint: Int,
        /** download accent — mock `--mint` */
        val mint: Int,
        /** upload accent — mock `--violet` */
        val violet: Int,
        /** in-progress / speed accent — mock `--amber` */
        val amber: Int,
        /** failure accent — mock `--danger` */
        val danger: Int,
        /**
         * Accents again, dark enough to be *read as letters* on this palette's
         * surfaces. Identical to the vivid values on a dark palette, where the
         * vivid values already clear 4.5:1.
         */
        val primaryText: Int = primary,
        val connectedText: Int = connected,
        val mintText: Int = mint,
        val violetText: Int = violet,
        val amberText: Int = amber,
        val dangerText: Int = danger,
        /** Failure text on the connection headline. */
        val error: Int = danger,
        /** How sculpted surfaces are lit on this palette. */
        val lighting: Sculpt.Lighting = Sculpt.DARK_LIGHTING,
    )

    /** MolidoVPN dark: deep teal-black page, teal accent, green "connected". */
    val ORBIT = Palette(
        // «Firoozeh & Shab» dark: night #0C1222 page, firoozeh #2DD4BF accent.
        canvas = 0xFF0C1222.toInt(),
        // v2: opaque night card (was 4% white glass, which rendered as grey chrome on some views).
        surface = 0xFF141D33.toInt(),
        surfaceVariant = 0xFF1C2640.toInt(),
        ink = 0xFFEEF2FA.toInt(),
        muted = 0xFF9AA6C0.toInt(),
        divider = 0x14FFFFFF,
        primary = 0xFF2DD4BF.toInt(),
        primaryContainer = 0xFF0C1222.toInt(),
        selectedSurface = 0xFF0C1222.toInt(),
        connected = 0xFF2DD4BF.toInt(),
        connectedContainer = 0xFF0E2F33.toInt(),
        faint = 0xFF66728F.toInt(),
        mint = 0xFF2DD4BF.toInt(),
        violet = 0xFF7088F2.toInt(),
        amber = 0xFFF4A93B.toInt(),
        danger = 0xFFE5484D.toInt(),
        error = 0xFFF2878A.toInt(),
        lighting = Sculpt.DARK_LIGHTING,
    )

    /**
     * MolidoVPN light: pale mint page, white cards with a 1dp border.
     * Vivid accents are for shapes; the `…Text` siblings clear 4.5:1 on white
     * and on the page.
     */
    val PORCELAIN = Palette(
        // «Firoozeh & Shab» light: mist #F6F8FB page, white cards, firoozeh #12A594 accent.
        canvas = 0xFFF6F8FB.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceVariant = 0xFFEEF2F7.toInt(),
        ink = 0xFF0F172A.toInt(),
        muted = 0xFF5B6478.toInt(),
        divider = 0xFFE2E8F0.toInt(),
        primary = 0xFF12A594.toInt(),
        primaryContainer = 0xFF0F172A.toInt(),
        selectedSurface = 0xFFE8FAF7.toInt(),
        connected = 0xFF0B8577.toInt(),
        connectedContainer = 0xFFC6F2EA.toInt(),
        faint = 0xFF6B7690.toInt(),
        mint = 0xFF12A594.toInt(),
        violet = 0xFF4F6BED.toInt(),
        amber = 0xFFD98B1E.toInt(),
        danger = 0xFFE5484D.toInt(),
        primaryText = 0xFF0B7A6E.toInt(),
        connectedText = 0xFF0B7A6E.toInt(),
        mintText = 0xFF0B7A6E.toInt(),
        violetText = 0xFF3B55D9.toInt(),
        amberText = 0xFF92400E.toInt(),
        dangerText = 0xFFC9363B.toInt(),
        error = 0xFFC9363B.toInt(),
        lighting = Sculpt.LIGHT_LIGHTING,
    )

    /**
     * Dark («Shab») and light («Firoozeh») are both offered again (v2 design). The stored value is
     * `dark`, `light` or `system`; with nothing stored the app follows the system night mode.
     */
    fun mode(context: Context): Mode {
        val stored = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(PREF_KEY, PREF_SYSTEM)
        return when (stored) {
            Mode.DARK.key -> Mode.DARK
            Mode.LIGHT.key -> Mode.LIGHT
            else -> if (systemIsNight(context)) Mode.DARK else Mode.LIGHT
        }
    }

    const val PREF_SYSTEM = "system"

    /** The raw preference including "system", for the settings row. */
    fun storedPref(context: Context): String =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(PREF_KEY, PREF_SYSTEM) ?: PREF_SYSTEM

    fun setPref(context: Context, value: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(PREF_KEY, value).apply()
    }

    private fun systemIsNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun setMode(context: Context, mode: Mode) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY, mode.key)
            .apply()
    }

    fun palette(mode: Mode): Palette = when (mode) {
        Mode.DARK -> ORBIT
        Mode.LIGHT -> PORCELAIN
    }

    /**
     * The palette for this session, and the only place [Sculpt.lighting] is set.
     *
     * Sculpt's drawing helpers are called from ~26 sites that do not hold a
     * palette (a settings row knows its own colours, not the app's lighting
     * model). Installing the lighting here means every one of those calls is
     * correct by construction, because nothing in the app can obtain a palette
     * without going through this function first.
     */
    fun load(context: Context): Palette = palette(mode(context)).also {
        Sculpt.lighting = it.lighting
    }

    /** Callers use this to pick system-bar icon colour and XML theme. */
    fun isNight(context: Context): Boolean = mode(context) == Mode.DARK

    /** Perceived brightness test, used where only a colour is in hand. */
    fun isDark(color: Int): Boolean =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 < 140
}
