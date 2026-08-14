package com.spectroflac.analysis

import com.spectroflac.flac.ContainerKind

enum class Verdict(val label: String, val short: String) {
    AUTHENTIC("Genuine lossless", "GENUINE"),
    SUSPICIOUS("Suspicious", "SUSPICIOUS"),
    FAKE("Not genuine lossless", "FAKE"),
    CORRUPT("Damaged file", "DAMAGED"),
    NOT_FLAC("Not a FLAC file", "NOT FLAC"),
    ERROR("Could not analyse", "ERROR"),
}

enum class Severity { CRITICAL, WARNING, INFO, GOOD }

data class Finding(
    val severity: Severity,
    val title: String,
    val detail: String,
)

data class TechnicalInfo(
    val bitsPerSample: Int,
    val sampleRate: Int,
    val channels: Int,
    val channelLayout: String,
    val durationSeconds: Double,
    val totalSamples: Long,
    val fileSizeBytes: Long,
    val bitrateKbps: Int,
    val uncompressedBitrateKbps: Int,
    val compressionRatio: Double,
    val minBlockSize: Int,
    val maxBlockSize: Int,
    val minFrameSize: Int,
    val maxFrameSize: Int,
    val fixedBlockSize: Boolean,
)

data class EncoderInfo(
    val vendor: String?,
    val compressionGuess: String?,
    val hasSeekTable: Boolean,
    val seekPoints: Int,
    val paddingBytes: Int,
    val hasCueSheet: Boolean,
    val metadataBytes: Long,
    val blockTypes: List<String>,
    val applicationIds: List<String>,
)

data class SpectralInfo(
    val cutoffHz: Double,
    val nyquistHz: Double,
    val cutoffRatio: Double,
    val rolloffDropDb: Double,
    val hasBrickWall: Boolean,
    val noiseFloorDb: Double,
    val peakSpectrumDb: Double,
    val sourceGuess: String?,
    val windowsAnalyzed: Int,
    val fftSize: Int,
    val reasoning: List<String>,
)

data class IntegrityInfo(
    val md5Expected: String,
    val md5Actual: String,
    val md5Present: Boolean,
    val md5Verified: Boolean,
    val md5Matches: Boolean,
    val crcErrors: Int,
    val truncated: Boolean,
    val framesDecoded: Long,
    val samplesDecoded: Long,
    val declaredSamples: Long,
    val decodeError: String?,
)

data class DynamicsInfo(
    val peakDbfs: Double,
    val rmsDbfs: Double,
    val crestFactorDb: Double,
    val dynamicRangeDb: Double?,
    val clippedSamples: Long,
    val clippedRuns: Long,
    val silentRatio: Double,
    val declaredBitDepth: Int,
    val effectiveBitDepth: Int,
    val unusedLowBits: Int,
    val dualMono: Boolean,
)

data class SpectrogramPreview(
    val columns: Int,
    val bands: Int,
    val data: ByteArray,
    val sampleRate: Int,
) {
    override fun equals(other: Any?) = this === other ||
        (other is SpectrogramPreview && columns == other.columns && data.contentEquals(other.data))

    override fun hashCode(): Int = 31 * columns + data.contentHashCode()
}

data class AnalysisReport(
    val fileName: String,
    val uri: String,
    val container: ContainerKind,
    val verdict: Verdict,
    val confidence: Int,
    val headline: String,
    val summary: String,
    val findings: List<Finding>,
    val technical: TechnicalInfo?,
    val encoder: EncoderInfo?,
    val spectral: SpectralInfo?,
    val integrity: IntegrityInfo?,
    val dynamics: DynamicsInfo?,
    val tags: Map<String, String>,
    val coverBytes: ByteArray?,
    val spectrogram: SpectrogramPreview?,
    val analysedAtMillis: Long,
    val analysisDurationMillis: Long,
    val errorMessage: String? = null,
) {
    val title: String? get() = tags["TITLE"]
    val artist: String? get() = tags["ARTIST"] ?: tags["ALBUMARTIST"]
    val album: String? get() = tags["ALBUM"]

    override fun equals(other: Any?) = this === other || (other is AnalysisReport && uri == other.uri &&
        analysedAtMillis == other.analysedAtMillis)

    override fun hashCode(): Int = 31 * uri.hashCode() + analysedAtMillis.hashCode()
}
