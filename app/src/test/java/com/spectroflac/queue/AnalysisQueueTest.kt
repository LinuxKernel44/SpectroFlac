package com.spectroflac.queue

import com.spectroflac.analysis.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AnalysisQueueTest {

    private var fixture: QueueFixture? = null

    @After
    fun tearDown() { fixture?.close() }

    private fun fixture(parallelism: Int = 2, auto: Long = 0): QueueFixture =
        QueueFixture(FakeAnalyzer(auto), parallelism).also { fixture = it }

    @Test
    fun `never runs more files at once than the parallelism and uses all of it`() {
        val f = fixture(parallelism = 3, auto = 40)
        f.queue.enqueue((1..12).map { file("f$it.flac") })
        f.awaitAllFinished()
        assertEquals(3, f.analyzer.maxConcurrent)
        assertEquals(12, f.queue.snapshot.value.finished.count { it.state == ItemState.DONE })
    }

    @Test
    fun `one thread processes files strictly one after another`() {
        val f = fixture(parallelism = 1, auto = 10)
        f.queue.enqueue((1..5).map { file("f$it.flac") })
        f.awaitAllFinished()
        assertEquals(1, f.analyzer.maxConcurrent)
        assertEquals((1..5).map { "content://test/f$it.flac" }, f.analyzer.started.toList())
    }

    @Test
    fun `pause lets running files finish and starts nothing new until resumed`() {
        val f = fixture(parallelism = 2)
        f.analyzer.hold("a.flac", "b.flac")
        f.queue.enqueue(listOf(file("a.flac"), file("b.flac"), file("c.flac"), file("d.flac")))
        f.await("a and b running") { f.state("a.flac") == ItemState.RUNNING && f.state("b.flac") == ItemState.RUNNING }
        f.queue.pause()
        f.analyzer.release("a.flac"); f.analyzer.release("b.flac")
        f.await("a and b done") { f.state("a.flac") == ItemState.DONE && f.state("b.flac") == ItemState.DONE }
        Thread.sleep(150)
        assertEquals(ItemState.PENDING, f.state("c.flac"))
        assertEquals(ItemState.PENDING, f.state("d.flac"))
        assertTrue(f.queue.snapshot.value.paused)
        f.queue.resume()
        f.awaitAllFinished()
        assertEquals(4, f.queue.snapshot.value.finished.size)
    }

    @Test
    fun `cancelling a running file stops it and the next one takes its place`() {
        val f = fixture(parallelism = 1)
        f.analyzer.hold("a.flac")
        f.queue.enqueue(listOf(file("a.flac"), file("b.flac")))
        f.await("a running") { f.state("a.flac") == ItemState.RUNNING }
        val id = f.queue.snapshot.value.items.first { it.name == "a.flac" }.id
        f.queue.cancel(id)
        f.await("b done") { f.state("b.flac") == ItemState.DONE }
        assertNull(f.state("a.flac"))
        assertTrue("analysis of a should have been cancelled", "content://test/a.flac" in f.analyzer.cancelled)
    }

    @Test
    fun `cancel all drops waiting and running files but keeps finished ones`() {
        val f = fixture(parallelism = 1)
        f.queue.enqueue(listOf(file("done.flac")))
        f.awaitAllFinished()
        f.analyzer.hold("run.flac")
        f.queue.enqueue(listOf(file("run.flac"), file("wait1.flac"), file("wait2.flac")))
        f.await("run running") { f.state("run.flac") == ItemState.RUNNING }
        f.queue.cancelAll()
        assertEquals(listOf("done.flac"), f.queue.snapshot.value.items.map { it.name })
        assertFalse(f.queue.snapshot.value.isActive)
        f.await("cancelled") { "content://test/run.flac" in f.analyzer.cancelled }
    }

    @Test
    fun `move to top makes a waiting file start next`() {
        val f = fixture(parallelism = 1)
        f.analyzer.hold("a.flac")
        f.queue.enqueue(listOf(file("a.flac"), file("b.flac"), file("c.flac"), file("d.flac")))
        f.await("a running") { f.state("a.flac") == ItemState.RUNNING }
        f.queue.moveToTop(f.queue.snapshot.value.items.first { it.name == "d.flac" }.id)
        f.analyzer.release("a.flac")
        f.awaitAllFinished()
        assertEquals(
            listOf("a.flac", "d.flac", "b.flac", "c.flac").map { "content://test/$it" },
            f.analyzer.started.toList(),
        )
    }

    @Test
    fun `a file already in the queue is not added twice but a finished one is replaced`() {
        val f = fixture(parallelism = 1)
        f.analyzer.hold("a.flac")
        assertEquals(1, f.queue.enqueue(listOf(file("a.flac"))))
        assertEquals(0, f.queue.enqueue(listOf(file("a.flac"))))
        f.analyzer.release("a.flac")
        f.awaitAllFinished()
        assertEquals(1, f.queue.enqueue(listOf(file("a.flac"))))
        f.await("rerun") { f.analyzer.calls.get() == 2 }
        assertEquals(1, f.queue.snapshot.value.items.size)
    }

    @Test
    fun `a file that throws fails and can be retried`() {
        val f = fixture(parallelism = 1)
        f.analyzer.failOnce += "content://test/bad.flac"
        f.queue.enqueue(listOf(file("bad.flac"), file("ok.flac")))
        f.awaitAllFinished()
        val bad = f.queue.snapshot.value.items.first { it.name == "bad.flac" }
        assertEquals(ItemState.FAILED, bad.state)
        assertEquals("boom", bad.error)
        assertEquals(ItemState.DONE, f.state("ok.flac"))
        f.queue.retry(bad.id)
        f.await("retried") { f.state("bad.flac") == ItemState.DONE }
    }

    @Test
    fun `a report with an error verdict counts as failed, retry all re-queues it`() {
        val f = fixture(parallelism = 2)
        f.analyzer.errorReportFor += "content://test/broken.flac"
        f.queue.enqueue(listOf(file("broken.flac"), file("fine.flac")))
        f.awaitAllFinished()
        val broken = f.queue.snapshot.value.items.first { it.name == "broken.flac" }
        assertEquals(ItemState.FAILED, broken.state)
        assertEquals("Malformed FLAC stream", broken.error)
        f.analyzer.errorReportFor.clear()
        f.queue.retryFailed()
        f.await("broken now done") { f.state("broken.flac") == ItemState.DONE }
    }

    @Test
    fun `lowering the parallelism does not stop running files but limits new ones`() {
        val f = fixture(parallelism = 3)
        f.analyzer.hold("a.flac", "b.flac", "c.flac", "d.flac", "e.flac")
        f.queue.enqueue(listOf("a", "b", "c", "d", "e").map { file("$it.flac") })
        f.await("three running") { f.queue.snapshot.value.running.size == 3 }
        f.queue.setParallelism(1)
        f.analyzer.release("a.flac")
        f.await("a done") { f.state("a.flac") == ItemState.DONE }
        Thread.sleep(100)
        assertEquals("b and c still run, nothing new starts", 2, f.queue.snapshot.value.running.size)
        f.analyzer.release("b.flac"); f.analyzer.release("c.flac")
        f.await("d starts once the pool is below the limit") { f.state("d.flac") == ItemState.RUNNING }
        assertEquals(1, f.queue.snapshot.value.running.size)
    }

    @Test
    fun `a device hold keeps files waiting until it is released`() {
        val f = fixture(parallelism = 2)
        f.queue.setHold("Phone is too hot")
        f.queue.enqueue(listOf(file("a.flac"), file("b.flac")))
        Thread.sleep(150)
        assertEquals(0, f.analyzer.calls.get())
        assertEquals("Phone is too hot", f.queue.snapshot.value.holdReason)
        f.queue.setHold(null)
        f.awaitAllFinished()
        assertEquals(2, f.analyzer.calls.get())
    }

    @Test
    fun `a parallelism of zero holds files back`() {
        val f = fixture(parallelism = 0)
        f.queue.enqueue(listOf(file("a.flac")))
        Thread.sleep(100)
        assertEquals(0, f.analyzer.calls.get())
        f.queue.setParallelism(1)
        f.awaitAllFinished()
    }

    @Test
    fun `skip known files returns the earlier result without analysing`() {
        val f = fixture(parallelism = 2)
        f.queue.skipKnown = true
        f.queue.lookupKnown = { item -> if (item.name == "known.flac") fakeReport(item.uri) else null }
        f.queue.enqueue(listOf(file("known.flac"), file("new.flac")))
        f.awaitAllFinished()
        assertEquals(ItemState.SKIPPED, f.state("known.flac"))
        assertEquals(ItemState.DONE, f.state("new.flac"))
        assertEquals("only the new file is analysed", 1, f.analyzer.calls.get())
        assertNotNull(f.queue.snapshot.value.items.first { it.name == "known.flac" }.report)
    }

    @Test
    fun `finished reports are light - no cover art is kept`() {
        val f = fixture(parallelism = 1)
        f.queue.enqueue(listOf(file("a.flac")))
        f.awaitAllFinished()
        val report = f.queue.snapshot.value.items.single().report!!
        assertNull(report.coverBytes)
        assertEquals(Verdict.AUTHENTIC, report.verdict)
    }

    @Test
    fun `idle callback fires once when the queue runs dry and not while paused`() {
        val f = fixture(parallelism = 2, auto = 10)
        val idle = AtomicInteger()
        f.queue.onIdle = { idle.incrementAndGet() }
        f.queue.enqueue((1..4).map { file("f$it.flac") })
        f.awaitAllFinished()
        f.await("idle callback") { idle.get() >= 1 }
        Thread.sleep(100)
        assertEquals(1, idle.get())
    }

    @Test
    fun `item finished callback sees every file`() {
        val f = fixture(parallelism = 2, auto = 5)
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        f.queue.onItemFinished = { seen += it.name }
        f.queue.enqueue((1..6).map { file("f$it.flac") })
        f.awaitAllFinished()
        f.await("callbacks") { seen.size == 6 }
        assertEquals(6, seen.toSet().size)
    }

    @Test
    fun `stats count files and weigh progress by bytes`() {
        val f = fixture(parallelism = 1)
        f.analyzer.hold("big.flac")
        f.queue.enqueue(listOf(file("big.flac", 90_000_000), file("small.flac", 10_000_000)))
        f.await("big running at 25 %") { f.queue.stats.value.running == 1 && f.queue.progress.value.values.any { it >= 0.25f } }
        val stats = f.queue.stats.value
        assertEquals(2, stats.total)
        assertEquals(1, stats.pending)
        assertEquals(100_000_000L, stats.totalBytes)
        // 25 % of the 90 MB file = 22.5 MB of 100 MB
        assertEquals(0.225f, stats.fraction, 0.01f)
        f.analyzer.release("big.flac")
        f.awaitAllFinished()
        f.await("final stats") { f.queue.stats.value.fraction == 1f }
        assertEquals(2, f.queue.stats.value.done)
        assertNull(f.queue.stats.value.etaMillis)
    }
}
