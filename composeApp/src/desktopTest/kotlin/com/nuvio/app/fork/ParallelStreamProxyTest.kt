package com.nuvio.app.fork

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParallelStreamProxyTest {
    private val mib = 1024 * 1024
    private val content = ByteArray(24 * mib) { i -> ((i * 31) xor (i ushr 11)).toByte() }
    private val starts = Collections.synchronizedList(mutableListOf<Long>())
    private val active = AtomicInteger()
    private val maxActive = AtomicInteger()
    private val origin = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/file") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val (from, to) = ParallelStreamProxy.parseRange(range)
            val end = minOf(to ?: (content.size - 1L), content.size - 1L)
            starts += from
            val now = active.incrementAndGet()
            maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                ex.responseHeaders.add("Content-Type", "video/x-matroska")
                if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-$end/${content.size}")
                ex.sendResponseHeaders(if (range != null) 206 else 200, end - from + 1)
                var pos = from.toInt()
                while (pos <= end) { // ~8 MB/s per connection
                    val n = minOf(64 * 1024, (end - pos + 1).toInt())
                    ex.responseBody.write(content, pos, n)
                    pos += n
                    Thread.sleep(8)
                }
            } catch (_: Exception) {
            } finally {
                active.decrementAndGet()
                ex.close()
            }
        }
        createContext("/redir") { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.1:${address.port}/file")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        createContext("/plain") { ex ->
            ex.sendResponseHeaders(200, 4)
            ex.responseBody.write(byteArrayOf(1, 2, 3, 4))
            ex.close()
        }
        start()
    }
    private val proxy = ParallelStreamProxy.Server(
        ParallelStreamProxy.Config(connections = 4, aheadSegments = 6, behindSegments = 10, segmentBytes = mib, logFile = null),
    )

    @AfterTest
    fun stop() {
        proxy.stop()
        origin.stop(0)
    }

    private fun originUrl(path: String) = "http://127.0.0.1:${origin.address.port}$path"

    private fun get(url: String, range: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            readTimeout = 20_000
            if (range != null) setRequestProperty("Range", range)
        }

    @Test
    fun servesTheWholeFileInOrderOverSeveralConnections() {
        val url = proxy.register(originUrl("/file"), emptyList())
        val conn = get(url)
        assertEquals(200, conn.responseCode)
        assertEquals(content.size.toLong(), conn.contentLengthLong)
        val bytes = conn.inputStream.use { it.readBytes() }
        assertContentEquals(content, bytes)
        assertTrue(maxActive.get() >= 3, "parallel upstream connections: ${maxActive.get()}")
    }

    @Test
    fun servesARangeFromTheMiddle() {
        val url = proxy.register(originUrl("/redir"), listOf("X-Test: 1"))
        val conn = get(url, "bytes=${5 * mib + 7}-${5 * mib + 7 + 99_999}")
        assertEquals(206, conn.responseCode)
        assertEquals("bytes ${5 * mib + 7}-${5 * mib + 7 + 99_999}/${content.size}", conn.getHeaderField("Content-Range"))
        val bytes = conn.inputStream.use { it.readBytes() }
        assertContentEquals(content.copyOfRange(5 * mib + 7, 5 * mib + 7 + 100_000), bytes)
    }

    @Test
    fun aReadBackComesFromMemory() {
        val url = proxy.register(originUrl("/file"), emptyList())
        get(url, "bytes=0-${8 * mib - 1}").inputStream.use { it.readBytes() }
        val before = starts.toList()
        val conn = get(url, "bytes=${mib}-${4 * mib - 1}") // what a subtitle refresh seek does
        assertContentEquals(content.copyOfRange(mib, 4 * mib), conn.inputStream.use { it.readBytes() })
        val refetched = starts.drop(before.size).filter { it < 4L * mib }
        assertTrue(refetched.isEmpty(), "re-read went upstream: $refetched")
    }

    @Test
    fun theSegmentTheReaderWaitsForIsSplitOverSeveralConnections() {
        val splitting = ParallelStreamProxy.Server(
            ParallelStreamProxy.Config(
                connections = 4, aheadSegments = 2, behindSegments = 2, segmentBytes = 4 * mib,
                minSplitBytes = 256 * 1024, logFile = null,
            ),
        )
        try {
            val url = splitting.register(originUrl("/file"), emptyList())
            val bytes = get(url, "bytes=0-${4 * mib - 1}").inputStream.use { it.readBytes() }
            assertContentEquals(content.copyOfRange(0, 4 * mib), bytes)
            val inFirstSegment = starts.filter { it < 4L * mib }
            assertTrue(inFirstSegment.size >= 2, "first segment fetched by one request only: $inFirstSegment")
        } finally {
            splitting.stop()
        }
    }

    @Test
    fun aSourceWithoutRangeSupportGoesToMpvDirectly() {
        val url = proxy.register(originUrl("/plain"), emptyList())
        val conn = get(url)
        assertEquals(302, conn.responseCode)
        assertEquals(originUrl("/plain"), conn.getHeaderField("Location"))
    }

    @Test
    fun onlyRemoteFilesAreProxied() {
        assertTrue(ParallelStreamProxy.isProxyable("https://hkg1-4.download.real-debrid.com/d/X/movie.mkv"))
        assertFalse(ParallelStreamProxy.isProxyable("file:///C:/movie.mkv"))
        assertFalse(ParallelStreamProxy.isProxyable("C:\\movie.mkv"))
        assertFalse(ParallelStreamProxy.isProxyable("http://127.0.0.1:8090/stream/x"))
        assertFalse(ParallelStreamProxy.isProxyable("http://127.0.0.2:8090/stream/x"))
        assertTrue(ParallelStreamProxy.isProxyable("http://192.168.1.20:8096/Videos/1/stream.mkv"))
        assertFalse(ParallelStreamProxy.isProxyable("https://cdn.example/master.m3u8?token=1"))
        assertEquals(ParallelStreamProxy.DEFAULT_CONNECTIONS, ParallelStreamProxy.configuredConnections(null))
        assertEquals(0, ParallelStreamProxy.configuredConnections("0"))
        assertEquals(16, ParallelStreamProxy.configuredConnections("99"))
        assertEquals(10L to 20L, ParallelStreamProxy.parseRange("bytes=10-20"))
        assertEquals(10L to null, ParallelStreamProxy.parseRange("bytes=10-"))
        assertEquals(0L to null, ParallelStreamProxy.parseRange(null))
    }
}
