package com.nuvio.app.features.player.desktop.audio

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.settings.AudioChannelLayout
import com.nuvio.app.features.settings.AudioDevice
import com.nuvio.app.features.settings.AudioOutputSettings
import com.nuvio.app.features.settings.audioMpvOptions
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// nuvio-rr fork, Phase 9 E1
class AudioOutputTest {
    private val dir = Files.createTempDirectory("nuvio-rr-audio")
    private val file = dir.resolve("$AUDIO_STORE.properties")

    @AfterTest
    fun tearDown() {
        file.deleteIfExists()
        dir.deleteIfExists()
    }

    @Test
    fun `defaults are mpv's own defaults`() {
        assertEquals(
            listOf("audio-channels" to "auto-safe", "audio-spdif" to "", "audio-exclusive" to "no", "audio-device" to "auto"),
            audioMpvOptions(AudioOutputSettings()),
        )
    }

    @Test
    fun `layouts, passthrough with exclusive mode, and a chosen device`() {
        val options = audioMpvOptions(
            AudioOutputSettings(AudioChannelLayout.SURROUND_51, passthrough = true, device = "wasapi/{abc}"),
        ).toMap()
        assertEquals("5.1,stereo", options["audio-channels"])
        assertEquals("ac3,eac3,dts,dts-hd,truehd", options["audio-spdif"])
        assertEquals("yes", options["audio-exclusive"])
        assertEquals("wasapi/{abc}", options["audio-device"])
        assertEquals("stereo", audioMpvOptions(AudioOutputSettings(AudioChannelLayout.STEREO)).toMap()["audio-channels"])
        assertEquals("7.1,5.1,stereo", audioMpvOptions(AudioOutputSettings(AudioChannelLayout.SURROUND_71)).toMap()["audio-channels"])
    }

    @Test
    fun `store round trip, missing file gives defaults`() {
        val preference = AudioOutputPreference(DesktopStorage.Store(file))
        assertEquals(AudioOutputSettings(), preference.load())
        val chosen = AudioOutputSettings(AudioChannelLayout.STEREO, passthrough = true, device = "wasapi/{x}")
        preference.save(chosen)
        assertEquals(chosen, AudioOutputPreference(DesktopStorage.Store(file)).load())
    }

    @Test
    fun `device list from mpv JSON`() {
        val json = """[{"name":"auto","description":"Autoselect device"},{"name":"wasapi/{0.0.0.1}","description":"Speakers (Realtek)"}]"""
        assertEquals(
            listOf(AudioDevice("auto", "Autoselect device"), AudioDevice("wasapi/{0.0.0.1}", "Speakers (Realtek)")),
            parseAudioDeviceList(json),
        )
        assertEquals(emptyList(), parseAudioDeviceList("not json"))
    }
}
