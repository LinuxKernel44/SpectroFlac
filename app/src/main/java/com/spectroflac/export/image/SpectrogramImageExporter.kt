package com.spectroflac.export.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.spectroflac.ui.components.SpectrogramLut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Renders the spectrogram of a file to a PNG at the resolution asked for.
 *
 * The audio is decoded once to a temporary file; the spectrogram is then computed afresh with an FFT
 * as long as the number of rows needs (so a bigger image really has more detail), by as many threads
 * as the phone has cores; the levels go to a second temporary file laid out so they can be read back
 * row by row; and the PNG is streamed out while it is composed. Memory use stays small however large
 * the image: only the free storage limits it.
 */
class SpectrogramImageExporter(private val context: Context) {

    suspend fun export(
        request: ExportRequest,
        fileSize: Long,
        openInput: () -> InputStream,
        out: OutputStream,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        val tmp = File(context.cacheDir, TMP_DIR).apply { mkdirs() }
        val id = System.nanoTime()
        val pcmFile = File(tmp, "$id.pcm")
        val levelFile = File(tmp, "$id.lvl")
        val job = coroutineContext
        try {
            // 1. Decode the chosen channel.
            val info = openInput().use {
                PcmExtractor.extract(
                    it, fileSize, request.channel, pcmFile,
                    onProgress = { f -> onProgress(ExportProgress(ExportPhase.DECODING, f * DECODE_SHARE)) },
                    isCancelled = { !job.isActive },
                )
            }
            ensureActive()
            if (info.samples < 4096) throw IllegalStateException("The file holds too little audio to draw.")

            val plot = request.plot
            val plan = StftPlan(info.sampleRate, info.samples, plot.width, plot.height)
            val layout = ImageLayout(plot, request.content)
            val duration = info.samples.toDouble() / info.sampleRate
            val subject = completeSubject(request.subject, info)
            val cover = if (request.content.trackInfo) decodeCover(info) else null
            val infoLine = String.format(
                Locale.US, "%s  ·  FFT %d  ·  %.1f Hz/row  ·  %.0f ms/column",
                request.channel.label, plan.fftSize, plan.hzPerRow, plan.columnSpan / info.sampleRate * 1000.0,
            )

            val counting = CountingOutputStream(BufferedOutputStream(out, 1 shl 16))
            RandomAccessFile(pcmFile, "r").use { pcmRaf ->
                val pcm = FilePcmSource(pcmRaf.channel, info.samples)
                LevelStore(levelFile, plot.width, plot.height).use { store ->
                    // 2. The spectrogram columns, in chunks, on all cores.
                    analyse(plan, pcm, store) { f ->
                        onProgress(ExportProgress(ExportPhase.ANALYSING, DECODE_SHARE + f * ANALYSE_SHARE))
                    }
                    ensureActive()

                    // 3. Compose and stream the PNG.
                    val painter = OverlayPainter(layout, subject, plan.nyquist, duration, infoLine, cover, SpectrogramLut)
                    val composer = ImageComposer(layout, painter, PlotLevels(store), SpectrogramLut)
                    val png = PngStreamWriter(counting, layout.totalWidth, layout.totalHeight, compressionLevel = 3)
                    composer.compose(
                        onRow = png::writeRow,
                        onProgress = { f -> onProgress(ExportProgress(ExportPhase.WRITING, DECODE_SHARE + ANALYSE_SHARE + f * (1f - DECODE_SHARE - ANALYSE_SHARE))) },
                        isCancelled = { !job.isActive },
                    )
                    ensureActive()
                    png.finish()
                }
            }
            counting.flush()
            ExportResult(
                width = layout.totalWidth, height = layout.totalHeight, bytes = counting.count,
                seconds = (System.nanoTime() - started) / 1e9, fftSize = plan.fftSize, partial = info.partial,
            )
        } finally {
            pcmFile.delete()
            levelFile.delete()
        }
    }

    /** Computes every column of the plan and stores it; reports progress 0..1. */
    private suspend fun analyse(plan: StftPlan, pcm: PcmSource, store: LevelStore, onProgress: (Float) -> Unit) {
        val columns = StftColumns(plan)
        val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val workers = List(workerCount) { columns.newWorker() }
        val height = plan.height
        val chunk = (16_000_000 / height).coerceIn(1, plan.width)
        val data = ByteArray(chunk * height)
        var first = 0
        while (first < plan.width) {
            val count = minOf(chunk, plan.width - first)
            coroutineScope {
                for (w in 0 until workerCount) {
                    launch {
                        val worker = workers[w]
                        var c = w
                        while (c < count) {
                            ensureActive()
                            worker.column(pcm, first + c, data, c * height)
                            c += workerCount
                        }
                    }
                }
            }
            store.writeColumns(first, count, data)
            first += count
            onProgress(first.toFloat() / plan.width)
        }
    }

    /** Fills in what the file itself knows: tags and the format line. */
    private fun completeSubject(subject: ExportSubject, info: PcmInfo): ExportSubject {
        val meta = info.metadata
        val bits = meta.streamInfo.bitsPerSample
        val rate = info.sampleRate
        val rateText = if (rate % 1000 == 0) "${rate / 1000} kHz" else String.format(Locale.US, "%.1f kHz", rate / 1000.0)
        val layout = when (info.channels) { 1 -> "mono"; 2 -> "stereo"; else -> "${info.channels} channels" }
        val seconds = (info.samples / rate).toInt()
        val time = if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
        else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
        return subject.copy(
            title = subject.title ?: meta.tag("TITLE"),
            artist = subject.artist ?: meta.tag("ARTIST", "ALBUMARTIST"),
            formatLine = subject.formatLine ?: "$bits bit  ·  $rateText  ·  $layout  ·  $time",
        )
    }

    /** The cover, decoded no bigger than the header needs. */
    private fun decodeCover(info: PcmInfo): Bitmap? {
        val data = info.metadata.picture?.data ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= COVER_PIXELS) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    private class CountingOutputStream(private val inner: OutputStream) : OutputStream() {
        var count = 0L
            private set
        override fun write(b: Int) { inner.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }

    companion object {
        const val TMP_DIR = "export-tmp"
        private const val DECODE_SHARE = 0.12f
        private const val ANALYSE_SHARE = 0.50f
        private const val COVER_PIXELS = 400

        /** Removes temporary files left behind by an export that was killed. */
        fun cleanLeftovers(context: Context) {
            runCatching { File(context.cacheDir, TMP_DIR).listFiles()?.forEach { it.delete() } }
            // Shared exports (possibly huge) are only needed until the share sheet is done with them.
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            runCatching { File(context.cacheDir, "exports").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() } }
        }
    }
}
