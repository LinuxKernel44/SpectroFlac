package com.spectroflac.flac

import java.io.EOFException
import java.io.InputStream

/**
 * Bit-level reader over an [InputStream], with the two running CRCs the FLAC bitstream needs
 * (CRC-8 over a frame header, CRC-16 over a whole frame).
 *
 * The low [bitCount] bits of [bitBuffer] are the valid ones; everything above them is zero.
 */
class BitInput(private val stream: InputStream) {

    private val byteBuffer = ByteArray(1 shl 16)
    private var byteLen = 0
    private var byteIdx = 0

    private var bitBuffer = 0L
    private var bitCount = 0

    /** Total number of bytes pulled out of [stream] and consumed by the reader. */
    var bytesRead = 0L
        private set

    private var crc8 = 0
    private var crc16 = 0
    private var crcEnabled = false

    val isByteAligned: Boolean get() = bitCount % 8 == 0

    // ---------------------------------------------------------------- refill

    private fun fillByteBuffer(): Boolean {
        if (byteIdx < byteLen) return true
        val n = stream.read(byteBuffer)
        if (n <= 0) {
            byteLen = 0
            byteIdx = 0
            return false
        }
        byteLen = n
        byteIdx = 0
        return true
    }

    /** Pulls one byte into the bit buffer. Returns false at end of stream. */
    private fun pullByte(): Boolean {
        if (!fillByteBuffer()) return false
        val b = byteBuffer[byteIdx++].toInt() and 0xFF
        bytesRead++
        if (crcEnabled) {
            crc8 = CRC8_TABLE[(crc8 xor b) and 0xFF]
            crc16 = ((crc16 shl 8) xor CRC16_TABLE[((crc16 ushr 8) xor b) and 0xFF]) and 0xFFFF
        }
        bitBuffer = (bitBuffer shl 8) or b.toLong()
        bitCount += 8
        return true
    }

    private fun ensure(bits: Int) {
        while (bitCount < bits) {
            if (!pullByte()) throw EOFException("Unexpected end of FLAC stream")
        }
    }

    // ------------------------------------------------------------------ read

    /** Reads [n] bits (0..32) as an unsigned value. */
    fun readUInt(n: Int): Int {
        if (n == 0) return 0
        ensure(n)
        bitCount -= n
        val v = (bitBuffer ushr bitCount) and ((1L shl n) - 1)
        bitBuffer = bitBuffer and ((1L shl bitCount) - 1)
        return v.toInt()
    }

    fun readULong(n: Int): Long {
        if (n <= 32) return readUInt(n).toLong() and 0xFFFFFFFFL
        val high = readUInt(n - 32).toLong() and 0xFFFFFFFFL
        val low = readUInt(32).toLong() and 0xFFFFFFFFL
        return (high shl 32) or low
    }

    /** Reads [n] bits as a two's-complement signed value. */
    fun readSInt(n: Int): Int {
        if (n == 0) return 0
        val v = readUInt(n)
        return if (n == 32) v else (v shl (32 - n)) shr (32 - n)
    }

    /** Reads a unary-coded value: the number of 0 bits before the next 1 bit. */
    fun readUnary(): Int {
        var count = 0
        while (true) {
            if (bitCount == 0) {
                if (!pullByte()) throw EOFException("Unexpected end of FLAC stream")
                continue
            }
            val aligned = bitBuffer shl (64 - bitCount)
            val zeros = java.lang.Long.numberOfLeadingZeros(aligned)
            if (zeros < bitCount) {
                count += zeros
                bitCount -= zeros + 1
                bitBuffer = bitBuffer and ((1L shl bitCount) - 1)
                return count
            }
            count += bitCount
            bitCount = 0
            bitBuffer = 0
        }
    }

    /** Reads the UTF-8-like coded frame or sample number of a frame header. */
    fun readUtf8Number(): Long {
        val head = readUInt(8)
        var extra = 0
        var value: Long
        when {
            head < 0x80 -> return head.toLong()
            head >= 0xFE -> { extra = 6; value = 0L }
            head >= 0xFC -> { extra = 5; value = (head and 0x01).toLong() }
            head >= 0xF8 -> { extra = 4; value = (head and 0x03).toLong() }
            head >= 0xF0 -> { extra = 3; value = (head and 0x07).toLong() }
            head >= 0xE0 -> { extra = 2; value = (head and 0x0F).toLong() }
            head >= 0xC0 -> { extra = 1; value = (head and 0x1F).toLong() }
            else -> throw FlacFormatException("Invalid UTF-8 coded number")
        }
        repeat(extra) {
            val b = readUInt(8)
            if (b and 0xC0 != 0x80) throw FlacFormatException("Invalid UTF-8 continuation byte")
            value = (value shl 6) or (b and 0x3F).toLong()
        }
        return value
    }

    fun readBytes(dest: ByteArray, offset: Int = 0, length: Int = dest.size) {
        for (i in 0 until length) dest[offset + i] = readUInt(8).toByte()
    }

    fun skipBytes(count: Long) {
        var remaining = count
        while (remaining > 0) {
            readUInt(8)
            remaining--
        }
    }

    fun alignToByte(): Int {
        val extra = bitCount % 8
        if (extra != 0) readUInt(extra)
        return extra
    }

    // ------------------------------------------------------------------- CRC

    fun startCrc() {
        crcEnabled = true
        crc8 = 0
        crc16 = 0
    }

    /** CRC-8 of everything consumed since [startCrc], excluding bits still in the bit buffer. */
    fun currentCrc8(): Int = crc8

    fun currentCrc16(): Int = crc16

    fun resetCrc16() {
        crc16 = 0
    }

    /**
     * Tries to read one more byte. Returns -1 at end of stream. Used to detect the end of the
     * frame sequence without throwing.
     */
    fun peekByteOrEof(): Int {
        if (bitCount >= 8) {
            return ((bitBuffer ushr (bitCount - 8)) and 0xFF).toInt()
        }
        if (!fillByteBuffer()) return -1
        return byteBuffer[byteIdx].toInt() and 0xFF
    }

    fun atEndOfStream(): Boolean = bitCount == 0 && !fillByteBuffer()

    companion object {
        private val CRC8_TABLE = IntArray(256) { i ->
            var c = i
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
            c
        }

        private val CRC16_TABLE = IntArray(256) { i ->
            var c = i shl 8
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
            c
        }
    }
}

class FlacFormatException(message: String) : Exception(message)
