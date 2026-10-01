package com.nuvio.app.features.player.desktop.video

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.settings.VideoDownscaler
import com.nuvio.app.features.settings.VideoQuality
import com.nuvio.app.features.settings.VideoQualitySettings
import com.nuvio.app.features.settings.videoMpvOptions
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// nuvio-rr fork: video quality options
class VideoQualityTest {
    private val dir = Files.createTempDirectory("nuvio-rr-video")
    private val file = dir.resolve("$VIDEO_QUALITY_STORE.properties")
    private val shader = """C:\Users\x\AppData\Local\Nuvio RR\Cache\shaders\v1\SSimDownscaler.glsl"""

    @AfterTest
    fun tearDown() {
        file.deleteIfExists()
        dir.deleteIfExists()
    }

    @Test
    fun `defaults give no options, so the player keeps its own scalers`() {
        assertEquals(emptyList(), videoMpvOptions(VideoQualitySettings(), shader))
    }

    @Test
    fun `high quality is mpv's high-quality profile`() {
        assertEquals(
            listOf(
                "scale" to "ewa_lanczossharp",
                "cscale" to "ewa_lanczossharp",
                "hdr-peak-percentile" to "99.995",
                "hdr-contrast-recovery" to "0.30",
                "allow-delayed-peak-detect" to "no",
            ),
            videoMpvOptions(VideoQualitySettings(quality = VideoQuality.HIGH), shader),
        )
    }

    @Test
    fun `catmull-rom downscaler`() {
        assertEquals(
            listOf("dscale" to "catmull_rom"),
            videoMpvOptions(VideoQualitySettings(downscaler = VideoDownscaler.CATMULL_ROM), shader),
        )
    }

    @Test
    fun `SSimDownscaler loads the shader with the settings it is tuned for`() {
        assertEquals(
            listOf("glsl-shaders" to shader, "dscale" to "mitchell", "linear-downscaling" to "no"),
            videoMpvOptions(VideoQualitySettings(downscaler = VideoDownscaler.SSIM), shader),
        )
    }

    @Test
    fun `SSimDownscaler without a usable shader file falls back to the default downscaler`() {
        val ssim = VideoQualitySettings(downscaler = VideoDownscaler.SSIM)
        assertEquals(emptyList(), videoMpvOptions(ssim, null))
        assertEquals(emptyList(), videoMpvOptions(ssim, ""))
        // ';' separates paths in mpv's list on Windows: such a path would load the wrong files.
        assertEquals(emptyList(), videoMpvOptions(ssim, """C:\a;b\SSimDownscaler.glsl"""))
    }

    @Test
    fun `high quality and a downscaler combine, quality options first`() {
        val options = videoMpvOptions(VideoQualitySettings(VideoQuality.HIGH, VideoDownscaler.SSIM), shader)
        assertEquals(
            listOf(
                "scale", "cscale", "hdr-peak-percentile", "hdr-contrast-recovery", "allow-delayed-peak-detect",
                "glsl-shaders", "dscale", "linear-downscaling",
            ),
            options.map { it.first },
        )
    }

    @Test
    fun `store round trip, missing file gives defaults`() {
        val preference = VideoQualityPreference(DesktopStorage.Store(file))
        assertEquals(VideoQualitySettings(), preference.load())
        val chosen = VideoQualitySettings(VideoQuality.HIGH, VideoDownscaler.SSIM)
        preference.save(chosen)
        assertEquals(chosen, VideoQualityPreference(DesktopStorage.Store(file)).load())
    }

    @Test
    fun `unknown stored values give defaults`() {
        file.writeText("quality=ULTRA\ndownscaler=BICUBIC\n")
        assertEquals(VideoQualitySettings(), VideoQualityPreference(DesktopStorage.Store(file)).load())
    }

    @Test
    fun `the shader is bundled with its license header`() {
        val bytes = assertNotNull(ssimShaderBytes(), "resource $SSIM_SHADER_RESOURCE")
        val text = bytes.decodeToString()
        assertTrue("GNU Lesser General Public" in text, "LGPL header kept")
        assertTrue("//!DESC SSimDownscaler final pass" in text)
    }
}
