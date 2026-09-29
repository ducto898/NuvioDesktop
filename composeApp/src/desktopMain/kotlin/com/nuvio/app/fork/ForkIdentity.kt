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

    /** A usable Windows folder name: 1–64 chars, none of `\ / : * ? " < > |`, not `.`/`..`, no trailing dot. */
    private val SAFE = Regex("""[^\\/:*?"<>|\x00-\x1f]{1,64}""")

    fun appDirName(property: String?): String {
        val name = property?.trim().orEmpty()
        val usable = SAFE.matches(name) && name != "." && name != ".." && !name.endsWith(".") &&
            !name.equals(UPSTREAM_NAME, ignoreCase = true) // Windows paths ignore case: "nuvio" is the official folder
        return if (usable) name else UPSTREAM_NAME
    }

    fun isFork(property: String?): Boolean = appDirName(property) != UPSTREAM_NAME

    /** The in-app updater would install the official build, so it is off in the fork (P8-8). */
    fun updaterEnabled(property: String?): Boolean = !isFork(property)
}
