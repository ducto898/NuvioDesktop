package com.nuvio.app.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// nuvio-rr fork, Phase 9 S3: addon URLs carry debrid keys in their path; logs keep only what helps debugging.
class LogRedactionTest {
    @Test
    fun `addon config segments, query and credentials are removed`() {
        val key = "rd_ABCDEF0123456789"
        val stream = "https://torrentio.strem.fun/realdebrid=$key|sort=quality/stream/movie/tt0111161.json"
        val redacted = redactUrlForLog(stream)
        assertEquals("https://torrentio.strem.fun/…/stream/movie/tt0111161.json", redacted)
        assertFalse(redacted.contains(key))

        assertEquals("https://aio.example.com/…/manifest.json", redactUrlForLog("https://aio.example.com/eyJrZXkiOiJzZWNyZXQifQ/manifest.json"))
        assertEquals("https://api.example.com/…/meta/series/tt0903747.json", redactUrlForLog("https://api.example.com/c/abc/meta/series/tt0903747.json?apikey=zzz"))
        assertEquals("https://host.example/…/file.mkv", redactUrlForLog("https://user:pass@host.example/dl/token123/file.mkv#t=5"))
    }

    @Test
    fun `short and odd inputs stay readable without leaking`() {
        assertEquals("https://example.com/manifest.json", redactUrlForLog("https://example.com/manifest.json"))
        assertEquals("https://example.com/", redactUrlForLog("https://example.com/?token=abc"))
        assertEquals("<blank>", redactUrlForLog(""))
        assertEquals("<not a url, 11 chars>", redactUrlForLog("magnet-ish!"))
    }
}
