package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal actual object RefreshRateMatchSetting {
    actual val available: Boolean = false
    actual val enabled: StateFlow<Boolean> = MutableStateFlow(false)

    actual fun setEnabled(value: Boolean) = Unit
}
