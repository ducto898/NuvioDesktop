package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * nuvio-rr fork: the font of plain-text subtitles (SRT, WebVTT; ASS/SSA keep their own fonts). Stored per PC by the
 * Windows app (bound at startup); unbound elsewhere, where the row is hidden. "" = the player's default (mpv's
 * sans-serif, which libass maps to Arial on Windows). Applies from the next video.
 */
const val DEFAULT_SUBTITLE_FONT = ""

/** Fonts subtitle enthusiasts recommend, offered when installed (Netflix Sans and Gandhi Sans need installing). */
val RECOMMENDED_SUBTITLE_FONTS = listOf(
    "Netflix Sans", "Gandhi Sans", "Segoe UI", "Trebuchet MS", "Verdana", "Tahoma", "Calibri", "Arial",
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
fun subtitleFontChoices(installed: List<String>, current: String): List<String> = TODO("nuvio-rr fork: subtitle font")

/** mpv's sub-font for [font]; none for the default or a name that can't be passed as one option line. */
fun subtitleFontMpvOptions(font: String): List<Pair<String, String>> = TODO("nuvio-rr fork: subtitle font")
