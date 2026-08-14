package com.spectroflac.analysis

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.spectroflac.flac.ContainerKind
import com.spectroflac.flac.ContainerSniffer
import com.spectroflac.flac.FlacDecoder
import com.spectroflac.flac.FlacFormatException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.BufferedInputStream
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Runs one file end to end: sniff the container, read the metadata, decode every sample once
 * while measuring, then judge.
 */
class FlacAnalyzer(private val context: Context) {

    suspend fun analyze(uri: Uri, onProgress: (Float) -> Unit = {}): AnalysisReport {
        val started = System.currentTimeMillis()
        val name = displayName(uri)
        val size = fileSize(uri)

        val head = ByteArray(16 * 1024)
        var headLen = 0
        try {
            openStream(uri).use { input ->
                var read = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n <= 0) break
                    read += n
                }
                headLen = read
            }
        } catch (e: Exception) {
            return errorReport(uri, name, ContainerKind.UNKNOWN, started, e.messageOrType())
        }
        if (headLen == 0) {
            return errorReport(uri, name, ContainerKind.UNKNOWN, started, "The file is empty.")
        }

        val sniffed = ContainerSniffer.sniff(head.copyOf(headLen))
        if (sniffed.kind != ContainerKind.FLAC) {
            return notFlacReport(uri, name, size, sniffed.kind, started)
        }

        return try {
            decodeAndJudge(uri, name, size, started, onProgress)
        } catch (e: FlacFormatException) {
            errorReport(uri, name, ContainerKind.FLAC, started, e.message ?: "Malformed FLAC stream")
        } catch (e: Exception) {
            errorReport(uri, name, ContainerKind.FLAC, started, e.messageOrType())
        }
    }

    private suspend fun decodeAndJudge(
        uri: Uri,
        name: String,
        size: Long,
        started: Long,
        onProgress: (Float) -> Unit,
    ): AnalysisReport {
        openStream(uri).use { raw ->
            val decoder = FlacDecoder(BufferedInputStream(raw, 1 shl 16))
            val meta = decoder.readMetadata()
            val info = meta.streamInfo

            val analyzer = AudioAnalyzer(info.sampleRate, info.channels, info.bitsPerSample, info.totalSamples)
            val job = currentCoroutineContext()[Job]
            var blocks = 0
            val decoded = decoder.decodeFrames({ channels, count ->
                analyzer.onBlock(channels, count)
                if (++blocks % 32 == 0 && size > 0) {
                    onProgress((decoder.bytesRead.toFloat() / size).coerceIn(0f, 1f))
                }
            }, isCancelled = { job != null && !job.isActive })
            onProgress(1f)

            val m = analyzer.finish()
            val duration = if (info.sampleRate > 0) {
                (if (info.totalSamples > 0) info.totalSamples else decoded.samplesDecoded).toDouble() / info.sampleRate
            } else 0.0

            val bitrate = if (duration > 0) ((size * 8.0) / duration / 1000.0).roundToInt() else 0
            val uncompressed = (info.sampleRate.toLong() * info.bitsPerSample * info.channels / 1000).toInt()

            val technical = TechnicalInfo(
                bitsPerSample = info.bitsPerSample,
                sampleRate = info.sampleRate,
                channels = info.channels,
                channelLayout = channelLayout(info.channels),
                durationSeconds = duration,
                totalSamples = if (info.totalSamples > 0) info.totalSamples else decoded.samplesDecoded,
                fileSizeBytes = size,
                bitrateKbps = bitrate,
                uncompressedBitrateKbps = uncompressed,
                compressionRatio = if (uncompressed > 0) bitrate.toDouble() / uncompressed else 0.0,
                minBlockSize = info.minBlockSize,
                maxBlockSize = info.maxBlockSize,
                minFrameSize = info.minFrameSize,
                maxFrameSize = info.maxFrameSize,
                fixedBlockSize = decoded.fixedBlockSize,
            )

            val encoder = EncoderInfo(
                vendor = meta.vendor,
                compressionGuess = compressionGuess(info.maxBlockSize, meta.vendor),
                hasSeekTable = meta.hasSeekTable,
                seekPoints = meta.seekPoints,
                paddingBytes = meta.paddingBytes,
                hasCueSheet = meta.hasCueSheet,
                metadataBytes = meta.metadataBytes,
                blockTypes = meta.blockTypes,
                applicationIds = meta.applicationIds,
            )

            val integrity = IntegrityInfo(
                md5Expected = decoded.md5Expected,
                md5Actual = decoded.md5Actual,
                md5Present = info.hasMd5,
                md5Verified = decoded.md5Verified,
                md5Matches = decoded.md5Matches,
                crcErrors = decoded.crcErrors,
                truncated = decoded.truncated,
                framesDecoded = decoded.framesDecoded,
                samplesDecoded = decoded.samplesDecoded,
                declaredSamples = info.totalSamples,
                decodeError = decoded.error,
            )

            val dynamics = DynamicsInfo(
                peakDbfs = m.peakSampleDbfs,
                rmsDbfs = m.rmsDbfs,
                crestFactorDb = m.crestFactorDb,
                dynamicRangeDb = m.dynamicRangeDb,
                clippedSamples = m.clippedSamples,
                clippedRuns = m.clippedRuns,
                silentRatio = m.silentSampleRatio,
                declaredBitDepth = info.bitsPerSample,
                effectiveBitDepth = m.effectiveBitDepth,
                unusedLowBits = m.unusedLowBits,
                dualMono = m.dualMono,
            )

            val spectral = Judge.spectral(m)
            val (verdict, confidence, findings) =
                Judge.assess(ContainerKind.FLAC, technical, spectral, integrity, dynamics, m)

            return AnalysisReport(
                fileName = name,
                uri = uri.toString(),
                container = ContainerKind.FLAC,
                verdict = verdict,
                confidence = confidence,
                headline = headline(info.bitsPerSample, info.sampleRate, bitrate),
                summary = Judge.summarise(verdict, spectral, integrity),
                findings = findings,
                technical = technical,
                encoder = encoder,
                spectral = spectral,
                integrity = integrity,
                dynamics = dynamics,
                tags = meta.tags,
                coverBytes = meta.picture?.data,
                spectrogram = SpectrogramPreview(
                    columns = m.spectrogramColumns,
                    bands = m.spectrogramBands,
                    data = m.spectrogram,
                    sampleRate = info.sampleRate,
                ),
                analysedAtMillis = System.currentTimeMillis(),
                analysisDurationMillis = System.currentTimeMillis() - started,
            )
        }
    }

    private fun notFlacReport(
        uri: Uri,
        name: String,
        size: Long,
        kind: ContainerKind,
        started: Long,
    ) = AnalysisReport(
        fileName = name,
        uri = uri.toString(),
        container = kind,
        verdict = Verdict.NOT_FLAC,
        confidence = 99,
        headline = "NOT A FLAC FILE",
        summary = Judge.summarise(Verdict.NOT_FLAC, null, null),
        findings = listOf(
            Finding(
                Severity.CRITICAL,
                "The contents are ${kind.label}",
                "The file has no fLaC stream marker. Its first bytes identify it as ${kind.label}" +
                    if (kind == ContainerKind.MP3 || kind == ContainerKind.AAC_ADTS)
                        " — a lossy file that has simply been renamed."
                    else ".",
            ),
        ),
        technical = null,
        encoder = null,
        spectral = null,
        integrity = null,
        dynamics = null,
        tags = emptyMap(),
        coverBytes = null,
        spectrogram = null,
        analysedAtMillis = System.currentTimeMillis(),
        analysisDurationMillis = System.currentTimeMillis() - started,
    )

    private fun errorReport(uri: Uri, name: String, kind: ContainerKind, started: Long, message: String) =
        AnalysisReport(
            fileName = name,
            uri = uri.toString(),
            container = kind,
            verdict = Verdict.ERROR,
            confidence = 0,
            headline = "UNREADABLE",
            summary = Judge.summarise(Verdict.ERROR, null, null),
            findings = listOf(Finding(Severity.CRITICAL, "Analysis failed", message)),
            technical = null,
            encoder = null,
            spectral = null,
            integrity = null,
            dynamics = null,
            tags = emptyMap(),
            coverBytes = null,
            spectrogram = null,
            analysedAtMillis = System.currentTimeMillis(),
            analysisDurationMillis = System.currentTimeMillis() - started,
            errorMessage = message,
        )

    private fun openStream(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open $uri")

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "audio.flac"
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "audio.flac"
    }

    private fun fileSize(uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { java.io.File(it).length() } ?: 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        }
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: 0L
    }

    private fun channelLayout(channels: Int) = when (channels) {
        1 -> "Mono"
        2 -> "Stereo"
        3 -> "3.0"
        4 -> "Quad"
        5 -> "5.0"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> "$channels channels"
    }

    private fun compressionGuess(maxBlockSize: Int, vendor: String?): String {
        val level = when {
            maxBlockSize <= 1152 -> "-0 to -2 (fast)"
            maxBlockSize <= 4096 -> "-3 to -8 (default block size)"
            else -> "-8e or a custom high setting"
        }
        return if (vendor.isNullOrBlank()) "estimated $level" else "estimated $level, ${maxBlockSize} sample blocks"
    }

    private fun Exception.messageOrType() = message ?: javaClass.simpleName

    companion object {
        fun headline(bits: Int, sampleRate: Int, bitrateKbps: Int): String {
            val rate = if (sampleRate % 1000 == 0) "${sampleRate / 1000}" else "%.1f".fmt(sampleRate / 1000.0)
            return "$bits BIT  ${rate}KHZ  $bitrateKbps KBPS FLAC"
        }
    }
}
