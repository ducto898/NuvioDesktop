package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// nuvio-rr fork, Phase 9 #2
class DownloadsStateUpdatesTest {
    private fun item(id: String, status: DownloadStatus = DownloadStatus.Downloading, bytes: Long = 0L, at: Long = 0L) = DownloadItem(
        id = id, contentType = "movie", parentMetaId = "tt$id", parentMetaType = "movie", videoId = "tt$id",
        title = id, streamTitle = id, providerName = "p", sourceUrl = "https://example.invalid/$id.mkv",
        fileName = "$id.mkv", status = status, downloadedBytes = bytes, createdAtEpochMs = 0L, updatedAtEpochMs = at,
    )

    @Test
    fun `a pause racing a flood of progress updates always stays paused`() {
        repeat(200) { trial ->
            val state = MutableStateFlow(DownloadsUiState(listOf(item("a"))))
            val start = CountDownLatch(1)
            val progress = thread {
                start.await()
                for (n in 1..2_000L) {
                    state.updateItem("a") { current ->
                        if (current.status != DownloadStatus.Downloading) current else current.copy(downloadedBytes = n)
                    }
                }
            }
            start.countDown()
            Thread.sleep(0, 200_000)
            state.updateItem("a") { it.copy(status = DownloadStatus.Paused) }
            progress.join()
            assertEquals(DownloadStatus.Paused, state.value.items.single().status, "trial $trial: a progress report undid the pause")
        }
    }

    @Test
    fun `adding an item racing another item's completion keeps both changes`() {
        repeat(200) { trial ->
            val state = MutableStateFlow(DownloadsUiState(listOf(item("a"))))
            val start = CountDownLatch(1)
            val completer = thread {
                start.await()
                for (n in 1..500L) state.updateItem("a") { it.copy(downloadedBytes = n) }
                state.updateItem("a") { it.copy(status = DownloadStatus.Completed) }
            }
            start.countDown()
            for (k in 1..50) state.updateItems { items -> listOf(item("n$k")) + items }
            completer.join()
            val items = state.value.items
            assertEquals(51, items.size, "trial $trial: an add was lost")
            assertEquals(DownloadStatus.Completed, items.single { it.id == "a" }.status, "trial $trial: the completion was lost")
        }
    }

    @Test
    fun `updateItem on a missing id changes nothing`() {
        val state = MutableStateFlow(DownloadsUiState(listOf(item("a"))))
        assertNull(state.updateItem("zzz") { it.copy(status = DownloadStatus.Failed) })
        assertEquals(DownloadStatus.Downloading, state.value.items.single().status)
    }

    @Test
    fun `progress-only changes are written at most every interval, per download`() {
        val policy = DownloadPersistPolicy(progressIntervalMs = 10_000L)
        val a0 = listOf(item("a"), item("b"))
        assertTrue(policy.shouldPersist(a0, listOf(item("a", bytes = 1, at = 1), item("b")), nowMs = 1_000), "first progress of a")
        assertFalse(policy.shouldPersist(a0, listOf(item("a", bytes = 2, at = 2), item("b")), nowMs = 5_000), "a again within 10 s")
        assertTrue(policy.shouldPersist(a0, listOf(item("a"), item("b", bytes = 9, at = 9)), nowMs = 5_000), "first progress of b")
        assertTrue(policy.shouldPersist(a0, listOf(item("a", bytes = 3, at = 3), item("b")), nowMs = 11_000), "a after 10 s")
    }

    @Test
    fun `status, file, error, added and removed items are written at once`() {
        val policy = DownloadPersistPolicy(progressIntervalMs = 10_000L)
        val before = listOf(item("a"))
        policy.shouldPersist(before, listOf(item("a", bytes = 1)), nowMs = 1_000)
        assertTrue(policy.shouldPersist(before, listOf(item("a", status = DownloadStatus.Paused)), nowMs = 1_001))
        assertTrue(policy.shouldPersist(before, listOf(item("a").copy(errorMessage = "x")), nowMs = 1_002))
        assertTrue(policy.shouldPersist(before, listOf(item("a").copy(localFileUri = "file:/x")), nowMs = 1_003))
        assertTrue(policy.shouldPersist(before, listOf(item("a"), item("b")), nowMs = 1_004))
        assertTrue(policy.shouldPersist(before, emptyList(), nowMs = 1_005))
        assertFalse(policy.shouldPersist(before, before, nowMs = 1_006), "no change at all")
    }
}
