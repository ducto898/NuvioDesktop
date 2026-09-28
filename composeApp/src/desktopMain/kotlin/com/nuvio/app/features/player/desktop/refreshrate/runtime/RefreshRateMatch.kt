package com.nuvio.app.features.player.desktop.refreshrate.runtime

import co.touchlab.kermit.Logger
import com.nuvio.app.features.settings.RefreshRateMatchSetting

/**
 * Process-global entry points (SPEC P4-13, P4-14, P6-9): the native upcalls at H2 (is the feature on for this
 * player?) and at mpv's on_preloaded hook, and the upstream hooks H6 (player screen gone) and H8 (main window close).
 * Nothing is created until a player the feature is on for reaches its first playback start.
 */
object RefreshRateMatch {
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
    fun nativeFeatureEnabled(): Int = enablementCode(System.getenv("NUVIO_RR_ENABLE")) { RefreshRateMatchSetting.stored() }

    /** H6: the player screen went away. Returns at once. */
    fun onScreenGone() {
        dispatcher?.screenGone()
    }

    /** H8: main window close. Waits at most 2 s for the restore. */
    fun onAppExit() {
        dispatcher?.appExit(EXIT_TIMEOUT_MS)
    }

    /**
     * Upcall from the native glue worker (never the mpv event thread), only for players H2 found the feature on for.
     * Returns [NativeCodec.timing].
     */
    @JvmStatic
    fun nativePlaybackStart(
        playerId: Long,
        display: String,
        containerFps: Double,
        estimatedFps: Double,
        isImage: Boolean,
        frameCap: Double,
    ): LongArray = try {
        val input = StartInput(
            playerId = playerId,
            display = display,
            containerFps = containerFps.takeIf { it.isFinite() },
            estimatedFps = estimatedFps.takeIf { it.isFinite() },
            isImage = isImage,
            frameCap = NativeCodec.frameCap(frameCap),
        )
        NativeCodec.timing(dispatcher().playbackStart(input, START_TIMEOUT_MS))
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
