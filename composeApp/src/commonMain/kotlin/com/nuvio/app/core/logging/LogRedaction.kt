package com.nuvio.app.core.logging

/**
 * nuvio-rr fork, Phase 9 S3. A URL for a log line: scheme, host and the Stremio resource tail
 * (`stream/<type>/<id>.json`, `manifest.json`, ...) stay; the path segments in between (where addons keep their
 * config, often with debrid keys), the query, user info and fragment are dropped.
 */
fun redactUrlForLog(url: String): String {
    if (url.isBlank()) return "<blank>"
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return "<not a url, ${url.length} chars>"
    val scheme = url.substring(0, schemeEnd)
    val rest = url.substring(schemeEnd + 3).substringBefore('#').substringBefore('?')
    val authorityEnd = rest.indexOf('/').let { if (it < 0) rest.length else it }
    val host = rest.substring(0, authorityEnd).substringAfterLast('@')
    val segments = rest.substring(authorityEnd).split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return "$scheme://$host/"
    val resourceIndex = segments.indexOfLast { it in StremioResources }
    val tail = if (resourceIndex >= 0) segments.subList(resourceIndex, segments.size) else listOf(segments.last())
    val dropped = segments.size - tail.size
    return "$scheme://$host/" + (if (dropped > 0) "…/" else "") + tail.joinToString("/")
}

private val StremioResources = setOf("stream", "meta", "catalog", "subtitles", "addon_catalog")
