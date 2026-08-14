package com.spectroflac.flac

import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest

/**
 * A complete FLAC decoder written in Kotlin.
 *
 * It is deliberately self-contained rather than delegating to `MediaCodec`: the platform decoder
 * hands back 16-bit PCM for a lot of devices, which would make both the MD5 check and the
 * "is this really 24-bit?" test meaningless.
 */
class FlacDecoder(stream: InputStream) {

    private val bit = BitInput(stream)

    lateinit var metadata: FlacMetadata
        private set

    /** Bytes consumed so far — used to drive a progress bar. */
    val bytesRead: Long get() = bit.bytesRead

    private var channelBuffers: Array<IntArray> = emptyArray()
    private var currentBlockSize = 0

    /** Receives decoded blocks as they come out of the decoder. */
    fun interface SampleSink {
        fun onBlock(channels: Array<IntArray>, count: Int)
    }

    data class DecodeResult(
        val framesDecoded: Long,
        val samplesDecoded: Long,
        val md5Actual: String,
        val md5Expected: String,
        val md5Verified: Boolean,
        val md5Matches: Boolean,
        val crcErrors: Int,
        val truncated: Boolean,
        val blockSizesSeen: Set<Int>,
        val fixedBlockSize: Boolean,
        val error: String?,
    )

    // ------------------------------------------------------------- metadata

    fun readMetadata(): FlacMetadata {
        val magic = ByteArray(4)
        bit.readBytes(magic)
        if (!(magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() &&
                magic[2] == 'a'.code.toByte() && magic[3] == 'C'.code.toByte())
        ) {
            throw FlacFormatException("Missing fLaC stream marker")
        }

        var streamInfo: StreamInfo? = null
        var vendor: String? = null
        val tags = LinkedHashMap<String, String>()
        var picture: FlacPicture? = null
        var seekPoints = 0
        var hasCueSheet = false
        var padding = 0
        val appIds = mutableListOf<String>()
        val blockTypes = mutableListOf<String>()

        while (true) {
            val header = bit.readUInt(8)
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val length = bit.readUInt(24)
            blockTypes += blockTypeName(type)

            when (type) {
                0 -> streamInfo = readStreamInfo()
                1 -> { padding += length; bit.skipBytes(length.toLong()) }
                2 -> {
                    val id = ByteArray(4)
                    if (length >= 4) {
                        bit.readBytes(id)
                        appIds += String(id, Charsets.ISO_8859_1)
                        bit.skipBytes((length - 4).toLong())
                    } else bit.skipBytes(length.toLong())
                }
                3 -> { seekPoints = length / 18; bit.skipBytes(length.toLong()) }
                4 -> {
                    val block = ByteArray(length)
                    bit.readBytes(block)
                    val parsed = parseVorbisComment(block)
                    vendor = parsed.first
                    tags.putAll(parsed.second)
                }
                5 -> { hasCueSheet = true; bit.skipBytes(length.toLong()) }
                6 -> {
                    val block = ByteArray(length)
                    bit.readBytes(block)
                    if (picture == null) picture = parsePicture(block)
                }
                else -> bit.skipBytes(length.toLong())
            }
            if (last) break
        }

        val info = streamInfo ?: throw FlacFormatException("No STREAMINFO block")
        metadata = FlacMetadata(
            streamInfo = info,
            vendor = vendor,
            tags = tags,
            picture = picture,
            hasSeekTable = seekPoints > 0,
            seekPoints = seekPoints,
            hasCueSheet = hasCueSheet,
            paddingBytes = padding,
            applicationIds = appIds,
            metadataBytes = bit.bytesRead,
            blockTypes = blockTypes,
        )
        return metadata
    }

    private fun readStreamInfo(): StreamInfo {
        val minBlock = bit.readUInt(16)
        val maxBlock = bit.readUInt(16)
        val minFrame = bit.readUInt(24)
        val maxFrame = bit.readUInt(24)
        val sampleRate = bit.readUInt(20)
        val channels = bit.readUInt(3) + 1
        val bps = bit.readUInt(5) + 1
        val totalSamples = bit.readULong(36)
        val md5 = ByteArray(16)
        bit.readBytes(md5)
        if (sampleRate == 0) throw FlacFormatException("STREAMINFO declares a sample rate of 0")
        return StreamInfo(minBlock, maxBlock, minFrame, maxFrame, sampleRate, channels, bps, totalSamples, md5)
    }

    private fun blockTypeName(type: Int) = when (type) {
        0 -> "STREAMINFO"
        1 -> "PADDING"
        2 -> "APPLICATION"
        3 -> "SEEKTABLE"
        4 -> "VORBIS_COMMENT"
        5 -> "CUESHEET"
        6 -> "PICTURE"
        127 -> "INVALID"
        else -> "RESERVED($type)"
    }

    private fun parseVorbisComment(block: ByteArray): Pair<String?, Map<String, String>> {
        val tags = LinkedHashMap<String, String>()
        var p = 0
        fun u32(): Int {
            if (p + 4 > block.size) throw FlacFormatException("Truncated VORBIS_COMMENT")
            val v = (block[p].toInt() and 0xFF) or ((block[p + 1].toInt() and 0xFF) shl 8) or
                ((block[p + 2].toInt() and 0xFF) shl 16) or ((block[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        return try {
            val vendorLen = u32()
            val vendor = String(block, p, vendorLen.coerceAtMost(block.size - p), Charsets.UTF_8)
            p += vendorLen
            val count = u32()
            repeat(count.coerceAtMost(4096)) {
                val len = u32()
                if (len < 0 || p + len > block.size) return@repeat
                val entry = String(block, p, len, Charsets.UTF_8)
                p += len
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    val key = entry.substring(0, eq).uppercase()
                    val value = entry.substring(eq + 1)
                    tags[key] = tags[key]?.let { "$it / $value" } ?: value
                }
            }
            vendor to tags
        } catch (_: Exception) {
            null to tags
        }
    }

    private fun parsePicture(block: ByteArray): FlacPicture? = try {
        var p = 0
        fun u32be(): Int {
            val v = ((block[p].toInt() and 0xFF) shl 24) or ((block[p + 1].toInt() and 0xFF) shl 16) or
                ((block[p + 2].toInt() and 0xFF) shl 8) or (block[p + 3].toInt() and 0xFF)
            p += 4
            return v
        }
        val type = u32be()
        val mimeLen = u32be()
        val mime = String(block, p, mimeLen, Charsets.ISO_8859_1); p += mimeLen
        val descLen = u32be()
        val desc = String(block, p, descLen, Charsets.UTF_8); p += descLen
        val width = u32be()
        val height = u32be()
        val depth = u32be()
        u32be() // indexed colours
        val dataLen = u32be()
        val data = block.copyOfRange(p, (p + dataLen).coerceAtMost(block.size))
        FlacPicture(type, mime, desc, width, height, depth, data)
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- frames

    fun decodeFrames(sink: SampleSink, isCancelled: () -> Boolean = { false }): DecodeResult {
        val info = metadata.streamInfo
        val maxBlock = if (info.maxBlockSize in 1..65535) info.maxBlockSize else 65535
        channelBuffers = Array(info.channels) { IntArray(maxBlock) }

        val md5 = MessageDigest.getInstance("MD5")
        val bytesPerSample = (info.bitsPerSample + 7) / 8
        var pcm = ByteArray(maxBlock * info.channels * bytesPerSample)

        var frames = 0L
        var samples = 0L
        var crcErrors = 0
        var truncated = false
        var error: String? = null
        val blockSizes = LinkedHashSet<Int>()
        var variableBlocking = false

        try {
            while (!isCancelled()) {
                if (bit.atEndOfStream()) break
                val header = try {
                    readFrameHeader(info)
                } catch (e: FlacFormatException) {
                    // Trailing bytes after the last frame (an ID3v1 tag, a padded rip) are common
                    // enough that they should not be reported as corruption once every declared
                    // sample has already been decoded.
                    if (info.totalSamples > 0 && samples >= info.totalSamples) break else throw e
                }
                if (header.variableBlocking) variableBlocking = true
                blockSizes += header.blockSize
                currentBlockSize = header.blockSize

                decodeSubframes(header, info)

                bit.alignToByte()
                val computed = bit.currentCrc16()
                val stored = bit.readUInt(16)
                if (computed != stored) crcErrors++

                val n = header.blockSize
                val needed = n * info.channels * bytesPerSample
                if (pcm.size < needed) pcm = ByteArray(needed)
                interleaveLittleEndian(channelBuffers, n, info.channels, bytesPerSample, pcm)
                md5.update(pcm, 0, needed)

                sink.onBlock(channelBuffers, n)
                frames++
                samples += n
            }
        } catch (_: EOFException) {
            truncated = true
        } catch (e: FlacFormatException) {
            error = e.message
            truncated = true
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }

        val actual = md5.digest().toHex()
        val expected = info.md5.toHex()
        val verified = info.hasMd5 && !truncated && error == null && !isCancelled()
        return DecodeResult(
            framesDecoded = frames,
            samplesDecoded = samples,
            md5Actual = actual,
            md5Expected = expected,
            md5Verified = verified,
            md5Matches = verified && actual == expected,
            crcErrors = crcErrors,
            truncated = truncated,
            blockSizesSeen = blockSizes,
            fixedBlockSize = !variableBlocking,
            error = error,
        )
    }

    private class FrameHeader(
        val blockSize: Int,
        val sampleRate: Int,
        val channelAssignment: Int,
        val bitsPerSample: Int,
        val variableBlocking: Boolean,
    )

    private fun readFrameHeader(info: StreamInfo): FrameHeader {
        bit.startCrc()
        val sync = bit.readUInt(14)
        if (sync != 0x3FFE) throw FlacFormatException("Lost frame sync (0x%04X)".format(sync))
        bit.readUInt(1) // reserved
        val variable = bit.readUInt(1) == 1

        val blockSizeCode = bit.readUInt(4)
        val sampleRateCode = bit.readUInt(4)
        val channelAssignment = bit.readUInt(4)
        val sampleSizeCode = bit.readUInt(3)
        bit.readUInt(1) // reserved

        bit.readUtf8Number()

        var blockSize = when (blockSizeCode) {
            0 -> throw FlacFormatException("Reserved block size code")
            1 -> 192
            in 2..5 -> 576 shl (blockSizeCode - 2)
            6, 7 -> 0
            else -> 256 shl (blockSizeCode - 8)
        }
        if (blockSizeCode == 6) blockSize = bit.readUInt(8) + 1
        if (blockSizeCode == 7) blockSize = bit.readUInt(16) + 1

        val sampleRate = when (sampleRateCode) {
            0 -> info.sampleRate
            1 -> 88200
            2 -> 176400
            3 -> 192000
            4 -> 8000
            5 -> 16000
            6 -> 22050
            7 -> 24000
            8 -> 32000
            9 -> 44100
            10 -> 48000
            11 -> 96000
            12 -> bit.readUInt(8) * 1000
            13 -> bit.readUInt(16)
            14 -> bit.readUInt(16) * 10
            else -> throw FlacFormatException("Invalid sample rate code")
        }

        val bitsPerSample = when (sampleSizeCode) {
            0 -> info.bitsPerSample
            1 -> 8
            2 -> 12
            4 -> 16
            5 -> 20
            6 -> 24
            7 -> 32
            else -> throw FlacFormatException("Reserved sample size code")
        }

        val computedCrc = bit.currentCrc8()
        val storedCrc = bit.readUInt(8)
        if (computedCrc != storedCrc) throw FlacFormatException("Frame header CRC-8 mismatch")

        if (channelAssignment > 10) throw FlacFormatException("Invalid channel assignment")
        val channels = if (channelAssignment < 8) channelAssignment + 1 else 2
        if (channels != info.channels) throw FlacFormatException("Frame channel count differs from STREAMINFO")
        if (blockSize > channelBuffers[0].size) {
            channelBuffers = Array(info.channels) { IntArray(blockSize) }
        }

        return FrameHeader(blockSize, sampleRate, channelAssignment, bitsPerSample, variable)
    }

    private fun decodeSubframes(header: FrameHeader, info: StreamInfo) {
        val n = header.blockSize
        val bps = header.bitsPerSample
        when (val ca = header.channelAssignment) {
            8 -> { // left / side
                decodeSubframe(channelBuffers[0], n, bps)
                decodeSubframe(channelBuffers[1], n, bps + 1)
                val l = channelBuffers[0]; val s = channelBuffers[1]
                for (i in 0 until n) s[i] = l[i] - s[i]
            }
            9 -> { // side / right
                decodeSubframe(channelBuffers[0], n, bps + 1)
                decodeSubframe(channelBuffers[1], n, bps)
                val s = channelBuffers[0]; val r = channelBuffers[1]
                for (i in 0 until n) s[i] = s[i] + r[i]
            }
            10 -> { // mid / side
                decodeSubframe(channelBuffers[0], n, bps)
                decodeSubframe(channelBuffers[1], n, bps + 1)
                val m = channelBuffers[0]; val s = channelBuffers[1]
                for (i in 0 until n) {
                    val side = s[i]
                    val mid = (m[i] shl 1) or (side and 1)
                    m[i] = (mid + side) shr 1
                    s[i] = (mid - side) shr 1
                }
            }
            else -> {
                for (c in 0..ca) decodeSubframe(channelBuffers[c], n, bps)
            }
        }
    }

    private fun decodeSubframe(out: IntArray, blockSize: Int, bpsIn: Int) {
        var bps = bpsIn
        if (bit.readUInt(1) != 0) throw FlacFormatException("Invalid subframe padding bit")
        val type = bit.readUInt(6)
        val wastedFlag = bit.readUInt(1)
        var wasted = 0
        if (wastedFlag == 1) {
            wasted = bit.readUnary() + 1
            bps -= wasted
        }
        if (bps <= 0 || bps > 33) throw FlacFormatException("Invalid effective bit depth ($bps)")

        when {
            type == 0 -> {
                val v = bit.readSInt(bps)
                java.util.Arrays.fill(out, 0, blockSize, v)
            }
            type == 1 -> {
                for (i in 0 until blockSize) out[i] = bit.readSInt(bps)
            }
            type in 8..12 -> {
                val order = type - 8
                for (i in 0 until order) out[i] = bit.readSInt(bps)
                decodeResidual(out, blockSize, order)
                restoreFixed(out, blockSize, order)
            }
            type >= 32 -> {
                val order = type - 31
                for (i in 0 until order) out[i] = bit.readSInt(bps)
                val precision = bit.readUInt(4) + 1
                if (precision == 16) throw FlacFormatException("Invalid LPC precision")
                val shift = bit.readSInt(5)
                if (shift < 0) throw FlacFormatException("Negative LPC shift")
                val coefs = IntArray(order) { bit.readSInt(precision) }
                decodeResidual(out, blockSize, order)
                restoreLpc(out, blockSize, coefs, shift)
            }
            else -> throw FlacFormatException("Reserved subframe type ($type)")
        }

        if (wasted > 0) {
            for (i in 0 until blockSize) out[i] = out[i] shl wasted
        }
    }

    private fun decodeResidual(out: IntArray, blockSize: Int, predictorOrder: Int) {
        val method = bit.readUInt(2)
        if (method > 1) throw FlacFormatException("Reserved residual coding method")
        val paramBits = if (method == 0) 4 else 5
        val escape = if (method == 0) 0xF else 0x1F

        val partitionOrder = bit.readUInt(4)
        val partitions = 1 shl partitionOrder
        if (blockSize % partitions != 0) throw FlacFormatException("Block size not divisible by partition count")
        val partitionSize = blockSize shr partitionOrder
        if (partitionSize < predictorOrder) throw FlacFormatException("Partition smaller than predictor order")

        var index = predictorOrder
        for (p in 0 until partitions) {
            val count = if (p == 0) partitionSize - predictorOrder else partitionSize
            val param = bit.readUInt(paramBits)
            if (param == escape) {
                val raw = bit.readUInt(5)
                if (raw == 0) {
                    java.util.Arrays.fill(out, index, index + count, 0)
                    index += count
                } else {
                    repeat(count) { out[index++] = bit.readSInt(raw) }
                }
            } else {
                repeat(count) {
                    val q = bit.readUnary()
                    val v = (q shl param) or bit.readUInt(param)
                    out[index++] = (v ushr 1) xor -(v and 1)
                }
            }
        }
    }

    private fun restoreFixed(data: IntArray, blockSize: Int, order: Int) {
        when (order) {
            0 -> Unit
            1 -> for (i in 1 until blockSize) data[i] += data[i - 1]
            2 -> for (i in 2 until blockSize) data[i] += 2 * data[i - 1] - data[i - 2]
            3 -> for (i in 3 until blockSize) data[i] += 3 * data[i - 1] - 3 * data[i - 2] + data[i - 3]
            4 -> for (i in 4 until blockSize) data[i] += 4 * data[i - 1] - 6 * data[i - 2] + 4 * data[i - 3] - data[i - 4]
            else -> throw FlacFormatException("Invalid fixed predictor order")
        }
    }

    private fun restoreLpc(data: IntArray, blockSize: Int, coefs: IntArray, shift: Int) {
        val order = coefs.size
        for (i in order until blockSize) {
            var sum = 0L
            for (j in 0 until order) sum += coefs[j].toLong() * data[i - 1 - j].toLong()
            data[i] += (sum shr shift).toInt()
        }
    }

    private fun interleaveLittleEndian(
        channels: Array<IntArray>,
        count: Int,
        channelCount: Int,
        bytesPerSample: Int,
        out: ByteArray,
    ) {
        var p = 0
        for (i in 0 until count) {
            for (c in 0 until channelCount) {
                var v = channels[c][i]
                for (b in 0 until bytesPerSample) {
                    out[p++] = (v and 0xFF).toByte()
                    v = v shr 8
                }
            }
        }
    }

    companion object {
        fun ByteArray.toHex(): String {
            val sb = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
