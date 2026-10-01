package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.nuvio.app.core.storage.DesktopStorage
import okio.Path.Companion.toPath

internal actual val platformProvidesImageLoader: Boolean = false

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    return components {
        add(SkiaGifDecoder.Factory())
        add(ReduceMotionInterceptor()) // nuvio-rr fork, Phase 9 E5: no crossfade while Reduce motion is on
    }
        // nuvio-rr fork, Phase 9 #13: explicit bounds (Coil's JVM memory default scales with the max heap, ~8 GB on a
        // 32 GB PC) and the disk cache in the app's own cache folder instead of %TEMP%\coil3_disk_cache (shared with
        // the official app).
        .memoryCache { MemoryCache.Builder().maxSizeBytes(256L * 1024 * 1024).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(DesktopStorage.cacheDir.resolve("image-cache").toString().toPath())
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
}
