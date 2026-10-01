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

data class VideoQualitySettings(
    val quality: VideoQuality = VideoQuality.STANDARD,
    val downscaler: VideoDownscaler = VideoDownscaler.DEFAULT,
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
 * The mpv properties for [settings], set over the player's own defaults; the defaults give no options, so untouched
 * settings change nothing. [ssimShaderPath] is the installed shader file; without a usable one SSIM falls back to
 * the default downscaler.
 */
fun videoMpvOptions(settings: VideoQualitySettings, ssimShaderPath: String?): List<Pair<String, String>> {
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
    return quality + downscaler
}
