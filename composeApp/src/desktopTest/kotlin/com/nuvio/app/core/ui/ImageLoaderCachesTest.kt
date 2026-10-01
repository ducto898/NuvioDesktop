package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext
import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 #13: bounded image caches, the disk one in the app's own cache folder (not %TEMP%).
class ImageLoaderCachesTest {
    @Test
    fun `desktop image caches are bounded and live in the app cache folder`() {
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).configurePlatformImageLoader().build()
        val memory = assertNotNull(loader.memoryCache)
        assertTrue(memory.maxSize <= 256L * 1024 * 1024, "memory cache max ${memory.maxSize}")
        val disk = assertNotNull(loader.diskCache)
        assertTrue(disk.maxSize <= 512L * 1024 * 1024, "disk cache max ${disk.maxSize}")
        val dir = Paths.get(disk.directory.toString()).toAbsolutePath().normalize()
        assertTrue(dir.startsWith(DesktopStorage.cacheDir.toAbsolutePath().normalize()), "disk cache at $dir")
    }
}
