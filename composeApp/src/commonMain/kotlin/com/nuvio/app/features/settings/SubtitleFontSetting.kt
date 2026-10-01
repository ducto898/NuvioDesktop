package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * nuvio-rr fork: the font of plain-text subtitles (SRT, WebVTT; ASS/SSA keep their own fonts). Stored per PC by the
 * Windows app (bound at startup); unbound elsewhere, where the row is hidden. "" = automatic: the best installed of
 * [AUTOMATIC_SUBTITLE_FONTS], else the player's default (mpv's sans-serif = Arial). Applies from the next video.
 */
const val DEFAULT_SUBTITLE_FONT = ""

/**
 * Fonts subtitle enthusiasts recommend, offered when installed (Netflix Sans and Gandhi Sans need installing), as
 * (name given to mpv, installed family that must exist). Netflix Sans's heavier weights go by PostScript name, which
 * libass matches: its Bold file names its family "Netflix Sans " (stray space), so a bold request on "Netflix Sans"
 * doesn't find it, and Medium is a family of its own ("Netflix Sans Med"). Measured live 2026-10-01.
 */
val RECOMMENDED_SUBTITLE_FONTS = listOf(
    "Netflix Sans" to "Netflix Sans",
    "NetflixSans-Medium" to "Netflix Sans Med",
    "NetflixSans-Bold" to "Netflix Sans",
    "Gandhi Sans" to "Gandhi Sans",
    "Segoe UI" to "Segoe UI",
    "Trebuchet MS" to "Trebuchet MS",
    "Verdana" to "Verdana",
    "Tahoma" to "Tahoma",
    "Calibri" to "Calibri",
    "Arial" to "Arial",
)

object SubtitleFontSetting {
    private val state = MutableStateFlow(DEFAULT_SUBTITLE_FONT)
    private var save: (String) -> Unit = {}
    private var installedFonts: () -> List<String> = { emptyList() }

    val available: Boolean
        get() = bound

    private var bound = false

    val font: StateFlow<String>
        get() = state

    fun bind(initial: String, save: (String) -> Unit, installedFonts: () -> List<String>) {
        state.value = initial
        this.save = save
        this.installedFonts = installedFonts
        bound = true
    }

    fun set(font: String) {
        state.value = font
        save(font)
    }

    /** The choices for the dialog: [subtitleFontChoices] over the fonts installed on this PC. */
    fun choices(): List<String> =
        subtitleFontChoices(runCatching { installedFonts() }.getOrDefault(emptyList()), state.value)
}

/**
 * The dialog's fonts: the default (""), then the [RECOMMENDED_SUBTITLE_FONTS] that are [installed] (matched
 * ignoring case, shown with the installed spelling), then the [current] choice if it is neither (e.g. uninstalled).
 */
fun subtitleFontChoices(installed: List<String>, current: String): List<String> {
    // Trimmed: some fonts name their family with a stray space (Netflix Sans Bold: "Netflix Sans "), which Java keeps.
    val names = installed.map { it.trim() }
    val recommended = RECOMMENDED_SUBTITLE_FONTS.mapNotNull { (mpvName, family) ->
        val installedFamily = names.firstOrNull { it.equals(family, ignoreCase = true) } ?: return@mapNotNull null
        // A plain family keeps the installed spelling; a PostScript name is given as is.
        if (mpvName.equals(family, ignoreCase = true)) installedFamily else mpvName
    }
    val offered = listOf(DEFAULT_SUBTITLE_FONT) + recommended
    val chosen = current.trim()
    val keepCurrent = chosen.isNotEmpty() && offered.none { it.equals(chosen, ignoreCase = true) }
    return if (keepCurrent) offered + chosen else offered
}

/** The font for [choice] ("" = automatic: the best installed); null = none fits, the player's default (Arial). */
fun resolveSubtitleFont(choice: String, installed: List<String>): String? {
    if (choice.isNotBlank()) return choice.trim()
    val names = installed.map { it.trim() }
    return AUTOMATIC_SUBTITLE_FONTS.firstOrNull { (_, family) -> names.any { it.equals(family, ignoreCase = true) } }?.first
}

/** Automatic's order (name given to mpv, installed family that must exist): medium weights read best on video. */
private val AUTOMATIC_SUBTITLE_FONTS = listOf(
    "NetflixSans-Medium" to "Netflix Sans Med",
    "Gandhi Sans" to "Gandhi Sans",
    "Segoe UI Semibold" to "Segoe UI Semibold",
)

/**
 * The fork's look for plain-text subtitles (owner: "optimise every aspect", 2026-10-01), at mpv's 720p scale: a 2.0
 * outline (mpv's 1.65 thins out on bright scenes), a soft half-black shadow 1.0 below-right and a 0.3 edge blur, the
 * streaming-service look. Colours, size and position stay with the app's subtitle style; ASS/SSA keep their own.
 */
fun subtitleMpvOptions(choice: String, installed: List<String>): List<Pair<String, String>> =
    subtitleFontMpvOptions(resolveSubtitleFont(choice, installed) ?: DEFAULT_SUBTITLE_FONT) + listOf(
        "sub-border-size" to "2.0",
        "sub-shadow-offset" to "1.0",
        "sub-shadow-color" to "#80000000",
        "sub-blur" to "0.3",
    )

/** mpv's sub-font for [font]; none for the default or a name that can't be passed as one option line. */
fun subtitleFontMpvOptions(font: String): List<Pair<String, String>> {
    val name = font.trim()
    if (name.isEmpty() || '\n' in name || '\r' in name) return emptyList()
    return listOf("sub-font" to name)
}
