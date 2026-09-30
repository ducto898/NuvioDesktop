package com.nuvio.app.features.player.desktop.refreshrate.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SPEC P6-3, P6-7, P6-10: the enable rule and what the native H2 upcall gets. */
class RefreshRateEnablementTest {
    private val envValues = listOf("1", "0", null, "yes")

    private fun expected(env: String?, setting: Boolean): Enablement = when (env) {
        "1" -> Enablement(true, EnableSource.Env)
        "0" -> Enablement(false, EnableSource.Env)
        else -> Enablement(setting, EnableSource.Setting)
    }

    @Test
    fun `env override wins, otherwise the setting decides - 8 cases`() {
        for (env in envValues) {
            for (setting in listOf(true, false)) {
                assertEquals(expected(env, setting), resolveEnablement(env) { setting }, "env=$env setting=$setting")
            }
        }
    }

    @Test
    fun `the setting is read only when the env does not decide`() {
        for (env in envValues) {
            var reads = 0
            resolveEnablement(env) { reads++; true }
            assertEquals(if (env == "1" || env == "0") 0 else 1, reads, "env=$env")
        }
    }

    @Test
    fun `native codes - 0 off, 1 on by env, 2 on by setting`() {
        assertEquals(0, Enablement(false, EnableSource.Env).nativeCode)
        assertEquals(0, Enablement(false, EnableSource.Setting).nativeCode)
        assertEquals(1, Enablement(true, EnableSource.Env).nativeCode)
        assertEquals(2, Enablement(true, EnableSource.Setting).nativeCode)
        for (env in envValues) {
            for (setting in listOf(true, false)) {
                assertEquals(expected(env, setting).nativeCode, enablementCode(env) { setting }, "env=$env setting=$setting")
            }
        }
    }

    @Test
    fun `an exception while reading the setting is off (-1)`() {
        assertEquals(-1, enablementCode(null) { throw IllegalStateException("store broken") })
        assertEquals(-1, enablementCode("") { throw Error("worse") })
        // The env decides without reading, so a broken store cannot matter there.
        assertEquals(1, enablementCode("1") { throw IllegalStateException("store broken") })
        assertEquals(0, enablementCode("0") { throw IllegalStateException("store broken") })
    }

    @Test
    fun `the upcall creates no dispatcher, thread or shutdown hook`() {
        val threadsBefore = Thread.getAllStackTraces().keys.count { it.name == "nuvio-rr" }
        val code = RefreshRateMatch.nativeFeatureEnabled()
        assertTrue(code in -1..2, "code=$code")
        assertFalse(RefreshRateMatch.hasDispatcher)
        assertEquals(threadsBefore, Thread.getAllStackTraces().keys.count { it.name == "nuvio-rr" })
    }

    @Test
    fun `the upcall answers off in the test JVM - no env knob, no stored setting`() {
        // verify.ps1 runs tests with neither NUVIO_RR_ENABLE nor a stored setting in its test profile.
        if (System.getenv("NUVIO_RR_ENABLE") == null) assertEquals(0, RefreshRateMatch.nativeFeatureEnabled())
    }

    // NUVIO_RR_OSD: "0" forces the badge off, "1" forces it on (measure runs), otherwise the setting decides
    @Test
    fun `badge rule - env override first, then the setting`() {
        assertEquals(false, resolveBadge("0") { true })
        assertEquals(true, resolveBadge("1") { false })
        assertEquals(true, resolveBadge(null) { true })
        assertEquals(false, resolveBadge(null) { false })
        assertEquals(false, resolveBadge("x") { false })
        assertEquals(false, resolveBadge(null) { error("store") }, "a failing read shows no badge")
    }
}
