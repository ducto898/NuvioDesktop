package com.nuvio.app.fork

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// SPEC P8-3: the fork's app identity comes from one system property; anything unusable means upstream's "Nuvio".
class ForkIdentityTest {
    @Test
    fun `no property is upstream`() {
        assertEquals("Nuvio", ForkIdentity.appDirName(null))
        assertFalse(ForkIdentity.isFork(null))
    }

    @Test
    fun `a valid name is used, trimmed`() {
        assertEquals("Nuvio RR", ForkIdentity.appDirName("Nuvio RR"))
        assertEquals("Nuvio RR", ForkIdentity.appDirName("  Nuvio RR "))
        assertTrue(ForkIdentity.isFork("Nuvio RR"))
    }

    @Test
    fun `blank or unsafe names fall back to upstream`() {
        for (bad in listOf("", "   ", ".", "..", "a\\b", "a/b", "C:x", "x*", "x?", "x\"", "x<", "x>", "x|", "tail.", "x".repeat(65))) {
            assertEquals("Nuvio", ForkIdentity.appDirName(bad), "'$bad'")
            assertFalse(ForkIdentity.isFork(bad), "'$bad'")
        }
    }

    @Test
    fun `the upstream name itself is not a fork`() {
        assertEquals("Nuvio", ForkIdentity.appDirName("Nuvio"))
        assertFalse(ForkIdentity.isFork("Nuvio"))
    }

    // Phase 8 verifier round: Windows paths ignore case, so "nuvio" is the official folder too
    @Test
    fun `the upstream name in any case is not a fork`() {
        for (name in listOf("nuvio", "NUVIO", " nUvIo ")) {
            assertEquals("Nuvio", ForkIdentity.appDirName(name), "'$name'")
            assertFalse(ForkIdentity.isFork(name), "'$name'")
            assertTrue(ForkIdentity.updaterEnabled(name), "'$name'")
        }
    }

    // Q44: upstream's App icon setting rewrites the OFFICIAL Nuvio shortcuts; the portable fork has none of its own
    @Test
    fun `the shortcut icon updater is on upstream and off in the fork`() {
        assertTrue(ForkIdentity.shortcutIconsEnabled(null))
        assertFalse(ForkIdentity.shortcutIconsEnabled("Nuvio RR"))
        assertTrue(ForkIdentity.shortcutIconsEnabled("nuvio"), "the upstream name is upstream")
    }

    // P8-8: the in-app updater would fetch the official build; it is off whenever the fork identity is on
    @Test
    fun `the updater is on upstream and off in the fork`() {
        assertTrue(ForkIdentity.updaterEnabled(null))
        assertFalse(ForkIdentity.updaterEnabled("Nuvio RR"))
        assertTrue(ForkIdentity.updaterEnabled("a/b"), "an unusable name is upstream")
    }
}
