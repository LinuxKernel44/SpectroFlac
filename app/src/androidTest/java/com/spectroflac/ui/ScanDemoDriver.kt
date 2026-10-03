package com.spectroflac.ui

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spectroflac.SpectroFlacApp
import com.spectroflac.queue.FileInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: a driver that opens the real app on the queue screen with a long scan running, so the
* screen can be inspected (screenshots, `dumpsys notification`). Skipped unless `-e demo true` is given:
 *
 *   adb shell am instrument -w -e class com.spectroflac.ui.ScanDemoDriver -e demo true \
 *       -e holdMs 45000 -e threads 2 com.spectroflac.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ScanDemoDriver {

    @Test
    fun openTheQueueWithALongScan() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("demo") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as SpectroFlacApp
        val source = File(context.filesDir, "e2e").listFiles { f -> f.extension == "flac" }.orEmpty().sortedBy { it.name }
        assumeTrue("no e2e files", source.isNotEmpty())

        // Many distinct copies, so the scan lasts long enough to look at.
        val copies = File(context.filesDir, "demo").apply { mkdirs() }
        val repeat = (args.getString("repeat") ?: "6").toInt()
        val files = (1..repeat).flatMap { round ->
            source.map { f -> File(copies, "r${round}_${f.name}").also { if (!it.exists()) f.copyTo(it) } }
        }

        val threads = (args.getString("threads") ?: "2").toInt()
        runBlocking { app.settingsRepository.update { it.copy(parallelFiles = threads, thermalProtection = false, animatedBackdrop = false, restoreQueueAfterRestart = args.getString("restore") == "true") } }
        app.queue.cancelAll(); app.queue.clearFinished()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                val added = app.queue.enqueue(files.map { FileInfo.resolve(context, Uri.fromFile(it)) })
                vm.openQueue()
                android.util.Log.i("DEMO", "enqueued $added of ${files.size}, screen=${vm.screen}, items=${app.queue.snapshot.value.items.size}")
            }
            android.util.Log.i("DEMO", "holding; items=${app.queue.snapshot.value.items.size} active=${app.queue.isActive.value}")
            Thread.sleep((args.getString("holdMs") ?: "45000").toLong())
        }
    }
}
