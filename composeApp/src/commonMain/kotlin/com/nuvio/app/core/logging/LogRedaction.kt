package com.nuvio.app.core.logging

/**
 * nuvio-rr fork, Phase 9 S3. A URL for a log line: scheme, host and the Stremio resource tail
 * (`stream/<type>/<id>.json`, `manifest.json`, ...) stay; the path segments in between (where addons keep their
 * config, often with debrid keys), the query, user info and fragment are dropped.
 */
fun redactUrlForLog(url: String): String = TODO("Phase 9 S3 commit B")
