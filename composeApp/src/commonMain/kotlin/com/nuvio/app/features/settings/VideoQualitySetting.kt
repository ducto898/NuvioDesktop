package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * nuvio-rr fork: video quality options (scalers). Stored per PC by the Windows app (bound at startup); unbound
 * elsewhere, where the section is hidden. A change applies from the next playback start, like the audio options.
 */
enum class VideoQuality {
    /** The player's own scalers (spline36 up, mpv's default down). */
    STANDARD,

    /** mpv's high-quality profile: ewa_lanczossharp for luma and chroma, plus its HDR peak-detection settings. */
    HIGH,
}

enum class VideoDownscaler {
    /** mpv's own dscale default. */
    DEFAULT,
    CATMULL_ROM,

    /** igv's SSimDownscaler shader (LGPL-3.0, bundled), with the settings it is tuned for. */
    SSIM,
}

/** Where mpv takes the display's HDR peak from (Windows HDR on). */
enum class HdrOutput {
    /** The monitor's EDID (desired max luminance): mpv tone-maps only what is brighter than that. */
    MONITOR_PEAK,

    /** The video's own metadata goes to the monitor, which tone-maps (target-colorspace-hint-mode=source). */
    PASSTHROUGH,

    /** mpv's default: the Windows HDR calibration profile's peak and primaries. */
    WINDOWS_CALIBRATION,
}

data class VideoQualitySettings(
    val quality: VideoQuality = VideoQuality.STANDARD,
    val downscaler: VideoDownscaler = VideoDownscaler.DEFAULT,
    val hdr: HdrOutput = HdrOutput.MONITOR_PEAK,
)

object VideoQualitySetting {
    private val state = MutableStateFlow(VideoQualitySettings())
    private var save: (VideoQualitySettings) -> Unit = {}

    val available: Boolean
        get() = bound

    private var bound = false

    val settings: StateFlow<VideoQualitySettings>
        get() = state

    fun bind(initial: VideoQualitySettings, save: (VideoQualitySettings) -> Unit) {
        state.value = initial
        this.save = save
        bound = true
    }

    fun update(transform: (VideoQualitySettings) -> VideoQualitySettings) {
        val next = transform(state.value)
        state.value = next
        save(next)
    }
}

/**
 * The mpv properties for [settings], set over the player's own defaults. [ssimShaderPath] is the installed shader
 * file; without a usable one SSIM falls back to the default downscaler. [monitorPeakNits] is the EDID's HDR peak of
 * the player's monitor; without a plausible one MONITOR_PEAK falls back to mpv's default (Windows calibration).
 */
fun videoMpvOptions(
    settings: VideoQualitySettings,
    ssimShaderPath: String?,
    monitorPeakNits: Int? = null,
): List<Pair<String, String>> {
    val quality = when (settings.quality) {
        VideoQuality.STANDARD -> emptyList()
        VideoQuality.HIGH -> listOf(
            "scale" to "ewa_lanczossharp",
            "cscale" to "ewa_lanczossharp",
            "hdr-peak-percentile" to "99.995",
            "hdr-contrast-recovery" to "0.30",
            "allow-delayed-peak-detect" to "no",
        )
    }
    // ';' separates the entries of mpv's path list on Windows, so a path containing one can't be passed as one file.
    val usableShader = ssimShaderPath?.takeIf { it.isNotBlank() && ';' !in it }
    val downscaler = when (settings.downscaler) {
        VideoDownscaler.DEFAULT -> emptyList()
        VideoDownscaler.CATMULL_ROM -> listOf("dscale" to "catmull_rom")
        // The shader refines mpv's own downscale (it hooks POSTKERNEL); its author tunes it for linear-downscaling=no.
        VideoDownscaler.SSIM -> if (usableShader == null) emptyList() else listOf(
            "glsl-shaders" to usableShader,
            "dscale" to "mitchell",
            "linear-downscaling" to "no",
        )
    }
    return quality + downscaler + hdrOptions(settings.hdr, monitorPeakNits)
}

/** A real HDR peak: above SDR reference white (203 nits) and within PQ's 10 000 nits. */
private val plausibleHdrPeak = 204..10_000

// Only HDR rendering changes: an SDR video keeps its 203-nit white either way (measured), except that PASSTHROUGH
// also hands SDR to Windows as SDR, so the Windows "SDR content brightness" slider then sets its brightness.
private fun hdrOptions(hdr: HdrOutput, monitorPeakNits: Int?): List<Pair<String, String>> = when (hdr) {
    HdrOutput.MONITOR_PEAK -> monitorPeakNits?.takeIf { it in plausibleHdrPeak }
        ?.let { listOf("target-peak" to it.toString()) }
        ?: emptyList()
    HdrOutput.PASSTHROUGH -> listOf("target-colorspace-hint-mode" to "source")
    HdrOutput.WINDOWS_CALIBRATION -> emptyList()
}
