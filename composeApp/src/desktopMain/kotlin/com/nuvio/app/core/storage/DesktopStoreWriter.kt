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
    private val lock = Object()
    private val pending = LinkedHashMap<Path, ByteArray>()
    private var writing = false
    private var closed = false
    private val thread = Thread(::drainQueue, "nuvio-store-writer").apply {
        isDaemon = true
        start()
    }

    fun submit(path: Path, bytes: ByteArray) {
        val key = path.toAbsolutePath().normalize()
        synchronized(lock) {
            if (!closed) {
                pending[key] = bytes  // replaces an older queued save of the same file
                lock.notifyAll()
                return
            }
        }
        writeLogged(key, bytes)  // after shutdown: write on the caller, nothing is lost
    }

    /** Waits until every queued save is written (at most [timeoutMs]). Never call it from the writer thread. */
    fun flush(timeoutMs: Long = Long.MAX_VALUE) {
        val deadline = if (timeoutMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (pending.isNotEmpty() || writing) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return
                lock.wait(minOf(left, 1_000L))
            }
        }
    }

    /** Drops queued (not yet started) saves of files under [dir]; a save already being written still finishes. */
    fun discardUnder(dir: Path) {
        val root = dir.toAbsolutePath().normalize()
        synchronized(lock) { pending.keys.removeIf { it.startsWith(root) } }
    }

    override fun close() = close(Long.MAX_VALUE)

    fun close(timeoutMs: Long) {
        flush(timeoutMs)
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        thread.join(if (timeoutMs == Long.MAX_VALUE) 0 else timeoutMs)
    }

    private fun drainQueue() {
        while (true) {
            val next = synchronized(lock) {
                while (pending.isEmpty() && !closed) lock.wait()
                if (pending.isEmpty()) return
                val first = pending.entries.first()
                pending.remove(first.key)
                writing = true
                first.key to first.value
            }
            try {
                writeLogged(next.first, next.second)
            } finally {
                synchronized(lock) {
                    writing = false
                    lock.notifyAll()
                }
            }
        }
    }

    private fun writeLogged(path: Path, bytes: ByteArray) {
        try {
            write(path, bytes)
        } catch (t: Throwable) {
            // The store keeps the data in memory; its next save tries again.
            System.err.println("nuvio-store-writer: writing $path failed: $t")
        }
    }

    companion object {
        /** The process-wide writer; a shutdown hook flushes it (at most 5 s) at exit. */
        fun startDefault(): DesktopStoreWriter = DesktopStoreWriter().also { writer ->
            Runtime.getRuntime().addShutdownHook(Thread({ writer.close(5_000L) }, "nuvio-store-writer-exit"))
        }
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
