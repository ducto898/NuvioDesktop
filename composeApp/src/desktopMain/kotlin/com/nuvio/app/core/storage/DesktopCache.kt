package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
        return directory
    }

    /**
     * nuvio-rr fork, Phase 9 #6: delete the other version folders of a namespace that were not used for
     * [olderThanMs] (each update left a ~113 MB native runtime behind). A folder used recently may belong to another
     * running copy of the app; a file Windows can't delete (a loaded DLL) is skipped.
     */
    fun pruneOldVersions(namespaceDir: Path, keep: Path, olderThanMs: Long): Unit = TODO("Phase 9 #6 commit B")

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
