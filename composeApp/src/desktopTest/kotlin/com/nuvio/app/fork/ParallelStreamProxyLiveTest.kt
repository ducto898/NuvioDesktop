package com.nuvio.app.fork

import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test

/**
 * Manual throughput check against a real source; skipped unless NUVIO_RR_LIVE_URL is set (never commit such a URL:
 * debrid links carry API keys). Per NUVIO_RR_LIVE_CONFIGS ("<segment MiB>:<connections>,...") reads
 * NUVIO_RR_LIVE_SECONDS through the proxy and prints the rate plus the fetches that failed or trickled (a server that
 * limits connections shows up there). NUVIO_RR_LIVE_ROUNDS repeats the list, interleaved; every read starts at a new
 * file region (NUVIO_RR_LIVE_OFFSET_GB, then +700 MB each): a debrid server's rate depends on the region.
 */
class ParallelStreamProxyLiveTest {
    @Test
    fun liveThroughput() {
        val source = System.getenv("NUVIO_RR_LIVE_URL")?.takeIf { it.isNotBlank() } ?: return
        val seconds = System.getenv("NUVIO_RR_LIVE_SECONDS")?.toLongOrNull() ?: 40L
        val offsetGb = System.getenv("NUVIO_RR_LIVE_OFFSET_GB")?.toLongOrNull() ?: 10L
        val rounds = System.getenv("NUVIO_RR_LIVE_ROUNDS")?.toIntOrNull() ?: 1
        val configs = (System.getenv("NUVIO_RR_LIVE_CONFIGS") ?: "16:1,16:4,16:8").split(",").map { it.trim().split(":") }
        var run = 0
        repeat(rounds) { round ->
            for ((segmentMib, connectionsText) in configs) {
                val connections = connectionsText.toInt()
                val segmentBytes = segmentMib.toInt() * 1024 * 1024
                val server = ParallelStreamProxy.Server(
                    ParallelStreamProxy.Config(
                        connections = connections,
                        segmentBytes = segmentBytes,
                        aheadSegments = maxOf(192 * 1024 * 1024 / segmentBytes, connections + 2),
                        behindSegments = 4,
                        logFile = null,
                    ),
                )
                try {
                    val url = server.register(source, emptyList())
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.setRequestProperty("Range", "bytes=${offsetGb * 1_000_000_000 + run++ * 700_000_000L}-")
                    val started = System.nanoTime()
                    val code = conn.responseCode
                    val buffer = ByteArray(1 shl 20)
                    var total = 0L
                    var firstByte = -1.0
                    if (code in 200..299) {
                        conn.inputStream.use { input ->
                            while ((System.nanoTime() - started) / 1e9 < seconds) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (firstByte < 0) firstByte = (System.nanoTime() - started) / 1e9
                                total += n
                            }
                        }
                    }
                    val elapsed = (System.nanoTime() - started) / 1e9
                    val snap = ParallelStreamProxy.snapshot(url) ?: server.session(url.substringAfter("/s/").substringBefore('/'))?.snapshot()
                    println(
                        ("LIVE round=${round + 1} segment=${segmentMib}MiB connections=$connections HTTP $code first byte %.1f s, " +
                            "%.0f MB in %.0f s = %.0f Mbit/s; failed ${snap?.failures} trickled ${snap?.trickles} split ${snap?.splits} state ${snap?.state}")
                            .format(firstByte, total / 1e6, elapsed, total * 8 / elapsed / 1e6),
                    )
                } finally {
                    server.stop()
                }
            }
        }
    }

    /**
     * Manual end-to-end helper; skipped unless NUVIO_RR_PROXY_HANDSHAKE is set to a folder. Waits for origin.txt there
     * (a test origin URL), registers it, writes the proxy URL to proxy.txt and serves until done.txt appears (max 15 min).
     */
    @Test
    fun serveForAnExternalPlayer() {
        val dir = System.getenv("NUVIO_RR_PROXY_HANDSHAKE")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) } ?: return
        val origin = java.io.File(dir, "origin.txt")
        val deadline = System.currentTimeMillis() + 15 * 60_000
        while (!origin.exists() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        val server = ParallelStreamProxy.Server(ParallelStreamProxy.Config(logFile = java.io.File(dir, "stream-proxy.log").toPath()))
        try {
            java.io.File(dir, "proxy.txt").writeText(server.register(origin.readText().trim(), emptyList()))
            while (!java.io.File(dir, "done.txt").exists() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        } finally {
            server.stop()
        }
    }
}