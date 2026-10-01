package com.nuvio.app.features.player.desktop

// nuvio-rr fork, Phase 9 E9: the saved window position is restored as is; after a monitor was unplugged or
// rearranged the window opened off-screen. Now a saved position is kept only while its title bar is on a screen.

/** A screen's usable area in window coordinates (dp; AWT logical pixels). */
internal data class ScreenArea(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * [saved] when enough of its title bar is on one of [screens] to grab it; otherwise the same size (shrunk to fit)
 * centred on the first (primary) screen. No screen info (empty [screens]) keeps [saved].
 */
internal fun fitWindowGeometry(saved: DesktopWindowGeometry, screens: List<ScreenArea>): DesktopWindowGeometry {
    if (screens.isEmpty()) return saved
    val titleBarGrabbable = screens.any { screen ->
        val visibleWidth = minOf(saved.x + saved.width, screen.x + screen.width) - maxOf(saved.x, screen.x)
        val titleBarOnScreen = saved.y >= screen.y && saved.y + TitleBarHeight <= screen.y + screen.height
        titleBarOnScreen && visibleWidth >= minOf(MinGrabWidth, saved.width)
    }
    if (titleBarGrabbable) return saved
    val primary = screens.first()
    val width = minOf(saved.width, primary.width)
    val height = minOf(saved.height, primary.height)
    return DesktopWindowGeometry(
        x = primary.x + (primary.width - width) / 2,
        y = primary.y + (primary.height - height) / 2,
        width = width,
        height = height,
    )
}

private const val TitleBarHeight = 32f
private const val MinGrabWidth = 120f

/** The usable area of every connected screen, primary first. */
internal fun currentScreenAreas(): List<ScreenArea> = runCatching {
    val environment = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
    val toolkit = java.awt.Toolkit.getDefaultToolkit()
    val primary = environment.defaultScreenDevice
    (listOf(primary) + environment.screenDevices.filter { it != primary }).map { device ->
        val configuration = device.defaultConfiguration
        val bounds = configuration.bounds
        val insets = toolkit.getScreenInsets(configuration)
        ScreenArea(
            x = (bounds.x + insets.left).toFloat(),
            y = (bounds.y + insets.top).toFloat(),
            width = (bounds.width - insets.left - insets.right).toFloat(),
            height = (bounds.height - insets.top - insets.bottom).toFloat(),
        )
    }
}.getOrDefault(emptyList())
