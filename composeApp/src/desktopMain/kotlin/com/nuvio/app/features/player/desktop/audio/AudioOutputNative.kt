package com.nuvio.app.features.player.desktop.audio

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.settings.AUTO_AUDIO_DEVICE
import com.nuvio.app.features.settings.AudioChannelLayout
import com.nuvio.app.features.settings.AudioDevice
import com.nuvio.app.features.settings.AudioOutputSetting
import com.nuvio.app.features.settings.AudioOutputSettings
import com.nuvio.app.features.settings.audioMpvOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// nuvio-rr fork, Phase 9 E1: the Windows side of the audio options.

internal const val AUDIO_STORE = "nuvio_audio"
private const val CHANNELS_KEY = "channels"
private const val PASSTHROUGH_KEY = "passthrough"
private const val DEVICE_KEY = "device"

/** Reads / writes the settings in the per-PC store (missing keys = defaults). */
internal class AudioOutputPreference(private val store: DesktopStorage.Store) {
    fun load(): AudioOutputSettings = AudioOutputSettings(
        channels = store.getString(CHANNELS_KEY)
            ?.let { name -> AudioChannelLayout.entries.firstOrNull { it.name == name } }
            ?: AudioChannelLayout.AUTO,
        passthrough = store.getBoolean(PASSTHROUGH_KEY) ?: false,
        device = store.getString(DEVICE_KEY)?.takeIf { it.isNotBlank() } ?: AUTO_AUDIO_DEVICE,
    )

    fun save(settings: AudioOutputSettings) {
        store.putString(CHANNELS_KEY, settings.channels.name)
        store.putBoolean(PASSTHROUGH_KEY, settings.passthrough)
        store.putString(DEVICE_KEY, settings.device)
    }
}

/** mpv's audio-device-list JSON ([{"name":..,"description":..}, ..]) to devices; bad input = empty list. */
internal fun parseAudioDeviceList(json: String): List<AudioDevice> = runCatching {
    Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
        val entry = element.jsonObject
        val name = entry["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
        AudioDevice(name, entry["description"]?.jsonPrimitive?.content ?: name)
    }
}.getOrDefault(emptyList())

/** Binds [AudioOutputSetting] on Windows (the only bridge that reads the options). */
internal fun bindAudioOutputSetting() {
    if (!com.nuvio.app.isWindows) return
    val preference = AudioOutputPreference(DesktopStorage.store(AUDIO_STORE))
    AudioOutputSetting.bind(preference.load(), preference::save) {
        parseAudioDeviceList(runCatching { AudioOutputNative.nativeDeviceListJson() }.getOrDefault("[]"))
    }
}

internal object AudioOutputNative {
    /** Called by the native bridge at hook H2 for each new player: "name=value" lines. */
    @JvmStatic
    fun nativeMpvOptions(): String =
        audioMpvOptions(AudioOutputSetting.settings.value).joinToString("\n") { (name, value) -> "$name=$value" }

    /** A short-lived mpv instance's audio-device-list (player_bridge.dll, display_mode_matcher.cpp). */
    @JvmStatic
    external fun nativeDeviceListJson(): String
}
