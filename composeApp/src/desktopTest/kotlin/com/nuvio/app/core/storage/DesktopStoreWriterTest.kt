package com.nuvio.app.core.storage

import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 #19: store writes leave the calling (UI) thread.
object StoreWriterExitChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val writer = DesktopStoreWriter.startDefault()
        val store = DesktopStorage.Store(File(args[0]).toPath(), writer)
        repeat(50) { store.putString("k$it", "v$it") }
        System.exit(0)  // the shutdown hook must flush the queued write
    }
}

class DesktopStoreWriterTest {
    private fun load(path: java.nio.file.Path) = Properties().also { p -> Files.newInputStream(path).use { p.load(it) } }

    @Test
    fun `a put returns before the file is written and flush writes it`() {
        val dir = Files.createTempDirectory("storewriter")
        val gate = Object()
        var released = false
        val writer = DesktopStoreWriter { path, bytes ->
            synchronized(gate) { while (!released) gate.wait() }
            writeStoreFileAtomically(path, bytes)
        }
        val file = dir.resolve("a.properties")
        val store = DesktopStorage.Store(file, writer)
        store.putString("key", "value")  // must not block on the held writer
        assertEquals("value", store.getString("key"), "memory is current at once")
        assertFalse(Files.exists(file), "nothing written while the writer is held")
        synchronized(gate) { released = true; gate.notifyAll() }
        writer.flush()
        assertEquals("value", load(file).getProperty("key"))
        writer.close()
    }

    @Test
    fun `queued writes of one file collapse to the newest content`() {
        val dir = Files.createTempDirectory("storewriter")
        var writes = 0
        val gate = Object()
        var released = false
        val writer = DesktopStoreWriter { path, bytes ->
            synchronized(gate) { while (!released) gate.wait() }
            writes++
            writeStoreFileAtomically(path, bytes)
        }
        val file = dir.resolve("b.properties")
        val store = DesktopStorage.Store(file, writer)
        repeat(500) { store.putString("counter", it.toString()) }
        synchronized(gate) { released = true; gate.notifyAll() }
        writer.flush()
        assertEquals("499", load(file).getProperty("counter"))
        assertTrue(writes <= 2, "500 queued puts wrote the file $writes times")
        writer.close()
    }

    @Test
    fun `discard drops queued writes under a folder (wipe)`() {
        val dir = Files.createTempDirectory("storewriter")
        val gate = Object()
        var released = false
        val writer = DesktopStoreWriter { path, bytes ->
            synchronized(gate) { while (!released) gate.wait() }
            writeStoreFileAtomically(path, bytes)
        }
        val first = dir.resolve("first.properties")
        val second = dir.resolve("second.properties")
        DesktopStorage.Store(first, writer).putString("a", "1")  // taken by the writer, held at the gate
        Thread.sleep(100)
        DesktopStorage.Store(second, writer).putString("b", "2")  // still queued
        writer.discardUnder(dir)
        synchronized(gate) { released = true; gate.notifyAll() }
        writer.flush()
        assertFalse(Files.exists(second), "a write queued before the wipe must not recreate the file")
        writer.close()
    }

    @Test
    fun `a normal exit flushes queued writes`() {
        val file = Files.createTempDirectory("storewriter").resolve("exit.properties")
        val p = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", System.getProperty("java.class.path"),
            "com.nuvio.app.core.storage.StoreWriterExitChild", file.toString(),
        ).redirectErrorStream(true).start()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals("v49", load(file).getProperty("k49"))
        assertEquals(50, load(file).size)
    }

    @Test
    fun `a store without a writer still writes at once`() {
        val file = Files.createTempDirectory("storewriter").resolve("sync.properties")
        DesktopStorage.Store(file).putString("key", "value")
        assertEquals("value", load(file).getProperty("key"))
    }
}
