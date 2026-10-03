package com.spectroflac.queue

import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.Verdict
import com.spectroflac.flac.ContainerKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

fun fakeReport(
    uri: String,
    verdict: Verdict = Verdict.AUTHENTIC,
    error: String? = null,
    cover: ByteArray? = null,
) = AnalysisReport(
    fileName = uri.substringAfterLast('/'), uri = uri, container = ContainerKind.FLAC, verdict = verdict,
    confidence = 90, headline = "", summary = "", findings = emptyList(), technical = null, encoder = null,
    spectral = null, integrity = null, dynamics = null, tags = emptyMap(), coverBytes = cover, spectrogram = null,
    analysedAtMillis = 0, analysisDurationMillis = 0, errorMessage = error,
)

fun file(name: String, size: Long = 1_000_000) = NewFile("content://test/$name", name, size)

/**
 * A fake analyser the test controls: each file blocks on a gate until released (or finishes after
 * [autoMillis] when no gate is used), and the number of files running at once is recorded.
 */
class FakeAnalyzer(private val autoMillis: Long = 0) {
    val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    val started = java.util.Collections.synchronizedList(ArrayList<String>())
    val cancelled = java.util.Collections.synchronizedSet(HashSet<String>())
    val failOnce = java.util.Collections.synchronizedSet(HashSet<String>())
    val errorReportFor = java.util.Collections.synchronizedSet(HashSet<String>())
    private val concurrent = AtomicInteger()
    @Volatile var maxConcurrent = 0
    val calls = AtomicInteger()

    fun gate(uri: String): CompletableDeferred<Unit> = gates.getOrPut(uri) { CompletableDeferred() }
    fun hold(vararg names: String) = names.forEach { gate("content://test/$it") }
    fun release(name: String) = gate("content://test/$name").complete(Unit)

    suspend fun analyze(uri: String, onProgress: (Float) -> Unit): AnalysisReport {
        calls.incrementAndGet()
        started += uri
        val now = concurrent.incrementAndGet()
        if (now > maxConcurrent) maxConcurrent = now
        try {
            onProgress(0.25f)
            val gate = gates[uri]
            try {
                if (gate != null) gate.await() else delay(autoMillis)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += uri
                throw e
            }
            onProgress(1f)
            if (failOnce.remove(uri)) throw IllegalStateException("boom")
            if (uri in errorReportFor) return fakeReport(uri, Verdict.ERROR, error = "Malformed FLAC stream")
            return fakeReport(uri, cover = ByteArray(1000))
        } finally {
            concurrent.decrementAndGet()
        }
    }
}

class QueueFixture(
    val analyzer: FakeAnalyzer = FakeAnalyzer(),
    parallelism: Int = 2,
) {
    private val pool = Executors.newFixedThreadPool(8)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val queue = AnalysisQueue(scope, pool.asCoroutineDispatcher(), analyzer::analyze, tickMillis = 20)

    init { queue.setParallelism(parallelism) }

    fun close() {
        scope.cancel()
        pool.shutdownNow()
    }

    fun state(name: String): ItemState? =
        queue.snapshot.value.items.firstOrNull { it.name == name }?.state

    /** Polls until [condition] holds, failing the test after [timeoutMillis]. */
    fun await(message: String = "condition", timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $message; state=" +
                queue.snapshot.value.items.joinToString { "${it.name}:${it.state}" })
            Thread.sleep(5)
        }
    }

    fun awaitAllFinished() = await("all files finished") { !queue.snapshot.value.isActive && queue.snapshot.value.items.isNotEmpty() }
}
