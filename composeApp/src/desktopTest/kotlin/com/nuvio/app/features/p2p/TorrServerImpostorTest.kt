package com.nuvio.app.features.p2p

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.BindException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// nuvio-rr fork, Phase 9 S4: something else answering on TorrServer's port must not be adopted as TorrServer.
class TorrServerImpostorTest {
    @Test
    fun `an unknown server on port 8091 gets no torrent and playback start fails clearly`() = runBlocking {
        val server = try {
            HttpServer.create(InetSocketAddress("127.0.0.1", 8091), 0)
        } catch (busy: BindException) {
            println("port 8091 is in use on this machine; skipping")
            return@runBlocking
        }
        val paths = CopyOnWriteArrayList<String>()
        server.createContext("/") { ex ->
            paths += "${ex.requestMethod} ${ex.requestURI.path}"
            val body = if (ex.requestURI.path == "/echo") "impostor".toByteArray() else "{}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())  // answers everything, ignores /shutdown
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            try {
                P2pStreamingEngine.startStream(P2pStreamRequest(infoHash = "0123456789abcdef0123456789abcdef01234567", fileIdx = 0))
                fail("playback started against an unknown server")
            } catch (expected: P2pStreamingException) {
                assertTrue(expected.message.orEmpty().contains("another program"), expected.message)
            }
            assertTrue(paths.none { it.contains("/torrents") || it.contains("/stream") }, "the impostor got: $paths")
        } finally {
            server.stop(0)
            runCatching { P2pStreamingEngine.shutdown() }
        }
    }
}
