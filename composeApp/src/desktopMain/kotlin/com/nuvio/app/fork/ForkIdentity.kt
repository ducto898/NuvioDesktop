package com.nuvio.app.fork

/**
 * The fork's own app identity (SPEC Phase 8, Q39): the packaged fork passes `-Dnuvio.fork.name=Nuvio RR`, so its data,
 * cache and WebView2 folders differ from the official Nuvio's and both can run side by side. Without the property
 * (tests, `run-dev.ps1`, a plain upstream build) every value is upstream's.
 */
object ForkIdentity {
    const val PROPERTY = "nuvio.fork.name"
    const val UPSTREAM_NAME = "Nuvio"

    private val current: String? get() = System.getProperty(PROPERTY)

    /** Folder name under %APPDATA% / %LOCALAPPDATA%. */
    @JvmStatic
    val appDirName: String get() = appDirName(current)

    @JvmStatic
    val updaterEnabled: Boolean get() = updaterEnabled(current)

    fun appDirName(property: String?): String = TODO("Phase 8 commit B")

    fun isFork(property: String?): Boolean = TODO("Phase 8 commit B")

    fun updaterEnabled(property: String?): Boolean = TODO("Phase 8 commit B")
}
