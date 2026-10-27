package com.nuvio.app.fork

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * nuvio-rr fork, STATS (owner 2026-10-27): a "Stats for nerds" overlay like YouTube's, toggled with I in the player
 * (player-ui/stats.js draws it). Once a second [Collector.sample] reads mpv's properties and the stream proxy's totals
 * and turns them into display-ready rows plus a verdict: the worst current problem (buffering, dropped frames, a
 * refresh rate that is not a multiple of the frame rate, a network slower than the file) is the headline, and each
 * row carries its own ok/warn/bad. Nothing it sends names the source URL (debrid links carry API keys): the host only.
 */
internal object StatsForNerds {
    /** Shown or not; kept for the app's lifetime, across players. NUVIO_RR_STATS=1 starts with it shown (measure runs). */
    @Volatile
    var enabled = System.getenv("NUVIO_RR_STATS") == "1"

    val PROPERTIES = listOf(
        "path", "file-format", "file-size", "duration", "pause", "paused-for-cache", "core-idle", "seeking",
        "video-format", "video-params/pixelformat", "video-params/hw-pixelformat", "video-params/w", "video-params/h", "video-params/gamma",
        "video-params/primaries", "video-params/colormatrix", "video-target-params/gamma", "dwidth", "dheight",
        "osd-dimensions/w", "osd-dimensions/h", "hwdec-current", "video-bitrate", "container-fps", "estimated-vf-fps",
        "display-fps", "estimated-display-fps", "video-sync", "vsync-jitter", "video-speed-correction",
        "audio-speed-correction", "frame-drop-count", "decoder-frame-drop-count", "vo-delayed-frame-count",
        "mistimed-frame-count", "audio-codec-name", "audio-params/samplerate", "audio-params/hr-channels",
        "audio-params/channel-count", "audio-out-params/format", "current-ao", "audio-bitrate",
        "demuxer-cache-duration", "demuxer-cache-state/fw-bytes", "demuxer-cache-state/raw-input-rate",
    )

    /** One per player controller: samples once a second while the overlay is shown and that player has a handle. */
    class Runner(private val handle: () -> Long, private val released: () -> Boolean) {
        @Volatile
        private var thread: Thread? = null

        fun toggle() {
            enabled = !enabled
            if (enabled) start() else push("""{"visible":false}""")
        }

        /** Starts sampling if the overlay is shown (a new player shows it again). */
        fun start() {
            if (!enabled || thread?.isAlive == true) return
            thread = Thread({
                val collector = Collector()
                while (enabled && !released()) {
                    val current = handle()
                    if (current != 0L) {
                        runCatching { collector.sample({ name -> NativePlayerBridgeAccess.property(current, name) }) }
                            .onSuccess { push(it.toString()) }
                    }
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }, "nuvio-rr-stats").apply {
                isDaemon = true
                start()
            }
        }

        private fun push(json: String) {
            val current = handle()
            if (current != 0L) NativePlayerBridgeAccess.updateStats(current, json)
        }
    }

    private class Sample(val atMs: Long, val drops: Long, val delayed: Long, val mistimed: Long, val fetched: Long?, val problems: Int)

    class Collector(private val proxy: (String?) -> ParallelStreamProxy.Snapshot? = ParallelStreamProxy::snapshot) {
        private val history = ArrayDeque<Sample>()

        fun sample(read: (String) -> String?, nowMs: Long = System.currentTimeMillis()): JsonObject {
            val p = PROPERTIES.associateWith { name -> read(name)?.takeIf { it.isNotBlank() } }
            fun num(name: String) = p[name]?.toDoubleOrNull()
            fun long(name: String) = p[name]?.toDoubleOrNull()?.toLong() ?: 0L
            fun yes(name: String) = p[name] == "yes"

            val snap = proxy(p["path"])
            val drops = long("frame-drop-count") + long("decoder-frame-drop-count")
            val delayed = long("vo-delayed-frame-count")
            val mistimed = long("mistimed-frame-count")
            val problems = (snap?.trickles ?: 0) + (snap?.failures ?: 0)
            val now = Sample(nowMs, drops, delayed, mistimed, snap?.fetchedBytes, problems)
            // a new file restarts mpv's counters
            if (history.lastOrNull()?.let { it.drops > drops || it.delayed > delayed || it.mistimed > mistimed } == true) history.clear()
            history.addLast(now)
            while (history.size > 1 && nowMs - history.first().atMs > 30_000) history.removeFirst()
            fun ago(ms: Long) = history.firstOrNull { nowMs - it.atMs <= ms } ?: now
            val tenAgo = ago(10_500)
            val dropsRecent = drops - tenAgo.drops
            val delayedRecent = (delayed - tenAgo.delayed) + (mistimed - tenAgo.mistimed)
            val problemsRecent = problems - history.first().problems

            val playing = p["pause"] != "yes"
            val waiting = yes("paused-for-cache")
            val bufferS = num("demuxer-cache-duration")
            val bufferBytes = num("demuxer-cache-state/fw-bytes")

            // network: what the proxy fetched over the last 3 s, else what mpv read from the source
            val viaProxy = snap != null && snap.state != "direct"
            val netMbit = if (viaProxy) {
                val from = ago(3_500)
                val seconds = (nowMs - from.atMs) / 1000.0
                if (seconds >= 0.9 && from.fetched != null) (snap.fetchedBytes - from.fetched) * 8 / seconds / 1e6 else null
            } else {
                num("demuxer-cache-state/raw-input-rate")?.let { it * 8 / 1e6 }
            }
            val duration = num("duration")
            val fileBytes = num("file-size")
            val averageMbit = if (duration != null && duration > 0 && fileBytes != null) fileBytes * 8 / duration / 1e6 else null
            val needMbit = averageMbit ?: listOfNotNull(num("video-bitrate"), num("audio-bitrate")).takeIf { it.isNotEmpty() }?.sum()?.let { it / 1e6 }

            val fps = num("container-fps") ?: num("estimated-vf-fps")
            val displayHz = num("display-fps")
            val match = rateMatch(fps, displayHz, p["video-sync"])

            val issues = mutableListOf<Pair<String, String>>() // status to text, worst first after sorting
            if (waiting) issues += "bad" to "Buffering: waiting for data"
            if (dropsRecent > 0) issues += "bad" to "$dropsRecent frame(s) dropped in the last 10 s"
            if (playing && !waiting && bufferS != null && bufferS < 2 && p["seeking"] != "yes") issues += "bad" to "Buffer almost empty"
            if (match.status == "warn") issues += "warn" to match.headline
            if (delayedRecent > 2) issues += "warn" to "$delayedRecent late frame(s) in the last 10 s"
            if (playing && bufferS != null && bufferS < 20 && netMbit != null && needMbit != null && netMbit < needMbit) {
                issues += "warn" to "Network ${mbit(netMbit)} is slower than the file (${mbit(needMbit)})"
            } else if (playing && !waiting && bufferS != null && bufferS < 5 && p["seeking"] != "yes") {
                issues += "warn" to "Buffer low"
            }
            if (problemsRecent > 0) issues += "warn" to "Server connection stalled; ${problemsRecent} fetch(es) retried in the last 30 s"
            issues.sortBy { if (it.first == "bad") 0 else 1 }
            val status = issues.firstOrNull()?.first ?: "ok"
            val headline = issues.firstOrNull()?.second ?: if (playing) "Playing smoothly" else "Paused"

            return buildJsonObject {
                put("visible", true)
                put("status", status)
                put("headline", headline)
                put("issues", buildJsonArray { issues.drop(1).forEach { (s, text) -> add(row("", text, null, s)) } })
                put("buffer", buildJsonObject {
                    bufferS?.let { put("seconds", round1(it)) }
                    bufferBytes?.let { put("mb", (it / 1e6).roundToInt()) }
                    put("status", if (waiting || (bufferS != null && bufferS < 2 && playing)) "bad" else if (bufferS != null && bufferS < 5 && playing) "warn" else "ok")
                })
                put("network", buildJsonObject {
                    netMbit?.let { put("mbit", round1(it)) }
                    needMbit?.let { put("need", round1(it)) }
                    put("via", if (viaProxy) "${snap.connections} connections" else "1 connection")
                })
                put("sections", buildJsonArray {
                    add(section("Video", videoRows(p, fps, needMbit, averageMbit)))
                    add(section("Display", displayRows(p, fps, displayHz, match, drops, delayed, mistimed, dropsRecent, delayedRecent)))
                    add(section("Audio", audioRows(p)))
                    add(section("Stream", streamRows(p, snap, netMbit, needMbit, bufferS, bufferBytes, problemsRecent)))
                })
            }
        }
    }

    class Match(val status: String, val value: String, val detail: String?, val headline: String)

    /** Whether the display shows every frame for the same time: its rate a whole multiple of the frame rate. */
    fun rateMatch(fps: Double?, displayHz: Double?, sync: String?): Match {
        if (fps == null || displayHz == null || fps <= 0 || displayHz <= 0) return Match("ok", "unknown", null, "")
        val ratio = displayHz / fps
        val multiple = ratio.roundToInt().coerceAtLeast(1)
        val error = abs(ratio / multiple - 1) * 100
        val resampled = sync?.startsWith("display-") == true
        return when {
            error < 0.01 -> Match("ok", "${multiple}× · exact", null, "")
            error < 1 && resampled -> Match("ok", "${multiple}× · ${pct(error)} off", "resampled by mpv", "")
            error < 1 -> {
                val every = 100 / (fps * error) // seconds between two repeated (or skipped) frames
                val often = if (every >= 1.5) "every ~${every.roundToInt()} s" else "${(1 / every).roundToInt()}× a second"
                Match("warn", "${multiple}× · ${pct(error)} off", "a frame repeats $often", "${hz(displayHz)} is ${pct(error)} off ${multiple}× ${fpsText(fps)}: a frame repeats $often")
            }
            else -> Match("warn", "${"%.2f".format(Locale.ROOT, ratio)}× · judder", "uneven frame times", "Judder: ${hz(displayHz)} is not a multiple of ${fpsText(fps)}")
        }
    }

    private fun videoRows(p: Map<String, String?>, fps: Double?, needMbit: Double?, averageMbit: Double?): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        val w = p["video-params/w"]?.toIntOrNull()
        val h = p["video-params/h"]?.toIntOrNull()
        if (p["video-format"] == null && w == null) return listOf(row("Video", "none", null, null))
        rows += row("Codec", codecName(p["video-format"]), p["video-params/hw-pixelformat"] ?: p["video-params/pixelformat"], null)
        if (w != null && h != null) {
            val shown = listOf(p["osd-dimensions/w"], p["osd-dimensions/h"]).mapNotNull { it?.toIntOrNull() }
            rows += row("Resolution", "$w×$h${resolutionLabel(w, h)?.let { " · $it" } ?: ""}", if (shown.size == 2) "window ${shown[0]}×${shown[1]}" else null, null)
        }
        fps?.let { rows += row("Frame rate", fpsText(it), null, null) }
        val gamma = p["video-params/gamma"]
        val range = when (gamma) {
            "pq" -> "HDR10 (PQ)"
            "hlg" -> "HLG"
            null -> null
            else -> "SDR"
        }
        if (range != null) {
            val target = p["video-target-params/gamma"]?.let { "out ${it.uppercase()}" }
            rows += row("Colour", range, listOfNotNull(p["video-params/primaries"], p["video-params/colormatrix"], target).joinToString(" · ").ifEmpty { null }, null)
        }
        val hwdec = p["hwdec-current"]
        val software = hwdec == null || hwdec == "no"
        rows += row("Decoder", if (software) "software" else "hardware", if (software) "CPU" else hwdec, if (software && (h ?: 0) >= 2000) "warn" else null)
        val now = p["video-bitrate"]?.toDoubleOrNull()?.let { it / 1e6 }
        if (averageMbit != null || now != null) {
            rows += row("Bitrate", mbit(averageMbit ?: now!!) + if (averageMbit != null) " avg" else "", now?.let { "video now ${mbit(it)}" }, null)
        }
        return rows
    }

    private fun displayRows(
        p: Map<String, String?>, fps: Double?, displayHz: Double?, match: Match,
        drops: Long, delayed: Long, mistimed: Long, dropsRecent: Long, delayedRecent: Long,
    ): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        val measured = p["estimated-display-fps"]?.toDoubleOrNull()
        rows += row("Refresh rate", displayHz?.let { hz(it) } ?: "unknown", measured?.let { "measured ${"%.3f".format(Locale.ROOT, it)}" }, null)
        if (fps != null && displayHz != null) rows += row("Match", match.value, match.detail, if (match.status == "ok") "ok" else match.status)
        val sync = p["video-sync"]
        if (sync != null) {
            val correction = p["video-speed-correction"]?.toDoubleOrNull()?.let { (it - 1) * 100 }
            rows += row("Sync", sync, correction?.takeIf { abs(it) >= 0.0005 }?.let { "video speed ${signedPct(it)}" }, null)
        }
        val recent = buildList {
            if (dropsRecent > 0) add("+$dropsRecent dropped")
            if (delayedRecent > 0) add("+$delayedRecent late")
        }.joinToString(", ").ifEmpty { null }
        rows += row(
            "Frames",
            "$drops dropped · $delayed delayed · $mistimed mistimed",
            recent?.let { "$it in 10 s" },
            when {
                dropsRecent > 0 -> "bad"
                delayedRecent > 2 -> "warn"
                else -> null
            },
        )
        p["vsync-jitter"]?.toDoubleOrNull()?.let { rows += row("Vsync jitter", "%.4f".format(Locale.ROOT, it), null, if (it > 0.05) "warn" else null) }
        return rows
    }

    private fun audioRows(p: Map<String, String?>): List<JsonObject> {
        val codec = p["audio-codec-name"] ?: return listOf(row("Audio", "none", null, null))
        val rows = mutableListOf<JsonObject>()
        val channels = p["audio-params/hr-channels"] ?: p["audio-params/channel-count"]?.let { "$it ch" }
        val rate = p["audio-params/samplerate"]?.toDoubleOrNull()?.let { "${"%.1f".format(Locale.ROOT, it / 1000).removeSuffix(".0")} kHz" }
        rows += row("Codec", audioCodecName(codec), listOfNotNull(channels, rate).joinToString(" · ").ifEmpty { null }, null)
        val format = p["audio-out-params/format"]
        val passthrough = format?.startsWith("spdif") == true
        rows += row("Output", if (passthrough) "passthrough" else "PCM", listOfNotNull(p["current-ao"], format).joinToString(" · ").ifEmpty { null }, null)
        p["audio-bitrate"]?.toDoubleOrNull()?.let { rows += row("Bitrate", "${(it / 1000).roundToInt()} kbit/s", null, null) }
        p["audio-speed-correction"]?.toDoubleOrNull()?.let { (it - 1) * 100 }?.takeIf { abs(it) >= 0.0005 }?.let {
            rows += row("Resampled", signedPct(it), "to follow the display clock", null)
        }
        return rows
    }

    private fun streamRows(
        p: Map<String, String?>, snap: ParallelStreamProxy.Snapshot?, netMbit: Double?, needMbit: Double?,
        bufferS: Double?, bufferBytes: Double?, problemsRecent: Int,
    ): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        rows += row("Source", sourceName(p["path"], snap), listOfNotNull(p["file-format"], p["file-size"]?.toDoubleOrNull()?.let { gb(it) }).joinToString(" · ").ifEmpty { null }, null)
        if (snap != null) {
            val mode = when (snap.state) {
                "proxy" -> "${snap.loading} of ${snap.connections} connections busy"
                "direct" -> "direct (server has no range support)"
                else -> snap.state
            }
            rows += row("Proxy", mode, null, if (snap.state == "failed") "bad" else null)
            if (snap.state == "proxy" || snap.state == "asleep") {
                rows += row("Proxy RAM", "${mb(snap.aheadBytes.toDouble())} ready ahead", "${mb(snap.heldBytes.toDouble())} held", if (snap.readerWaiting) "warn" else null)
                rows += row("Retries", "${snap.trickles} trickle · ${snap.failures} error", "${snap.splits} split(s)", if (problemsRecent > 0) "warn" else null)
            }
        }
        if (netMbit != null) {
            val short = needMbit != null && netMbit < needMbit && (bufferS ?: 99.0) < 20
            rows += row("Network", mbit(netMbit), needMbit?.let { "file needs ~${mbit(it)}" }, if (short) "warn" else null)
        }
        if (bufferS != null) rows += row("Buffer", "${"%.1f".format(Locale.ROOT, bufferS)} s", bufferBytes?.let { mb(it) }, null)
        return rows
    }

    private fun sourceName(path: String?, snap: ParallelStreamProxy.Snapshot?): String {
        if (snap != null) return snap.host
        if (path == null) return "unknown"
        val uri = runCatching { URI(path) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        return when {
            scheme == null || scheme.length == 1 || scheme == "file" -> "local file"
            uri.host == "127.0.0.1" || uri.host == "localhost" -> "local server (torrent)"
            else -> uri.host ?: scheme
        }
    }

    private fun section(title: String, rows: List<JsonObject>) = buildJsonObject {
        put("title", title)
        put("rows", JsonArray(rows))
    }

    private fun row(label: String, value: String, detail: String?, status: String?) = buildJsonObject {
        put("k", label)
        put("v", value)
        detail?.let { put("d", it) }
        status?.let { put("s", it) }
    }

    fun codecName(format: String?): String = when (format?.lowercase()) {
        null -> "unknown"
        "hevc", "h265" -> "HEVC"
        "h264", "avc" -> "H.264"
        "av1" -> "AV1"
        "vp9" -> "VP9"
        "mpeg2video" -> "MPEG-2"
        "vc1" -> "VC-1"
        else -> format.uppercase()
    }

    fun audioCodecName(codec: String): String = when (codec.lowercase()) {
        "eac3" -> "E-AC-3"
        "ac3" -> "AC-3"
        "truehd" -> "TrueHD"
        "dts" -> "DTS"
        "aac" -> "AAC"
        "opus" -> "Opus"
        "flac" -> "FLAC"
        "mp3" -> "MP3"
        else -> codec.uppercase()
    }

    private fun resolutionLabel(w: Int, h: Int): String? = when {
        w >= 3800 || h >= 2100 -> "4K"
        w >= 2500 || h >= 1400 -> "1440p"
        w >= 1900 || h >= 1060 -> "1080p"
        w >= 1260 || h >= 700 -> "720p"
        else -> null
    }

    private object NativePlayerBridgeAccess {
        fun property(handle: Long, name: String): String? =
            com.nuvio.app.features.player.desktop.NativePlayerBridge.stringProperty(handle, name).takeIf { it.isNotBlank() }

        // an older native library without updateStats: the overlay stays hidden
        fun updateStats(handle: Long, json: String) {
            runCatching { com.nuvio.app.features.player.desktop.NativePlayerBridge.updateStats(handle, json) }
        }
    }

    private fun round1(value: Double) = (value * 10).roundToInt() / 10.0
    private fun pct(value: Double) = "%.2f %%".format(Locale.ROOT, value)
    private fun signedPct(value: Double) = "%+.3f %%".format(Locale.ROOT, value)
    private fun hz(value: Double) = "%.3f Hz".format(Locale.ROOT, value)
    private fun fpsText(value: Double) = "%.3f fps".format(Locale.ROOT, value)
    fun mbit(value: Double): String = if (value < 10) "%.1f Mbit/s".format(Locale.ROOT, value) else "${value.roundToInt()} Mbit/s"
    private fun mb(bytes: Double) = "${(bytes / 1e6).roundToInt()} MB"
    private fun gb(bytes: Double) = "%.1f GB".format(Locale.ROOT, bytes / 1e9)
}
