package com.spectroflac.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.SpectrogramFull
import com.spectroflac.analysis.SpectrogramLayer
import com.spectroflac.analysis.Verdict
import com.spectroflac.flac.ContainerKind
import com.spectroflac.ui.screens.SPECTROGRAM_CANVAS_TAG
import com.spectroflac.ui.screens.SpectrogramScreen
import com.spectroflac.ui.theme.SpectroFlacTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the interactive spectrogram with real touch gestures and reads its zoom state back. */
@RunWith(AndroidJUnit4::class)
class SpectrogramScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val sampleRate = 44_100
    private val columns = 300
    private val bands = 1024
    private val stride = 2048L

    private fun report(): AnalysisReport {
        val data = ByteArray(columns * bands) { ((it * 31) and 0xFF).toByte() }
        val full = SpectrogramFull(
            sampleRate = sampleRate,
            columns = columns,
            bands = bands,
            strideSamples = stride,
            totalSamples = columns * stride,
            layers = listOf(SpectrogramLayer("Mid", data), SpectrogramLayer("Side", data)),
        )
        return AnalysisReport(
            fileName = "synthetic.flac", uri = "content://synthetic", container = ContainerKind.FLAC,
            verdict = Verdict.AUTHENTIC, confidence = 90, headline = "", summary = "", findings = emptyList(),
            technical = null, encoder = null, spectral = null, integrity = null, dynamics = null,
            tags = emptyMap(), coverBytes = null, spectrogram = null, analysedAtMillis = 0, analysisDurationMillis = 0,
            spectrogramFull = full,
        )
    }

    private fun start() {
        rule.setContent { SpectroFlacTheme { SpectrogramScreen(report(), onBack = {}) } }
    }

    private val canvas: SemanticsNodeInteraction
        get() = rule.onNodeWithTag(SPECTROGRAM_CANVAS_TAG)

    /** "time A to B s, frequency C to D Hz" parsed back into numbers. */
    private fun window(): DoubleArray {
        val text = canvas.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        return Regex("-?\\d+(\\.\\d+)?").findAll(text).map { it.value.toDouble() }.toList().toDoubleArray()
    }

    @Test
    fun startsFullyZoomedOut() {
        start()
        val (t0, t1, f0, f1) = window()
        assertEquals(0.0, t0, 0.01)
        assertEquals(columns * stride.toDouble() / sampleRate, t1, 0.1)
        assertEquals(0.0, f0, 0.5)
        assertEquals(sampleRate / 2.0, f1, 1.0)
    }

    @Test
    fun horizontalPinchZoomsTimeOnly() {
        start()
        val before = window()
        canvas.performTouchInput {
            val y = height * 0.4f
            pinch(
                Offset(width * 0.40f, y), Offset(width * 0.30f, y),
                Offset(width * 0.60f, y), Offset(width * 0.75f, y),
                durationMillis = 400,
            )
        }
        rule.waitForIdle()
        val after = window()
        assertTrue("time span should shrink: ${before.toList()} -> ${after.toList()}", after[1] - after[0] < (before[1] - before[0]) * 0.8)
        assertEquals("frequency span must not change", before[3] - before[2], after[3] - after[2], 1.0)
    }

    @Test
    fun verticalPinchZoomsFrequencyOnly() {
        start()
        val before = window()
        canvas.performTouchInput {
            val x = width * 0.6f
            pinch(
                Offset(x, height * 0.45f), Offset(x, height * 0.30f),
                Offset(x, height * 0.60f), Offset(x, height * 0.75f),
                durationMillis = 400,
            )
        }
        rule.waitForIdle()
        val after = window()
        assertTrue("frequency span should shrink: ${before.toList()} -> ${after.toList()}", after[3] - after[2] < (before[3] - before[2]) * 0.8)
        assertEquals("time span must not change", before[1] - before[0], after[1] - after[0], 0.15)
    }

    @Test
    fun diagonalPinchZoomsBothAxes() {
        start()
        val before = window()
        canvas.performTouchInput {
            val c = Offset(width * 0.55f, height * 0.45f)
            pinch(
                c + Offset(-60f, -60f), c + Offset(-200f, -200f),
                c + Offset(60f, 60f), c + Offset(200f, 200f),
                durationMillis = 400,
            )
        }
        rule.waitForIdle()
        val after = window()
        assertTrue(after[1] - after[0] < (before[1] - before[0]) * 0.8)
        assertTrue(after[3] - after[2] < (before[3] - before[2]) * 0.8)
    }

    @Test
    fun dragPansTheZoomedWindow() {
        start()
        canvas.performTouchInput { doubleClick(center) }   // zoom in 4x
        rule.waitForIdle()
        val zoomed = window()
        assertTrue("double tap should zoom", zoomed[1] - zoomed[0] < (columns * stride.toDouble() / sampleRate) * 0.5)
        canvas.performTouchInput { swipe(Offset(width * 0.8f, height * 0.5f), Offset(width * 0.3f, height * 0.5f), 300) }
        rule.waitForIdle()
        val panned = window()
        assertNotEquals("window should move", zoomed[0], panned[0], 0.01)
        // The state text is rounded to 0.1 s, so two readings of the same span can differ by that much.
        assertEquals("span preserved while panning", zoomed[1] - zoomed[0], panned[1] - panned[0], 0.15)
    }

    @Test
    fun doubleTapAgainResetsTheView() {
        start()
        canvas.performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        canvas.performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        val (t0, _, f0, f1) = window()
        assertEquals(0.0, t0, 0.01)
        assertEquals(0.0, f0, 0.5)
        assertEquals(sampleRate / 2.0, f1, 1.0)
    }

    @Test
    fun tapPlacesTheCursorAndShowsItsValues() {
        start()
        canvas.performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Frequency").assertExists()
        rule.onNodeWithText("Level").assertExists()
    }

    @Test
    fun channelChipsSwitchLayers() {
        start()
        rule.onNodeWithText("Side").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Mid").assertExists()
    }
}
