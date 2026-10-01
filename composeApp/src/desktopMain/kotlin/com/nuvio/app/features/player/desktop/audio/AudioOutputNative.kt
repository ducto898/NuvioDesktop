package com.nuvio.app.features.player.desktop.audio

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.settings.AudioDevice
import com.nuvio.app.features.settings.AudioOutputSettings

// nuvio-rr fork, Phase 9 E1: the Windows side of the audio options.

internal const val AUDIO_STORE = "nuvio_audio"

/** Reads / writes the settings in the per-PC store (missing keys = defaults). */
internal class AudioOutputPreference(private val store: DesktopStorage.Store) {
    fun load(): AudioOutputSettings = TODO("Phase 9 E1 commit B")

    fun save(settings: AudioOutputSettings): Unit = TODO("Phase 9 E1 commit B")
}

/** mpv's audio-device-list JSON ([{"name":..,"description":..}, ..]) to devices; bad input = empty list. */
internal fun parseAudioDeviceList(json: String): List<AudioDevice> = TODO("Phase 9 E1 commit B")
