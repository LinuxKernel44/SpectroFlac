package com.spectroflac.export.image

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * A small PNG reader for the tests, written against the specification and independent of
 * [PngStreamWriter]: it checks the signature, every chunk's CRC and the closing IEND, inflates the
 * IDAT data and undoes the row filters.
 */
object TestPng {
    class Decoded(val width: Int, val height: Int, private val rgb: ByteArray) {
        fun getRGB(x: Int, y: Int): Int {
            val i = (y * width + x) * 3
            return (0xFF shl 24) or ((rgb[i].toInt() and 0xFF) shl 16) or ((rgb[i + 1].toInt() and 0xFF) shl 8) or (rgb[i + 2].toInt() and 0xFF)
        }
    }

    fun decode(bytes: ByteArray): Decoded {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        check(bytes.copyOfRange(0, 8).contentEquals(signature)) { "bad PNG signature" }
        var pos = 8
        var width = 0
        var height = 0
        val idat = ByteArrayOutputStream()
        var sawEnd = false
        while (pos < bytes.size) {
            val length = int(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val crc = CRC32().apply { update(bytes, pos + 4, 4 + length) }.value.toInt()
            check(crc == int(bytes, pos + 8 + length)) { "bad CRC in $type" }
            when (type) {
                "IHDR" -> {
                    width = int(bytes, pos + 8); height = int(bytes, pos + 12)
                    check(bytes[pos + 16].toInt() == 8 && bytes[pos + 17].toInt() == 2) { "expected 8-bit RGB" }
                }
                "IDAT" -> idat.write(bytes, pos + 8, length)
                "IEND" -> sawEnd = true
            }
            pos += 12 + length
        }
        check(sawEnd && pos == bytes.size) { "missing IEND or trailing data" }

        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        val stride = 1 + width * 3
        val raw = ByteArray(stride * height)
        var filled = 0
        while (filled < raw.size) {
            val n = inflater.inflate(raw, filled, raw.size - filled)
            check(n > 0 || !inflater.needsInput()) { "IDAT data ended early" }
            if (n == 0 && inflater.finished()) break
            filled += n
        }
        check(filled == raw.size) { "expected ${raw.size} bytes of pixels, got $filled" }
        val rgb = ByteArray(width * height * 3)
        for (y in 0 until height) {
            check(raw[y * stride].toInt() == 0) { "unexpected row filter" }
            System.arraycopy(raw, y * stride + 1, rgb, y * width * 3, width * 3)
        }
        return Decoded(width, height, rgb)
    }

    private fun int(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
