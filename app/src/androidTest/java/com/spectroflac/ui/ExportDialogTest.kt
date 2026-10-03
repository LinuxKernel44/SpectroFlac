package com.spectroflac.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.SpectralInfo
import com.spectroflac.analysis.TechnicalInfo
import com.spectroflac.analysis.Verdict
import com.spectroflac.export.image.ExportPlanner
import com.spectroflac.export.image.ImageLayout
import com.spectroflac.export.image.PlotSize
import com.spectroflac.flac.ContainerKind
import com.spectroflac.ui.components.ExportChoice
import com.spectroflac.ui.components.ExportDialog
import com.spectroflac.ui.components.ExportDialogTags
import com.spectroflac.ui.theme.SpectroFlacTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val fourMinutes = 44_100L * 240

    private fun report(channels: Int = 2, brickWall: Boolean = true) = AnalysisReport(
        fileName = "track.flac", uri = "content://x/track.flac", container = ContainerKind.FLAC, verdict = Verdict.FAKE, confidence = 97,
        headline = "", summary = "", findings = emptyList(),
        technical = TechnicalInfo(16, 44_100, channels, "Stereo", 240.0, fourMinutes, 30_000_000, 1000, 1411, 0.7, 4096, 4096, 0, 0, true),
        encoder = null,
        spectral = SpectralInfo(16_760.0, 22_050.0, 0.76, 60.0, brickWall, -120.0, -30.0, null, 400, 4096, emptyList()),
        integrity = null, dynamics = null, tags = emptyMap(), coverBytes = null, spectrogram = null,
        analysedAtMillis = 0, analysisDurationMillis = 0,
    )

    private var saved: ExportChoice? = null
    private var shared: ExportChoice? = null
    private var dismissed = 0

    private fun show(report: AnalysisReport = report(), free: Long = 100L * 1024 * 1024 * 1024) {
        rule.setContent {
            SpectroFlacTheme {
                ExportDialog(report, { dismissed++ }, { saved = it }, { shared = it }, freeBytes = free)
            }
        }
    }

    private fun text(tag: String) = rule.onNodeWithTag(tag)

    private fun scrollTo(tag: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
    }

    private fun setSize(w: String, h: String) {
        text(ExportDialogTags.WIDTH).performTextClearance(); text(ExportDialogTags.WIDTH).performTextInput(w)
        text(ExportDialogTags.HEIGHT).performTextClearance(); text(ExportDialogTags.HEIGHT).performTextInput(h)
    }

    @Test
    fun opensOn4kAndExplainsTheDetail() {
        show()
        text(ExportDialogTags.WIDTH).assertTextContains("3840")
        text(ExportDialogTags.HEIGHT).assertTextContains("2160")
        // 22050 Hz over 2160 rows = 10.2 Hz per row; FFT 8192 for 2160 rows.
        text(ExportDialogTags.SUMMARY).assertTextContains("FFT 8192", substring = true)
        text(ExportDialogTags.SUMMARY).assertTextContains("10 Hz per row", substring = true)
        text(ExportDialogTags.SIZE_LINE).assertTextContains("PNG", substring = true)
    }

    @Test
    fun presetsFillTheFields() {
        show()
        text(ExportDialogTags.preset("Screen")).performClick()
        text(ExportDialogTags.WIDTH).assertTextContains("1920"); text(ExportDialogTags.HEIGHT).assertTextContains("1080")
        text(ExportDialogTags.preset("8K")).performClick()
        text(ExportDialogTags.WIDTH).assertTextContains("7680"); text(ExportDialogTags.HEIGHT).assertTextContains("4320")
    }

    @Test
    fun maximumPicksTheFinestAnalysisTheFileAllows() {
        show()
        text(ExportDialogTags.preset("Maximum")).performClick()
        text(ExportDialogTags.HEIGHT).assertTextContains("16384")
        text(ExportDialogTags.WIDTH).assertTextContains("41343") // one column every 256 samples
        text(ExportDialogTags.SUMMARY).assertTextContains("FFT 32768", substring = true)
    }

    @Test
    fun maximumShrinksWhenStorageIsTight() {
        show(free = 2L * 1024 * 1024 * 1024)
        text(ExportDialogTags.preset("Maximum")).performClick()
        val expected = ExportPlanner.maximum(fourMinutes, 2L * 1024 * 1024 * 1024) { ImageLayout(it, com.spectroflac.export.image.ExportContent()).totalPixels }
        text(ExportDialogTags.WIDTH).assertTextContains(expected.width.toString())
        text(ExportDialogTags.HEIGHT).assertTextContains(expected.height.toString())
    }

    @Test
    fun anyWidthAndHeightCanBeTyped() {
        show()
        setSize("5000", "3000")
        text(ExportDialogTags.WIDTH).assertTextContains("5000")
        text(ExportDialogTags.SUMMARY).assertTextContains("15.0 MP", substring = true)
        text(ExportDialogTags.SAVE).assertIsEnabled()
    }

    @Test
    fun aSizeThatIsTooSmallIsRefusedAndTheButtonsAreDisabled() {
        show()
        setSize("100", "100")
        text(ExportDialogTags.WARNING).assertTextContains("at least 256", substring = true)
        text(ExportDialogTags.SAVE).assertIsNotEnabled()
        text(ExportDialogTags.SHARE).assertIsNotEnabled()
    }

    @Test
    fun aHeightAboveTheFinestAnalysisIsRefused() {
        show()
        setSize("4000", "40000")
        text(ExportDialogTags.WARNING).assertTextContains("limited to ${ExportPlanner.MAX_HEIGHT}", substring = true)
        text(ExportDialogTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun notEnoughFreeSpaceBlocksTheExport() {
        show(free = 50L * 1024 * 1024)
        text(ExportDialogTags.preset("8K")).performClick()
        text(ExportDialogTags.WARNING).assertTextContains("Not enough free space", substring = true)
        text(ExportDialogTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun veryLargeImagesCarryAWarningButStillWork() {
        show()
        setSize("30000", "10000")
        text(ExportDialogTags.WARNING).assertTextContains("Very large image", substring = true)
        text(ExportDialogTags.SAVE).assertIsEnabled()
    }

    @Test
    fun saveReportsTheWholeChoice() {
        show()
        setSize("4000", "2500")
        scrollTo(ExportDialogTags.channel("Left")); text(ExportDialogTags.channel("Left")).performClick()
        scrollTo(ExportDialogTags.content("Legend")); text(ExportDialogTags.content("Legend")).performClick()
        text(ExportDialogTags.content("Axes")).performClick()
        scrollTo(ExportDialogTags.SAVE); text(ExportDialogTags.SAVE).performClick()
        val choice = saved
        assertNotNull(choice)
        assertEquals(PlotSize(4000, 2500), choice!!.plot)
        assertEquals("Left", choice.channel.label)
        assertFalse(choice.content.legend); assertFalse(choice.content.axes)
        assertTrue(choice.content.header && choice.content.trackInfo && choice.content.cutoff)
        assertNull(shared)
    }

    @Test
    fun shareReportsTheChoiceAndCancelDismisses() {
        show()
        scrollTo(ExportDialogTags.SHARE); text(ExportDialogTags.SHARE).performClick()
        assertEquals(PlotSize(3840, 2160), shared!!.plot)
        scrollTo(ExportDialogTags.CANCEL); text(ExportDialogTags.CANCEL).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun aMonoFileHasNoChannelChoice() {
        show(report(channels = 1))
        rule.onNodeWithTag(ExportDialogTags.channel("Mono")).assertDoesNotExist()
        rule.onNodeWithTag(ExportDialogTags.channel("Left")).assertDoesNotExist()
    }

    @Test
    fun stereoOffersMidLeftRightAndSide() {
        show()
        for (name in listOf("Mid", "Left", "Right", "Side")) { scrollTo(ExportDialogTags.channel(name)); text(ExportDialogTags.channel(name)).assertExists() }
    }

    @Test
    fun withoutAMeasuredCutoffTheLineCannotBeSwitchedOn() {
        show(report(brickWall = false))
        scrollTo(ExportDialogTags.content("Cutoff"))
        text(ExportDialogTags.content("Cutoff")).assertTextContains("none found", substring = true)
        text(ExportDialogTags.content("Cutoff")).performClick()
        scrollTo(ExportDialogTags.SAVE); text(ExportDialogTags.SAVE).performClick()
        assertFalse(saved!!.content.cutoff)
    }

    @Test
    fun theImageSizeLineIncludesTheHeaderAndAxes() {
        show()
        text(ExportDialogTags.preset("Screen")).performClick()
        val layout = ImageLayout(PlotSize(1920, 1080), com.spectroflac.export.image.ExportContent())
        text(ExportDialogTags.SIZE_LINE).assertTextContains("${layout.totalWidth} × ${layout.totalHeight} px", substring = true)
    }
}
