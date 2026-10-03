package com.spectroflac.queue

import com.spectroflac.analysis.AnalysisReport

enum class ItemState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

/** A file as it enters the queue. */
data class NewFile(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    /** Last-modified time of the source file, 0 when the provider does not say. */
    val lastModified: Long = 0L,
)

data class QueueItem(
    val id: Long,
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long = 0L,
    val state: ItemState = ItemState.PENDING,
    /** The verdict and numbers, without the heavy charts: opening the file re-analyses it. */
    val report: AnalysisReport? = null,
    val error: String? = null,
    /** 1, 2, 3… in the order files finished; 0 while not finished. */
    val completedSeq: Int = 0,
    val durationMillis: Long = 0L,
) {
    val isFinished: Boolean
        get() = state == ItemState.DONE || state == ItemState.FAILED || state == ItemState.SKIPPED
}

data class QueueSnapshot(
    val items: List<QueueItem> = emptyList(),
    /** The user paused the queue. */
    val paused: Boolean = false,
    /** The queue is holding back for a device reason (heat, battery). */
    val holdReason: String? = null,
    /** How many files may run at once right now. */
    val parallelism: Int = 1,
) {
    val running: List<QueueItem> get() = items.filter { it.state == ItemState.RUNNING }
    val pending: List<QueueItem> get() = items.filter { it.state == ItemState.PENDING }
    val finished: List<QueueItem> get() = items.filter { it.isFinished }.sortedBy { it.completedSeq }
    val isActive: Boolean get() = items.any { it.state == ItemState.PENDING || it.state == ItemState.RUNNING }
    val isHeld: Boolean get() = paused || holdReason != null
}

/** Overall progress of the queue. Byte-based, so a 90 MB file weighs more than a 10 MB one. */
data class QueueStats(
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val running: Int = 0,
    val pending: Int = 0,
    /** 0..1 over the bytes of every file that is not skipped. */
    val fraction: Float = 0f,
    val totalBytes: Long = 0L,
    val processedBytes: Long = 0L,
    /** Null while there is not enough data to estimate. */
    val etaMillis: Long? = null,
    val bytesPerSecond: Double? = null,
) {
    /** Files that are no longer waiting or running. */
    val completed: Int get() = done + failed + skipped

    companion object {
        val EMPTY = QueueStats()

        fun compute(items: List<QueueItem>, progress: Map<Long, Float>): QueueStats {
            var done = 0; var failed = 0; var skipped = 0; var running = 0; var pending = 0
            var totalBytes = 0L
            var processed = 0.0
            for (item in items) {
                when (item.state) {
                    ItemState.DONE -> { done++; totalBytes += item.sizeBytes; processed += item.sizeBytes }
                    ItemState.FAILED -> { failed++; totalBytes += item.sizeBytes; processed += item.sizeBytes }
                    ItemState.SKIPPED -> skipped++
                    ItemState.RUNNING -> {
                        running++
                        totalBytes += item.sizeBytes
                        processed += item.sizeBytes * (progress[item.id] ?: 0f).coerceIn(0f, 1f)
                    }
                    ItemState.PENDING -> { pending++; totalBytes += item.sizeBytes }
                }
            }
            val fraction = when {
                totalBytes > 0 -> (processed / totalBytes).toFloat().coerceIn(0f, 1f)
                items.isNotEmpty() && pending + running == 0 -> 1f
                else -> 0f
            }
            return QueueStats(
                total = items.size, done = done, failed = failed, skipped = skipped, running = running,
                pending = pending, fraction = fraction, totalBytes = totalBytes, processedBytes = processed.toLong(),
            )
        }
    }
}
