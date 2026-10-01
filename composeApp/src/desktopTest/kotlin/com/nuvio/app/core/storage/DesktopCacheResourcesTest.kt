package com.nuvio.app.core.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 #10: the ~115 MB native runtime was read from the jar, hashed and byte-compared on every
// launch (0.68 s on the main thread). A cheap stamp (zip CRC + size) decides; an installed version is not read again.
class DesktopCacheResourcesTest {
    private val content = mapOf("libmpv-2.dll" to ByteArray(4096) { 1 }, "player_bridge.dll" to ByteArray(512) { 2 })

    private class Counting(val content: Map<String, ByteArray>, val stamp: (String) -> String?) {
        var reads = 0
        fun read(name: String): ByteArray? { reads++; return content[name] }
    }

    @Test
    fun `an installed version is reused without reading any resource`() {
        val ns = "res-test-${System.nanoTime()}"
        val first = Counting(content) { name -> content[name]?.let { "crc-${it.size}-$name" } }
        val dir1 = DesktopCache.installVersionedResources(ns, listOf("libmpv-2.dll", "player_bridge.dll", "optional.dll"), first.stamp, first::read)
        assertEquals(2, first.reads, "the first install reads the two present files")
        assertTrue(content.all { (name, bytes) -> Files.readAllBytes(dir1.resolve(name)).contentEquals(bytes) })

        val second = Counting(content) { name -> content[name]?.let { "crc-${it.size}-$name" } }
        val dir2 = DesktopCache.installVersionedResources(ns, listOf("libmpv-2.dll", "player_bridge.dll", "optional.dll"), second.stamp, second::read)
        assertEquals(dir1, dir2)
        assertEquals(0, second.reads, "a complete install must not read the resources again")
    }

    @Test
    fun `a changed resource installs a new version`() {
        val ns = "res-test-${System.nanoTime()}"
        val a = Counting(content) { name -> content[name]?.let { "v1-$name" } }
        val dir1 = DesktopCache.installVersionedResources(ns, content.keys.toList(), a.stamp, a::read)
        val changed = content + ("player_bridge.dll" to ByteArray(600) { 3 })
        val b = Counting(changed) { name -> if (name == "player_bridge.dll") "v2-$name" else "v1-$name" }
        val dir2 = DesktopCache.installVersionedResources(ns, changed.keys.toList(), b.stamp, b::read)
        assertNotEquals(dir1, dir2)
        assertTrue(b.reads > 0)
        assertTrue(Files.readAllBytes(dir2.resolve("player_bridge.dll")).contentEquals(changed.getValue("player_bridge.dll")))
    }

    @Test
    fun `an interrupted install (no completion marker) is redone`() {
        val ns = "res-test-${System.nanoTime()}"
        val a = Counting(content) { name -> "s-$name" }
        val dir = DesktopCache.installVersionedResources(ns, content.keys.toList(), a.stamp, a::read)
        Files.delete(dir.resolve(".complete"))
        Files.write(dir.resolve("libmpv-2.dll"), ByteArray(10))  // half-written file from a crash
        val b = Counting(content) { name -> "s-$name" }
        DesktopCache.installVersionedResources(ns, content.keys.toList(), b.stamp, b::read)
        assertTrue(b.reads > 0)
        assertTrue(Files.readAllBytes(dir.resolve("libmpv-2.dll")).contentEquals(content.getValue("libmpv-2.dll")))
    }
}
