/**
 * Molido Mesh AI's two coordinated visual palettes.
 *
 * - [ORBIT] is Mesh Night: deep ink surfaces with teal and violet intelligence accents.
 * - [PORCELAIN] is Mesh Mist: cool pale canvas, crisp cards and accessible teal-violet accents.
 *
 * The mesh background is a low-contrast visual layer only. Real network state and
 * measurements are always rendered by the connection and diagnostics components.
 *
 * Surface lighting remains palette-aware through [Sculpt.Lighting], so cards and
 * controls keep consistent depth in both dark and light modes. Text-safe accent
 * siblings are used wherever a vivid shape colour would not meet readable contrast.
 */
object AppAppearance {

    /** Which palette the user picked. Persisted in the shared "settings" store. */
    enum class Mode(val key: String, val enLabel: String, val description: String) {
        DARK("dark", "Dark", "Mesh Night — teal and violet"),
        LIGHT("light", "Light — theme", "Mesh Mist — cool light surfaces"),
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
        // New Molido Mesh AI identity: deep ink canvas, layered blue surfaces and living teal.
        canvas = 0xFF080D1B.toInt(),
        surface = 0xFF111A2D.toInt(),
        surfaceVariant = 0xFF192640.toInt(),
        ink = 0xFFF2F6FF.toInt(),
        muted = 0xFFA3B0C8.toInt(),
        divider = 0x263B5272,
        primary = 0xFF43E6CF.toInt(),
        primaryContainer = 0xFF071521.toInt(),
        selectedSurface = 0xFF0B1D2B.toInt(),
        connected = 0xFF52E6A6.toInt(),
        connectedContainer = 0xFF0D332F.toInt(),
        faint = 0xFF71819F.toInt(),
        mint = 0xFF43E6CF.toInt(),
        violet = 0xFF8B82FF.toInt(),
        amber = 0xFFFFC36B.toInt(),
        danger = 0xFFFF6B82.toInt(),
        error = 0xFFFF9AA8.toInt(),
        lighting = Sculpt.DARK_LIGHTING,
    )

    /**
     * MolidoVPN light: pale mint page, white cards with a 1dp border.
     * Vivid accents are for shapes; the `…Text` siblings clear 4.5:1 on white
     * and on the page.
     */
    val PORCELAIN = Palette(
        // Bright counterpart to the new mesh identity: cool mist, crisp white and teal-violet accents.
        canvas = 0xFFF1F5FC.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceVariant = 0xFFE8EEF8.toInt(),
        ink = 0xFF111B31.toInt(),
        muted = 0xFF596982.toInt(),
        divider = 0xFFD9E2F0.toInt(),
        primary = 0xFF087F79.toInt(),
        primaryContainer = 0xFF101A30.toInt(),
        selectedSurface = 0xFFE1F7F3.toInt(),
        connected = 0xFF087D65.toInt(),
        connectedContainer = 0xFFD0F5E8.toInt(),
        faint = 0xFF71819B.toInt(),
        mint = 0xFF087F79.toInt(),
        violet = 0xFF6559D9.toInt(),
        amber = 0xFFB66B13.toInt(),
        danger = 0xFFC93650.toInt(),
        primaryText = 0xFF076B66.toInt(),
        connectedText = 0xFF076B58.toInt(),
        mintText = 0xFF076B66.toInt(),
        violetText = 0xFF5145C1.toInt(),
        amberText = 0xFF8A4B08.toInt(),
        dangerText = 0xFFB52340.toInt(),
        error = 0xFFB52340.toInt(),
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
