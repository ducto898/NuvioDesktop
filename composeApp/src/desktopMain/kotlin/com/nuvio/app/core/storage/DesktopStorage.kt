package com.nuvio.app.core.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.Locale
import java.util.Properties
import kotlin.io.path.exists

internal object DesktopStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()

    val rootDir: Path by lazy {
        resolveAppDataDir().also { Files.createDirectories(it) }
    }

    val cacheDir: Path by lazy {
        resolveCacheDir().also { Files.createDirectories(it) }
    }

    // nuvio-rr fork, Phase 9 #19: app stores save on a background writer, not on the calling (UI) thread.
    private val writer by lazy { DesktopStoreWriter.startDefault() }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties"), writer, DesktopSecretStores.codecFor(name)) }
    }

    fun wipe() {
        synchronized(stores) {
            stores.values.forEach(Store::clearInMemory)
            stores.clear()
        }
        // Phase 9 #19: no queued save may recreate a file after the wipe.
        writer.discardUnder(rootDir)
        writer.flush(5_000L)
        if (!rootDir.exists()) return
        Files.walk(rootDir).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .filter { it != rootDir }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun resolveAppDataDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Application Support/Nuvio")
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let(Paths::get) ?: userHome.resolve("AppData/Roaming")).resolve(com.nuvio.app.fork.ForkIdentity.appDirName) // nuvio-rr fork hook H11
            }
            else -> {
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                (xdgConfig?.let(Paths::get) ?: userHome.resolve(".config")).resolve("nuvio")
            }
        }
    }

    private fun resolveCacheDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Caches/Nuvio")
            osName.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                (localAppData?.let(Paths::get) ?: userHome.resolve("AppData/Local")).resolve("${com.nuvio.app.fork.ForkIdentity.appDirName}/Cache") // nuvio-rr fork hook H12
            }
            else -> {
                val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                (xdgCache?.let(Paths::get) ?: userHome.resolve(".cache")).resolve("nuvio")
            }
        }
    }

    internal class Store(
        private val file: Path,
        // nuvio-rr fork, Phase 9 #19: null = write on the calling thread (tests, tools).
        private val writer: DesktopStoreWriter? = null,
        // nuvio-rr fork, Phase 9 S1: encrypts the file of a secret store (null = plain text).
        private val codec: StoreCodec? = null,
    ) {
        private val lock = Any()
        private val properties = Properties()
        private var loaded = false

        fun contains(key: String): Boolean = synchronized(lock) {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = synchronized(lock) {
            ensureLoaded()
            properties.getProperty(key)
        }

        fun putString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            val changed = if (value == null) {
                properties.remove(key) != null
            } else {
                properties.setProperty(key, value) != value
            }
            if (changed) persist()
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = synchronized(lock) {
            ensureLoaded()
            if (properties.remove(key) != null) persist()
        }

        fun removeAll(keys: Iterable<String>) = synchronized(lock) {
            ensureLoaded()
            var changed = false
            keys.forEach { key ->
                if (properties.remove(key) != null) changed = true
            }
            if (changed) persist()
        }

        fun clearInMemory() = synchronized(lock) {
            properties.clear()
            loaded = false
        }

        private fun ensureLoaded() {
            if (loaded) return
            loaded = true
            properties.clear()
            if (!file.exists()) return
            runCatching {
                val stored = Files.readAllBytes(file)
                val plain = if (codec == null) stored else codec.decode(stored)
                if (plain == null) {
                    System.err.println("nuvio storage: ${file.fileName} can't be decrypted on this Windows account; starting empty")
                } else {
                    properties.load(plain.inputStream())
                }
            }
        }

        private fun persist() {
            val plain = ByteArrayOutputStream().also { properties.store(it, "Nuvio desktop preferences") }.toByteArray()
            val bytes = try {
                codec?.encode(plain) ?: plain
            } catch (t: Throwable) {
                // Phase 9 S1: never fall back to writing a secret store in plain text; the data stays in memory.
                System.err.println("nuvio storage: encrypting ${file.fileName} failed ($t); not saved")
                return
            }
            if (writer != null) writer.submit(file, bytes) else writeStoreFileAtomically(file, bytes)
        }
    }
}
