package com.spectroflac.flac

/** The STREAMINFO block: the only metadata block a FLAC file is required to carry. */
data class StreamInfo(
    val minBlockSize: Int,
    val maxBlockSize: Int,
    val minFrameSize: Int,
    val maxFrameSize: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
    val md5: ByteArray,
) {
    val hasMd5: Boolean get() = md5.any { it.toInt() != 0 }
    val durationSeconds: Double
        get() = if (sampleRate > 0 && totalSamples > 0) totalSamples.toDouble() / sampleRate else 0.0

    override fun equals(other: Any?): Boolean = this === other ||
        (other is StreamInfo && other.hashCode() == hashCode())

    override fun hashCode(): Int {
        var result = minBlockSize
        result = 31 * result + maxBlockSize
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + bitsPerSample
        result = 31 * result + totalSamples.hashCode()
        result = 31 * result + md5.contentHashCode()
        return result
    }
}

/** An embedded picture (usually the album cover). */
data class FlacPicture(
    val type: Int,
    val mimeType: String,
    val description: String,
    val width: Int,
    val height: Int,
    val colorDepth: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is FlacPicture && type == other.type && data.contentEquals(other.data))

    override fun hashCode(): Int = 31 * type + data.contentHashCode()
}

/** Everything read from the metadata blocks, before a single audio frame is decoded. */
data class FlacMetadata(
    val streamInfo: StreamInfo,
    val vendor: String?,
    val tags: Map<String, String>,
    val picture: FlacPicture?,
    val hasSeekTable: Boolean,
    val seekPoints: Int,
    val hasCueSheet: Boolean,
    val paddingBytes: Int,
    val applicationIds: List<String>,
    val metadataBytes: Long,
    val blockTypes: List<String>,
) {
    fun tag(vararg keys: String): String? {
        for (key in keys) {
            tags[key.uppercase()]?.let { if (it.isNotBlank()) return it }
        }
        return null
    }
}

/** What kind of file we are actually looking at, whatever the extension claims. */
enum class ContainerKind(val label: String) {
    FLAC("FLAC"),
    FLAC_IN_OGG("Ogg FLAC"),
    MP3("MP3 (MPEG Layer III)"),
    MP2("MP2 (MPEG Layer II)"),
    AAC_ADTS("AAC (ADTS)"),
    MP4("MP4 / M4A container"),
    OGG("Ogg (Vorbis/Opus)"),
    WAV("WAV / RIFF"),
    AIFF("AIFF"),
    WAVPACK("WavPack"),
    APE("Monkey's Audio"),
    MUSEPACK("Musepack"),
    WMA("ASF / WMA"),
    UNKNOWN("Unrecognised"),
}
