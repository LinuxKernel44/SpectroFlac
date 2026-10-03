package com.spectroflac.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spectroflac.settings.AppSettings
import com.spectroflac.settings.AutoExportFormat
import com.spectroflac.ui.screens.SettingsScreen
import com.spectroflac.ui.screens.SettingsTags
import com.spectroflac.ui.theme.SpectroFlacTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var settings by mutableStateOf(AppSettings())
    private var resets = 0
    private var folderPicks = 0

    private fun show(cores: Int = 8, folder: String? = null) {
        rule.setContent {
            SpectroFlacTheme {
                SettingsScreen(
                    settings = settings, cores = cores, exportFolderName = folder,
                    onChange = { transform -> settings = transform(settings) },
                    onPickExportFolder = { folderPicks++ }, onReset = { resets++ }, onBack = {},
                )
            }
        }
    }

    /** The settings list is lazy: rows below the fold only exist once scrolled to. */
    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        rule.onNode(hasScrollAction()).performScrollToNode(matcher)
        return rule.onNode(matcher)
    }

    private fun text(value: String) = node(hasText(value))
    private fun toggleOf(tag: String) = node(isToggleable() and hasAnyAncestor(hasTestTag(tag)))

    @Test
    fun startsOnAutoAndExplainsWhatAutoMeans() {
        show(cores = 8)
        assertEquals(AppSettings.AUTO, settings.parallelFiles)
        rule.onNodeWithText("Auto runs 4 of this phone's 8 cores", substring = true).assertExists()
    }

    @Test
    fun theExplanationFollowsTheCoreCount() {
        show(cores = 4)
        rule.onNodeWithText("Auto runs 2 of this phone's 4 cores", substring = true).assertExists()
    }

    @Test
    fun aFixedNumberOfFilesCanBePickedAndAutoRestored() {
        show()
        rule.onNodeWithTag(SettingsTags.parallelChip("6")).performClick()
        assertEquals(6, settings.parallelFiles)
        rule.onNodeWithTag(SettingsTags.parallelChip("1")).performClick()
        assertEquals(1, settings.parallelFiles)
        rule.onNodeWithTag(SettingsTags.parallelChip("8")).performClick()
        assertEquals(8, settings.parallelFiles)
        rule.onNodeWithTag(SettingsTags.parallelChip("Auto")).performClick()
        assertEquals(AppSettings.AUTO, settings.parallelFiles)
    }

    @Test
    fun switchesStartAtTheirDefaultsAndFlip() {
        show()
        toggleOf(SettingsTags.BACKGROUND).assertIsOn()
        toggleOf(SettingsTags.BACKGROUND).performClick()
        assertFalse(settings.backgroundScan)
        toggleOf(SettingsTags.THERMAL).performClick()
        assertFalse(settings.thermalProtection)
        toggleOf(SettingsTags.GLASS).performClick()
        assertFalse(settings.liquidGlass)
    }

    @Test
    fun theBatteryThresholdAndAutoExportCanBeChosen() {
        show()
        text("20 %").performClick()
        assertEquals(20, settings.pauseBelowBatteryPercent)
        text("CSV + JSON").performClick()
        assertEquals(AutoExportFormat.BOTH, settings.autoExport)
    }

    @Test
    fun autoExportWithoutAFolderSaysWhatIsMissing() {
        settings = settings.copy(autoExport = AutoExportFormat.CSV)
        show(folder = null)
        text("No export folder chosen").assertExists()
        node(hasText("Choose a folder", substring = true)).assertExists()
        text("Choose folder").performClick()
        assertEquals(1, folderPicks)
    }

    @Test
    fun aChosenFolderIsNamed() {
        show(folder = "SpectroFlac exports")
        text("Folder: SpectroFlac exports").assertExists()
    }

    @Test
    fun resetReportsItsClick() {
        show()
        text("Reset all settings").performClick()
        assertEquals(1, resets)
        assertTrue(settings.backgroundScan)
    }
}
