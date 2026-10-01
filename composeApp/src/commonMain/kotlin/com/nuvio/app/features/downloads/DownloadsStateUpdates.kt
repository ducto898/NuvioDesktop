package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * nuvio-rr fork, Phase 9 #2. The downloads list is changed from the UI thread (enqueue, pause, cancel) and from the
 * downloader's IO threads (progress, success, failure). Every change must be one atomic read-modify-write, so a
 * progress report racing a Pause, or an enqueue racing a completion, can't write an old list back.
 *
 * Returns the list before and after the change.
 */
internal fun MutableStateFlow<DownloadsUiState>.updateItems(
    transform: (List<DownloadItem>) -> List<DownloadItem>,
): Pair<List<DownloadItem>, List<DownloadItem>> {
    while (true) {
        val current = value
        val after = transform(current.items)
        if (compareAndSet(current, DownloadsUiState(after))) return current.items to after
    }
}

/** [updateItems] for one item; null when no item has [downloadId] (then nothing changes). */
internal fun MutableStateFlow<DownloadsUiState>.updateItem(
    downloadId: String,
    transform: (DownloadItem) -> DownloadItem,
): Pair<List<DownloadItem>, List<DownloadItem>>? {
    if (value.items.none { it.id == downloadId }) return null
    return updateItems { items -> items.map { if (it.id == downloadId) transform(it) else it } }
}

/**
 * When a change has to be written to the downloads store. Progress (bytes and the time stamp) only matters for the
 * UI while a download runs: after a restart a running download comes back as Paused and resumes from the size of its
 * partial file. So a progress-only change is written at most every [progressIntervalMs] per download; anything else
 * (status, file, error, new or removed item) is written at once.
 */
internal class DownloadPersistPolicy(private val progressIntervalMs: Long = 10_000L) {
    private val lock = SynchronizedObject()
    private val lastWrittenMs = mutableMapOf<String, Long>()

    fun shouldPersist(before: List<DownloadItem>, after: List<DownloadItem>, nowMs: Long): Boolean = synchronized(lock) {
        val previous = before.associateBy { it.id }
        var structural = after.size != before.size || after.any { it.id !in previous }
        val progressed = mutableListOf<String>()
        for (item in after) {
            val old = previous[item.id] ?: continue
            if (old == item) continue
            if (old.withProgressOf(item) == item) progressed += item.id else structural = true
        }
        val due = structural || progressed.any { id -> lastWrittenMs[id]?.let { nowMs - it >= progressIntervalMs } ?: true }
        if (due) progressed.forEach { lastWrittenMs[it] = nowMs }
        lastWrittenMs.keys.retainAll(after.map { it.id }.toSet())
        due
    }

    private fun DownloadItem.withProgressOf(other: DownloadItem): DownloadItem = copy(
        downloadedBytes = other.downloadedBytes,
        totalBytes = other.totalBytes,
        updatedAtEpochMs = other.updatedAtEpochMs,
    )
}
