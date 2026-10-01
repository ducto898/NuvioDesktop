package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * nuvio-rr fork, Phase 9 E1: audio output options (channel layout, passthrough, device). Stored per PC by the
 * Windows app (bound at startup); unbound elsewhere, where the section is hidden. A change applies from the next
 * playback start, like the refresh-rate switch.
 */
enum class AudioChannelLayout { AUTO, STEREO, SURROUND_51, SURROUND_71 }

data class AudioOutputSettings(
    val channels: AudioChannelLayout = AudioChannelLayout.AUTO,
    /** AC3 / E-AC3 / DTS / DTS-HD / TrueHD bitstreamed to the receiver; needs WASAPI exclusive mode. */
    val passthrough: Boolean = false,
    /** mpv audio-device name; "auto" = the Windows default device. */
    val device: String = AUTO_AUDIO_DEVICE,
)

data class AudioDevice(val name: String, val description: String)

const val AUTO_AUDIO_DEVICE = "auto"

object AudioOutputSetting {
    private val state = MutableStateFlow(AudioOutputSettings())
    private var save: (AudioOutputSettings) -> Unit = {}
    private var listDevices: () -> List<AudioDevice> = { emptyList() }

    val available: Boolean
        get() = bound

    private var bound = false

    val settings: StateFlow<AudioOutputSettings>
        get() = state

    fun bind(initial: AudioOutputSettings, save: (AudioOutputSettings) -> Unit, listDevices: () -> List<AudioDevice>) {
        state.value = initial
        this.save = save
        this.listDevices = listDevices
        bound = true
    }

    fun update(transform: (AudioOutputSettings) -> AudioOutputSettings) {
        val next = transform(state.value)
        state.value = next
        save(next)
    }

    /** The output devices mpv sees (slow: opens the audio system once); empty when unknown. */
    fun devices(): List<AudioDevice> = runCatching { listDevices() }.getOrDefault(emptyList())
}

/** The mpv properties for [settings]; the defaults are mpv's own defaults, so untouched settings change nothing. */
fun audioMpvOptions(settings: AudioOutputSettings): List<Pair<String, String>> = listOf(
    "audio-channels" to when (settings.channels) {
        AudioChannelLayout.AUTO -> "auto-safe"
        AudioChannelLayout.STEREO -> "stereo"
        AudioChannelLayout.SURROUND_51 -> "5.1,stereo"
        AudioChannelLayout.SURROUND_71 -> "7.1,5.1,stereo"
    },
    "audio-spdif" to if (settings.passthrough) "ac3,eac3,dts,dts-hd,truehd" else "",
    "audio-exclusive" to if (settings.passthrough) "yes" else "no",
    "audio-device" to settings.device.ifBlank { AUTO_AUDIO_DEVICE },
)
