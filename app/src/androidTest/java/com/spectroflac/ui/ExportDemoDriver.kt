package com.spectroflac.ui

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spectroflac.SpectroFlacApp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: opens the real app on the result of `files/e2e/tagged_lossy.flac` with the export dialog
 * showing, so it can be inspected (screenshots). Skipped unless `-e demo true`.
 */
@RunWith(AndroidJUnit4::class)
class ExportDemoDriver {
    @Test
    fun openTheExportDialog() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("demo") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as SpectroFlacApp
        val file = File(context.filesDir, "e2e/tagged_lossy.flac")
        assumeTrue(file.exists())
        runBlocking { app.settingsRepository.update { it.copy(animatedBackdrop = false) } }
        val report = runBlocking { app.analyzer.analyze(Uri.fromFile(file)) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                vm.navigate(Screen.Result(report))
                if (args.getString("dialog") != "false") vm.openExportDialog(report)
            }
            Thread.sleep((args.getString("holdMs") ?: "60000").toLong())
        }
    }
}
