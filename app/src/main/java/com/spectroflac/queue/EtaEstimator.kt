package com.spectroflac.queue

/**
 * Estimates the time left from how fast bytes were processed over the last few seconds.
 *
 * Throughput is measured over a sliding window of the *total* bytes processed by all workers, so it
 * already reflects how many files run in parallel and how loaded the phone is. Time spent paused
 * is not counted: pausing (or a drop in the processed total, e.g. a cancelled file) restarts the
 * measurement. Until there are [MIN_SPAN_MILLIS] of data the estimate is null ("estimating").
 */
class EtaEstimator(
    private val windowMillis: Long = 20_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Sample(val time: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()

    /** Reports the total bytes processed so far (done files plus the partial progress of running ones). */
    fun record(processedBytes: Long) {
        val now = clock()
        val last = samples.lastOrNull()
        if (last != null && processedBytes < last.bytes) samples.clear()
        samples.addLast(Sample(now, processedBytes))
        while (samples.size > 2 && now - samples.first().time > windowMillis) samples.removeFirst()
    }

    /** Forget the history, e.g. when the queue is paused or becomes idle. */
    fun reset() = samples.clear()

    /** Bytes per second over the window, or null until enough data has been seen. */
    fun throughput(): Double? {
        val first = samples.firstOrNull() ?: return null
        val last = samples.last()
        val span = last.time - first.time
        val bytes = last.bytes - first.bytes
        if (span < MIN_SPAN_MILLIS || bytes <= 0) return null
        return bytes * 1000.0 / span
    }

    /** Milliseconds left for [remainingBytes], or null while estimating. */
    fun remainingMillis(remainingBytes: Long): Long? {
        if (remainingBytes <= 0) return 0
        val rate = throughput() ?: return null
        return (remainingBytes * 1000.0 / rate).toLong()
    }

    companion object {
        const val MIN_SPAN_MILLIS = 3_000L
    }
}
