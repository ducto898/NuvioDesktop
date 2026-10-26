package com.nuvio.app.features.home.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpServer
import org.junit.Rule
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

// nuvio-rr fork: the hover animation must start even when the visibility prefetch finishes after the hover
// composition read the codec cache (empty) but before that composition's LaunchedEffect ran. The window is one frame
// wide; a composable placed after the card holds that frame until the prefetch has filled the cache.
class CollectionCardHoverRaceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun hoverStartsTheAnimationWhenThePrefetchLandsInTheSameFrame() {
        val release = CountDownLatch(1)
        val served = CountDownLatch(1)
        val cover = ByteArrayOutputStream().apply {
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply {
                for (x in 0..1) for (y in 0..1) setRGB(x, y, 0x0000ff)
            }, "png", this)
        }.toByteArray()
        val animation = Base64.getDecoder().decode(WEBP)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            val isCover = exchange.requestURI.path == "/cover.png"
            if (!isCover) release.await(10, TimeUnit.SECONDS)
            val bytes = if (isCover) cover else animation
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            if (!isCover) served.countDown()
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            compose.setContent {
                // The parent sees the hover in the same frame as the card, so both recompose in one pass.
                val parentHover = remember { MutableInteractionSource() }
                val hovered by parentHover.collectIsHoveredAsState()
                Box(Modifier.hoverable(parentHover)) {
                    CollectionCardRemoteImage(
                        imageUrl = "$baseUrl/race.webp",
                        staticImageUrl = "$baseUrl/cover.png",
                        contentDescription = "Collection preview",
                        modifier = Modifier.size(64.dp).testTag("card"),
                        contentScale = ContentScale.Crop,
                        animateIfPossible = true,
                    )
                    if (hovered && release.count > 0) {
                        // Composed after the card in the same frame: let the prefetch finish and land in the cache
                        // before the card's LaunchedEffect gets to run.
                        release.countDown()
                        served.await(5, TimeUnit.SECONDS)
                        Thread.sleep(500)
                    }
                }
            }
            val card = compose.onNodeWithTag("card")
            fun pixel() = card.captureToImage().toPixelMap()[16, 16]
            compose.waitUntil(timeoutMillis = 5_000) { pixel().let { it.blue > 0.8f && it.red < 0.1f } }

            compose.mainClock.autoAdvance = false
            card.performMouseInput { enter(center) }
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.mainClock.autoAdvance = true
            assertTrue(served.count == 0L, "the frame hold released the prefetch")

            var sawRed = false
            runCatching {
                compose.waitUntil(timeoutMillis = 3_000) { pixel().let { it.red > 0.8f && it.green < 0.1f } }
                sawRed = true
            }
            assertTrue(sawRed, "hover never started the animation: the card kept codecHolder = null")
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        // Two solid 2x2 frames (red, green), 200 ms each, looping indefinitely (same as CollectionCardRemoteImageTest).
        const val WEBP = "UklGRoQAAABXRUJQVlA4WAoAAAACAAAAAQAAAQAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAAEAAAEAAMgAAAJWUDhMDwAAAC8BQAAABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAAEAAAEAAMgAAABWUDhMDwAAAC8BQAAAB1DAiP4HIqL/AQA="
    }
}
