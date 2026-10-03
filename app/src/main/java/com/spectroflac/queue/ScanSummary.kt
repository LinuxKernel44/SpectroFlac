package com.spectroflac.queue

import com.spectroflac.analysis.Verdict

/** The numbers behind the "scan complete" notification. No Android types, so it is unit-tested. */
data class ScanSummary(
    val genuine: Int = 0,
    val suspicious: Int = 0,
    val notGenuine: Int = 0,
    val damaged: Int = 0,
    val errors: Int = 0,
) {
    val total: Int get() = genuine + suspicious + notGenuine + damaged + errors

    /** "118 genuine, 4 not genuine, 1 damaged"; parts that are zero are left out. */
    fun text(): String {
        val parts = buildList {
            if (genuine > 0) add("$genuine genuine")
            if (suspicious > 0) add("$suspicious suspicious")
            if (notGenuine > 0) add("$notGenuine not genuine")
            if (damaged > 0) add("$damaged damaged")
            if (errors > 0) add("$errors ${if (errors == 1) "error" else "errors"}")
        }
        return if (parts.isEmpty()) "No file was analysed" else parts.joinToString(", ")
    }

    val title: String get() = if (total == 1) "Scan complete: 1 file" else "Scan complete: $total files"

    companion object {
        fun from(items: List<QueueItem>): ScanSummary {
            var genuine = 0; var suspicious = 0; var notGenuine = 0; var damaged = 0; var errors = 0
            for (item in items) {
                if (!item.isFinished) continue
                if (item.state == ItemState.FAILED) { errors++; continue }
                when (item.report?.verdict) {
                    Verdict.AUTHENTIC -> genuine++
                    Verdict.SUSPICIOUS -> suspicious++
                    Verdict.FAKE, Verdict.NOT_FLAC -> notGenuine++
                    Verdict.CORRUPT -> damaged++
                    Verdict.ERROR -> errors++
                    null -> Unit
                }
            }
            return ScanSummary(genuine, suspicious, notGenuine, damaged, errors)
        }
    }
}
