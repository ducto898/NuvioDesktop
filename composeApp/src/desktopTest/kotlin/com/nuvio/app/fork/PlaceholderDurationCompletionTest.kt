package com.nuvio.app.fork

import com.nuvio.app.features.watchprogress.WatchProgressCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// nuvio-rr fork (SYNC1): upstream 80860602 treats any duration of 1 ms..121 s as an error/placeholder clip that never
// completes. Trakt history and show-progress rows use durationMs = 1 as their own placeholder; they must stay watched
// through their explicit flags, or a merge from upstream would silently mark every Trakt-watched episode unwatched.
class PlaceholderDurationCompletionTest {
    private fun payload(extra: String, positionMs: Long, durationMs: Long) = """
        {
          "entries": [{
            "contentType": "series",
            "parentMetaId": "show",
            "parentMetaType": "series",
            "videoId": "show:1:2",
            "title": "Show",
            "seasonNumber": 1,
            "episodeNumber": 2,
            "lastPositionMs": $positionMs,
            "durationMs": $durationMs,
            "lastUpdatedEpochMs": 100$extra
          }]
        }
    """.trimIndent()

    @Test
    fun `a Trakt row with the 1 ms placeholder stays watched after a store round trip`() {
        val decoded = WatchProgressCodec.decodeEntries(
            payload(""", "isCompleted": true, "progressPercent": 100.0""", positionMs = 1L, durationMs = 1L),
        ).single()
        assertTrue(decoded.isCompleted)
        assertTrue(decoded.isEffectivelyCompleted)

        val again = WatchProgressCodec.decodeEntries(WatchProgressCodec.encodeEntries(listOf(decoded))).single()
        assertTrue(again.isCompleted)
        assertEquals(1L, again.durationMs)
    }

    @Test
    fun `explicit completion alone or a 100 percent alone keeps a placeholder row watched`() {
        val flagOnly = WatchProgressCodec.decodeEntries(payload(""", "isCompleted": true""", 1L, 1L)).single()
        val percentOnly = WatchProgressCodec.decodeEntries(payload(""", "progressPercent": 100.0""", 1L, 1L)).single()
        assertTrue(flagOnly.isCompleted)
        assertTrue(percentOnly.isCompleted)
    }

    @Test
    fun `a short clip without explicit completion is not derived as watched, a real episode is`() {
        val clip = WatchProgressCodec.decodeEntries(payload("", positionMs = 30_000L, durationMs = 30_000L)).single()
        assertFalse(clip.isCompleted)

        val episode = WatchProgressCodec.decodeEntries(payload("", positionMs = 940_000L, durationMs = 1_000_000L)).single()
        assertTrue(episode.isCompleted)
        assertEquals(940_000L, episode.lastPositionMs)
    }
}
