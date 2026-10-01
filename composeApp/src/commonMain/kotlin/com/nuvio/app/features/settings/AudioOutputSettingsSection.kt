package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.settings_playback_audio_channels
import nuvio.composeapp.generated.resources.settings_playback_audio_channels_51
import nuvio.composeapp.generated.resources.settings_playback_audio_channels_71
import nuvio.composeapp.generated.resources.settings_playback_audio_channels_auto
import nuvio.composeapp.generated.resources.settings_playback_audio_channels_stereo
import nuvio.composeapp.generated.resources.settings_playback_audio_device
import nuvio.composeapp.generated.resources.settings_playback_audio_device_default
import nuvio.composeapp.generated.resources.settings_playback_audio_device_loading
import nuvio.composeapp.generated.resources.settings_playback_audio_next_playback
import nuvio.composeapp.generated.resources.settings_playback_audio_passthrough
import nuvio.composeapp.generated.resources.settings_playback_audio_passthrough_desc
import nuvio.composeapp.generated.resources.settings_playback_audio_section
import org.jetbrains.compose.resources.stringResource

/** nuvio-rr fork, Phase 9 E1: "AUDIO OUTPUT" on the Playback page (Windows), called from the fork's Display section. */
@Composable
internal fun AudioOutputSettingsSection(isTablet: Boolean) {
    if (!AudioOutputSetting.available) return
    val settings by AudioOutputSetting.settings.collectAsState()
    var showChannels by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<AudioDevice>?>(null) }
    val defaultDeviceLabel = stringResource(Res.string.settings_playback_audio_device_default)
    val deviceLabel = when {
        settings.device == AUTO_AUDIO_DEVICE -> defaultDeviceLabel
        else -> devices?.firstOrNull { it.name == settings.device }?.description ?: settings.device
    }

    SettingsSection(title = stringResource(Res.string.settings_playback_audio_section), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_audio_channels),
                description = channelLabel(settings.channels),
                isTablet = isTablet,
                onClick = { showChannels = true },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_playback_audio_passthrough),
                description = stringResource(Res.string.settings_playback_audio_passthrough_desc),
                checked = settings.passthrough,
                isTablet = isTablet,
                onCheckedChange = { on -> AudioOutputSetting.update { it.copy(passthrough = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_audio_device),
                description = deviceLabel + " · " + stringResource(Res.string.settings_playback_audio_next_playback),
                isTablet = isTablet,
                onClick = { showDevices = true },
            )
        }
    }

    if (showChannels) {
        ChoiceDialog(
            title = stringResource(Res.string.settings_playback_audio_channels),
            options = AudioChannelLayout.entries.map { it to channelLabel(it) },
            selected = settings.channels,
            onSelect = { layout ->
                AudioOutputSetting.update { it.copy(channels = layout) }
                showChannels = false
            },
            onDismiss = { showChannels = false },
        )
    }
    if (showDevices) {
        LaunchedEffect(Unit) {
            if (devices == null) devices = withContext(Dispatchers.IO) { AudioOutputSetting.devices() }
        }
        val list = devices
        val options = if (list == null) {
            listOf(settings.device to stringResource(Res.string.settings_playback_audio_device_loading))
        } else {
            val withDefault = if (list.any { it.name == AUTO_AUDIO_DEVICE }) list else listOf(AudioDevice(AUTO_AUDIO_DEVICE, "")) + list
            withDefault.map { device ->
                device.name to if (device.name == AUTO_AUDIO_DEVICE) defaultDeviceLabel else device.description
            }
        }
        ChoiceDialog(
            title = stringResource(Res.string.settings_playback_audio_device),
            options = options,
            selected = settings.device,
            onSelect = { name ->
                if (list != null) AudioOutputSetting.update { it.copy(device = name) }
                showDevices = false
            },
            onDismiss = { showDevices = false },
        )
    }
}

@Composable
private fun channelLabel(layout: AudioChannelLayout): String = stringResource(
    when (layout) {
        AudioChannelLayout.AUTO -> Res.string.settings_playback_audio_channels_auto
        AudioChannelLayout.STEREO -> Res.string.settings_playback_audio_channels_stereo
        AudioChannelLayout.SURROUND_51 -> Res.string.settings_playback_audio_channels_51
        AudioChannelLayout.SURROUND_71 -> Res.string.settings_playback_audio_channels_71
    },
)

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(onDismissRequest = onDismiss, title = title) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                DialogOption(text = label, selected = value == selected, onClick = { onSelect(value) })
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_done), onClick = onDismiss)
        }
    }
}
