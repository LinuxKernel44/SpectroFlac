package com.spectroflac.queue

import com.spectroflac.analysis.Verdict
import com.spectroflac.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadPresetTest {
    @Test
    fun `presets on an eight core phone`() {
        assertEquals(2, Parallelism.presetThreads(ThreadPreset.QUIET, 8))
        assertEquals(4, Parallelism.presetThreads(ThreadPreset.BALANCED, 8))
        assertEquals(7, Parallelism.presetThreads(ThreadPreset.FAST, 8))
    }

    @Test
    fun `presets on smaller and bigger phones`() {
        assertEquals(listOf(1, 2, 3), listOf(ThreadPreset.QUIET, ThreadPreset.BALANCED, ThreadPreset.FAST).map { Parallelism.presetThreads(it, 4) })
        assertEquals(listOf(1, 1, 1), listOf(ThreadPreset.QUIET, ThreadPreset.BALANCED, ThreadPreset.FAST).map { Parallelism.presetThreads(it, 2) })
        // Never above the maximum, however many cores there are.
        assertEquals(8, Parallelism.presetThreads(ThreadPreset.FAST, 32))
        assertEquals(8, Parallelism.presetThreads(ThreadPreset.QUIET, 64))
    }

    @Test
    fun `applying a preset sets the thread count and the priority`() {
        val start = AppSettings(parallelFiles = 5, lowPriorityThreads = false)
        val quiet = Parallelism.applyPreset(start, ThreadPreset.QUIET, 8)
        assertEquals(2, quiet.parallelFiles); assertEquals(true, quiet.lowPriorityThreads)
        val balanced = Parallelism.applyPreset(start, ThreadPreset.BALANCED, 8)
        assertEquals(AppSettings.AUTO, balanced.parallelFiles); assertEquals(true, balanced.lowPriorityThreads)
        val fast = Parallelism.applyPreset(start, ThreadPreset.FAST, 8)
        assertEquals(7, fast.parallelFiles); assertEquals(false, fast.lowPriorityThreads)
    }

    @Test
    fun `applying leaves the other settings alone`() {
        val start = AppSettings(backgroundScan = false, thermalProtection = false, historyLimit = 100)
        val result = Parallelism.applyPreset(start, ThreadPreset.FAST, 8)
        assertEquals(false, result.backgroundScan); assertEquals(false, result.thermalProtection); assertEquals(100, result.historyLimit)
    }

    @Test
    fun `the current preset is recognised after applying it`() {
        for (cores in listOf(4, 6, 8, 12)) {
            for (preset in ThreadPreset.entries) {
                val applied = Parallelism.applyPreset(AppSettings(), preset, cores)
                assertEquals("$preset on $cores cores", preset, Parallelism.currentPreset(applied, cores))
            }
        }
    }

    @Test
    fun `settings tuned by hand match no preset`() {
        assertNull(Parallelism.currentPreset(AppSettings(parallelFiles = 6, lowPriorityThreads = true), 8))
        assertNull(Parallelism.currentPreset(AppSettings(parallelFiles = 3, lowPriorityThreads = false), 8))
    }

    @Test
    fun `a default install is on Balanced`() {
        assertEquals(ThreadPreset.BALANCED, Parallelism.currentPreset(AppSettings(), 8))
    }
}

class ScanSummaryTest {
    private fun item(verdict: Verdict?, state: ItemState = ItemState.DONE) = QueueItem(
        1, "u", "n", 1, state = state, completedSeq = 1,
        report = verdict?.let {
            com.spectroflac.analysis.AnalysisReport(
                fileName = "n", uri = "u", container = com.spectroflac.flac.ContainerKind.FLAC, verdict = it, confidence = 1,
                headline = "", summary = "", findings = emptyList(), technical = null, encoder = null, spectral = null,
                integrity = null, dynamics = null, tags = emptyMap(), coverBytes = null, spectrogram = null,
                analysedAtMillis = 0, analysisDurationMillis = 0,
            )
        },
    )

    @Test
    fun `counts each outcome and writes the text`() {
        val items = List(118) { item(Verdict.AUTHENTIC) } + List(3) { item(Verdict.FAKE) } + item(Verdict.NOT_FLAC) +
            item(Verdict.CORRUPT) + List(2) { item(null, ItemState.FAILED) } + List(2) { item(Verdict.SUSPICIOUS) }
        val summary = ScanSummary.from(items)
        assertEquals("118 genuine, 2 suspicious, 4 not genuine, 1 damaged, 2 errors", summary.text())
        assertEquals(127, summary.total)
        assertEquals("Scan complete: 127 files", summary.title)
    }

    @Test
    fun `zero parts are left out and one file is singular`() {
        val summary = ScanSummary.from(listOf(item(Verdict.AUTHENTIC)))
        assertEquals("1 genuine", summary.text())
        assertEquals("Scan complete: 1 file", summary.title)
        assertEquals("1 error", ScanSummary(errors = 1).text())
    }

    @Test
    fun `unfinished files are not counted`() {
        val summary = ScanSummary.from(listOf(item(Verdict.AUTHENTIC), item(null, ItemState.PENDING), item(null, ItemState.RUNNING)))
        assertEquals(1, summary.total)
    }

    @Test
    fun `an empty scan says so`() {
        assertEquals("No file was analysed", ScanSummary().text())
    }

    @Test
    fun `a skipped file counts with its earlier verdict`() {
        assertEquals("1 not genuine", ScanSummary.from(listOf(item(Verdict.FAKE, ItemState.SKIPPED))).text())
    }
}
