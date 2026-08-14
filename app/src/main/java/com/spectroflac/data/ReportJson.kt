package com.spectroflac.data

import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.DynamicsInfo
import com.spectroflac.analysis.EncoderInfo
import com.spectroflac.analysis.Finding
import com.spectroflac.analysis.IntegrityInfo
import com.spectroflac.analysis.Severity
import com.spectroflac.analysis.SpectralInfo
import com.spectroflac.analysis.TechnicalInfo
import com.spectroflac.analysis.Verdict
import com.spectroflac.flac.ContainerKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises a report so it can be stored in the history database and exported as JSON.
 * The cover art and the spectrogram are deliberately left out: they are large, and the history
 * screen offers a re-analysis instead.
 */
object ReportJson {

    fun toJson(report: AnalysisReport): JSONObject = JSONObject().apply {
        put("fileName", report.fileName)
        put("uri", report.uri)
        put("container", report.container.name)
        put("verdict", report.verdict.name)
        put("confidence", report.confidence)
        put("headline", report.headline)
        put("summary", report.summary)
        put("analysedAt", report.analysedAtMillis)
        put("analysisMillis", report.analysisDurationMillis)
        report.errorMessage?.let { put("error", it) }

        put("findings", JSONArray().also { array ->
            report.findings.forEach { f ->
                array.put(
                    JSONObject()
                        .put("severity", f.severity.name)
                        .put("title", f.title)
                        .put("detail", f.detail),
                )
            }
        })

        report.technical?.let { t ->
            put("technical", JSONObject().apply {
                put("bitsPerSample", t.bitsPerSample)
                put("sampleRate", t.sampleRate)
                put("channels", t.channels)
                put("channelLayout", t.channelLayout)
                put("durationSeconds", t.durationSeconds)
                put("totalSamples", t.totalSamples)
                put("fileSizeBytes", t.fileSizeBytes)
                put("bitrateKbps", t.bitrateKbps)
                put("uncompressedBitrateKbps", t.uncompressedBitrateKbps)
                put("compressionRatio", t.compressionRatio)
                put("minBlockSize", t.minBlockSize)
                put("maxBlockSize", t.maxBlockSize)
                put("minFrameSize", t.minFrameSize)
                put("maxFrameSize", t.maxFrameSize)
                put("fixedBlockSize", t.fixedBlockSize)
            })
        }
        report.encoder?.let { e ->
            put("encoder", JSONObject().apply {
                put("vendor", e.vendor ?: JSONObject.NULL)
                put("compressionGuess", e.compressionGuess ?: JSONObject.NULL)
                put("hasSeekTable", e.hasSeekTable)
                put("seekPoints", e.seekPoints)
                put("paddingBytes", e.paddingBytes)
                put("hasCueSheet", e.hasCueSheet)
                put("metadataBytes", e.metadataBytes)
                put("blockTypes", JSONArray(e.blockTypes))
                put("applicationIds", JSONArray(e.applicationIds))
            })
        }
        report.spectral?.let { s ->
            put("spectral", JSONObject().apply {
                put("cutoffHz", s.cutoffHz)
                put("nyquistHz", s.nyquistHz)
                put("cutoffRatio", s.cutoffRatio)
                put("rolloffDropDb", s.rolloffDropDb)
                put("hasBrickWall", s.hasBrickWall)
                put("noiseFloorDb", s.noiseFloorDb)
                put("peakSpectrumDb", s.peakSpectrumDb)
                put("sourceGuess", s.sourceGuess ?: JSONObject.NULL)
                put("windowsAnalyzed", s.windowsAnalyzed)
                put("fftSize", s.fftSize)
                put("reasoning", JSONArray(s.reasoning))
            })
        }
        report.integrity?.let { i ->
            put("integrity", JSONObject().apply {
                put("md5Expected", i.md5Expected)
                put("md5Actual", i.md5Actual)
                put("md5Present", i.md5Present)
                put("md5Verified", i.md5Verified)
                put("md5Matches", i.md5Matches)
                put("crcErrors", i.crcErrors)
                put("truncated", i.truncated)
                put("framesDecoded", i.framesDecoded)
                put("samplesDecoded", i.samplesDecoded)
                put("declaredSamples", i.declaredSamples)
                put("decodeError", i.decodeError ?: JSONObject.NULL)
            })
        }
        report.dynamics?.let { d ->
            put("dynamics", JSONObject().apply {
                put("peakDbfs", d.peakDbfs)
                put("rmsDbfs", d.rmsDbfs)
                put("crestFactorDb", d.crestFactorDb)
                put("dynamicRangeDb", d.dynamicRangeDb ?: JSONObject.NULL)
                put("clippedSamples", d.clippedSamples)
                put("clippedRuns", d.clippedRuns)
                put("silentRatio", d.silentRatio)
                put("declaredBitDepth", d.declaredBitDepth)
                put("effectiveBitDepth", d.effectiveBitDepth)
                put("unusedLowBits", d.unusedLowBits)
                put("dualMono", d.dualMono)
            })
        }
        put("tags", JSONObject().also { tags -> report.tags.forEach { (k, v) -> tags.put(k, v) } })
    }

    fun fromJson(json: JSONObject): AnalysisReport {
        val findings = json.optJSONArray("findings")?.let { array ->
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Finding(
                    severity = runCatching { Severity.valueOf(o.getString("severity")) }.getOrDefault(Severity.INFO),
                    title = o.optString("title"),
                    detail = o.optString("detail"),
                )
            }
        }.orEmpty()

        val technical = json.optJSONObject("technical")?.let { t ->
            TechnicalInfo(
                bitsPerSample = t.optInt("bitsPerSample"),
                sampleRate = t.optInt("sampleRate"),
                channels = t.optInt("channels"),
                channelLayout = t.optString("channelLayout"),
                durationSeconds = t.optDouble("durationSeconds", 0.0),
                totalSamples = t.optLong("totalSamples"),
                fileSizeBytes = t.optLong("fileSizeBytes"),
                bitrateKbps = t.optInt("bitrateKbps"),
                uncompressedBitrateKbps = t.optInt("uncompressedBitrateKbps"),
                compressionRatio = t.optDouble("compressionRatio", 0.0),
                minBlockSize = t.optInt("minBlockSize"),
                maxBlockSize = t.optInt("maxBlockSize"),
                minFrameSize = t.optInt("minFrameSize"),
                maxFrameSize = t.optInt("maxFrameSize"),
                fixedBlockSize = t.optBoolean("fixedBlockSize", true),
            )
        }
        val encoder = json.optJSONObject("encoder")?.let { e ->
            EncoderInfo(
                vendor = e.optStringOrNull("vendor"),
                compressionGuess = e.optStringOrNull("compressionGuess"),
                hasSeekTable = e.optBoolean("hasSeekTable"),
                seekPoints = e.optInt("seekPoints"),
                paddingBytes = e.optInt("paddingBytes"),
                hasCueSheet = e.optBoolean("hasCueSheet"),
                metadataBytes = e.optLong("metadataBytes"),
                blockTypes = e.optJSONArray("blockTypes").toStringList(),
                applicationIds = e.optJSONArray("applicationIds").toStringList(),
            )
        }
        val spectral = json.optJSONObject("spectral")?.let { s ->
            SpectralInfo(
                cutoffHz = s.optDouble("cutoffHz", 0.0),
                nyquistHz = s.optDouble("nyquistHz", 0.0),
                cutoffRatio = s.optDouble("cutoffRatio", 0.0),
                rolloffDropDb = s.optDouble("rolloffDropDb", 0.0),
                hasBrickWall = s.optBoolean("hasBrickWall"),
                noiseFloorDb = s.optDouble("noiseFloorDb", 0.0),
                peakSpectrumDb = s.optDouble("peakSpectrumDb", 0.0),
                sourceGuess = s.optStringOrNull("sourceGuess"),
                windowsAnalyzed = s.optInt("windowsAnalyzed"),
                fftSize = s.optInt("fftSize"),
                reasoning = s.optJSONArray("reasoning").toStringList(),
            )
        }
        val integrity = json.optJSONObject("integrity")?.let { i ->
            IntegrityInfo(
                md5Expected = i.optString("md5Expected"),
                md5Actual = i.optString("md5Actual"),
                md5Present = i.optBoolean("md5Present"),
                md5Verified = i.optBoolean("md5Verified"),
                md5Matches = i.optBoolean("md5Matches"),
                crcErrors = i.optInt("crcErrors"),
                truncated = i.optBoolean("truncated"),
                framesDecoded = i.optLong("framesDecoded"),
                samplesDecoded = i.optLong("samplesDecoded"),
                declaredSamples = i.optLong("declaredSamples"),
                decodeError = i.optStringOrNull("decodeError"),
            )
        }
        val dynamics = json.optJSONObject("dynamics")?.let { d ->
            DynamicsInfo(
                peakDbfs = d.optDouble("peakDbfs", 0.0),
                rmsDbfs = d.optDouble("rmsDbfs", 0.0),
                crestFactorDb = d.optDouble("crestFactorDb", 0.0),
                dynamicRangeDb = if (d.isNull("dynamicRangeDb")) null else d.optDouble("dynamicRangeDb"),
                clippedSamples = d.optLong("clippedSamples"),
                clippedRuns = d.optLong("clippedRuns"),
                silentRatio = d.optDouble("silentRatio", 0.0),
                declaredBitDepth = d.optInt("declaredBitDepth"),
                effectiveBitDepth = d.optInt("effectiveBitDepth"),
                unusedLowBits = d.optInt("unusedLowBits"),
                dualMono = d.optBoolean("dualMono"),
            )
        }
        val tags = LinkedHashMap<String, String>()
        json.optJSONObject("tags")?.let { t ->
            t.keys().forEach { key -> tags[key] = t.optString(key) }
        }

        return AnalysisReport(
            fileName = json.optString("fileName"),
            uri = json.optString("uri"),
            container = runCatching { ContainerKind.valueOf(json.optString("container")) }
                .getOrDefault(ContainerKind.UNKNOWN),
            verdict = runCatching { Verdict.valueOf(json.optString("verdict")) }.getOrDefault(Verdict.ERROR),
            confidence = json.optInt("confidence"),
            headline = json.optString("headline"),
            summary = json.optString("summary"),
            findings = findings,
            technical = technical,
            encoder = encoder,
            spectral = spectral,
            integrity = integrity,
            dynamics = dynamics,
            tags = tags,
            coverBytes = null,
            spectrogram = null,
            analysedAtMillis = json.optLong("analysedAt"),
            analysisDurationMillis = json.optLong("analysisMillis"),
            errorMessage = json.optStringOrNull("error"),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }
}
