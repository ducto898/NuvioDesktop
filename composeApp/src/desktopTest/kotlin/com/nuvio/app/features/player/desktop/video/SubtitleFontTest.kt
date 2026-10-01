package com.nuvio.app.features.player.desktop.video

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.settings.DEFAULT_SUBTITLE_FONT
import com.nuvio.app.features.settings.resolveSubtitleFont
import com.nuvio.app.features.settings.subtitleFontChoices
import com.nuvio.app.features.settings.subtitleFontMpvOptions
import com.nuvio.app.features.settings.subtitleMpvOptions
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// nuvio-rr fork: subtitle font
class SubtitleFontTest {
    private val dir = Files.createTempDirectory("nuvio-rr-subfont")
    private val file = dir.resolve("$SUBTITLE_FONT_STORE.properties")

    @AfterTest
    fun tearDown() {
        file.deleteIfExists()
        dir.deleteIfExists()
    }

    @Test
    fun `the default sends no option, a chosen font sets sub-font`() {
        assertEquals(emptyList(), subtitleFontMpvOptions(DEFAULT_SUBTITLE_FONT))
        assertEquals(listOf("sub-font" to "Netflix Sans"), subtitleFontMpvOptions("Netflix Sans"))
        assertEquals(listOf("sub-font" to "Segoe UI"), subtitleFontMpvOptions("  Segoe UI "), "trimmed")
    }

    @Test
    fun `automatic picks the best installed font, in the enthusiasts' order`() {
        val all = listOf("Arial", "Gandhi Sans", "Netflix Sans ", "Netflix Sans Med", "Segoe UI Semibold")
        assertEquals("NetflixSans-Medium", resolveSubtitleFont(DEFAULT_SUBTITLE_FONT, all))
        assertEquals("Gandhi Sans", resolveSubtitleFont(DEFAULT_SUBTITLE_FONT, listOf("Arial", "Gandhi Sans", "Segoe UI Semibold")))
        assertEquals("Segoe UI Semibold", resolveSubtitleFont(DEFAULT_SUBTITLE_FONT, listOf("Arial", "segoe ui semibold")))
        assertEquals(null, resolveSubtitleFont(DEFAULT_SUBTITLE_FONT, listOf("Arial")), "none: the player's Arial")
        assertEquals("Verdana", resolveSubtitleFont("Verdana", all), "a chosen font wins")
    }

    @Test
    fun `the subtitle look is the font, then outline 2, a soft half-black shadow and a slight edge blur`() {
        assertEquals(
            listOf(
                "sub-font" to "NetflixSans-Medium",
                "sub-border-size" to "2.0",
                "sub-shadow-offset" to "1.0",
                "sub-shadow-color" to "#80000000",
                "sub-blur" to "0.3",
            ),
            subtitleMpvOptions(DEFAULT_SUBTITLE_FONT, listOf("Netflix Sans Med")),
        )
        assertEquals("sub-border-size", subtitleMpvOptions(DEFAULT_SUBTITLE_FONT, emptyList()).first().first, "no font line without one")
    }

    @Test
    fun `the desktop subtitle size default is 15 (45 at mpv's 720p scale)`() {
        assertEquals(15, SubtitleStyleState.DEFAULT.fontSizeSp)
    }

    @Test
    fun `names that would break the option line are refused`() {
        assertEquals(emptyList(), subtitleFontMpvOptions("   "))
        assertEquals(emptyList(), subtitleFontMpvOptions("Evil\nscale=bilinear"))
        assertEquals(emptyList(), subtitleFontMpvOptions("Evil\rFont"))
    }

    @Test
    fun `choices are the default, the installed recommended fonts in order, with the installed spelling`() {
        val installed = listOf("Arial", "Calibri", "Comic Sans MS", "netflix sans", "Segoe UI", "Verdana", "Wingdings")
        assertEquals(
            listOf(DEFAULT_SUBTITLE_FONT, "netflix sans", "NetflixSans-Bold", "Segoe UI", "Verdana", "Calibri", "Arial"),
            subtitleFontChoices(installed, current = DEFAULT_SUBTITLE_FONT),
        )
    }

    @Test
    fun `family names with stray spaces still match, offered trimmed (Java lists Netflix Sans as 'Netflix Sans ')`() {
        assertEquals(
            listOf(DEFAULT_SUBTITLE_FONT, "Netflix Sans", "NetflixSans-Medium", "NetflixSans-Bold", "Segoe UI"),
            subtitleFontChoices(listOf("Netflix Sans ", "Netflix Sans Light", "Netflix Sans Med", "Segoe UI"), DEFAULT_SUBTITLE_FONT),
        )
        assertEquals(
            listOf(DEFAULT_SUBTITLE_FONT, "Netflix Sans", "NetflixSans-Bold"),
            subtitleFontChoices(listOf("Netflix Sans "), current = "Netflix Sans"),
            "the stored choice is the same font, not listed twice",
        )
    }

    @Test
    fun `Netflix Sans weights are offered by PostScript name, which libass matches (its Bold family name has a stray space)`() {
        assertEquals(
            listOf(DEFAULT_SUBTITLE_FONT, "Netflix Sans", "NetflixSans-Medium", "NetflixSans-Bold"),
            subtitleFontChoices(listOf("Netflix Sans ", "Netflix Sans Med"), DEFAULT_SUBTITLE_FONT),
        )
        assertEquals(listOf("sub-font" to "NetflixSans-Bold"), subtitleFontMpvOptions("NetflixSans-Bold"))
    }

    @Test
    fun `a current font that is not offered stays selectable`() {
        assertEquals(
            listOf(DEFAULT_SUBTITLE_FONT, "Arial", "Gandhi Sans"),
            subtitleFontChoices(listOf("Arial"), current = "Gandhi Sans"),
            "uninstalled since it was chosen",
        )
        assertEquals(listOf(DEFAULT_SUBTITLE_FONT, "Arial"), subtitleFontChoices(listOf("Arial"), current = "arial"))
    }

    @Test
    fun `store round trip, missing file gives the default`() {
        val preference = SubtitleFontPreference(DesktopStorage.Store(file))
        assertEquals(DEFAULT_SUBTITLE_FONT, preference.load())
        preference.save("Netflix Sans")
        assertEquals("Netflix Sans", SubtitleFontPreference(DesktopStorage.Store(file)).load())
        preference.save(DEFAULT_SUBTITLE_FONT)
        assertEquals(DEFAULT_SUBTITLE_FONT, SubtitleFontPreference(DesktopStorage.Store(file)).load())
    }
}
