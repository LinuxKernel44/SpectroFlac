package com.spectroflac.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.Severity
import com.spectroflac.data.ReportJson
import com.spectroflac.util.formatBytes
import com.spectroflac.util.formatDb
import com.spectroflac.util.formatDbfs
import com.spectroflac.util.formatDuration
import com.spectroflac.util.formatHz
import com.spectroflac.util.formatTimestamp
import org.json.JSONArray
import java.io.File
import java.util.Locale

/** Turns reports into something the user can paste, save or send on. */
object Exporter {

    fun textReport(report: AnalysisReport): String = buildString {
        appendLine("SpectroFlac analysis")
        appendLine("=".repeat(52))
        appendLine("File      : ${report.fileName}")
        report.artist?.let { appendLine("Artist    : $it") }
        report.album?.let { appendLine("Album     : $it") }
        report.title?.let { appendLine("Title     : $it") }
        appendLine("Analysed  : ${formatTimestamp(report.analysedAtMillis)}")
        appendLine()
        appendLine(report.headline)
        appendLine("Verdict   : ${report.verdict.label} (${report.confidence}% confidence)")
        appendLine()
        appendLine(report.summary)
        appendLine()

        if (report.findings.isNotEmpty()) {
            appendLine("FINDINGS")
            appendLine("-".repeat(52))
            report.findings.forEach { finding ->
                val mark = when (finding.severity) {
                    Severity.CRITICAL -> "[!]"
                    Severity.WARNING -> "[~]"
                    Severity.INFO -> "[i]"
                    Severity.GOOD -> "[+]"
                }
                appendLine("$mark ${finding.title}")
                appendLine("    ${finding.detail}")
            }
            appendLine()
        }

        report.technical?.let { t ->
            appendLine("TECHNICAL")
            appendLine("-".repeat(52))
            appendLine("Bit depth        : ${t.bitsPerSample} bit")
            appendLine("Sample rate      : ${t.sampleRate} Hz")
            appendLine("Channels         : ${t.channels} (${t.channelLayout})")
            appendLine("Duration         : ${formatDuration(t.durationSeconds)}")
            appendLine("File size        : ${formatBytes(t.fileSizeBytes)}")
            appendLine("Bitrate          : ${t.bitrateKbps} kbps (uncompressed ${t.uncompressedBitrateKbps} kbps)")
            appendLine("Compression      : ${percent(t.compressionRatio)} of the raw stream")
            appendLine("Total samples    : ${t.totalSamples}")
            appendLine("Block size       : ${t.minBlockSize}–${t.maxBlockSize} (${if (t.fixedBlockSize) "fixed" else "variable"})")
            appendLine()
        }
        report.spectral?.let { s ->
            appendLine("SPECTRAL ANALYSIS")
            appendLine("-".repeat(52))
            appendLine("Measured cut     : ${formatHz(s.cutoffHz)} of ${formatHz(s.nyquistHz)} possible")
            appendLine("Steepest step    : ${formatDb(s.rolloffDropDb)}${if (s.hasBrickWall) " (brick wall)" else ""}")
            appendLine("Noise floor      : ${formatDbfs(s.noiseFloorDb)}")
            s.sourceGuess?.let { appendLine("Profile matches  : $it") }
            appendLine("Windows analysed : ${s.windowsAnalyzed} x ${s.fftSize}-point FFT")
            s.reasoning.forEach { appendLine("  - $it") }
            appendLine()
        }
        report.integrity?.let { i ->
            appendLine("INTEGRITY")
            appendLine("-".repeat(52))
            appendLine("Stored MD5       : ${if (i.md5Present) i.md5Expected else "none"}")
            appendLine("Decoded MD5      : ${i.md5Actual}")
            appendLine("Match            : ${if (!i.md5Verified) "not verified" else if (i.md5Matches) "yes" else "NO"}")
            appendLine("Frames decoded   : ${i.framesDecoded}")
            appendLine("CRC errors       : ${i.crcErrors}")
            appendLine()
        }
        report.dynamics?.let { d ->
            appendLine("LEVELS AND DYNAMICS")
            appendLine("-".repeat(52))
            appendLine("Peak             : ${formatDbfs(d.peakDbfs)}")
            appendLine("RMS              : ${formatDbfs(d.rmsDbfs)}")
            appendLine("Crest factor     : ${formatDb(d.crestFactorDb)}")
            d.dynamicRangeDb?.let { appendLine("Dynamic range    : DR${Math.round(it)}") }
            appendLine("Clipped samples  : ${d.clippedSamples} in ${d.clippedRuns} runs")
            appendLine("Real bit depth   : ${d.effectiveBitDepth} of ${d.declaredBitDepth} bits")
            appendLine()
        }
        report.encoder?.let { e ->
            appendLine("ENCODER")
            appendLine("-".repeat(52))
            appendLine("Vendor           : ${e.vendor ?: "not stated"}")
            appendLine("Compression      : ${e.compressionGuess ?: "unknown"}")
            appendLine("Metadata blocks  : ${e.blockTypes.joinToString(", ")}")
            appendLine("Seek table       : ${if (e.hasSeekTable) "${e.seekPoints} points" else "none"}")
            appendLine("Padding          : ${e.paddingBytes} bytes")
            appendLine()
        }
        if (report.tags.isNotEmpty()) {
            appendLine("TAGS")
            appendLine("-".repeat(52))
            report.tags.forEach { (k, v) -> appendLine("${k.padEnd(17)}: $v") }
        }
    }

    private fun percent(ratio: Double) = String.format(Locale.US, "%.0f %%", ratio * 100)

    private val csvColumns = listOf(
        "file", "verdict", "confidence", "bits", "sample_rate", "channels", "duration_s",
        "bitrate_kbps", "size_bytes", "cutoff_hz", "step_db", "brick_wall", "source_guess",
        "md5_present", "md5_match", "crc_errors", "peak_dbfs", "rms_dbfs", "dr", "clipped_samples",
        "effective_bits", "artist", "album", "title", "summary",
    )

    fun csv(reports: List<AnalysisReport>): String = buildString {
        appendLine(csvColumns.joinToString(","))
        reports.forEach { r ->
            val t = r.technical
            val s = r.spectral
            val i = r.integrity
            val d = r.dynamics
            val row = listOf(
                r.fileName,
                r.verdict.short,
                r.confidence.toString(),
                t?.bitsPerSample?.toString().orEmpty(),
                t?.sampleRate?.toString().orEmpty(),
                t?.channels?.toString().orEmpty(),
                t?.durationSeconds?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
                t?.bitrateKbps?.toString().orEmpty(),
                t?.fileSizeBytes?.toString().orEmpty(),
                s?.cutoffHz?.let { String.format(Locale.US, "%.0f", it) }.orEmpty(),
                s?.rolloffDropDb?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
                s?.hasBrickWall?.toString().orEmpty(),
                s?.sourceGuess.orEmpty(),
                i?.md5Present?.toString().orEmpty(),
                i?.let { if (it.md5Verified) it.md5Matches.toString() else "" }.orEmpty(),
                i?.crcErrors?.toString().orEmpty(),
                d?.peakDbfs?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
                d?.rmsDbfs?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
                d?.dynamicRangeDb?.let { Math.round(it).toString() }.orEmpty(),
                d?.clippedSamples?.toString().orEmpty(),
                d?.effectiveBitDepth?.toString().orEmpty(),
                r.artist.orEmpty(),
                r.album.orEmpty(),
                r.title.orEmpty(),
                r.summary,
            )
            appendLine(row.joinToString(",") { escapeCsv(it) })
        }
    }

    fun json(reports: List<AnalysisReport>): String {
        val array = JSONArray()
        reports.forEach { array.put(ReportJson.toJson(it)) }
        return array.toString(2)
    }

    private fun escapeCsv(value: String): String {
        val cleaned = value.replace("\r", " ").replace("\n", " ")
        return if (cleaned.any { it == ',' || it == '"' }) "\"" + cleaned.replace("\"", "\"\"") + "\"" else cleaned
    }

    fun shareText(context: Context, text: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Writes the payload into the app cache and shares it through the FileProvider. */
    fun shareFile(context: Context, fileName: String, content: String, mimeType: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
