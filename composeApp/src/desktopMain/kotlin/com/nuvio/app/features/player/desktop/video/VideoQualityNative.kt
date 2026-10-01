package com.nuvio.app.features.player.desktop.video

import com.nuvio.app.core.storage.DesktopCache
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.settings.HdrOutput
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
private const val HDR_KEY = "hdr"

/** Reads / writes the settings in the per-PC store (missing or unknown keys = defaults). */
internal class VideoQualityPreference(private val store: DesktopStorage.Store) {
    fun load(): VideoQualitySettings = VideoQualitySettings(
        quality = store.getString(QUALITY_KEY)
            ?.let { name -> VideoQuality.entries.firstOrNull { it.name == name } }
            ?: VideoQuality.STANDARD,
        downscaler = store.getString(DOWNSCALER_KEY)
            ?.let { name -> VideoDownscaler.entries.firstOrNull { it.name == name } }
            ?: VideoDownscaler.DEFAULT,
        hdr = store.getString(HDR_KEY)
            ?.let { name -> HdrOutput.entries.firstOrNull { it.name == name } }
            ?: HdrOutput.MONITOR_PEAK,
    )

    fun save(settings: VideoQualitySettings) {
        store.putString(QUALITY_KEY, settings.quality.name)
        store.putString(DOWNSCALER_KEY, settings.downscaler.name)
        store.putString(HDR_KEY, settings.hdr.name)
    }
}

/**
 * The HDR peak the monitor asks for, from its EDID's CTA-861 HDR static metadata block (desired max luminance,
 * 50 * 2^(CV/32) nits, rounded); null when there is no such block, the value is 0 (not given) or the EDID is bad.
 */
internal fun edidHdrPeakNits(edid: ByteArray): Int? {
    val blocks = edid.size / 128
    for (blockIndex in 1 until blocks) {
        val base = blockIndex * 128
        fun at(offset: Int) = edid[base + offset].toInt() and 0xff
        if (at(0) != 0x02) continue  // CTA-861 extension
        val dataEnd = at(2).coerceIn(4, 127)
        var i = 4
        while (i < dataEnd) {
            val tag = at(i) shr 5
            val length = at(i) and 0x1f
            if (i + length >= 128) break
            // Extended tag 6: HDR static metadata (EOTFs, descriptor types, max / max-average / min luminance)
            if (tag == 7 && length >= 4 && at(i + 1) == 6) {
                val code = at(i + 4)
                return if (code == 0) null else Math.round(50.0 * Math.pow(2.0, code / 32.0)).toInt()
            }
            i += length + 1
        }
    }
    return null
}

/** "00ff..." hex as bytes; empty for odd length or non-hex. */
internal fun hexBytes(hex: String): ByteArray {
    if (hex.length % 2 != 0) return ByteArray(0)
    val bytes = ByteArray(hex.length / 2)
    for (index in bytes.indices) {
        val value = hex.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return ByteArray(0)
        bytes[index] = value.toByte()
    }
    return bytes
}

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

    /**
     * Called by the native bridge at hook H2 for each new player with the EDID (hex, "" if unknown) of the monitor
     * the player opens on: "name=value" lines.
     */
    @JvmStatic
    fun nativeMpvOptions(edidHex: String): String {
        val settings = VideoQualitySetting.settings.value
        val shader = if (settings.downscaler == VideoDownscaler.SSIM) ssimShaderPath else null
        val peak = if (settings.hdr == HdrOutput.MONITOR_PEAK) runCatching { edidHdrPeakNits(hexBytes(edidHex)) }.getOrNull() else null
        return videoMpvOptions(settings, shader, peak).joinToString("\n") { (name, value) -> "$name=$value" }
    }
}
