package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_display_section
import nuvio.composeapp.generated.resources.settings_playback_match_refresh_rate
import nuvio.composeapp.generated.resources.settings_playback_match_refresh_rate_desc
import org.jetbrains.compose.resources.stringResource

/** nuvio-rr fork (SPEC P6-11): the "Display" section, called by hook H9 below "NVIDIA RTX Video". Windows only. */
@Composable
internal fun RefreshRateMatchSettingsSection(isTablet: Boolean) {
    if (!RefreshRateMatchSetting.available) return
    val enabled by RefreshRateMatchSetting.enabled.collectAsStateWithLifecycle()
    SettingsSection(
        title = stringResource(Res.string.settings_playback_display_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_playback_match_refresh_rate),
                description = stringResource(Res.string.settings_playback_match_refresh_rate_desc),
                checked = enabled,
                isTablet = isTablet,
                onCheckedChange = RefreshRateMatchSetting::setEnabled,
            )
        }
    }
}
