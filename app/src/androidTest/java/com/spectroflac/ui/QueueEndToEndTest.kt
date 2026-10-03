package com.spectroflac.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spectroflac.SpectroFlacApp
import com.spectroflac.analysis.Verdict
import com.spectroflac.queue.FileInfo
import com.spectroflac.queue.ItemState
import com.spectroflac.queue.NewFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real thing: the app's own queue, analyser, settings and history database, run over real FLAC
 * files on the device. The files are pushed to `files/e2e` before the run (see `scripts/e2e-queue.sh`);
 * the tests are skipped when they are not there. `real_*` must come out genuine, `fake_*` must not.
 */
@RunWith(AndroidJUnit4::class)
class QueueEndToEndTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as SpectroFlacApp
    private val dir = File(context.filesDir, "e2e")

    private fun files(): List<NewFile> =
        dir.listFiles { f -> f.extension == "flac" }.orEmpty().sortedBy { it.name }
            .map { FileInfo.resolve(context, Uri.fromFile(it)) }

    @Before
    fun setUp() {
        assumeTrue("no files in ${dir.path}", files().size >= 6)
        app.queue.cancelAll()
        app.queue.clearFinished()
        runBlocking { app.history.clear() }
    }

    @After
    fun tearDown() {
        app.queue.cancelAll()
        app.queue.clearFinished()
        runBlocking { app.settingsRepository.reset() }
    }

    private fun setParallel(n: Int) {
        runBlocking { app.settingsRepository.update { it.copy(parallelFiles = n, thermalProtection = false) } }
        waitFor("parallelism $n applied") { app.queue.snapshot.value.parallelism == n }
    }

    private fun waitFor(what: String, timeoutMs: Long = 180_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for $what; " + app.queue.snapshot.value.items.joinToString { "${it.name}:${it.state}" })
            }
            Thread.sleep(25)
        }
    }

    /** Samples the queue while it works, to see what a user would see. */
    private class Observer(private val app: SpectroFlacApp) {
        @Volatile var maxRunning = 0
        @Volatile var sawEta = false
        @Volatile var sawPartialProgress = false
        @Volatile var sawGlobalPartial = false
        @Volatile var running = true
        val thread = Thread {
            while (running) {
                val q = app.queue
                maxRunning = maxOf(maxRunning, q.snapshot.value.running.size)
                if (q.stats.value.etaMillis != null) sawEta = true
                if (q.progress.value.values.any { it > 0.05f && it < 0.95f }) sawPartialProgress = true
                if (q.stats.value.fraction in 0.05f..0.95f) sawGlobalPartial = true
                Thread.sleep(20)
            }
        }.apply { isDaemon = true; start() }
        fun stop() { running = false; thread.join(1000) }
    }

    @Test
    fun scansAFolderInParallelAndEveryVerdictIsRight() {
        val all = files()
        setParallel(3)
        val observer = Observer(app)
        app.queue.enqueue(all)
        waitFor("all files finished") { !app.queue.snapshot.value.isActive }
        observer.stop()

        val items = app.queue.snapshot.value.items
        assertEquals(all.size, items.size)
        assertTrue("every file done: " + items.joinToString { "${it.name}:${it.state}:${it.error}" }, items.all { it.state == ItemState.DONE })
        items.forEach { item ->
            val verdict = item.report!!.verdict
            if (item.name.startsWith("real_")) assertEquals(item.name, Verdict.AUTHENTIC, verdict)
            if (item.name.startsWith("fake_")) assertEquals(item.name, Verdict.FAKE, verdict)
            assertTrue("heavy data must not be kept", item.report!!.spectrogramFull == null && item.report!!.coverBytes == null)
        }
        assertTrue("files really ran in parallel, max was ${observer.maxRunning}", observer.maxRunning >= 2)
        assertTrue("never more than 3 at once, max was ${observer.maxRunning}", observer.maxRunning <= 3)
        assertTrue("per-file progress was visible", observer.sawPartialProgress)
        assertTrue("overall progress was visible", observer.sawGlobalPartial)
        assertEquals(1f, app.queue.stats.value.fraction, 0f)
        // Finished files reach the history as they complete.
        waitFor("history written") { runBlocking { app.history.all().size } == all.size }
    }

    @Suppress("DEPRECATION")
    private fun scanServiceRunning(): Boolean {
        val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(100).any { it.service.className.endsWith("ScanService") }
    }

    @Test
    fun theBackgroundServiceRunsDuringAScanAndStopsByItselfAfterwards() {
        setParallel(2)
        app.queue.enqueue(files())
        waitFor("the foreground service starts", timeoutMs = 10_000) { scanServiceRunning() }
        waitFor("scan done") { !app.queue.snapshot.value.isActive }
        // It must not linger: a leftover service keeps a wake lock and a stale notification.
        waitFor("the service stops by itself", timeoutMs = 8_000) { !scanServiceRunning() }
        // And a second scan starts a fresh one.
        app.queue.clearFinished()
        app.queue.enqueue(files())
        waitFor("the service starts again", timeoutMs = 10_000) { scanServiceRunning() }
        waitFor("second scan done") { !app.queue.snapshot.value.isActive }
        waitFor("and stops again", timeoutMs = 8_000) { !scanServiceRunning() }
    }

    @Test
    fun oneThreadNeverRunsTwoFilesAtOnce() {
        setParallel(1)
        val observer = Observer(app)
        app.queue.enqueue(files().take(6))
        waitFor("done") { !app.queue.snapshot.value.isActive }
        observer.stop()
        assertEquals(1, observer.maxRunning)
    }

    @Test
    fun pauseHoldsNewFilesAndResumeFinishesTheScan() {
        setParallel(2)
        app.queue.enqueue(files())
        waitFor("a file running") { app.queue.snapshot.value.running.isNotEmpty() }
        app.queue.pause()
        waitFor("running files drained") { app.queue.snapshot.value.running.isEmpty() }
        val doneWhilePaused = app.queue.snapshot.value.finished.size
        Thread.sleep(1500)
        assertEquals("nothing new starts while paused", doneWhilePaused, app.queue.snapshot.value.finished.size)
        assertTrue(app.queue.snapshot.value.pending.isNotEmpty())
        app.queue.resume()
        waitFor("rest done") { !app.queue.snapshot.value.isActive }
        assertEquals(files().size, app.queue.snapshot.value.finished.size)
    }

    @Test
    fun cancellingStopsARunningAnalysisAndTheRestCarriesOn() {
        setParallel(1)
        app.queue.enqueue(files())
        waitFor("first file running") { app.queue.snapshot.value.running.size == 1 }
        val victim = app.queue.snapshot.value.running.single()
        app.queue.cancel(victim.id)
        waitFor("done") { !app.queue.snapshot.value.isActive }
        val names = app.queue.snapshot.value.items.map { it.name }
        assertTrue("${victim.name} was removed", victim.name !in names)
        assertEquals(files().size - 1, names.size)
    }

    @Test
    fun theUnfinishedQueueIsSavedWhileAScanRunsWhenRestoreIsOn() {
        val saved = File(context.filesDir, "queue.json")
        saved.delete()
        runBlocking { app.settingsRepository.update { it.copy(restoreQueueAfterRestart = true, parallelFiles = 1, thermalProtection = false) } }
        waitFor("parallelism 1") { app.queue.snapshot.value.parallelism == 1 }
        val all = files()
        app.queue.enqueue(all + all.map { it.copy(uri = it.uri + "?copy=1") }) // enough to keep it busy
        waitFor("a file finishes while others wait") { app.queue.snapshot.value.finished.isNotEmpty() && app.queue.snapshot.value.pending.size > 3 }
        waitFor("the queue was saved", timeoutMs = 10_000) { saved.exists() && saved.readText().contains("e2e") }
        val stored = org.json.JSONArray(saved.readText())
        val pendingNow = app.queue.snapshot.value.items.count { !it.isFinished }
        assertTrue("saved ${stored.length()} files, ${pendingNow} unfinished now", stored.length() in 1..(all.size * 2) && stored.length() >= pendingNow - 2)
        app.queue.cancelAll()
        waitFor("an emptied queue clears the saved file", timeoutMs = 10_000) { !saved.exists() }
    }

    @Test
    fun nothingIsSavedWhenRestoreIsOff() {
        val saved = File(context.filesDir, "queue.json")
        saved.delete()
        runBlocking { app.settingsRepository.update { it.copy(restoreQueueAfterRestart = false, parallelFiles = 1, thermalProtection = false) } }
        app.queue.enqueue(files())
        waitFor("running") { app.queue.snapshot.value.running.isNotEmpty() }
        Thread.sleep(2_500)
        assertTrue("no saved queue", !saved.exists())
        app.queue.cancelAll()
    }

    @Test
    fun scanningInParallelIsReallyFasterThanOneAtATime() {
        assumeTrue("needs at least 3 cores", Runtime.getRuntime().availableProcessors() >= 3)
        val all = files()
        fun timed(threads: Int): Long {
            setParallel(threads)
            app.queue.clearFinished()
            val start = System.nanoTime()
            app.queue.enqueue(all)
            waitFor("scan with $threads thread(s)") { !app.queue.snapshot.value.isActive }
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue(app.queue.snapshot.value.items.all { it.state == ItemState.DONE })
            return ms
        }
        timed(2) // warm-up: JIT, file cache
        val one = timed(1)
        val four = timed(4)
        val speedup = one.toDouble() / four
        android.util.Log.i("E2E", "12 files: 1 thread ${one} ms, 4 threads ${four} ms, speed-up x%.2f".format(speedup))
        assertTrue("4 threads should be clearly faster than 1 (was x%.2f)".format(speedup), speedup > 1.4)
    }

    @Test
    fun aRescanSkipsFilesThatAreUnchangedWhenSkippingIsOn() {
        setParallel(3)
        val all = files()
        app.queue.enqueue(all)
        waitFor("first scan") { !app.queue.snapshot.value.isActive }
        waitFor("history written") { runBlocking { app.history.all().size } == all.size }

        runBlocking { app.settingsRepository.update { it.copy(skipKnownFiles = true) } }
        waitFor("skip enabled") { app.queue.skipKnown }
        app.queue.clearFinished()
        app.queue.enqueue(all)
        waitFor("second scan") { !app.queue.snapshot.value.isActive }
        val items = app.queue.snapshot.value.items
        assertTrue("all skipped: " + items.joinToString { "${it.name}:${it.state}" }, items.all { it.state == ItemState.SKIPPED })
        items.forEach { assertTrue("a skipped file still has its verdict", it.report != null) }

        // Touching a file makes it new again.
        val touched = File(dir, all.first().name)
        touched.setLastModified(touched.lastModified() + 60_000)
        app.queue.clearFinished()
        app.queue.enqueue(files())
        waitFor("third scan") { !app.queue.snapshot.value.isActive }
        val states = app.queue.snapshot.value.items.associate { it.name to it.state }
        assertEquals(ItemState.DONE, states[all.first().name])
        assertEquals(all.size - 1, states.values.count { it == ItemState.SKIPPED })
    }
}
