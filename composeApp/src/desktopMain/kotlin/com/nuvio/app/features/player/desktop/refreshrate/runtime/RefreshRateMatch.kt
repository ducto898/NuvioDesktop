package com.nuvio.app.features.player.desktop.refreshrate.runtime

import co.touchlab.kermit.Logger

/**
 * Process-global entry points (SPEC P4-13, P4-14): the native upcall at mpv's on_preloaded hook,
 * and the upstream hooks H6 (player screen gone) and H8 (main window close).
 * Every entry returns at once when the feature is off, before creating anything.
 */
object RefreshRateMatch {
    /** Dev knob until the Phase 6 setting (Q14). */
    private val enabled = System.getenv("NUVIO_RR_ENABLE") == "1"

    /** The native hook waits at most this long; the mpv hook must be continued within 5 s (P4-9). */
    private const val START_TIMEOUT_MS = 4_500L

    /** Window close / JVM shutdown wait at most this long for the restore (P4-13). */
    private const val EXIT_TIMEOUT_MS = 2_000L

    private val logger = Logger.withTag("RefreshRateMatch")

    @Volatile
    private var dispatcher: RefreshRateDispatcher? = null

    /** True once a playback start created the dispatcher (its thread and shutdown hook come with it). */
    internal val hasDispatcher: Boolean
        get() = dispatcher != null

    /**
     * Upcall from the native H2, once per player before loadfile (SPEC P6-9): [Enablement.nativeCode], -1 on error.
     * Creates nothing.
     */
    @JvmStatic
    fun nativeFeatureEnabled(): Int = TODO("Phase 6 commit B")

    /** H6: the player screen went away. Returns at once. */
    fun onScreenGone() {
        if (enabled) dispatcher?.screenGone()
    }

    /** H8: main window close. Waits at most 2 s for the restore. */
    fun onAppExit() {
        if (enabled) dispatcher?.appExit(EXIT_TIMEOUT_MS)
    }

    /** Upcall from the native glue worker (never the mpv event thread). Returns [NativeCodec.timing]. */
    @JvmStatic
    fun nativePlaybackStart(
        playerId: Long,
        display: String,
        containerFps: Double,
        estimatedFps: Double,
        isImage: Boolean,
        frameCap: Double,
    ): LongArray = try {
        if (!enabled) {
            NativeCodec.timing(null)
        } else {
            val input = StartInput(
                playerId = playerId,
                display = display,
                containerFps = containerFps.takeIf { it.isFinite() },
                estimatedFps = estimatedFps.takeIf { it.isFinite() },
                isImage = isImage,
                frameCap = NativeCodec.frameCap(frameCap),
            )
            NativeCodec.timing(dispatcher().playbackStart(input, START_TIMEOUT_MS))
        }
    } catch (t: Throwable) {
        log("start p$playerId unexpected-error ${t.javaClass.simpleName}: ${t.message}")
        NativeCodec.timing(null)
    }

    private fun dispatcher(): RefreshRateDispatcher = dispatcher ?: synchronized(this) {
        dispatcher ?: RefreshRateDispatcher(RefreshRateController(NativeDisplayPort, ::log), ::log).also { created ->
            dispatcher = created
            // exitProcess paths skip onCloseRequest (P4-14 c); a crash or kill is reverted by Windows (D7).
            Runtime.getRuntime().addShutdownHook(Thread({ created.appExit(EXIT_TIMEOUT_MS) }, "nuvio-rr-shutdown"))
        }
    }

    private fun log(line: String) {
        logger.i { line }
        NativeDisplayPort.log(line)
    }
}
