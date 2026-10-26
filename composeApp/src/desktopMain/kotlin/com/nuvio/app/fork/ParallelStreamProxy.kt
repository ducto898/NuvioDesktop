package com.nuvio.app.fork

import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * nuvio-rr fork, PAR (owner 2026-10-27). mpv reads a stream over one HTTP connection, so a debrid server that gives
 * each connection 45 Mbit/s cannot feed an 80 Mbit/s remux (owner log 2026-10-27, Oppenheimer from an RD server
 * whose per-connection rate varied 0.5-120 Mbit/s by file region). Remote http(s) sources go through this proxy on
 * 127.0.0.1 instead: it fetches the file in [SEGMENT_BYTES] segments over [Config.connections] parallel range requests
 * and serves the bytes to mpv in order. It also keeps what it already served ([Config.behindSegments] behind mpv's
 * read point), splits the part mpv is waiting for over more connections when one is slow (the rate of a debrid
 * connection depends on the file region it reads), and drops a connection that only trickles ([Config.trickleBytes] in [Config.trickleMs]) to fetch the rest
 * again. Keeping what was served means mpv's re-reads come from memory: picking an embedded subtitle drops mpv's own cache and reads again
 * from ~11 s back (refresh seek), and short seeks back do the same.
 * A source that does not answer a range request with 206 and a total size is handed back to mpv with a 302, so mpv
 * plays it directly as before. NUVIO_RR_STREAM_CONNECTIONS=0 turns the proxy off; 1..16 sets the connection count.
 * Sessions keep their source and headers for the app's lifetime (small); the segment memory is freed after
 * [Config.idleMs] without a reader, and a later request starts the session again.
 * The log (stream-proxy.log in the cache folder) names hosts only: debrid links carry API keys.
 */
internal object ParallelStreamProxy {
    const val SEGMENT_BYTES = 8 * 1024 * 1024 // live test 2026-10-27: 4-8 MiB beat 16 MiB on a slow RD server

    data class Config(
        val connections: Int = DEFAULT_CONNECTIONS,
        val aheadSegments: Int = 24, // 192 MiB fetched ahead of mpv's read point
        val behindSegments: Int = 80, // 640 MiB kept behind it: more than mpv's 512 MiB forward cache + 11 s
        val segmentBytes: Int = SEGMENT_BYTES,
        val idleMs: Long = 60_000,
        val connectTimeoutMs: Int = 10_000,
        val readTimeoutMs: Int = 15_000,
        val trickleMs: Long = 8_000,
        val trickleBytes: Int = 256 * 1024,
        val minSplitBytes: Int = 1024 * 1024,
        val preemptAfterMs: Long = 700,
        val logFile: Path? = runCatching { DesktopStorage.cacheDir.resolve("stream-proxy.log") }.getOrNull(),
    )

    @Volatile
    private var instance: Server? = null

    /** The URL mpv should open for [source]: a proxy URL for a remote file, else [source] unchanged. */
    fun wrap(source: String, headerLines: List<String>): String {
        val connections = configuredConnections(System.getenv("NUVIO_RR_STREAM_CONNECTIONS"))
        if (connections == 0 || !isProxyable(source)) return source
        return runCatching {
            val server = synchronized(this) {
                instance?.takeIf { it.config.connections == connections }
                    ?: Server(Config(connections = connections)).also { instance?.stop(); instance = it }
            }
            server.register(source, headerLines)
        }.getOrElse { source }
    }

    const val DEFAULT_CONNECTIONS = 6

    fun configuredConnections(value: String?): Int = value?.trim()?.toIntOrNull()?.coerceIn(0, 16) ?: DEFAULT_CONNECTIONS

    fun isProxyable(source: String): Boolean {
        val uri = runCatching { URI(source) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        // local servers (nuvio-engine streams torrents from 127.0.0.1 and orders pieces by its reader) are left alone
        if (host == "localhost" || host.startsWith("127.") || host == "::1" || host == "[::1]") return false
        val path = uri.path?.lowercase().orEmpty()
        // playlists resolve their segments relative to their own URL
        return !(path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd"))
    }

    fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "?"

    class Server(val config: Config) {
        private val http: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64)
        private val sessions = ConcurrentHashMap<String, Session>()
        private val log = ProxyLog(config.logFile)
        private val reaper = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nuvio-rr-stream-reaper").apply { isDaemon = true } }
        val port: Int

        init {
            http.executor = Executors.newCachedThreadPool { r -> Thread(r, "nuvio-rr-stream-serve").apply { isDaemon = true } }
            http.createContext("/s/") { exchange -> serve(exchange) }
            http.start()
            port = http.address.port
            reaper.scheduleWithFixedDelay({ sessions.values.forEach { it.reapIfIdle() } }, 5, 5, TimeUnit.SECONDS)
        }

        fun register(source: String, headerLines: List<String>): String {
            val id = UUID.randomUUID().toString().replace("-", "").take(16)
            val headers = headerLines.mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
            sessions[id] = Session(id, source, headers, config, log)
            log.line("session $id registered: host ${hostOf(source)}, ${config.connections} connection(s)")
            val name = runCatching { URI(source).path.substringAfterLast('/') }.getOrNull().orEmpty().ifBlank { "stream" }
            return "http://127.0.0.1:$port/s/$id/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        }

        fun session(id: String): Session? = sessions[id]

        fun stop() {
            sessions.values.forEach { it.sleep("server stopped") }
            reaper.shutdownNow()
            http.stop(0)
            (http.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }

        private fun serve(exchange: HttpExchange) {
            try {
                val id = exchange.requestURI.path.removePrefix("/s/").substringBefore('/')
                val session = sessions[id]
                if (session == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    session.serve(exchange)
                }
            } catch (_: IOException) {
                // mpv closed the connection (seek, stop)
            } catch (error: Exception) {
                log.line("serve failed: ${error.javaClass.simpleName}")
            } finally {
                exchange.close()
            }
        }
    }

    /** One range request's share of a segment: bytes [start, end) of it, of which [got] have arrived. */
    class Piece(val segment: Segment, val start: Int, var end: Int) {
        var got = 0
        var loading = false
        var errors = 0 // in a row; reset by data
        val next: Int get() = start + got
        val complete: Boolean get() = next >= end
    }

    /** [pieces] are sorted and cover [0, length) without gaps; a split shortens one and adds the rest after it. */
    class Segment(val index: Long, val data: ByteBuffer, val length: Int) { // direct: up to ~850 MiB, kept off the 2 GiB heap
        val pieces = mutableListOf(Piece(this, 0, length))
        var lastUsed = 0L

        /** Bytes readable from the segment start. */
        val filled: Int
            get() {
                var pos = 0
                for (piece in pieces) {
                    if (piece.start > pos) break
                    pos = maxOf(pos, piece.next) // a cut piece may have read past its new end: those bytes are valid
                    if (!piece.complete) break
                }
                return pos
            }
        val done: Boolean get() = pieces.all { it.complete }
        val loading: Boolean get() = pieces.any { it.loading }
    }

    class Session(
        val id: String,
        private val source: String,
        private val headers: List<Pair<String, String>>,
        private val config: Config,
        private val log: ProxyLog,
    ) {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private var target: URL? = null
        private var size = -1L
        private var contentType: String? = null
        private var redirect: String? = null
        private var failure: String? = null
        private var failureAt = 0L
        private var resolving = false
        private var reResolves = 0
        private val segments = HashMap<Long, Segment>()
        private var focus = 0L
        private var readers = 0
        private var lastActivity = System.currentTimeMillis()
        private val workers = ArrayList<Thread>()
        private var generation = 0
        private var readerWaitingSince = 0L // 0: the reader is not waiting
        private var lastPreemptAt = 0L
        // statistics for the log, reset every 10 s
        private var fetched = 0L
        private var served = 0L
        private var waitedMs = 0L
        private var statsSince = System.currentTimeMillis()
        var upstreamRequests = 0
            private set

        private val lastIndex: Long get() = (size - 1) / config.segmentBytes

        fun serve(exchange: HttpExchange) {
            val head = exchange.requestMethod.equals("HEAD", ignoreCase = true)
            val range = exchange.requestHeaders.getFirst("Range")
            val (start, requestedEnd) = parseRange(range)
            lock.withLock {
                readers++
                lastActivity = System.currentTimeMillis()
            }
            try {
                ensureResolved(start)
                val (total, type, moved, failed) = lock.withLock { Quad(size, contentType, redirect, failure) }
                if (moved != null) {
                    exchange.responseHeaders.add("Location", moved)
                    exchange.sendResponseHeaders(302, -1)
                    return
                }
                if (failed != null || total < 0) {
                    exchange.sendResponseHeaders(502, -1)
                    return
                }
                if (start >= total) {
                    exchange.responseHeaders.add("Content-Range", "bytes */$total")
                    exchange.sendResponseHeaders(416, -1)
                    return
                }
                val end = minOf(requestedEnd ?: (total - 1), total - 1)
                val length = end - start + 1
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Type", type ?: "application/octet-stream")
                if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
                exchange.sendResponseHeaders(if (range != null) 206 else 200, if (head) -1 else length)
                if (head) return
                stream(exchange.responseBody, start, end)
            } finally {
                lock.withLock {
                    readers--
                    lastActivity = System.currentTimeMillis()
                }
            }
        }

        private fun stream(out: java.io.OutputStream, start: Long, end: Long) {
            val buffer = ByteArray(256 * 1024)
            var pos = start
            while (pos <= end) {
                val count = lock.withLock {
                    val index = pos / config.segmentBytes
                    val offset = (pos % config.segmentBytes).toInt()
                    if (focus != index) {
                        focus = index
                        changed.signalAll()
                    }
                    ensureWorkersLocked()
                    var segment = segments[index]
                    val waitStart = System.nanoTime()
                    var waited = false
                    while ((segment == null || segment.filled <= offset) && failure == null) {
                        waited = true
                        if (readerWaitingSince == 0L) readerWaitingSince = System.currentTimeMillis()
                        changed.await(250, TimeUnit.MILLISECONDS)
                        segment = segments[index]
                        if (focus != index) {
                            focus = index
                            changed.signalAll()
                        }
                    }
                    // a trickle that keeps the reader waiting keeps it marked; only data already there clears it
                    if (!waited) readerWaitingSince = 0L
                    waitedMs += (System.nanoTime() - waitStart) / 1_000_000
                    if (failure != null || segment == null) return
                    segment.lastUsed = System.nanoTime()
                    lastActivity = System.currentTimeMillis()
                    val remaining = end - pos + 1
                    val n = minOf(segment.filled - offset, buffer.size).let { if (remaining < it) remaining.toInt() else it }
                    segment.data.get(offset, buffer, 0, n)
                    served += n
                    n
                }
                out.write(buffer, 0, count)
                pos += count
                logStatsIfDue()
            }
            out.flush()
        }

        /** Opens the source once (following redirects) to learn the final URL and the size. */
        private fun ensureResolved(start: Long) {
            lock.withLock {
                while (resolving) changed.await(500, TimeUnit.MILLISECONDS)
                // a failed source is tried again by the next request (mpv reconnects), but not in a tight loop
                if (failure != null && System.currentTimeMillis() - failureAt > 3_000) failure = null
                if (target != null || redirect != null || failure != null) return
                resolving = true
            }
            val alignedStart = start - start % config.segmentBytes
            var connection: HttpURLConnection? = null
            try {
                val conn = open(URL(source), alignedStart, alignedStart + config.segmentBytes - 1)
                connection = conn
                val code = conn.responseCode
                val total = totalFromContentRange(conn.getHeaderField("Content-Range"))
                val type = conn.contentType
                val finalUrl = conn.url
                val playlist = type?.lowercase()?.let { "mpegurl" in it || "dash+xml" in it } == true
                if (code == 206 && total != null && total > 0 && !playlist) {
                    lock.withLock {
                        target = finalUrl
                        size = total
                        contentType = type
                        val index = alignedStart / config.segmentBytes
                        val piece = (segments[index] ?: newSegmentLocked(index))?.pieces?.singleOrNull()
                        if (piece != null && piece.got == 0 && !piece.loading) {
                            piece.loading = true
                            val gen = generation
                            Thread({ fill(piece, conn, gen) }, "nuvio-rr-stream-first").apply { isDaemon = true }.start()
                            connection = null
                        }
                    }
                    log.line("session $id resolved: host ${finalUrl.host}, ${total / 1_000_000} MB, ${config.connections} connection(s)")
                } else if (code in 200..399) {
                    lock.withLock { redirect = finalUrl.toString() }
                    log.line("session $id: no range support (HTTP $code${if (playlist) ", playlist" else ""}); mpv plays ${finalUrl.host} directly")
                } else {
                    lock.withLock { failure = "HTTP $code"; failureAt = System.currentTimeMillis() }
                    log.line("session $id: source answered HTTP $code (host ${finalUrl.host})")
                }
            } catch (error: IOException) {
                lock.withLock { failure = error.javaClass.simpleName; failureAt = System.currentTimeMillis() }
                log.line("session $id: source failed: ${error.javaClass.simpleName} (host ${hostOf(source)})")
            } finally {
                connection?.disconnect()
                lock.withLock {
                    resolving = false
                    changed.signalAll()
                }
            }
        }

        private fun open(url: URL, from: Long, to: Long): HttpURLConnection {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = config.connectTimeoutMs
            conn.readTimeout = config.readTimeoutMs
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            if (headers.none { it.first.equals("User-Agent", ignoreCase = true) }) conn.setRequestProperty("User-Agent", "libmpv")
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Range", "bytes=$from-$to")
            lock.withLock { upstreamRequests++ }
            return conn
        }

        private fun ensureWorkersLocked() {
            if (workers.size >= config.connections || target == null) return
            val gen = generation
            repeat(config.connections - workers.size) { n ->
                workers += Thread({ work(gen) }, "nuvio-rr-stream-$id-$n").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        private fun work(gen: Int) {
            while (true) {
                val (piece, url) = lock.withLock {
                    var picked: Piece? = null
                    while (picked == null) {
                        if (gen != generation) return
                        picked = if (target == null) null else pickLocked() // null target: resolving again
                        if (picked == null) changed.await(250, TimeUnit.MILLISECONDS)
                    }
                    picked.loading = true
                    picked to target!!
                }
                val base = piece.segment.index * config.segmentBytes
                try {
                    val conn = open(url, base + piece.next, base + piece.end - 1)
                    val code = conn.responseCode
                    if (code == 206) {
                        fill(piece, conn, gen)
                    } else {
                        conn.disconnect()
                        failed(piece, "HTTP $code", expired = code == 401 || code == 403 || code == 404 || code == 410)
                    }
                } catch (error: IOException) {
                    failed(piece, error.javaClass.simpleName, expired = false)
                }
            }
        }

        /**
         * What an idle connection fetches next: an unfetched piece of the reader's segment or the one after it, then
         * the file's last segment (an mkv's index, Cues, is there; mpv reads it at its first seek or subtitle switch,
         * and it is kept), then a split of the reader's segment if the reader waits, then the rest of the window in
         * order, and with nothing left, a split of the slowest piece near the reader.
         */
        private fun pickLocked(): Piece? {
            if (size < 0) return null
            val last = minOf(lastIndex, focus + config.aheadSegments)
            fun unfetched(index: Long): Piece? {
                val segment = segments[index] ?: newSegmentLocked(index) ?: return null
                return segment.pieces.firstOrNull { !it.complete && !it.loading }
            }
            for (index in focus..minOf(last, focus + 1)) unfetched(index)?.let { return it }
            if (lastIndex > last) unfetched(lastIndex)?.let { return it }
            if (readerWaitingSince != 0L) splitLocked(focus)?.let { return it }
            for (index in focus + 2..last) unfetched(index)?.let { return it }
            for (index in focus..minOf(last, focus + 1)) splitLocked(index)?.let { return it }
            return null
        }

        /** Gives the second half of the segment's largest unfinished, loading piece to a new piece. */
        private fun splitLocked(index: Long): Piece? {
            val segment = segments[index] ?: return null
            val largest = segment.pieces.filter { it.loading }.maxByOrNull { it.end - it.next } ?: return null
            val remaining = largest.end - largest.next
            if (remaining < 2 * config.minSplitBytes) return null
            val mid = largest.next + remaining / 2
            val piece = Piece(segment, mid, largest.end)
            largest.end = mid
            segment.pieces.add(segment.pieces.indexOf(largest) + 1, piece)
            return piece
        }

        private fun newSegmentLocked(index: Long): Segment? {
            val length = minOf(config.segmentBytes.toLong(), size - index * config.segmentBytes).toInt()
            if (length <= 0) return null
            val capacity = config.aheadSegments + config.behindSegments + 1
            val data = if (segments.size < capacity) {
                ByteBuffer.allocateDirect(config.segmentBytes)
            } else {
                // evict the least recently read segment outside [focus - behind, focus + ahead]
                val victim = segments.values
                    .filter { !it.loading && it.index != lastIndex }
                    .filter { it.index < focus - config.behindSegments || it.index > focus + config.aheadSegments }
                    .minByOrNull { it.lastUsed }
                    ?: segments.values
                        .filter { !it.loading && it.index != lastIndex && it.index < focus && it.index < index }
                        .minByOrNull { it.lastUsed }
                    ?: return null
                segments.remove(victim.index)
                victim.data
            }
            return Segment(index, data, length).also {
                it.lastUsed = System.nanoTime()
                segments[index] = it
            }
        }

        private fun fill(piece: Piece, conn: HttpURLConnection, gen: Int) {
            val segment = piece.segment
            val requestedEnd = lock.withLock { piece.end }
            var input: InputStream? = null
            try {
                input = conn.inputStream
                var windowStart = System.currentTimeMillis()
                var windowBytes = 0
                val chunk = ByteArray(256 * 1024)
                while (true) {
                    val (offset, room) = lock.withLock {
                        if (gen != generation || abandonLocked(piece)) 0 to -1 else piece.next to piece.end - piece.next
                    }
                    if (room <= 0) break
                    val n = input.read(chunk, 0, minOf(room, chunk.size))
                    if (n < 0) throw IOException("early end")
                    segment.data.put(offset, chunk, 0, n)
                    windowBytes += n
                    val now = System.currentTimeMillis()
                    if (now - windowStart >= config.trickleMs) {
                        if (windowBytes < config.trickleBytes) throw Trickle("trickle, ${windowBytes / 1024} KiB in ${config.trickleMs / 1000} s")
                        windowStart = now
                        windowBytes = 0
                    }
                    lock.withLock {
                        piece.errors = 0
                        piece.got += n // a split may have moved [end] below this; the extra bytes are the new piece's too
                        fetched += n
                        changed.signalAll()
                    }
                }
                val requestedAll = lock.withLock {
                    piece.loading = false
                    changed.signalAll()
                    piece.complete && piece.end == requestedEnd
                }
                // a range read to its end returns the connection to the keep-alive pool; a cut one is dropped
                if (requestedAll) runCatching { input.close() } else conn.disconnect()
            } catch (error: IOException) {
                runCatching { input?.close() }
                conn.disconnect()
                // only our own message: JDK messages can quote the URL, which carries the debrid key
                failed(piece, (error as? Trickle)?.message ?: error.javaClass.simpleName, expired = false)
            }
        }

        /**
         * Stop a fetch whose segment was evicted or left the window, or, while the reader has waited [Config.preemptAfterMs]
         * for its segment, the farthest fetch ahead (one per [Config.preemptAfterMs]) so that connection helps the reader.
         */
        private fun abandonLocked(piece: Piece): Boolean {
            val index = piece.segment.index
            if (segments[index] !== piece.segment) return true
            if (index == lastIndex && index > focus) return false
            if (index < focus || index > focus + config.aheadSegments) return true
            if (index <= focus + 1) return false
            val now = System.currentTimeMillis()
            if (readerWaitingSince == 0L || now - readerWaitingSince < config.preemptAfterMs) return false
            if (now - lastPreemptAt < config.preemptAfterMs) return false
            // up to half the connections may move to the reader's segment; the rest keep fetching ahead
            val current = segments[focus]
            if (current != null) {
                val helpers = current.pieces.count { it.loading }
                val more = current.pieces.any { (!it.loading && !it.complete) || (it.loading && it.end - it.next >= 2 * config.minSplitBytes) }
                if (helpers >= maxOf(2, config.connections / 2) || !more) return false
            }
            val farthest = segments.values.filter { it.loading && it.index != lastIndex }.maxByOrNull { it.index }
            if (farthest !== piece.segment) return false
            lastPreemptAt = now
            return true
        }

        private fun failed(piece: Piece, reason: String, expired: Boolean) {
            var reResolve = false
            val errors = lock.withLock {
                piece.loading = false
                piece.errors++
                changed.signalAll()
                if (expired && reResolves < 3) {
                    reResolves++
                    reResolve = true
                }
                piece.errors
            }
            val at = piece.segment.index * config.segmentBytes + piece.next
            if (errors <= 3 || errors % 20 == 0) log.line("session $id: fetch at ${at / 1_000_000} MB failed ($reason, $errors in a row)${if (reResolve) "; resolving the source again" else ""}")
            if (reResolve) {
                lock.withLock { target = null; size = -1 }
                ensureResolved(piece.segment.index * config.segmentBytes)
            } else {
                Thread.sleep(minOf(250L shl minOf(errors, 6), 10_000L)) // 0.5 s doubling to 10 s
            }
        }

        fun reapIfIdle() {
            val idle = lock.withLock { readers == 0 && segments.isNotEmpty() && System.currentTimeMillis() - lastActivity > config.idleMs }
            if (idle) sleep("idle ${config.idleMs / 1000} s")
        }

        /** Frees the segments and stops the workers; the next request starts the session again. */
        fun sleep(reason: String) {
            lock.withLock {
                generation++
                workers.clear()
                segments.clear()
                failure = null
                changed.signalAll()
            }
            log.line("session $id asleep ($reason)")
        }

        private fun logStatsIfDue() {
            val line = lock.withLock {
                val now = System.currentTimeMillis()
                val seconds = (now - statsSince) / 1000.0
                if (seconds < 10) return
                val text = "session $id ${"%.0f".format(seconds)} s: fetched ${fetched / 1_000_000} MB (${"%.0f".format(fetched * 8 / seconds / 1e6)} Mbit/s), " +
                    "served ${served / 1_000_000} MB, reader waited ${"%.1f".format(waitedMs / 1000.0)} s, " +
                    "${segments.size} segment(s) held, ${segments.values.count { it.loading }} loading, read point ${focus * config.segmentBytes / 1_000_000} MB"
                fetched = 0; served = 0; waitedMs = 0; statsSince = now
                text
            }
            log.line(line)
        }

        // test hooks
        fun heldSegments(): Int = lock.withLock { segments.size }
    }

    private class Trickle(message: String) : IOException(message)

    private data class Quad(val size: Long, val type: String?, val redirect: String?, val failure: String?)

    /** "bytes=X-" / "bytes=X-Y" -> (X, Y); anything else -> (0, null). */
    fun parseRange(header: String?): Pair<Long, Long?> {
        val spec = header?.trim()?.lowercase()?.removePrefix("bytes=") ?: return 0L to null
        if (header.trim().lowercase() == spec || ',' in spec) return 0L to null
        val from = spec.substringBefore('-').trim().toLongOrNull() ?: return 0L to null
        val to = spec.substringAfter('-', "").trim().toLongOrNull()
        return from to to
    }

    /** "bytes 0-99/1234" -> 1234. */
    fun totalFromContentRange(header: String?): Long? = header?.substringAfterLast('/')?.trim()?.toLongOrNull()

    class ProxyLog(private val file: Path?) {
        private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        init {
            runCatching { if (file != null && Files.exists(file) && Files.size(file) > 4_000_000) Files.delete(file) }
        }

        fun line(text: String) {
            val path = file ?: return
            synchronized(this) {
                runCatching {
                    Files.createDirectories(path.parent)
                    Files.writeString(
                        path,
                        "${LocalTime.now().format(time)} $text\n",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                    )
                }
            }
        }
    }
}
