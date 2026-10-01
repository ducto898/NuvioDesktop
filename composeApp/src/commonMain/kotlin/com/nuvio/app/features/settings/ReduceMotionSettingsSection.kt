package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.app.core.ui.ReduceMotion
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_appearance_motion_section
import nuvio.composeapp.generated.resources.settings_appearance_reduce_motion
import nuvio.composeapp.generated.resources.settings_appearance_reduce_motion_desc
import org.jetbrains.compose.resources.stringResource

/** nuvio-rr fork, Phase 9 E5: the "Motion" section at the end of Appearance (desktop only). */
@Composable
internal fun ReduceMotionSettingsSection(isTablet: Boolean) {
    val enabled by ReduceMotion.enabled.collectAsState()
    SettingsSection(
        title = stringResource(Res.string.settings_appearance_motion_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_appearance_reduce_motion),
                description = stringResource(Res.string.settings_appearance_reduce_motion_desc),
                checked = enabled,
                isTablet = isTablet,
                onCheckedChange = ReduceMotion::set,
            )
        }
    }
}
