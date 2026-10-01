package com.nuvio.app.features.addons

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 #18: an addon request stops when its coroutine is cancelled (a blocking execute() kept
// the thread until the 60 s read timeout).
class AddonRequestCancelTest {
    @Test
    fun `cancelling the caller stops a request to a host that never answers`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow") { ex -> Thread.sleep(10_000); ex.sendResponseHeaders(200, -1); ex.close() }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        try {
            val request = async(Dispatchers.Default) {
                httpRequestRaw("GET", "http://127.0.0.1:${server.address.port}/slow", emptyMap(), "")
            }
            delay(200)
            val started = System.nanoTime()
            request.cancel()
            request.join()
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 1_000, "the cancelled request took $ms ms to stop")
        } finally {
            server.stop(0)
        }
    }
}
