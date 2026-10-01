package com.nuvio.app.core.ui

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.crossfade
import coil3.request.crossfadeMillis
import com.nuvio.app.core.storage.DesktopStorage

// nuvio-rr fork, Phase 9 E5: the desktop side of Reduce motion.

internal const val MOTION_STORE = "nuvio_motion"
internal const val REDUCE_MOTION_KEY = "reduce_motion"

/** Binds [ReduceMotion] to the per-PC store (not per profile, not synced, like the refresh-rate switch). */
internal fun bindReduceMotion(store: DesktopStorage.Store = DesktopStorage.store(MOTION_STORE)) {
    ReduceMotion.bind(initial = store.getBoolean(REDUCE_MOTION_KEY) ?: false) { store.putBoolean(REDUCE_MOTION_KEY, it) }
}

/** Coil: no crossfade while Reduce motion is on (the desktop painter reads the crossfade time from each request). */
internal class ReduceMotionInterceptor(private val reduceMotion: () -> Boolean = { ReduceMotion.enabled.value }) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (!reduceMotion() || chain.request.crossfadeMillis == 0) return chain.proceed()
        return chain.withRequest(chain.request.newBuilder().crossfade(0).build()).proceed()
    }
}
