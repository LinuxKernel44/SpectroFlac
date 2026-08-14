package com.spectroflac.flac

/**
 * Works out what a file really is from its first bytes, so a lossy file renamed to `.flac`
 * is called out for what it is instead of failing with a parse error.
 */
object ContainerSniffer {

    data class Sniffed(val kind: ContainerKind, val audioOffset: Int, val id3Bytes: Int)

    fun sniff(head: ByteArray): Sniffed {
        var offset = 0
        var id3 = 0

        // ID3v2 tags are legal in front of a FLAC stream and common in front of MP3s.
        while (offset + 10 <= head.size && head[offset] == 'I'.code.toByte() &&
            head[offset + 1] == 'D'.code.toByte() && head[offset + 2] == '3'.code.toByte()
        ) {
            val size = ((head[offset + 6].toInt() and 0x7F) shl 21) or
                ((head[offset + 7].toInt() and 0x7F) shl 14) or
                ((head[offset + 8].toInt() and 0x7F) shl 7) or
                (head[offset + 9].toInt() and 0x7F)
            val total = 10 + size
            if (total <= 0) break
            id3 += total
            offset += total
            if (offset >= head.size) return Sniffed(ContainerKind.UNKNOWN, offset, id3)
        }

        fun magic(at: Int, s: String): Boolean {
            if (at + s.length > head.size) return false
            for (i in s.indices) if (head[at + i] != s[i].code.toByte()) return false
            return true
        }

        val kind = when {
            magic(offset, "fLaC") -> ContainerKind.FLAC
            magic(offset, "OggS") -> if (findAscii(head, "FLAC") >= 0) ContainerKind.FLAC_IN_OGG else ContainerKind.OGG
            magic(offset, "RIFF") && magic(offset + 8, "WAVE") -> ContainerKind.WAV
            magic(offset, "FORM") -> ContainerKind.AIFF
            magic(offset, "wvpk") -> ContainerKind.WAVPACK
            magic(offset, "MAC ") -> ContainerKind.APE
            magic(offset, "MPCK") || magic(offset, "MP+") -> ContainerKind.MUSEPACK
            magic(offset + 4, "ftyp") -> ContainerKind.MP4
            offset + 4 <= head.size &&
                head[offset].toInt() and 0xFF == 0x30 && head[offset + 1].toInt() and 0xFF == 0x26 &&
                head[offset + 2].toInt() and 0xFF == 0xB2 && head[offset + 3].toInt() and 0xFF == 0x75 -> ContainerKind.WMA
            else -> mpegSync(head, offset)
        }
        return Sniffed(kind, offset, id3)
    }

    private fun mpegSync(head: ByteArray, offset: Int): ContainerKind {
        // Scan a little way in: some MP3s start with junk or a partial tag.
        val limit = minOf(head.size - 2, offset + 8192)
        var i = offset
        while (i < limit) {
            val b0 = head[i].toInt() and 0xFF
            val b1 = head[i + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                return when ((b1 shr 1) and 0x03) {
                    0 -> ContainerKind.AAC_ADTS   // "reserved" layer for MPEG audio = ADTS AAC
                    1 -> ContainerKind.MP3
                    2 -> ContainerKind.MP2
                    else -> ContainerKind.UNKNOWN
                }
            }
            i++
        }
        return ContainerKind.UNKNOWN
    }

    private fun findAscii(data: ByteArray, needle: String): Int {
        val n = needle.length
        outer@ for (i in 0..data.size - n) {
            for (j in 0 until n) if (data[i + j] != needle[j].code.toByte()) continue@outer
            return i
        }
        return -1
    }
}
