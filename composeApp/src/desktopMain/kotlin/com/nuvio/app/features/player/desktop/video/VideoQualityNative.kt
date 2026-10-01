package com.nuvio.app.features.player.desktop.video

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.settings.VideoQualitySettings

// nuvio-rr fork: the Windows side of the video quality options.

internal const val VIDEO_QUALITY_STORE = "nuvio_video_quality"
internal const val SSIM_SHADER_RESOURCE = "/shaders/SSimDownscaler.glsl"

/** Reads / writes the settings in the per-PC store (missing or unknown keys = defaults). */
internal class VideoQualityPreference(private val store: DesktopStorage.Store) {
    fun load(): VideoQualitySettings = TODO("nuvio-rr fork: video quality store")

    fun save(settings: VideoQualitySettings): Unit = TODO("nuvio-rr fork: video quality store")
}

/** The bundled shader's bytes; null when the resource is missing. */
internal fun ssimShaderBytes(): ByteArray? = TODO("nuvio-rr fork: bundled shader")
