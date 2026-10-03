package com.spectroflac.util

import java.util.Locale
import java.util.concurrent.TimeUnit

fun formatDuration(seconds: Double): String {
    if (seconds <= 0) return "0:00"
    val total = seconds.toLong()
    val h = TimeUnit.SECONDS.toHours(total)
    val m = TimeUnit.SECONDS.toMinutes(total) % 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.US, "%.1f kB", bytes / 1e3)
    else -> "$bytes B"
}

fun formatHz(hz: Double): String {
    if (hz < 1000) return String.format(Locale.US, "%.0f Hz", hz)
    val khz = hz / 1000
    // 48 kHz rather than 48.00 kHz, but 22.05 kHz keeps its digits.
    return String.format(Locale.US, "%.2f", khz).trimEnd('0').trimEnd('.') + " kHz"
}

fun formatDb(db: Double): String = String.format(Locale.US, "%.1f dB", db)

fun formatDbfs(db: Double): String =
    if (db <= -190) "silence" else String.format(Locale.US, "%.1f dBFS", db)

fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

fun formatTimestamp(millis: Long): String {
    val date = java.util.Date(millis)
    return java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(date)
}

/** "about 4 min 20 s", "about 1 h 05 min", "a few seconds": a time left, rounded the way people read it. */
fun formatEta(millis: Long): String {
    val seconds = ((millis + 500) / 1000).coerceAtLeast(0)
    if (seconds < 10) return "a few seconds"
    val rounded = (seconds + 2) / 5 * 5
    return when {
        rounded < 60 -> "about $rounded s"
        rounded < 600 -> "about ${rounded / 60} min ${"%02d".format(Locale.US, rounded % 60)} s"
        rounded < 3600 -> "about ${rounded / 60} min"
        else -> "about ${rounded / 3600} h ${"%02d".format(Locale.US, rounded % 3600 / 60)} min"
    }
}
