package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Comparator

internal object DesktopCache {
    fun installVersionedFiles(namespace: String, files: Map<String, ByteArray>): Path {
        require(namespace.isNotBlank())
        require(files.isNotEmpty())
        val version = contentVersion(files)
        val directory = DesktopStorage.cacheDir.resolve(namespace).resolve(version).normalize()
        require(directory.startsWith(DesktopStorage.cacheDir))
        files.forEach { (relativePath, bytes) ->
            val target = directory.resolve(relativePath).normalize()
            require(target.startsWith(directory))
            writeIfChanged(target, bytes)
        }
        // Phase 9 #6: mark this version as used now, then drop versions no copy of the app used for a day.
        runCatching { Files.setLastModifiedTime(directory, FileTime.fromMillis(System.currentTimeMillis())) }
        runCatching { pruneOldVersions(directory.parent, keep = directory, olderThanMs = UNUSED_VERSION_AGE_MS) }
        return directory
    }

    /**
     * nuvio-rr fork, Phase 9 #6: delete the other version folders of a namespace that were not used for
     * [olderThanMs] (each update left a ~113 MB native runtime behind). A folder used recently may belong to another
     * running copy of the app; a file Windows can't delete (a loaded DLL) is skipped.
     */
    fun pruneOldVersions(namespaceDir: Path, keep: Path, olderThanMs: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        val keepNormalized = keep.toAbsolutePath().normalize()
        val siblings = Files.list(namespaceDir).use { stream -> stream.filter(Files::isDirectory).toList() }
        for (dir in siblings) {
            if (dir.toAbsolutePath().normalize() == keepNormalized) continue
            if (Files.getLastModifiedTime(dir).toMillis() > cutoff) continue
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { Files.deleteIfExists(path) } }
            }
        }
    }

    private const val UNUSED_VERSION_AGE_MS = 24 * 60 * 60 * 1000L

    private fun contentVersion(files: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (path, bytes) ->
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }.take(24)
    }

    private fun writeIfChanged(target: Path, bytes: ByteArray) {
        if (Files.exists(target) && Files.readAllBytes(target).contentEquals(bytes)) return
        Files.createDirectories(target.parent)
        val pending = Files.createTempFile(target.parent, target.fileName.toString(), ".part")
        try {
            Files.write(pending, bytes)
            runCatching {
                Files.move(
                    pending,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(pending)
        }
    }
}
