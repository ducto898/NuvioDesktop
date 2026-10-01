package com.nuvio.app.core.storage

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 #6: every app update left a ~113 MB native runtime folder behind.
class DesktopCachePruneTest {
    private val day = 24 * 60 * 60 * 1000L

    private fun version(ns: Path, name: String, ageMs: Long): Path {
        val dir = ns.resolve(name)
        Files.createDirectories(dir)
        val file = dir.resolve("libmpv-2.dll")
        Files.write(file, ByteArray(1024))
        val time = FileTime.fromMillis(System.currentTimeMillis() - ageMs)
        Files.setLastModifiedTime(file, time)
        Files.setLastModifiedTime(dir, time)
        return dir
    }

    @Test
    fun `old versions are deleted, the current and recently used ones are kept`() {
        val ns = Files.createTempDirectory("cacheprune")
        val current = version(ns, "current", ageMs = 0)
        val old1 = version(ns, "old1", ageMs = 3 * day)
        val old2 = version(ns, "old2", ageMs = 40 * day)
        val recent = version(ns, "recent", ageMs = 60 * 60 * 1000L)
        DesktopCache.pruneOldVersions(ns, keep = current, olderThanMs = day)
        assertTrue(Files.exists(current))
        assertTrue(Files.exists(recent), "a version used within the last day may belong to a running copy")
        assertFalse(Files.exists(old1))
        assertFalse(Files.exists(old2))
    }

    @Test
    fun `a locked file is skipped without failing and the rest is still pruned`() {
        val ns = Files.createTempDirectory("cacheprune")
        val current = version(ns, "current", ageMs = 0)
        val locked = version(ns, "locked", ageMs = 3 * day)
        val old = version(ns, "old", ageMs = 3 * day)
        FileChannel.open(locked.resolve("libmpv-2.dll"), StandardOpenOption.READ).use {
            // Windows refuses to delete an open file
            DesktopCache.pruneOldVersions(ns, keep = current, olderThanMs = day)
        }
        assertTrue(Files.exists(current))
        assertFalse(Files.exists(old))
    }

    @Test
    fun `installVersionedFiles prunes its namespace`() {
        val ns = "prune-test-${System.nanoTime()}"
        val first = DesktopCache.installVersionedFiles(ns, mapOf("a.txt" to "one".toByteArray()))
        val oldTime = FileTime.fromMillis(System.currentTimeMillis() - 3 * day)
        Files.setLastModifiedTime(first.resolve("a.txt"), oldTime)
        Files.setLastModifiedTime(first, oldTime)
        val second = DesktopCache.installVersionedFiles(ns, mapOf("a.txt" to "two".toByteArray()))
        assertTrue(Files.exists(second))
        assertFalse(Files.exists(first), "the previous version of the namespace is removed")
    }
}
