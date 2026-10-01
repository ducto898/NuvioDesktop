package com.nuvio.app.features.player.desktop.video

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.settings.HdrOutput
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

    /** The scaler tests: HDR on mpv's default, which sends no option, so only the scaler options remain. */
    private val scalersOnly = VideoQualitySettings(hdr = HdrOutput.WINDOWS_CALIBRATION)

    @AfterTest
    fun tearDown() {
        file.deleteIfExists()
        dir.deleteIfExists()
    }

    @Test
    fun `defaults keep the player's own scalers and pass HDR metadata through (owner, 2026-10-01)`() {
        assertEquals(HdrOutput.PASSTHROUGH, VideoQualitySettings().hdr)
        assertEquals(
            listOf("target-colorspace-hint-mode" to "source"),
            videoMpvOptions(VideoQualitySettings(), shader, monitorPeakNits = 1532),
        )
        assertEquals(emptyList(), videoMpvOptions(scalersOnly, shader))
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
            videoMpvOptions(scalersOnly.copy(quality = VideoQuality.HIGH), shader),
        )
    }

    @Test
    fun `catmull-rom downscaler`() {
        assertEquals(
            listOf("dscale" to "catmull_rom"),
            videoMpvOptions(scalersOnly.copy(downscaler = VideoDownscaler.CATMULL_ROM), shader),
        )
    }

    @Test
    fun `SSimDownscaler loads the shader with the settings it is tuned for`() {
        assertEquals(
            listOf("glsl-shaders" to shader, "dscale" to "mitchell", "linear-downscaling" to "no"),
            videoMpvOptions(scalersOnly.copy(downscaler = VideoDownscaler.SSIM), shader),
        )
    }

    @Test
    fun `SSimDownscaler without a usable shader file falls back to the default downscaler`() {
        val ssim = scalersOnly.copy(downscaler = VideoDownscaler.SSIM)
        assertEquals(emptyList(), videoMpvOptions(ssim, null))
        assertEquals(emptyList(), videoMpvOptions(ssim, ""))
        // ';' separates paths in mpv's list on Windows: such a path would load the wrong files.
        assertEquals(emptyList(), videoMpvOptions(ssim, """C:\a;b\SSimDownscaler.glsl"""))
    }

    @Test
    fun `high quality and a downscaler combine, quality options first`() {
        val options = videoMpvOptions(scalersOnly.copy(quality = VideoQuality.HIGH, downscaler = VideoDownscaler.SSIM), shader)
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
        file.writeText("quality=ULTRA\ndownscaler=BICUBIC\nhdr=DOLBY\n")
        assertEquals(VideoQualitySettings(), VideoQualityPreference(DesktopStorage.Store(file)).load())
    }

    @Test
    fun `HDR monitor peak caps mpv at the EDID peak`() {
        val monitorPeak = VideoQualitySettings(hdr = HdrOutput.MONITOR_PEAK)
        assertEquals(listOf("target-peak" to "1532"), videoMpvOptions(monitorPeak, shader, monitorPeakNits = 1532))
    }

    @Test
    fun `HDR monitor peak without a plausible EDID value leaves mpv's default`() {
        val defaults = VideoQualitySettings(hdr = HdrOutput.MONITOR_PEAK)
        assertEquals(emptyList(), videoMpvOptions(defaults, shader, monitorPeakNits = null))
        assertEquals(emptyList(), videoMpvOptions(defaults, shader, monitorPeakNits = 50), "below SDR white: not a real HDR peak")
        assertEquals(emptyList(), videoMpvOptions(defaults, shader, monitorPeakNits = 20000), "beyond PQ's 10 000 nits")
    }

    @Test
    fun `HDR passthrough sends the video's own metadata, Windows calibration sends nothing`() {
        assertEquals(
            listOf("target-colorspace-hint-mode" to "source"),
            videoMpvOptions(VideoQualitySettings(hdr = HdrOutput.PASSTHROUGH), shader, monitorPeakNits = 1532),
        )
        assertEquals(
            emptyList(),
            videoMpvOptions(VideoQualitySettings(hdr = HdrOutput.WINDOWS_CALIBRATION), shader, monitorPeakNits = 1532),
        )
    }

    @Test
    fun `HDR options come after quality and downscaler`() {
        val options = videoMpvOptions(
            VideoQualitySettings(VideoQuality.HIGH, VideoDownscaler.CATMULL_ROM, HdrOutput.MONITOR_PEAK),
            shader,
            monitorPeakNits = 1532,
        )
        assertEquals("target-peak" to "1532", options.last())
        assertEquals("dscale" to "catmull_rom", options[options.size - 2])
    }

    @Test
    fun `EDID HDR peak of the owner's Gigabyte MO27Q28G is 1532 nits (madVR shows the same)`() {
        assertEquals(1532, edidHdrPeakNits(hexBytes(MO27Q28G_EDID)))
    }

    @Test
    fun `EDID without an HDR block, with CV 0, or broken gives no peak`() {
        val edid = hexBytes(MO27Q28G_EDID)
        assertEquals(null, edidHdrPeakNits(edid.copyOf(128)), "base block only (the monitor's old 128-byte entry)")
        val noValue = edid.copyOf()
        val block = MO27Q28G_EDID.indexOf("e6060d01") / 2
        noValue[block + 4] = 0
        assertEquals(null, edidHdrPeakNits(noValue), "desired max luminance not given")
        assertEquals(null, edidHdrPeakNits(ByteArray(0)))
        assertEquals(null, edidHdrPeakNits(edid.copyOf(200)), "truncated extension")
    }

    @Test
    fun `hex to bytes`() {
        assertEquals(listOf<Byte>(0, -1, 0x1c), hexBytes("00ff1C").toList())
        assertEquals(0, hexBytes("abc").size)
        assertEquals(0, hexBytes("zz").size)
    }

    @Test
    fun `store round trip keeps the HDR choice`() {
        val preference = VideoQualityPreference(DesktopStorage.Store(file))
        val chosen = VideoQualitySettings(hdr = HdrOutput.PASSTHROUGH)
        preference.save(chosen)
        assertEquals(chosen, VideoQualityPreference(DesktopStorage.Store(file)).load())
    }

    @Test
    fun `the shader is bundled with its license header`() {
        val bytes = assertNotNull(ssimShaderBytes(), "resource $SSIM_SHADER_RESOURCE")
        val text = bytes.decodeToString()
        assertTrue("GNU Lesser General Public" in text, "LGPL header kept")
        assertTrue("//!DESC SSimDownscaler final pass" in text)
    }
}

// The owner's monitor, read from the registry 2026-10-01 (384 bytes: base + CTA-861 + DisplayID).
private const val MO27Q28G_EDID =
    "00ffffffffffff001c543c270101010134230104b53b2178fbced5b04f3cb7260a5054bfef80714f81c0810081408180" +
    "9500a9c0b300565e00a0a0a02950302035004e4e2100001a000000fd0e5019ffff7e010a202020202020000000fc004d" +
    "4f3237513238470a20202020000000ff003235353232463030303330360a02980203367149031304290f1f10403f2309" +
    "060783010000741a0000030350f000a09e029e0218010000000000e305c301e6060d019e52026fc200a0a0a055503020" +
    "35004e4e2100001a09ec00a0a0a06750302035004e4e2100001a00000000000000000000000000000000000000000000" +
    "000000000000000000000000000000ef7012790300030150b2eb0104ff099f002f801f009f05d40002000400e9ec0004" +
    "7f079f002f801f003704860002000400071a01047f079f002f801f0037049f0002000400e36e0104ff094f0007001f00" +
    "9f052a002000070000000000000000000000000000000000000000000000000000000000000000000000000000003590"
