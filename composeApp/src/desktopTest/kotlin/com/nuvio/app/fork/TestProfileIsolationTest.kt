package com.nuvio.app.fork

import kotlin.test.Test
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 S6: desktop tests run against a throw-away profile however they are started
// (plain gradlew included), never the user's real %APPDATA% / %LOCALAPPDATA%.
class TestProfileIsolationTest {
    @Test
    fun `the test JVM's profile folders are redirected`() {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
        for (name in listOf("APPDATA", "LOCALAPPDATA")) {
            val value = System.getenv(name).orEmpty().replace("\\", "/").lowercase()
            assertTrue(value.contains("test-profile") || value.contains("testprofile"), "$name=$value")
        }
    }
}
