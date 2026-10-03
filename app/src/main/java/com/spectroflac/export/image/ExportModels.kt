package com.spectroflac.export.image

import android.graphics.Bitmap
import android.net.Uri
import java.io.File

/** Which audio the spectrogram is made from. [id]: -1 the mono mix, -2 the side channel, 0.. a single channel. */
data class ExportChannel(val id: Int, val label: String) {
    companion object {
        const val MIX = -1
        const val SIDE = -2

        /** The same names the in-app spectrogram uses. */
        fun forChannelCount(channels: Int): List<ExportChannel> = when {
            channels <= 1 -> listOf(ExportChannel(MIX, "Mono"))
            channels == 2 -> listOf(
                ExportChannel(MIX, "Mid"), ExportChannel(0, "Left"), ExportChannel(1, "Right"), ExportChannel(SIDE, "Side"),
            )
            else -> listOf(ExportChannel(MIX, "Mix")) + (0 until channels).map { ExportChannel(it, "Ch ${it + 1}") }
        }
    }
}

/** What the header and the overlays say about the file; filled from the analysis report and the file's tags. */
data class ExportSubject(
    val fileName: String,
    val title: String?,
    val artist: String?,
    /** "16 bit · 44.1 kHz · stereo · 3:54", or null when unknown. */
    val formatLine: String?,
    val verdictLabel: String?,
    val verdictColor: Int,
    val confidence: Int,
    /** The measured cutoff to mark, when the analysis found a brick wall. */
    val cutoffHz: Double?,
)

data class ExportRequest(
    val uri: Uri,
    val plot: PlotSize,
    val channel: ExportChannel,
    val content: ExportContent,
    val subject: ExportSubject,
)

enum class ExportPhase(val label: String) {
    DECODING("Decoding the audio"),
    ANALYSING("Analysing the spectrum"),
    WRITING("Writing the image"),
}

data class ExportProgress(val phase: ExportPhase, val fraction: Float)

data class ExportResult(
    val width: Int,
    val height: Int,
    val bytes: Long,
    val seconds: Double,
    val fftSize: Int,
    val partial: Boolean,
)

/** Where the finished image goes. */
sealed interface ExportTarget {
    /** A document the user picked ("Save as…"). */
    data class Document(val uri: Uri, val displayName: String) : ExportTarget

    /** A temporary file, shared afterwards through the share sheet. */
    data class Share(val file: File, val displayName: String) : ExportTarget
}

/** What the header draws besides text: a small cover, decoded off the main thread. */
class HeaderArt(val cover: Bitmap?)
