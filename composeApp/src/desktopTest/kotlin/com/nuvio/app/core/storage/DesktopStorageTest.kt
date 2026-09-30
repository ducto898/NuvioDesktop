package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class DesktopStorageTest {
    @Test
    fun unchanged_operations_do_not_rewrite_the_store() {
        val directory = Files.createTempDirectory("desktop-storage-test")
        val file = directory.resolve("preferences.properties")
        try {
            val store = DesktopStorage.Store(file)
            store.putString("key", "value")
            val sentinel = FileTime.fromMillis(1_000L)
            Files.setLastModifiedTime(file, sentinel)

            store.putString("key", "value")
            store.remove("missing")
            store.removeAll(listOf("also-missing"))

            assertEquals(sentinel, Files.getLastModifiedTime(file))

            store.putString("key", "updated")

            assertNotEquals(sentinel, Files.getLastModifiedTime(file))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun a_write_replaces_the_file_whole_and_leaves_no_temp_file() {
        val directory = Files.createTempDirectory("desktop-storage-test")
        val file = directory.resolve("preferences.properties")
        val temp = directory.resolve("preferences.properties.tmp")
        try {
            val store = DesktopStorage.Store(file)
            store.putString("progress", "x".repeat(10_000))
            store.putString("token", "abc")

            assertFalse(Files.exists(temp), "the temp file is moved over the store file")
            val reloaded = DesktopStorage.Store(file)
            assertEquals("x".repeat(10_000), reloaded.getString("progress"))
            assertEquals("abc", reloaded.getString("token"))
        } finally {
            Files.deleteIfExists(temp)
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun a_temp_file_left_by_a_crash_does_not_replace_the_store() {
        val directory = Files.createTempDirectory("desktop-storage-test")
        val file = directory.resolve("preferences.properties")
        val temp = directory.resolve("preferences.properties.tmp")
        try {
            DesktopStorage.Store(file).putString("key", "value")
            Files.writeString(temp, "key=half-writ") // a crash between writing the temp file and the move

            assertEquals("value", DesktopStorage.Store(file).getString("key"))
        } finally {
            Files.deleteIfExists(temp)
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
