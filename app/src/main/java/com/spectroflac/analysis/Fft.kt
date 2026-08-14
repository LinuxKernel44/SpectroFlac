package com.spectroflac.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 FFT with precomputed tables, sized once for the analysis window.
 */
class Fft(val size: Int) {

    init {
        require(size > 1 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    private val levels = Integer.numberOfTrailingZeros(size)
    private val cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2 * PI * it / size) }

    fun transform(re: DoubleArray, im: DoubleArray) {
        // Bit-reversal permutation.
        for (i in 0 until size) {
            val j = Integer.reverse(i) ushr (32 - levels)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var span = 2
        while (span <= size) {
            val halfSpan = span / 2
            val tableStep = size / span
            var i = 0
            while (i < size) {
                var j = i
                var k = 0
                while (j < i + halfSpan) {
                    val l = j + halfSpan
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val tre = re[l] * c + im[l] * s
                    val tim = -re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    j++
                    k += tableStep
                }
                i += span
            }
            if (span == size) break
            span *= 2
        }
    }

    companion object {
        /** Periodic Hann window, the usual choice for spectral estimation. */
        fun hannWindow(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
    }
}
