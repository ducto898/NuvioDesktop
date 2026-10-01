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

/** The bitstream formats passthrough can send, with the bit the native device probe reports for each. */
enum class PassthroughCodec(val mpvName: String, val bit: Int) {
    AC3("ac3", 1), EAC3("eac3", 2), DTS("dts", 4), DTS_HD("dts-hd", 8), TRUEHD("truehd", 16);

    companion object {
        /** The codecs in [mask]; a negative mask (the device could not be asked) means none. */
        fun fromMask(mask: Int): Set<PassthroughCodec> =
            if (mask < 0) emptySet() else entries.filter { mask and it.bit != 0 }.toSet()
    }
}

/**
 * The mpv properties for [settings]; the defaults are mpv's own defaults, so untouched settings change nothing.
 * Passthrough asks only for the codecs the device accepts ([passthroughSupported]): mpv's own fallback for a refused
 * bitstream left network streams stuck on their first frame (owner report 2026-10-01). With none supported the
 * output stays as without passthrough (shared mode, decoded).
 */
fun audioMpvOptions(
    settings: AudioOutputSettings,
    passthroughSupported: Set<PassthroughCodec> = PassthroughCodec.entries.toSet(),
): List<Pair<String, String>> {
    val passthrough = if (settings.passthrough) PassthroughCodec.entries.filter { it in passthroughSupported } else emptyList()
    return listOf(
        "audio-channels" to when (settings.channels) {
            AudioChannelLayout.AUTO -> "auto-safe"
            AudioChannelLayout.STEREO -> "stereo"
            AudioChannelLayout.SURROUND_51 -> "5.1,stereo"
            AudioChannelLayout.SURROUND_71 -> "7.1,5.1,stereo"
        },
        "audio-spdif" to passthrough.joinToString(",") { it.mpvName },
        "audio-exclusive" to if (passthrough.isNotEmpty()) "yes" else "no",
        "audio-device" to settings.device.ifBlank { AUTO_AUDIO_DEVICE },
    )
}
