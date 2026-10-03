package com.spectroflac.queue

import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.Verdict
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * The scan queue: files wait here, up to [parallelism] of them are analysed at the same time, and
 * everything the UI needs (items, per-file progress, overall progress and time left) is published
 * as state flows.
 *
 * It knows nothing about Android: the analysis, the worker threads and every device-dependent
 * decision (how many files at once, whether to hold back) are handed in, so the logic runs on a
 * plain JVM in unit tests.
 */
class AnalysisQueue(
    private val scope: CoroutineScope,
    private val workers: CoroutineDispatcher,
    private val analyze: suspend (uri: String, onProgress: (Float) -> Unit) -> AnalysisReport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMillis: Long = 250,
) {
    /** Called on the worker thread before each file, e.g. to set the thread priority. */
    @Volatile var onWorkerStart: () -> Unit = {}

    /** When [skipKnown] is on, returns an up-to-date earlier result for the file, if there is one. */
    @Volatile var lookupKnown: suspend (QueueItem) -> AnalysisReport? = { null }
    @Volatile var skipKnown: Boolean = false

    /** Called after every finished file (done, failed or skipped), e.g. to store it in the history. */
    @Volatile var onItemFinished: suspend (QueueItem) -> Unit = {}

    /** Called when the queue runs dry after having had work to do. */
    @Volatile var onIdle: (QueueSnapshot) -> Unit = {}

    private val lock = Any()
    private var nextId = 1L
    private var completedCounter = 0
    private val items = ArrayList<QueueItem>()
    private val jobs = HashMap<Long, Job>()
    private var paused = false
    private var holdReason: String? = null
    private var parallelism = 1
    private var tickerJob: Job? = null
    private var hadWork = false

    private val progressMap = ConcurrentHashMap<Long, Float>()
    private val estimator = EtaEstimator(clock = clock)
    private var lastEta: Long? = null
    private var lastRate: Double? = null

    private val _snapshot = MutableStateFlow(QueueSnapshot())
    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    private val _stats = MutableStateFlow(QueueStats.EMPTY)
    private val _active = MutableStateFlow(false)

    val snapshot: StateFlow<QueueSnapshot> get() = _snapshot
    /** Per-file progress (0..1) of the files that are running. */
    val progress: StateFlow<Map<Long, Float>> get() = _progress
    val stats: StateFlow<QueueStats> get() = _stats
    /** True while any file waits or runs. */
    val isActive: StateFlow<Boolean> get() = _active

    // ---- adding ----------------------------------------------------------------------------

    /** Appends files to the queue; files already waiting or running are ignored. Returns how many were added. */
    fun enqueue(files: List<NewFile>): Int {
        var added = 0
        synchronized(lock) {
            for (file in files) {
                val existing = items.firstOrNull { it.uri == file.uri }
                if (existing != null) {
                    if (!existing.isFinished) continue
                    items.remove(existing) // a new request replaces an old result
                }
                items += QueueItem(nextId++, file.uri, file.name, file.sizeBytes, file.lastModified)
                added++
            }
            if (added > 0) hadWork = true
            publish()
        }
        kick()
        return added
    }

    // ---- control ---------------------------------------------------------------------------

    fun pause() {
        synchronized(lock) { paused = true; estimator.reset(); publish() }
    }

    fun resume() {
        synchronized(lock) { paused = false; publish() }
        kick()
    }

    /** How many files may run at once; lowering it never stops files that already run. */
    fun setParallelism(n: Int) {
        synchronized(lock) { parallelism = n.coerceAtLeast(0); publish() }
        kick()
    }

    /** Hold new files back for a device reason (null releases the hold). */
    fun setHold(reason: String?) {
        synchronized(lock) {
            if (holdReason == reason) return
            holdReason = reason
            if (reason != null) estimator.reset()
            publish()
        }
        kick()
    }

    /** Stops a running file or removes a waiting one. */
    fun cancel(id: Long) {
        synchronized(lock) {
            val item = items.firstOrNull { it.id == id } ?: return
            if (item.state == ItemState.RUNNING) jobs.remove(id)?.cancel()
            items.remove(item)
            progressMap.remove(id)
            publish()
        }
        kick()
    }

    /** Stops everything that runs and drops everything that waits. Finished files stay. */
    fun cancelAll() {
        synchronized(lock) {
            items.filter { !it.isFinished }.forEach { item ->
                if (item.state == ItemState.RUNNING) jobs.remove(item.id)?.cancel()
                progressMap.remove(item.id)
            }
            items.removeAll { !it.isFinished }
            estimator.reset()
            publish()
        }
    }

    /** Puts a waiting file in front of the other waiting ones. */
    fun moveToTop(id: Long) {
        synchronized(lock) {
            val item = items.firstOrNull { it.id == id && it.state == ItemState.PENDING } ?: return
            items.remove(item)
            val firstPending = items.indexOfFirst { it.state == ItemState.PENDING }
            items.add(if (firstPending < 0) items.size else firstPending, item)
            publish()
        }
    }

    fun retry(id: Long) {
        synchronized(lock) {
            val index = items.indexOfFirst { it.id == id && it.state == ItemState.FAILED }
            if (index < 0) return
            items[index] = items[index].copy(state = ItemState.PENDING, report = null, error = null)
            hadWork = true
            publish()
        }
        kick()
    }

    fun retryFailed() {
        synchronized(lock) {
            for (i in items.indices) {
                if (items[i].state == ItemState.FAILED) {
                    items[i] = items[i].copy(state = ItemState.PENDING, report = null, error = null)
                    hadWork = true
                }
            }
            publish()
        }
        kick()
    }

    fun clearFinished() {
        synchronized(lock) {
            items.removeAll { it.isFinished }
            publish()
        }
    }

    // ---- scheduling ------------------------------------------------------------------------

    private fun kick() {
        synchronized(lock) {
            if (paused || holdReason != null) return
            while (runningCount() < parallelism) {
                val index = items.indexOfFirst { it.state == ItemState.PENDING }
                if (index < 0) break
                start(index)
            }
        }
    }

    private fun runningCount() = items.count { it.state == ItemState.RUNNING }

    /** Must be called with [lock] held. */
    private fun start(index: Int) {
        val item = items[index].copy(state = ItemState.RUNNING)
        items[index] = item
        progressMap[item.id] = 0f
        // The job's completion takes the same lock, so it cannot race the registration below.
        jobs[item.id] = scope.launch(workers) { run(item) }
        publish()
        ensureTicker()
    }

    private suspend fun run(item: QueueItem) {
        val started = clock()
        var report: AnalysisReport? = null
        var error: String? = null
        var skipped = false
        try {
            onWorkerStart()
            val known = if (skipKnown) runCatching { lookupKnown(item) }.getOrNull() else null
            if (known != null) {
                report = known
                skipped = true
            } else {
                val full = analyze(item.uri) { fraction -> progressMap[item.id] = fraction.coerceIn(0f, 1f) }
                // A cancelled decode returns a truncated report: drop it instead of recording it.
                coroutineContext.ensureActive()
                report = full.light()
                if (full.verdict == Verdict.ERROR) error = full.errorMessage ?: "The file could not be analysed."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
        }

        val finished = synchronized(lock) {
            val index = items.indexOfFirst { it.id == item.id }
            jobs.remove(item.id)
            progressMap.remove(item.id)
            if (index < 0) {
                null // cancelled while finishing
            } else {
                val state = when {
                    skipped -> ItemState.SKIPPED
                    error != null -> ItemState.FAILED
                    else -> ItemState.DONE
                }
                val updated = items[index].copy(
                    state = state, report = report, error = error,
                    completedSeq = ++completedCounter, durationMillis = clock() - started,
                )
                items[index] = updated
                publish()
                updated
            }
        }
        if (finished != null) {
            runCatching { onItemFinished(finished) }
            kick()
            val idle = synchronized(lock) { items.none { !it.isFinished } && hadWork }
            if (idle) {
                synchronized(lock) { hadWork = false }
                onIdle(_snapshot.value)
            }
        }
    }

    // ---- state publishing ------------------------------------------------------------------

    /** Must be called with [lock] held. */
    private fun publish() {
        val copy = items.toList()
        _snapshot.value = QueueSnapshot(copy, paused, holdReason, parallelism)
        _active.value = copy.any { it.state == ItemState.PENDING || it.state == ItemState.RUNNING }
        refreshStats(copy)
    }

    private fun refreshStats(copy: List<QueueItem>) {
        val progress = HashMap(progressMap)
        _progress.value = progress
        _stats.value = QueueStats.compute(copy, progress).copy(etaMillis = lastEta, bytesPerSecond = lastRate)
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                delay(tickMillis)
                val keepGoing = synchronized(lock) {
                    val copy = items.toList()
                    val progress = HashMap(progressMap)
                    val stats = QueueStats.compute(copy, progress)
                    val working = stats.running > 0 && !paused && holdReason == null
                    if (working) {
                        estimator.record(stats.processedBytes)
                        lastEta = estimator.remainingMillis(stats.totalBytes - stats.processedBytes)
                        lastRate = estimator.throughput()
                    } else {
                        estimator.reset()
                        lastEta = null
                        lastRate = null
                    }
                    refreshStats(copy)
                    stats.running > 0 || stats.pending > 0
                }
                if (!keepGoing) break
            }
            synchronized(lock) {
                lastEta = null
                lastRate = null
                estimator.reset()
                refreshStats(items.toList())
            }
        }
    }

    /** The results of every file that finished with one, in the order they finished. */
    fun reports(): List<AnalysisReport> =
        _snapshot.value.finished.mapNotNull { it.report }
}
