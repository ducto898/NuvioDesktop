package com.nuvio.app.features.player.desktop

// nuvio-rr fork, Phase 9 E9: the saved window position is restored as is; after a monitor was unplugged or
// rearranged the window opened off-screen. Now a saved position is kept only while its title bar is on a screen.

/** A screen's usable area in window coordinates (dp; AWT logical pixels). */
internal data class ScreenArea(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * [saved] when enough of its title bar is on one of [screens] to grab it; otherwise the same size (shrunk to fit)
 * centred on the first (primary) screen. Null [screens] info means "unknown": [saved] is kept.
 */
internal fun fitWindowGeometry(saved: DesktopWindowGeometry, screens: List<ScreenArea>): DesktopWindowGeometry =
    TODO("Phase 9 E9 commit B")

/** The usable area of every connected screen, primary first. */
internal fun currentScreenAreas(): List<ScreenArea> = TODO("Phase 9 E9 commit B")
