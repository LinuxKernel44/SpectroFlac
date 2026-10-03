package com.spectroflac.export.image

import com.spectroflac.analysis.Fft
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.log10
import kotlin.math.sqrt

/** Mono audio the analysis can read windows from. Samples outside the file read as silence. */
interface PcmSource {
    /** Fills `dest[0 until count]` with the samples `start until start + count`. */
    fun read(start: Long, count: Int, dest: FloatArray)
}

/** Audio held in memory; used by the tests. */
class ArrayPcm(private val data: FloatArray) : PcmSource {
    override fun read(start: Long, count: Int, dest: FloatArray) {
        for (i in 0 until count) {
            val index = start + i
            dest[i] = if (index in 0 until data.size) data[index.toInt()] else 0f
        }
    }
}

/**
 * Decoded audio on disk as little-endian 32-bit floats. Reads are positional, so any number of
 * threads can read windows at the same time.
 */
class FilePcmSource(private val channel: FileChannel, val totalSamples: Long) : PcmSource {
    private val buffers = ThreadLocal<ByteBuffer>()

    override fun read(start: Long, count: Int, dest: FloatArray) {
        val first = maxOf(start, 0L)
        val last = minOf(start + count, totalSamples)
        java.util.Arrays.fill(dest, 0, count, 0f)
        if (last <= first) return
        val samples = (last - first).toInt()
        var buffer = buffers.get()
        if (buffer == null || buffer.capacity() < samples * 4) {
            buffer = ByteBuffer.allocate(samples * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffers.set(buffer)
        }
        buffer.clear()
        buffer.limit(samples * 4)
        var position = first * 4
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, position)
            if (n < 0) break
            position += n
        }
        buffer.flip()
        val offset = (first - start).toInt()
        buffer.asFloatBuffer().get(dest, offset, minOf(samples, buffer.remaining() / 4))
    }
}

/** The geometry of one export: how the audio maps onto the columns and rows of the image. */
class StftPlan(val sampleRate: Int, val totalSamples: Long, val width: Int, val height: Int) {
    val fftSize: Int = ExportPlanner.fftSize(height)
    val bins: Int = fftSize / 2

    /** Samples of audio behind one column. */
    val columnSpan: Double = totalSamples.toDouble() / width
    val windowsPerColumn: Int = ExportPlanner.windowsPerColumn(totalSamples, width, fftSize)

    /** Row `r` covers the bins `binStart[r] until binStart[r + 1]`: always at least one. */
    val binStart: IntArray = IntArray(height + 1) { ((it.toLong() * bins) / height).toInt() }

    val hzPerRow: Double get() = sampleRate / 2.0 / height
    val nyquist: Double get() = sampleRate / 2.0
}

/**
 * Computes the spectrogram columns of a [StftPlan]. The plan, window and FFT tables are shared; each
 * thread asks for its own [Worker], which owns the scratch buffers.
 */
class StftColumns(private val plan: StftPlan) {
    private val fft = Fft(plan.fftSize)
    private val window = Fft.hannWindow(plan.fftSize)

    /** dB of a power of 1.0 once scaled to a single-sided amplitude spectrum. */
    private val dbOffset = 20.0 * log10(2.0 / (plan.fftSize / 2.0))

    fun newWorker() = Worker()

    inner class Worker {
        private val re = DoubleArray(plan.fftSize)
        private val im = DoubleArray(plan.fftSize)
        private val samples = FloatArray(plan.fftSize)

        /**
         * Writes the [StftPlan.height] level bytes (0..255 for -120..0 dBFS, band 0 first) of column
         * [column] into `out` at [offset]. When a column spans more audio than one window, several
         * windows are taken across it and the loudest value of each band is kept (peak-hold), as the
         * analysis does.
         */
        fun column(pcm: PcmSource, column: Int, out: ByteArray, offset: Int) {
            val height = plan.height
            java.util.Arrays.fill(out, offset, offset + height, 0)
            val windows = plan.windowsPerColumn
            for (k in 0 until windows) {
                val center = ((column + (k + 0.5) / windows) * plan.columnSpan).toLong()
                // Keep the window inside the file: zero-padding past either end would turn the abrupt
                // start (or stop) of the audio into a burst of fake high-frequency energy.
                val start = (center - plan.fftSize / 2).coerceIn(0L, maxOf(0L, plan.totalSamples - plan.fftSize))
                pcm.read(start, plan.fftSize, samples)
                for (n in 0 until plan.fftSize) {
                    re[n] = samples[n] * window[n]
                    im[n] = 0.0
                }
                fft.transform(re, im)
                for (row in 0 until height) {
                    var peak = 0.0
                    for (b in plan.binStart[row] until plan.binStart[row + 1]) {
                        val p = re[b] * re[b] + im[b] * im[b]
                        if (p > peak) peak = p
                    }
                    val level = level(peak)
                    if (level > (out[offset + row].toInt() and 0xFF)) out[offset + row] = level.toByte()
                }
            }
        }
    }

    private fun level(power: Double): Int {
        if (power <= 0.0) return 0
        val db = DB_PER_LOG2 * fastLog2(power) + dbOffset
        return (((db + 120.0) / 120.0) * 255.0).toInt().coerceIn(0, 255)
    }

    private companion object {
        /** 10 * log10(2): converts a log2 of power into dB. */
        const val DB_PER_LOG2 = 3.0102999566398120

        /** log2 from the exponent bits and a cubic fit of the mantissa (accurate to about 0.005). */
        fun fastLog2(x: Double): Double {
            val bits = x.toRawBits()
            val exponent = ((bits ushr 52) and 0x7FF).toInt() - 1023
            val m = java.lang.Double.longBitsToDouble((bits and 0xFFFFFFFFFFFFFL) or 0x3FF0000000000000L)
            return exponent + ((-0.34484843 * m + 2.02466578) * m - 0.67487759)
        }
    }
}

/** Mono level of a column, used to check the engine without the full pipeline. */
internal fun rms(data: FloatArray): Double {
    var sum = 0.0
    for (v in data) sum += v * v
    return sqrt(sum / data.size)
}
