package com.spectroflac.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.Verdict
import com.spectroflac.flac.ContainerKind
import com.spectroflac.queue.ItemState
import com.spectroflac.queue.QueueItem
import com.spectroflac.queue.QueueSnapshot
import com.spectroflac.queue.QueueStats
import com.spectroflac.settings.QueueFilter
import com.spectroflac.settings.QueueSort
import com.spectroflac.ui.screens.QueueScreen
import com.spectroflac.ui.screens.QueueTags
import com.spectroflac.ui.theme.SpectroFlacTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The queue screen driven with a fixed snapshot: what is shown, and what each control reports. */
@RunWith(AndroidJUnit4::class)
class QueueScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun report(name: String, verdict: Verdict) = AnalysisReport(
        fileName = name, uri = "u/$name", container = ContainerKind.FLAC, verdict = verdict, confidence = 90,
        headline = "16 BIT  44.1KHZ  900 KBPS FLAC", summary = "", findings = emptyList(), technical = null,
        encoder = null, spectral = null, integrity = null, dynamics = null, tags = emptyMap(), coverBytes = null,
        spectrogram = null, analysedAtMillis = 0, analysisDurationMillis = 0,
    )

    private fun item(id: Long, name: String, state: ItemState, verdict: Verdict? = null, seq: Int = 0, error: String? = null) =
        QueueItem(id, "u/$name", name, 20_000_000, state = state, report = verdict?.let { report(name, it) }, error = error, completedSeq = seq)

    private val items = listOf(
        item(1, "alpha.flac", ItemState.RUNNING),
        item(2, "bravo.flac", ItemState.RUNNING),
        item(3, "charlie.flac", ItemState.PENDING),
        item(4, "delta.flac", ItemState.PENDING),
        item(5, "echo.flac", ItemState.DONE, Verdict.AUTHENTIC, 1),
        item(6, "foxtrot.flac", ItemState.DONE, Verdict.FAKE, 2),
        item(7, "golf.flac", ItemState.FAILED, error = "Malformed FLAC stream", seq = 3),
    )

    private class Calls {
        var pause = 0; var resume = 0; var cancelAll = 0
        val cancel = mutableListOf<Long>(); val top = mutableListOf<Long>(); val retry = mutableListOf<Long>()
        var retryFailed = 0; var clear = 0
        var filter: QueueFilter? = null
        val opened = mutableListOf<String>()
    }

    private fun show(
        snapshot: QueueSnapshot = QueueSnapshot(items, parallelism = 4),
        stats: QueueStats = QueueStats.compute(snapshot.items, mapOf(1L to 0.40f, 2L to 0.75f)).copy(etaMillis = 125_000, bytesPerSecond = 12_500_000.0),
        listing: Boolean = false,
        calls: Calls = Calls(),
        filter: QueueFilter = QueueFilter.ALL,
    ): Calls {
        val progress = mutableStateOf(mapOf(1L to 0.40f, 2L to 0.75f))
        rule.setContent {
            SpectroFlacTheme {
                QueueScreen(
                    snapshot = snapshot, progress = progress, stats = stats, filter = filter, sort = QueueSort.PROCESSING_ORDER,
                    listing = listing, onBack = {}, onSettings = {}, onPause = { calls.pause++ }, onResume = { calls.resume++ },
                    onCancelAll = { calls.cancelAll++ }, onCancel = { calls.cancel += it }, onMoveToTop = { calls.top += it },
                    onRetry = { calls.retry += it }, onRetryFailed = { calls.retryFailed++ }, onClearFinished = { calls.clear++ },
                    onOpen = { calls.opened += it.name }, onExportCsv = {}, onExportJson = {}, onFilter = { calls.filter = it }, onSort = {},
                )
            }
        }
        return calls
    }

    /** The list is lazy: rows below the fold only exist once scrolled to. */
    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        rule.onNode(hasScrollAction()).performScrollToNode(matcher)
        return rule.onNode(matcher)
    }

    private fun stateText(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test
    fun showsEachRunningFileWithItsOwnProgress() {
        show()
        assertEquals("40 percent", stateText(QueueTags.running("alpha.flac")))
        assertEquals("75 percent", stateText(QueueTags.running("bravo.flac")))
        rule.onNodeWithTag(QueueTags.waiting("charlie.flac")).assertExists()
        rule.onNodeWithTag(QueueTags.waiting("delta.flac")).assertExists()
    }

    @Test
    fun showsOverallProgressAndTimeLeft() {
        show()
        rule.onNodeWithTag(QueueTags.ETA).assertTextContains("about 2 min 05 s left")
        // Seven 20 MB files: 3 finished (2 done, 1 failed) + 0.40 + 0.75 of the two running = 4.15 of 7 files.
        assertEquals("59 percent", stateText(QueueTags.GLOBAL_PROGRESS))
        rule.onNodeWithText("3 / 7").assertExists()
        rule.onNodeWithText("4 at a time", substring = true).assertExists()
    }

    @Test
    fun saysEstimatingBeforeThereIsEnoughData() {
        show(stats = QueueStats.compute(items, emptyMap()))
        rule.onNodeWithTag(QueueTags.ETA).assertTextContains("Estimating time left…")
    }

    @Test
    fun pauseAndCancelAllReportTheirClicks() {
        val calls = show()
        rule.onNodeWithTag(QueueTags.PAUSE_RESUME).performClick()
        rule.onNodeWithTag(QueueTags.CANCEL_ALL).performClick()
        assertEquals(1, calls.pause); assertEquals(1, calls.cancelAll); assertEquals(0, calls.resume)
    }

    @Test
    fun aPausedQueueOffersResumeAndSaysSo() {
        val calls = show(snapshot = QueueSnapshot(items, paused = true, parallelism = 4))
        rule.onNodeWithTag(QueueTags.ETA).assertTextContains("Paused")
        rule.onNodeWithTag(QueueTags.PAUSE_RESUME).assertTextContains("Resume")
        rule.onNodeWithTag(QueueTags.PAUSE_RESUME).performClick()
        assertEquals(1, calls.resume)
    }

    @Test
    fun aHotPhoneExplainsWhyNothingIsMoving() {
        show(snapshot = QueueSnapshot(items, holdReason = "Phone is too hot", parallelism = 0))
        rule.onNodeWithTag(QueueTags.ETA).assertTextContains("Phone is too hot", substring = true)
    }

    @Test
    fun stoppingARunningFileCancelsThatFile() {
        val calls = show()
        rule.onNodeWithContentDescription("Stop analysing alpha.flac").performClick()
        assertEquals(listOf(1L), calls.cancel)
    }

    @Test
    fun aWaitingFileCanBeMovedToTheTopOrRemoved() {
        val calls = show()
        rule.onNodeWithContentDescription("Options for delta.flac").performClick()
        rule.onNodeWithText("Analyse next").performClick()
        rule.onNodeWithContentDescription("Options for charlie.flac").performClick()
        rule.onNodeWithText("Remove from queue").performClick()
        assertEquals(listOf(4L), calls.top)
        assertEquals(listOf(3L), calls.cancel)
    }

    @Test
    fun finishedFilesOpenAndFailedOnesCanBeRetried() {
        val calls = show()
        rule.onNodeWithText("Retry 1 failed").performClick()
        node(hasTestTag(QueueTags.finished("echo.flac"))).performClick()
        node(hasText("RETRY")).performClick()
        assertEquals(listOf("echo.flac"), calls.opened)
        assertEquals(listOf(7L), calls.retry)
        assertEquals(1, calls.retryFailed)
    }

    @Test
    fun aFailedFileShowsItsErrorAndIsNotOpenable() {
        val calls = show()
        node(hasText("Malformed FLAC stream")).assertExists()
        node(hasTestTag(QueueTags.finished("golf.flac"))).performClick()
        assertTrue(calls.opened.isEmpty())
    }

    @Test
    fun theFilterHidesOtherVerdicts() {
        show(filter = QueueFilter.FAKE)
        node(hasTestTag(QueueTags.finished("foxtrot.flac"))).assertExists()
        rule.onNodeWithTag(QueueTags.finished("echo.flac")).assertDoesNotExist()
        rule.onNodeWithTag(QueueTags.finished("golf.flac")).assertDoesNotExist()
    }

    @Test
    fun filterChipsReportTheChoice() {
        val calls = show()
        node(hasText("Errors")).performClick()
        assertEquals(QueueFilter.ERRORS, calls.filter)
    }

    @Test
    fun anEmptyQueueSaysWhatToDo() {
        show(snapshot = QueueSnapshot(), stats = QueueStats.EMPTY)
        rule.onNodeWithText("The queue is empty", substring = true).assertExists()
    }

    @Test
    fun listingAFolderIsShown() {
        show(snapshot = QueueSnapshot(items.take(3)), stats = QueueStats.compute(items.take(3), emptyMap()), listing = true)
        rule.onNodeWithText("Looking for .flac files", substring = true).assertExists()
    }
}
