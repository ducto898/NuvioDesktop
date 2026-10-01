package com.nuvio.app.features.player

/**
 * nuvio-rr fork, Phase 9 E7: live scrub preview. While the seek bar is dragged, the player does fast keyframe seeks
 * (the release still does the exact seek); this keeps them to one per [intervalMs] and skips repeats.
 */
internal class ScrubPreviewThrottle(private val intervalMs: Long = 150L) {
    /** The position to preview now, or null to skip this drag update. */
    fun offer(positionMs: Long, nowMs: Long): Long? = TODO("Phase 9 E7 commit B")

    /** Drag finished: the next drag previews its first position at once. */
    fun reset(): Unit = TODO("Phase 9 E7 commit B")
}
