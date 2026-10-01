package com.nuvio.app.core.ui

import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.crossfade
import coil3.request.crossfadeMillis
import coil3.size.Size
import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 E5
class ReduceMotionTest {
    private val dir = Files.createTempDirectory("nuvio-rr-motion")
    private val file = dir.resolve("$MOTION_STORE.properties")

    @AfterTest
    fun tearDown() {
        ReduceMotion.bind(false) {}
        file.deleteIfExists()
        dir.deleteIfExists()
    }

    @Test
    fun `off by default, a change is stored per PC and read back at the next start`() {
        bindReduceMotion(DesktopStorage.Store(file))
        assertFalse(ReduceMotion.enabled.value)
        assertFalse(Files.exists(file), "reading must not create the file")
        ReduceMotion.set(true)
        assertTrue(ReduceMotion.enabled.value)
        ReduceMotion.bind(false) {}
        bindReduceMotion(DesktopStorage.Store(file))
        assertTrue(ReduceMotion.enabled.value)
    }

    private class RecordingChain(override val request: ImageRequest) : Interceptor.Chain {
        var proceeded: ImageRequest? = null
        override val size: Size = Size.ORIGINAL
        override fun withRequest(request: ImageRequest): Interceptor.Chain = RecordingChain(request).also { child = it }
        override fun withSize(size: Size): Interceptor.Chain = this
        override suspend fun proceed(): ImageResult {
            proceeded = request
            return ErrorResult(null, request, IllegalStateException("test"))
        }
        var child: RecordingChain? = null
    }

    private fun proceededCrossfade(reduce: Boolean): Int {
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data("x").crossfade(200).build()
        val chain = RecordingChain(request)
        runBlocking { ReduceMotionInterceptor { reduce }.intercept(chain) }
        return ((chain.child ?: chain).proceeded ?: error("not proceeded")).crossfadeMillis
    }

    @Test
    fun `crossfade is dropped only while reduce motion is on`() {
        assertEquals(200, proceededCrossfade(reduce = false))
        assertEquals(0, proceededCrossfade(reduce = true))
    }
}
