package com.spectroflac.export.image

import com.spectroflac.analysis.Fft

/** Times one FFT on this device, so the dialog can estimate how long an export will take. */
object ExportBenchmark {
    private val cache = HashMap<Int, Double>()

    /** Milliseconds for one transform of [size] points (the median of a few runs). */
    @Synchronized
    fun fftMillis(size: Int): Double = cache.getOrPut(size) {
        val fft = Fft(size)
        val re = DoubleArray(size) { Math.sin(it * 0.01) }
        val im = DoubleArray(size)
        val times = DoubleArray(7)
        repeat(2) { fft.transform(re, im) } // warm up the JIT
        for (i in times.indices) {
            val start = System.nanoTime()
            fft.transform(re, im)
            times[i] = (System.nanoTime() - start) / 1e6
        }
        times.sort()
        times[times.size / 2]
    }
}
