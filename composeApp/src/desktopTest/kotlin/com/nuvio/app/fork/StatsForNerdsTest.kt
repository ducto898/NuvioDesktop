package com.nuvio.app.fork

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatsForNerdsTest {
    private val healthy = mapOf(
        "path" to "https://hkg1.download.real-debrid.com/d/SECRETKEY123/movie.mkv",
        "file-format" to "mkv",
        "file-size" to "60000000000",
        "duration" to "6000",
        "pause" to "no",
        "video-format" to "hevc",
        "video-params/pixelformat" to "yuv420p10",
        "video-params/w" to "3840",
        "video-params/h" to "2160",
        "video-params/gamma" to "pq",
        "video-params/primaries" to "bt.2020",
        "hwdec-current" to "d3d11va",
        "container-fps" to "23.976024",
        "display-fps" to "119.880120",
        "video-sync" to "display-resample",
        "frame-drop-count" to "0",
        "decoder-frame-drop-count" to "0",
        "vo-delayed-frame-count" to "0",
        "mistimed-frame-count" to "0",
        "audio-codec-name" to "truehd",
        "audio-params/hr-channels" to "7.1",
        "audio-out-params/format" to "spdif-truehd",
        "demuxer-cache-duration" to "48.2",
        "demuxer-cache-state/fw-bytes" to "500000000",
        "demuxer-cache-state/raw-input-rate" to "12500000",
    )

    private fun sample(props: Map<String, String>, collector: StatsForNerds.Collector = StatsForNerds.Collector { null }, at: Long = 0): JsonObject =
        collector.sample({ props[it] }, at)

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.rows(): List<JsonObject> =
        getValue("sections").jsonArray.flatMap { it.jsonObject.getValue("rows").jsonArray.map { row -> row.jsonObject } }
    private fun JsonObject.row(label: String) = rows().first { it.text("k") == label }

    @Test
    fun `a healthy 4K HDR file plays smoothly`() {
        val stats = sample(healthy)
        assertEquals("ok", stats.text("status"))
        assertEquals("Playing smoothly", stats.text("headline"))
        assertEquals("5× · exact", stats.row("Match").text("v"))
        assertEquals("HEVC", stats.rows().first { it.text("k") == "Codec" }.text("v"))
        assertEquals("3840×2160 · 4K", stats.row("Resolution").text("v"))
        assertEquals("HDR10 (PQ)", stats.row("Colour").text("v"))
        assertEquals("passthrough", stats.row("Output").text("v"))
        assertEquals(80.0, stats.getValue("network").jsonObject.text("need").toDouble()) // 60 GB over 100 min
        assertEquals(100.0, stats.getValue("network").jsonObject.text("mbit").toDouble())
    }

    @Test
    fun `the source URL never leaves the collector, only its host`() {
        val text = sample(healthy).toString()
        assertFalse("SECRETKEY123" in text)
        assertEquals("hkg1.download.real-debrid.com", sample(healthy).row("Source").text("v"))
    }

    @Test
    fun `waiting for the cache is the headline`() {
        val stats = sample(healthy + mapOf("paused-for-cache" to "yes", "demuxer-cache-duration" to "0.4"))
        assertEquals("bad", stats.text("status"))
        assertEquals("Buffering: waiting for data", stats.text("headline"))
    }

    @Test
    fun `frames dropped in the last 10 s are flagged, older drops are not`() {
        val collector = StatsForNerds.Collector { null }
        sample(healthy, collector, at = 0)
        val dropped = sample(healthy + ("frame-drop-count" to "4"), collector, at = 1_000)
        assertEquals("bad", dropped.text("status"))
        assertEquals("4 frame(s) dropped in the last 10 s", dropped.text("headline"))
        val later = sample(healthy + ("frame-drop-count" to "4"), collector, at = 13_000)
        assertEquals("ok", later.text("status"))
    }

    @Test
    fun `a slow network only matters while the buffer is short`() {
        val slow = healthy + ("demuxer-cache-state/raw-input-rate" to "2000000") // 16 Mbit/s for an 80 Mbit/s file
        assertEquals("ok", sample(slow).text("status"))
        val short = sample(slow + ("demuxer-cache-duration" to "8"))
        assertEquals("warn", short.text("status"))
        assertTrue(short.text("headline").startsWith("Network 16 Mbit/s is slower than the file"))
    }

    @Test
    fun `refresh rate match`() {
        assertEquals("ok", StatsForNerds.rateMatch(23.976, 119.88, "display-resample").status)
        assertEquals("ok", StatsForNerds.rateMatch(23.976, 24.0, "display-resample").status) // 0.1 %, resampled
        assertEquals("warn", StatsForNerds.rateMatch(23.976, 24.0, "audio").status)
        val judder = StatsForNerds.rateMatch(23.976, 60.0, "display-resample")
        assertEquals("warn", judder.status)
        assertTrue(judder.value.endsWith("judder"))
    }

    @Test
    fun `proxy rows and network rate come from the proxy totals`() {
        var fetched = 0L
        val snap = { _: String? ->
            ParallelStreamProxy.Snapshot("hkg1.download.real-debrid.com", "proxy", 6, 4, fetched, 800_000_000, 190_000_000, 60_000_000_000, false, 1, 0, 3)
        }
        val collector = StatsForNerds.Collector(snap)
        sample(healthy, collector, at = 0)
        fetched = 30_000_000 // 30 MB in 2 s = 120 Mbit/s
        val stats = sample(healthy, collector, at = 2_000)
        assertEquals(120.0, stats.getValue("network").jsonObject.text("mbit").toDouble())
        assertEquals("6 connections", stats.getValue("network").jsonObject.text("via"))
        assertEquals("4 of 6 connections busy", stats.row("Proxy").text("v"))
        assertEquals("190 MB ready ahead", stats.row("Proxy RAM").text("v"))
        assertTrue(stats.getValue("sections") is JsonArray)
    }

    @Test
    fun `a local file and a torrent are named, not printed`() {
        assertEquals("local file", sample(healthy + ("path" to "C:\\Movies\\a.mkv")).row("Source").text("v"))
        assertEquals("local server (torrent)", sample(healthy + ("path" to "http://127.0.0.1:8090/stream/x?link=abc")).row("Source").text("v"))
    }

    @Test
    fun `a proxy URL that is not this proxy's has no snapshot`() {
        assertEquals(null, ParallelStreamProxy.snapshot(null))
        assertEquals(null, ParallelStreamProxy.snapshot("http://127.0.0.1:1/s/abc/x.mkv"))
    }
}
