package com.spectroflac.queue

import com.spectroflac.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaEstimatorTest {
    private var now = 0L
    private fun estimator() = EtaEstimator(windowMillis = 20_000, clock = { now })

    @Test
    fun `no estimate until a few seconds of data exist`() {
        val e = estimator()
        e.record(0)
        now += 1_000; e.record(5_000_000)
        assertNull(e.remainingMillis(100_000_000))
        now += 2_500; e.record(17_500_000)
        assertNotNull(e.remainingMillis(100_000_000))
    }

    @Test
    fun `steady throughput gives the exact remaining time`() {
        val e = estimator()
        for (s in 0..10) { e.record(s * 10_000_000L); now += 1_000 }
        // 10 MB/s: 50 MB left takes 5 s
        assertEquals(5_000.0, e.remainingMillis(50_000_000)!!.toDouble(), 100.0)
        assertEquals(10_000_000.0, e.throughput()!!, 200_000.0)
    }

    @Test
    fun `only the recent window counts when the speed changes`() {
        val e = estimator()
        var bytes = 0L
        repeat(30) { bytes += 5_000_000; e.record(bytes); now += 1_000 }   // 5 MB/s for 30 s
        repeat(25) { bytes += 20_000_000; e.record(bytes); now += 1_000 }  // then 20 MB/s
        val rate = e.throughput()!!
        assertTrue("window should reflect the new speed, was $rate", rate > 18_000_000)
    }

    @Test
    fun `a drop in processed bytes restarts the measurement`() {
        val e = estimator()
        for (s in 0..5) { e.record(s * 10_000_000L); now += 1_000 }
        e.record(1_000_000) // a running file was cancelled
        assertNull(e.throughput())
    }

    @Test
    fun `reset forgets the pause`() {
        val e = estimator()
        for (s in 0..5) { e.record(s * 10_000_000L); now += 1_000 }
        e.reset()
        now += 60_000 // a long pause
        e.record(60_000_000); now += 1_000; e.record(70_000_000)
        assertNull("only one second of data since the reset", e.remainingMillis(10_000_000))
    }

    @Test
    fun `nothing remaining means zero`() {
        assertEquals(0L, estimator().remainingMillis(0))
    }
}

class QueueStatsTest {
    private fun item(id: Long, size: Long, state: ItemState) =
        QueueItem(id, "u$id", "f$id", size, state = state)

    @Test
    fun `fraction is bytes processed over bytes that will be processed`() {
        val items = listOf(
            item(1, 50, ItemState.DONE), item(2, 100, ItemState.RUNNING), item(3, 50, ItemState.PENDING),
        )
        val stats = QueueStats.compute(items, mapOf(2L to 0.5f))
        assertEquals(200L, stats.totalBytes)
        assertEquals(100L, stats.processedBytes)
        assertEquals(0.5f, stats.fraction, 0.001f)
        assertEquals(1, stats.done); assertEquals(1, stats.running); assertEquals(1, stats.pending)
    }

    @Test
    fun `skipped files are excluded from the work`() {
        val items = listOf(item(1, 1000, ItemState.SKIPPED), item(2, 100, ItemState.DONE))
        val stats = QueueStats.compute(items, emptyMap())
        assertEquals(100L, stats.totalBytes)
        assertEquals(1f, stats.fraction, 0.001f)
        assertEquals(2, stats.completed)
    }

    @Test
    fun `failed files count as processed`() {
        val stats = QueueStats.compute(listOf(item(1, 100, ItemState.FAILED), item(2, 100, ItemState.PENDING)), emptyMap())
        assertEquals(0.5f, stats.fraction, 0.001f)
        assertEquals(1, stats.failed)
    }

    @Test
    fun `an empty queue is at zero and all-skipped is complete`() {
        assertEquals(0f, QueueStats.compute(emptyList(), emptyMap()).fraction, 0f)
        assertEquals(1f, QueueStats.compute(listOf(item(1, 10, ItemState.SKIPPED)), emptyMap()).fraction, 0f)
    }
}

class ParallelismTest {
    @Test
    fun `auto is half the cores`() {
        assertEquals(4, Parallelism.auto(8))
        assertEquals(2, Parallelism.auto(4))
        assertEquals(1, Parallelism.auto(2))
        assertEquals(1, Parallelism.auto(1))
        assertEquals(8, Parallelism.auto(32))
    }

    @Test
    fun `a fixed number is respected up to the maximum`() {
        assertEquals(6, Parallelism.requested(6, 8))
        assertEquals(AppSettings.MAX_PARALLEL, Parallelism.requested(99, 8))
    }

    @Test
    fun `heat lowers both auto and fixed numbers`() {
        val none = Parallelism.THERMAL_NONE
        assertEquals(4, Parallelism.effective(0, 8, true, none, false))
        assertEquals(4, Parallelism.effective(0, 8, true, Parallelism.THERMAL_LIGHT, false))
        assertEquals(2, Parallelism.effective(0, 8, true, Parallelism.THERMAL_MODERATE, false))
        assertEquals(3, Parallelism.effective(6, 8, true, Parallelism.THERMAL_MODERATE, false))
        assertEquals(1, Parallelism.effective(6, 8, true, Parallelism.THERMAL_SEVERE, false))
        assertEquals(0, Parallelism.effective(6, 8, true, Parallelism.THERMAL_CRITICAL, false))
    }

    @Test
    fun `thermal protection can be switched off`() {
        assertEquals(6, Parallelism.effective(6, 8, false, Parallelism.THERMAL_CRITICAL, false))
    }

    @Test
    fun `battery saver only lowers auto`() {
        assertEquals(2, Parallelism.effective(0, 8, true, Parallelism.THERMAL_NONE, true))
        assertEquals(6, Parallelism.effective(6, 8, true, Parallelism.THERMAL_NONE, true))
    }

    @Test
    fun `battery pause only when low and not charging`() {
        assertEquals("Battery below 15 %", Parallelism.batteryPauseReason(10, false, 15))
        assertNull(Parallelism.batteryPauseReason(10, true, 15))
        assertNull(Parallelism.batteryPauseReason(50, false, 15))
        assertNull(Parallelism.batteryPauseReason(5, false, 0))
    }

    @Test
    fun `thermal pause reason`() {
        assertEquals("Phone is too hot", Parallelism.thermalPauseReason(true, Parallelism.THERMAL_CRITICAL))
        assertNull(Parallelism.thermalPauseReason(false, Parallelism.THERMAL_CRITICAL))
        assertNull(Parallelism.thermalPauseReason(true, Parallelism.THERMAL_SEVERE))
    }
}
