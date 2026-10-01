package com.nuvio.app.features.player.desktop.video

import com.nuvio.app.core.storage.DesktopCache
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.settings.VideoDownscaler
import com.nuvio.app.features.settings.VideoQuality
import com.nuvio.app.features.settings.VideoQualitySetting
import com.nuvio.app.features.settings.VideoQualitySettings
import com.nuvio.app.features.settings.videoMpvOptions

// nuvio-rr fork: the Windows side of the video quality options.

internal const val VIDEO_QUALITY_STORE = "nuvio_video_quality"
internal const val SSIM_SHADER_RESOURCE = "/shaders/SSimDownscaler.glsl"
private const val QUALITY_KEY = "quality"
private const val DOWNSCALER_KEY = "downscaler"

/** Reads / writes the settings in the per-PC store (missing or unknown keys = defaults). */
internal class VideoQualityPreference(private val store: DesktopStorage.Store) {
    fun load(): VideoQualitySettings = VideoQualitySettings(
        quality = store.getString(QUALITY_KEY)
            ?.let { name -> VideoQuality.entries.firstOrNull { it.name == name } }
            ?: VideoQuality.STANDARD,
        downscaler = store.getString(DOWNSCALER_KEY)
            ?.let { name -> VideoDownscaler.entries.firstOrNull { it.name == name } }
            ?: VideoDownscaler.DEFAULT,
    )

    fun save(settings: VideoQualitySettings) {
        store.putString(QUALITY_KEY, settings.quality.name)
        store.putString(DOWNSCALER_KEY, settings.downscaler.name)
    }
}

/**
 * The HDR peak the monitor asks for, from its EDID's CTA-861 HDR static metadata block (desired max luminance,
 * 50 * 2^(CV/32) nits, rounded); null when there is no such block, the value is 0 (not given) or the EDID is bad.
 */
internal fun edidHdrPeakNits(edid: ByteArray): Int? = TODO("nuvio-rr fork: EDID HDR peak")

/** "00ff..." hex as bytes; empty for odd length or non-hex. */
internal fun hexBytes(hex: String): ByteArray = TODO("nuvio-rr fork: EDID HDR peak")

/** The bundled shader's bytes; null when the resource is missing. */
internal fun ssimShaderBytes(): ByteArray? =
    VideoQualityPreference::class.java.getResourceAsStream(SSIM_SHADER_RESOURCE)?.use { it.readBytes() }

/** Binds [VideoQualitySetting] on Windows (the only bridge that reads the options). */
internal fun bindVideoQualitySetting() {
    if (!com.nuvio.app.isWindows) return
    val preference = VideoQualityPreference(DesktopStorage.store(VIDEO_QUALITY_STORE))
    VideoQualitySetting.bind(preference.load(), preference::save)
}

internal object VideoQualityNative {
    /** mpv loads shaders from files: the bundled one is copied into the app's cache once per content version. */
    private val ssimShaderPath: String? by lazy {
        runCatching {
            val bytes = ssimShaderBytes() ?: return@runCatching null
            DesktopCache.installVersionedFiles("shaders", mapOf("SSimDownscaler.glsl" to bytes))
                .resolve("SSimDownscaler.glsl").toString()
        }.getOrNull()
    }

    /** Called by the native bridge at hook H2 for each new player: "name=value" lines. */
    @JvmStatic
    fun nativeMpvOptions(): String {
        val settings = VideoQualitySetting.settings.value
        val shader = if (settings.downscaler == VideoDownscaler.SSIM) ssimShaderPath else null
        return videoMpvOptions(settings, shader).joinToString("\n") { (name, value) -> "$name=$value" }
    }
}
