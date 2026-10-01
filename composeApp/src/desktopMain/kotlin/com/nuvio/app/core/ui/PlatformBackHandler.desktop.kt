package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

/** nuvio-rr fork, Phase 9 E4: back handlers of the composed screens; Esc / Alt+Left / mouse Back run the newest. */
internal class DesktopBackDispatcher {
    class Entry(var enabled: Boolean, var onBack: () -> Unit)

    fun register(enabled: Boolean, onBack: () -> Unit): Entry = TODO("Phase 9 E4 commit B")

    fun unregister(entry: Entry): Unit = TODO("Phase 9 E4 commit B")

    /** Runs the newest enabled handler; false when there is none (then the key or button is not consumed). */
    fun dispatch(): Boolean = TODO("Phase 9 E4 commit B")

    companion object {
        val main = DesktopBackDispatcher()
    }
}

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit
