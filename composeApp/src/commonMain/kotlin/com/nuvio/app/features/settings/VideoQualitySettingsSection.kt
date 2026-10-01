package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
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
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.settings_playback_audio_next_playback
import nuvio.composeapp.generated.resources.settings_playback_video_downscaler
import nuvio.composeapp.generated.resources.settings_playback_video_downscaler_catmull_rom
import nuvio.composeapp.generated.resources.settings_playback_video_downscaler_default
import nuvio.composeapp.generated.resources.settings_playback_video_downscaler_ssim
import nuvio.composeapp.generated.resources.settings_playback_video_hdr
import nuvio.composeapp.generated.resources.settings_playback_video_hdr_monitor_peak
import nuvio.composeapp.generated.resources.settings_playback_video_hdr_passthrough
import nuvio.composeapp.generated.resources.settings_playback_video_hdr_windows
import nuvio.composeapp.generated.resources.settings_playback_video_quality
import nuvio.composeapp.generated.resources.settings_playback_video_quality_high
import nuvio.composeapp.generated.resources.settings_playback_video_quality_standard
import nuvio.composeapp.generated.resources.settings_playback_video_section
import org.jetbrains.compose.resources.stringResource

/** nuvio-rr fork: "VIDEO QUALITY" on the Playback page (Windows), called from the fork's Display section. */
@Composable
internal fun VideoQualitySettingsSection(isTablet: Boolean) {
    if (!VideoQualitySetting.available) return
    val settings by VideoQualitySetting.settings.collectAsState()
    var showQuality by remember { mutableStateOf(false) }
    var showDownscaler by remember { mutableStateOf(false) }
    var showHdr by remember { mutableStateOf(false) }
    val nextPlayback = stringResource(Res.string.settings_playback_audio_next_playback)

    SettingsSection(title = stringResource(Res.string.settings_playback_video_section), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_video_quality),
                description = qualityLabel(settings.quality) + " · " + nextPlayback,
                isTablet = isTablet,
                onClick = { showQuality = true },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_video_downscaler),
                description = downscalerLabel(settings.downscaler) + " · " + nextPlayback,
                isTablet = isTablet,
                onClick = { showDownscaler = true },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_video_hdr),
                description = hdrLabel(settings.hdr) + " · " + nextPlayback,
                isTablet = isTablet,
                onClick = { showHdr = true },
            )
        }
    }

    if (showQuality) {
        VideoChoiceDialog(
            title = stringResource(Res.string.settings_playback_video_quality),
            options = VideoQuality.entries.map { it to qualityLabel(it) },
            selected = settings.quality,
            onSelect = { quality ->
                VideoQualitySetting.update { it.copy(quality = quality) }
                showQuality = false
            },
            onDismiss = { showQuality = false },
        )
    }
    if (showDownscaler) {
        VideoChoiceDialog(
            title = stringResource(Res.string.settings_playback_video_downscaler),
            options = VideoDownscaler.entries.map { it to downscalerLabel(it) },
            selected = settings.downscaler,
            onSelect = { downscaler ->
                VideoQualitySetting.update { it.copy(downscaler = downscaler) }
                showDownscaler = false
            },
            onDismiss = { showDownscaler = false },
        )
    }
    if (showHdr) {
        VideoChoiceDialog(
            title = stringResource(Res.string.settings_playback_video_hdr),
            options = HdrOutput.entries.map { it to hdrLabel(it) },
            selected = settings.hdr,
            onSelect = { hdr ->
                VideoQualitySetting.update { it.copy(hdr = hdr) }
                showHdr = false
            },
            onDismiss = { showHdr = false },
        )
    }
}

@Composable
private fun hdrLabel(hdr: HdrOutput): String = stringResource(
    when (hdr) {
        HdrOutput.MONITOR_PEAK -> Res.string.settings_playback_video_hdr_monitor_peak
        HdrOutput.PASSTHROUGH -> Res.string.settings_playback_video_hdr_passthrough
        HdrOutput.WINDOWS_CALIBRATION -> Res.string.settings_playback_video_hdr_windows
    },
)

@Composable
private fun qualityLabel(quality: VideoQuality): String = stringResource(
    when (quality) {
        VideoQuality.STANDARD -> Res.string.settings_playback_video_quality_standard
        VideoQuality.HIGH -> Res.string.settings_playback_video_quality_high
    },
)

@Composable
private fun downscalerLabel(downscaler: VideoDownscaler): String = stringResource(
    when (downscaler) {
        VideoDownscaler.DEFAULT -> Res.string.settings_playback_video_downscaler_default
        VideoDownscaler.CATMULL_ROM -> Res.string.settings_playback_video_downscaler_catmull_rom
        VideoDownscaler.SSIM -> Res.string.settings_playback_video_downscaler_ssim
    },
)

@Composable
private fun <T> VideoChoiceDialog(
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
