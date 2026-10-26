package com.nuvio.app.core.ui

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.nuvio.app.core.storage.DesktopStorage
import okio.Path.Companion.toPath

private const val ImageMemoryCacheBytes = 256L * 1024 * 1024

internal actual val platformProvidesImageLoader: Boolean = false

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    return memoryCache {
        MemoryCache.Builder()
            .maxSizeBytes(ImageMemoryCacheBytes)
            .build()
    }
        // nuvio-rr fork, Phase 9 #13: the disk cache in the app's own cache folder instead of %TEMP%\coil3_disk_cache
        // (shared with the official app).
        .diskCache {
            DiskCache.Builder()
                .directory(DesktopStorage.cacheDir.resolve("image-cache").toString().toPath())
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
}

internal actual fun ComponentRegistry.Builder.addPlatformDecoders() {
    add(SkiaGifDecoder.Factory())
    add(SvgEmbeddedImageDecoder.Factory())
    add(ReduceMotionInterceptor()) // nuvio-rr fork, Phase 9 E5: no crossfade while Reduce motion is on
}
