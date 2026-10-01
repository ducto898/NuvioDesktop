package com.nuvio.app.core.storage

import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * nuvio-rr fork, Phase 9 #19. A store save took ~2.7 ms (atomic write + flush to disk) on the calling thread, which is
 * usually the UI thread. Stores from [DesktopStorage.store] hand their bytes to this one background writer instead:
 * queued saves of one file collapse to the newest content, [flush] waits for everything queued, a JVM shutdown hook
 * flushes at exit, and [discardUnder] drops queued saves before a wipe.
 */
internal class DesktopStoreWriter(
    private val write: (Path, ByteArray) -> Unit = ::writeStoreFileAtomically,
) : AutoCloseable {
    fun submit(path: Path, bytes: ByteArray): Unit = TODO("Phase 9 #19 commit B")

    fun flush(): Unit = TODO("Phase 9 #19 commit B")

    fun discardUnder(dir: Path): Unit = TODO("Phase 9 #19 commit B")

    override fun close(): Unit = TODO("Phase 9 #19 commit B")

    companion object {
        /** The process-wide writer, with its shutdown hook. */
        fun startDefault(): DesktopStoreWriter = TODO("Phase 9 #19 commit B")
    }
}

/**
 * Write a sibling temp file, flush it to disk, then swap it in: a crash or power loss mid-write leaves the previous
 * file intact instead of a truncated one that loads as empty (watch progress, tokens, ...).
 */
internal fun writeStoreFileAtomically(file: Path, bytes: ByteArray) {
    Files.createDirectories(file.parent)
    val temp = file.resolveSibling("${file.fileName}.tmp")
    FileChannel.open(
        temp,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING,
    ).use { channel ->
        Channels.newOutputStream(channel).write(bytes)
        channel.force(true)
    }
    try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: IOException) {
        // e.g. another process holds the file open on Windows: fall back to a direct write.
        Files.deleteIfExists(temp)
        Files.write(file, bytes)
    }
}
