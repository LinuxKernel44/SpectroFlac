package com.spectroflac.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spectroflac.SpectroFlacApp
import com.spectroflac.export.image.ExportChannel
import com.spectroflac.export.image.ExportContent
import com.spectroflac.export.image.ExportPhase
import com.spectroflac.export.image.ExportRequest
import com.spectroflac.export.image.ExportService
import com.spectroflac.export.image.ExportState
import com.spectroflac.export.image.ExportSubject
import com.spectroflac.export.image.ExportTarget
import com.spectroflac.export.image.ImageLayout
import com.spectroflac.export.image.PlotSize
import com.spectroflac.export.image.SpectrogramImageExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Renders real FLAC files with the real exporter on the device and checks the PNG with Android's own
 * decoder: size, layout, where the spectrum shows, channel choice, progress, cancellation and the
 * foreground service. Files come from `files/e2e` (see `scripts/e2e-queue.sh`).
 */
@RunWith(AndroidJUnit4::class)
class SpectrogramExportEndToEndTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as SpectroFlacApp
    private val dir = File(context.filesDir, "e2e")
    private val outDir = File(context.cacheDir, "export-test").apply { mkdirs() }
    private val exporter = SpectrogramImageExporter(context)

    private val real = File(dir, "real_1.flac")
    private val lossy = File(dir, "fake_mp3_128_1.flac")   // LAME 128 kbps: content stops at 16.76 kHz
    private val lossyCutoffHz = 16_764.0

    @Before
    fun setUp() {
        assumeTrue("no e2e files in ${dir.path}", real.exists() && lossy.exists())
    }

    @After
    fun tearDown() {
        outDir.listFiles()?.forEach { it.delete() }
        app.exportJob.cancel()
    }

    private fun subject(file: File, cutoff: Double? = null) = ExportSubject(
        fileName = file.name, title = "Test title", artist = "Test artist", formatLine = null,
        verdictLabel = "FAKE", verdictColor = Color.parseColor("#FB7185"), confidence = 97, cutoffHz = cutoff,
    )

    private fun request(file: File, plot: PlotSize, channel: ExportChannel = ExportChannel(ExportChannel.MIX, "Mid"),
                        content: ExportContent = ExportContent(), cutoff: Double? = null) =
        ExportRequest(Uri.fromFile(file), plot, channel, content, subject(file, cutoff))

    private fun render(req: ExportRequest, name: String, progress: (com.spectroflac.export.image.ExportProgress) -> Unit = {}): Pair<File, com.spectroflac.export.image.ExportResult> {
        val out = File(outDir, "$name.png")
        val result = runBlocking {
            FileOutputStream(out).use { stream ->
                exporter.export(req, req.uri.path!!.let { File(it).length() }, { FileInputStream(File(req.uri.path!!)) }, stream, progress)
            }
        }
        return out to result
    }

    private fun luma(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** Mean brightness of a rectangle, sampling every few pixels. */
    private fun meanLuma(bitmap: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, step: Int = 3): Double {
        var sum = 0L; var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) { sum += luma(bitmap.getPixel(x, y)); n++; x += step }
            y += step
        }
        return sum.toDouble() / n
    }

    private fun bounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun theImageHasExactlyTheRequestedSizePlusItsSurroundings() {
        val plot = PlotSize(1280, 720)
        val (png, result) = render(request(real, plot), "size")
        val layout = ImageLayout(plot, ExportContent())
        val bitmap = BitmapFactory.decodeFile(png.path)
        assertEquals(layout.totalWidth, bitmap.width)
        assertEquals(layout.totalHeight, bitmap.height)
        assertEquals(layout.totalWidth, result.width); assertEquals(layout.totalHeight, result.height)
        assertEquals(png.length(), result.bytes)
        assertFalse(result.partial)
    }

    @Test
    fun aFullBandFileShowsContentUpToTheTop() {
        val plot = PlotSize(1280, 720)
        val (png, _) = render(request(real, plot), "real")
        val layout = ImageLayout(plot, ExportContent())
        val bitmap = BitmapFactory.decodeFile(png.path)
        val top = meanLuma(bitmap, layout.plotLeft + 20, layout.plotTop + 6, layout.plotLeft + plot.width - 20, layout.plotTop + 40)
        val background = luma(ExportBackground)
        assertTrue("the top of a genuine file's spectrogram should not be black, luma $top", top > background + 25)
    }

    @Test
    fun aLossyFilesBrickWallSitsAtItsCutoffRow() {
        val plot = PlotSize(1280, 720)
        val (png, _) = render(request(lossy, plot, cutoff = lossyCutoffHz, content = ExportContent(cutoff = false)), "lossy")
        val layout = ImageLayout(plot, ExportContent(cutoff = false))
        val bitmap = BitmapFactory.decodeFile(png.path)
        val x0 = layout.plotLeft + 20
        val x1 = layout.plotLeft + plot.width - 20
        // Above the cut: dark. Well below it: bright.
        val above = meanLuma(bitmap, x0, layout.plotTop + 8, x1, layout.plotTop + 90)
        val below = meanLuma(bitmap, x0, layout.plotTop + plot.height - 240, x1, layout.plotTop + plot.height - 160)
        assertTrue("above the cut should be dark ($above) and below bright ($below)", below > above + 40)
        // The brightest transition is at the cutoff: find the first bright row from the top.
        var firstBright = -1
        for (row in 8 until plot.height step 2) { // skip the plot border line
            if (meanLuma(bitmap, x0, layout.plotTop + row, x1, layout.plotTop + row + 1, step = 5) > 30) { firstBright = row; break }
        }
        val expected = ((1 - lossyCutoffHz / 22_050.0) * plot.height).toInt()
        assertTrue("brick wall at row $firstBright, expected about $expected", kotlin.math.abs(firstBright - expected) < plot.height * 0.04)
    }

    @Test
    fun theCutoffLineIsDrawnInRedAtTheMeasuredFrequency() {
        val plot = PlotSize(1280, 720)
        val (png, _) = render(request(lossy, plot, cutoff = lossyCutoffHz), "cutline")
        val layout = ImageLayout(plot, ExportContent())
        val bitmap = BitmapFactory.decodeFile(png.path)
        val expectedRow = layout.plotTop + ((1 - lossyCutoffHz / 22_050.0) * plot.height).toInt()
        var redRows = 0
        for (row in expectedRow - 4..expectedRow + 4) {
            var red = 0
            for (x in layout.plotLeft + 400 until layout.plotLeft + 1200 step 2) {
                val c = bitmap.getPixel(x, row)
                if (Color.red(c) > 200 && Color.green(c) < 140 && Color.blue(c) < 160) red++
            }
            if (red > 40) redRows++
        }
        assertTrue("a red dashed line should cross the plot near row $expectedRow", redRows >= 1)
    }

    @Test
    fun theHeaderHoldsTextAndSwitchingItOffRemovesIt() {
        val plot = PlotSize(1280, 720)
        val withHeader = ImageLayout(plot, ExportContent())
        val (png, _) = render(request(real, plot), "header")
        val bitmap = BitmapFactory.decodeFile(png.path)
        var inked = 0; var total = 0
        for (y in 0 until withHeader.headerHeight step 2) for (x in 0 until withHeader.totalWidth step 2) {
            total++; if (bitmap.getPixel(x, y) != ExportBackground) inked++
        }
        assertTrue("the header should hold text (${inked * 100 / total} % of its pixels differ from the background)", inked * 1000 / total > 5)

        val bare = ExportContent(header = false, trackInfo = false)
        val (png2, result2) = render(request(real, plot, content = bare), "noheader")
        assertEquals(ImageLayout(plot, bare).totalHeight, result2.height)
        assertTrue(result2.height < withHeader.totalHeight)
        assertEquals(ImageLayout(plot, bare).totalHeight, bounds(png2).second)
    }

    @Test
    fun everyCombinationOfContentGivesTheLayoutItPromises() {
        val plot = PlotSize(640, 360)
        for (axes in listOf(true, false)) for (legend in listOf(true, false)) for (header in listOf(true, false)) {
            val content = ExportContent(axes = axes, header = header, legend = legend, trackInfo = header, cutoff = false)
            val (png, result) = render(request(real, plot, content = content), "combo-$axes-$legend-$header")
            val layout = ImageLayout(plot, content)
            assertEquals("axes=$axes legend=$legend header=$header", layout.totalWidth to layout.totalHeight, bounds(png))
            assertEquals(layout.totalWidth, result.width)
        }
    }

    @Test
    fun eachChannelIsDrawnFromItsOwnAudio() {
        val plot = PlotSize(960, 540)
        val layout = ImageLayout(plot, ExportContent())
        fun brightness(channel: ExportChannel, name: String): Double {
            val (png, _) = render(request(real, plot, channel), name)
            val b = BitmapFactory.decodeFile(png.path)
            return meanLuma(b, layout.plotLeft + 10, layout.plotTop + 10, layout.plotLeft + plot.width - 10, layout.plotTop + plot.height - 10)
        }
        val mid = brightness(ExportChannel(ExportChannel.MIX, "Mid"), "mid")
        val side = brightness(ExportChannel(ExportChannel.SIDE, "Side"), "side")
        val left = brightness(ExportChannel(0, "Left"), "left")
        val right = brightness(ExportChannel(1, "Right"), "right")
        // The test file's side channel is about 9 dB below its mid channel.
        assertTrue("side ($side) should be darker than mid ($mid)", side < mid - 5)
        assertTrue("left and right both carry signal", left > luma(ExportBackground) + 20 && right > luma(ExportBackground) + 20)
        assertNotEquals("left and right are different recordings", left, right, 1e-9)
    }

    @Test
    fun aHighResolutionImageIsMadeWithoutRunningOutOfMemory() {
        val plot = PlotSize(8192, 4096)   // 33.5 megapixels
        val started = System.nanoTime()
        val (png, result) = render(request(lossy, plot, cutoff = lossyCutoffHz), "big")
        val seconds = (System.nanoTime() - started) / 1e9
        android.util.Log.i("EXPORT", "8192x4096 (${result.width}x${result.height}): %.1f s, %d MB, FFT %d".format(seconds, png.length() / 1_000_000, result.fftSize))
        val layout = ImageLayout(plot, ExportContent())
        assertEquals(layout.totalWidth to layout.totalHeight, bounds(png))
        assertEquals(8_192, result.fftSize)   // 4096 rows need an 8192-point FFT (4096 bins)
        // Look at a 600x600 crop on each side of the brick wall without decoding the whole image.
        val decoder = BitmapRegionDecoder.newInstance(png.path, false)!!
        val cutRow = layout.plotTop + ((1 - lossyCutoffHz / 22_050.0) * plot.height).toInt()
        val x = layout.plotLeft + 3000
        val above = decoder.decodeRegion(Rect(x, cutRow - 700, x + 600, cutRow - 100), null)
        val below = decoder.decodeRegion(Rect(x, cutRow + 100, x + 600, cutRow + 700), null)
        assertTrue("above ${meanLuma(above, 0, 0, 600, 600)} below ${meanLuma(below, 0, 0, 600, 600)}", meanLuma(below, 0, 0, 600, 600) > meanLuma(above, 0, 0, 600, 600) + 40)
        decoder.recycle()
    }

    @Test
    fun finerImagesReallyShowFinerDetail() {
        // A bigger image uses a longer FFT, so the brick wall's edge is sharper (fewer rows of transition).
        fun edgeRows(plot: PlotSize, name: String): Double {
            val (png, _) = render(request(lossy, plot, content = ExportContent(cutoff = false, axes = false, legend = false, header = false, trackInfo = false)), name)
            val layout = ImageLayout(plot, ExportContent(cutoff = false, axes = false, legend = false, header = false, trackInfo = false))
            val b = BitmapFactory.decodeFile(png.path)
            val x0 = layout.plotLeft + 10; val x1 = layout.plotLeft + plot.width - 10
            var darkToBright = 0
            val center = ((1 - lossyCutoffHz / 22_050.0) * plot.height).toInt()
            for (row in center - plot.height / 10..center + plot.height / 10) {
                val l = meanLuma(b, x0, layout.plotTop + row, x1, layout.plotTop + row + 1, step = 4)
                if (l in 25.0..70.0) darkToBright++
            }
            return darkToBright.toDouble() / plot.height   // transition thickness as a share of the height
        }
        val coarse = edgeRows(PlotSize(1024, 512), "edge-coarse")
        val fine = edgeRows(PlotSize(1024, 2048), "edge-fine")
        assertTrue("the edge should be sharper in the taller image (coarse $coarse, fine $fine)", fine <= coarse + 0.002)
    }

    @Test
    fun progressRunsThroughThePhasesInOrder() {
        val seen = mutableListOf<com.spectroflac.export.image.ExportProgress>()
        render(request(real, PlotSize(1920, 1080)), "progress") { seen += it }
        assertTrue(seen.isNotEmpty())
        val order = seen.map { it.phase }.distinct()
        assertEquals(listOf(ExportPhase.DECODING, ExportPhase.ANALYSING, ExportPhase.WRITING), order)
        assertTrue("fractions never go back", seen.zipWithNext().all { (a, b) -> b.fraction >= a.fraction - 1e-6f })
        assertTrue("it ends near 100 %", seen.last().fraction > 0.95f)
    }

    @Test
    fun cancellingStopsTheRenderAndLeavesNoTemporaryFiles() {
        val tmp = File(context.cacheDir, SpectrogramImageExporter.TMP_DIR)
        val out = File(outDir, "cancel.png")
        runBlocking {
            val job = launch(Dispatchers.Default) {
                FileOutputStream(out).use { stream ->
                    exporter.export(request(real, PlotSize(16_384, 8_192)), real.length(), { FileInputStream(real) }, stream) {}
                }
            }
            delay(1500)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
        assertTrue("temporary files must be removed, found ${tmp.listFiles()?.map { it.name }}", tmp.listFiles().isNullOrEmpty())
    }

    @Test
    fun theJobSharesAFinishedImageAndTheServiceRunsOnlyWhileItWorks() {
        val name = "job-share.png"
        val file = File(context.cacheDir, "exports/$name")
        file.delete()
        app.exportJob.start(request(real, PlotSize(7680, 4320)), ExportTarget.Share(file, name), name)
        waitUntil(10_000, "the foreground service starts") { serviceRunning() }
        waitUntil(10_000, "the job reports running") { app.exportJob.state.value is ExportState.Running }
        waitUntil(240_000, "the export finishes") { app.exportJob.state.value !is ExportState.Running }
        val state = app.exportJob.state.value
        assertTrue("expected Done, was $state", state is ExportState.Done)
        assertTrue(file.exists() && file.length() > 1000)
        assertEquals(ImageLayout(PlotSize(7680, 4320), ExportContent()).totalWidth, bounds(file).first)
        app.exportJob.acknowledge()
        waitUntil(10_000, "the service stops by itself") { !serviceRunning() }
        assertTrue(app.exportJob.state.value is ExportState.Idle)
        file.delete()
    }

    @Test
    fun cancellingTheJobDeletesTheHalfWrittenFile() {
        val name = "job-cancel.png"
        val file = File(context.cacheDir, "exports/$name")
        app.exportJob.start(request(real, PlotSize(16_384, 8_192)), ExportTarget.Share(file, name), name)
        waitUntil(10_000, "running") { app.exportJob.state.value is ExportState.Running }
        Thread.sleep(1500)
        app.exportJob.cancel()
        waitUntil(30_000, "back to idle") { app.exportJob.state.value is ExportState.Idle }
        assertFalse("a cancelled export leaves no file", file.exists())
        waitUntil(10_000, "the service stops") { !serviceRunning() }
    }

    @Test
    fun anUnreadableFileFailsCleanly() {
        val bogus = File(outDir, "not-audio.flac").apply { writeText("this is not a flac stream at all") }
        val name = "job-fail.png"
        val file = File(context.cacheDir, "exports/$name")
        app.exportJob.start(request(bogus, PlotSize(640, 360)), ExportTarget.Share(file, name), name)
        waitUntil(30_000, "the job fails") { app.exportJob.state.value is ExportState.Failed }
        assertFalse(file.exists())
        app.exportJob.acknowledge()
        assertTrue(app.exportJob.state.value is ExportState.Idle)
    }

    /** Opt-in (`-e showcase true`): writes sample images to the app's external files dir for a visual check. */
    @Test
    fun writeShowcaseImages() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("showcase") == "true")
        val tagged = File(dir, "tagged_lossy.flac")
        val taggedReal = File(dir, "tagged_real.flac")
        assumeTrue(tagged.exists() && taggedReal.exists())
        val out = File(context.getExternalFilesDir(null), "showcase").apply { mkdirs() }
        fun write(req: ExportRequest, name: String) {
            val (png, _) = render(req.copy(subject = req.subject.copy(title = null, artist = null)), name)
            png.copyTo(File(out, "$name.png"), overwrite = true)
        }
        write(request(tagged, PlotSize(1920, 1080), cutoff = lossyCutoffHz), "lossy-1080p")
        write(request(taggedReal, PlotSize(1920, 1080)), "real-1080p")
        write(request(tagged, PlotSize(1280, 720), channel = ExportChannel(ExportChannel.SIDE, "Side"), cutoff = lossyCutoffHz), "lossy-side")
        write(request(tagged, PlotSize(960, 540), content = ExportContent(false, false, false, false, false)), "bare")
        write(request(tagged, PlotSize(3840, 2160), cutoff = lossyCutoffHz), "lossy-4k")
    }

    /** Opt-in (`-e heavy true`): the largest image the dialog allows a short file, 268 megapixels. */
    @Test
    fun theLargestImageWorks() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("heavy") == "true")
        val plot = PlotSize(16_384, 16_384)
        val started = System.nanoTime()
        val (png, result) = render(request(lossy, plot, cutoff = lossyCutoffHz), "largest")
        val seconds = (System.nanoTime() - started) / 1e9
        android.util.Log.i("EXPORT", "16384x16384 -> ${result.width}x${result.height}: %.0f s, %d MB, FFT %d".format(seconds, png.length() / 1_000_000, result.fftSize))
        assertEquals(result.width to result.height, bounds(png))
        val decoder = BitmapRegionDecoder.newInstance(png.path, false)!!
        val layout = ImageLayout(plot, ExportContent())
        val cutRow = layout.plotTop + ((1 - lossyCutoffHz / 22_050.0) * plot.height).toInt()
        val above = decoder.decodeRegion(Rect(layout.plotLeft + 5000, cutRow - 900, layout.plotLeft + 5600, cutRow - 300), null)
        val below = decoder.decodeRegion(Rect(layout.plotLeft + 5000, cutRow + 300, layout.plotLeft + 5600, cutRow + 900), null)
        assertTrue(meanLuma(below, 0, 0, 600, 600) > meanLuma(above, 0, 0, 600, 600) + 40)
        decoder.recycle()
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean {
        val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(100).any { it.service.className == ExportService::class.java.name }
    }

    private fun waitUntil(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what; state=${app.exportJob.state.value}")
            Thread.sleep(50)
        }
    }

    private companion object {
        val ExportBackground = 0xFF05060E.toInt()
    }
}
