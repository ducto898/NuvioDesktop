package com.nuvio.app.fork

/**
 * nuvio-rr fork, Phase 9 #8. The player bridge and TorrServer looked for dev build outputs in paths relative to the
 * working directory (composeApp/build/native/..., vendor/TorrServer/...) before their bundled copies, so starting
 * the app from a folder someone else controls ran their player_bridge.dll or TorrServer. Those paths are for dev
 * runs only; jpackage sets `jpackage.app-path` in every packaged app.
 */
internal object DesktopDevPaths {
    fun allowed(jpackageAppPath: String?): Boolean = TODO("Phase 9 #8 commit B")

    val allowedHere: Boolean
        get() = allowed(System.getProperty("jpackage.app-path"))
}
