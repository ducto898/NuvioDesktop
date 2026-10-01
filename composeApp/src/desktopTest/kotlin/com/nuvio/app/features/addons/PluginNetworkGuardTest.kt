package com.nuvio.app.features.addons

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

// nuvio-rr fork, Phase 9 S2: scraper plugins can't reach this PC (TorrServer on 127.0.0.1:8091) or the LAN.
class PluginNetworkGuardTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun `public addresses are allowed, local and private ones are not`() {
        for (public in listOf("8.8.8.8", "1.1.1.1", "151.101.1.69", "2606:4700:4700::1111")) {
            assertTrue(PluginNetworkGuard.isAllowed(ip(public), allowLan = false), public)
        }
        for (local in listOf("127.0.0.1", "127.8.9.10", "::1", "0.0.0.0", "169.254.169.254", "fe80::1", "224.0.0.1", "::ffff:127.0.0.1")) {
            assertFalse(PluginNetworkGuard.isAllowed(ip(local), allowLan = false), local)
            assertFalse(PluginNetworkGuard.isAllowed(ip(local), allowLan = true), "$local even with LAN allowed")
        }
        for (lan in listOf("10.0.0.5", "172.16.3.4", "172.31.255.1", "192.168.1.1", "100.64.0.1", "fd12:3456::1")) {
            assertFalse(PluginNetworkGuard.isAllowed(ip(lan), allowLan = false), lan)
            assertTrue(PluginNetworkGuard.isAllowed(ip(lan), allowLan = true), "$lan with NUVIO_PLUGINS_ALLOW_LAN=1")
        }
        assertTrue(PluginNetworkGuard.isAllowed(ip("172.32.0.1"), allowLan = false), "just outside 172.16/12")
    }

    @Test
    fun `a plugin request to this PC is refused before it is sent, an addon request is not`() = runBlocking {
        var hits = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/shutdown") { ex -> hits++; ex.sendResponseHeaders(200, 0); ex.close() }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/shutdown"
            assertEquals(200, httpRequestRaw("GET", url, emptyMap(), "").status, "addons may use localhost")
            assertEquals(1, hits)
            try {
                withContext(PluginNetworkRestriction) { httpRequestRaw("GET", url, emptyMap(), "") }
                fail("a plugin reached 127.0.0.1")
            } catch (expected: java.io.IOException) {
                assertTrue(expected.message.orEmpty().contains("blocked"), expected.message)
            }
            assertEquals(1, hits, "the blocked request must never arrive")
        } finally {
            server.stop(0)
        }
    }
}
