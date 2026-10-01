package com.nuvio.app.fork

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 #8: a packaged app never loads native code from the folder it was started in.
class DesktopDevPathsTest {
    @Test
    fun `dev build paths are used only outside a packaged app`() {
        assertTrue(DesktopDevPaths.allowed(jpackageAppPath = null), "gradle run / tests")
        assertTrue(DesktopDevPaths.allowed(jpackageAppPath = " "))
        assertFalse(DesktopDevPaths.allowed(jpackageAppPath = "C:/Apps/Nuvio RR/Nuvio RR.exe"), "packaged app")
    }

    @Test
    fun `the test JVM is not a packaged app`() {
        assertTrue(DesktopDevPaths.allowedHere)
    }
}
