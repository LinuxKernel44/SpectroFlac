package com.spectroflac.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectroflac.analysis.Verdict
import com.spectroflac.queue.ItemState
import com.spectroflac.queue.QueueItem
import com.spectroflac.queue.QueueSnapshot
import com.spectroflac.queue.QueueStats
import com.spectroflac.settings.QueueFilter
import com.spectroflac.settings.QueueSort
import com.spectroflac.ui.components.FlatPanel
import com.spectroflac.ui.components.GlassButton
import com.spectroflac.ui.components.GlassProgress
import com.spectroflac.ui.components.SlimProgress
import com.spectroflac.ui.components.ToggleChip
import com.spectroflac.ui.components.color
import com.spectroflac.ui.glass.GlassPanel
import com.spectroflac.ui.theme.SpectroColors
import com.spectroflac.util.formatBytes
import com.spectroflac.util.formatEta
import java.util.Locale

/** Test tags, so UI tests can find the parts of the queue. */
object QueueTags {
    const val OVERVIEW = "queue-overview"
    const val GLOBAL_PROGRESS = "queue-global-progress"
    const val ETA = "queue-eta"
    const val PAUSE_RESUME = "queue-pause-resume"
    const val CANCEL_ALL = "queue-cancel-all"
    fun running(name: String) = "queue-running-$name"
    fun waiting(name: String) = "queue-waiting-$name"
    fun finished(name: String) = "queue-finished-$name"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueueScreen(
    snapshot: QueueSnapshot,
    progress: State<Map<Long, Float>>,
    stats: QueueStats,
    filter: QueueFilter,
    sort: QueueSort,
    listing: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancelAll: () -> Unit,
    onCancel: (Long) -> Unit,
    onMoveToTop: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onRetryFailed: () -> Unit,
    onClearFinished: () -> Unit,
    onOpen: (QueueItem) -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onFilter: (QueueFilter) -> Unit,
    onSort: (QueueSort) -> Unit,
) {
    val running = remember(snapshot.items) { snapshot.items.filter { it.state == ItemState.RUNNING } }
    val waiting = remember(snapshot.items) { snapshot.items.filter { it.state == ItemState.PENDING } }
    val finished = remember(snapshot.items, filter, sort) { visibleFinished(snapshot.items, filter, sort) }
    val finishedCount = remember(snapshot.items) { snapshot.items.count { it.isFinished } }
    val counts = remember(snapshot.items) { snapshot.items.filter { it.report != null && it.state != ItemState.FAILED }.groupingBy { it.report!!.verdict }.eachCount() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Scan queue", style = MaterialTheme.typography.headlineMedium, color = SpectroColors.TextPrimary)
                    Text(
                        text = headerSubtitle(snapshot, stats, listing),
                        style = MaterialTheme.typography.bodySmall,
                        color = SpectroColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GlassIconButton(Icons.Filled.Settings, "Settings", onSettings)
            }
        }

        item { Overview(snapshot, stats, listing, onPause, onResume, onCancelAll, onRetryFailed, onClearFinished) }

        if (counts.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(Verdict.AUTHENTIC, Verdict.SUSPICIOUS, Verdict.FAKE, Verdict.CORRUPT).forEach { verdict ->
                        val n = counts[verdict] ?: 0
                        if (n > 0) CountPill(verdict, n, Modifier.weight(1f))
                    }
                }
            }
        }

        if (running.isNotEmpty()) {
            item { SectionLabel("Analysing now", running.size) }
            items(running, key = { "run-${it.id}" }) { item -> RunningCard(item, progress, onCancel) }
        }

        if (waiting.isNotEmpty()) {
            item { SectionLabel("Waiting", waiting.size) }
            items(waiting, key = { "wait-${it.id}" }) { item -> WaitingRow(item, onMoveToTop, onCancel) }
        }

        if (finishedCount > 0) {
            item { SectionLabel("Finished", finishedCount) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QueueFilter.entries.forEach { f -> ToggleChip(f.label, filter == f, { onFilter(f) }) }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sort", style = MaterialTheme.typography.labelSmall, color = SpectroColors.TextTertiary, modifier = Modifier.padding(top = 9.dp, end = 4.dp))
                    QueueSort.entries.forEach { s -> ToggleChip(s.label, sort == s, { onSort(s) }, accent = SpectroColors.BackdropMagenta) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("CSV", onExportCsv, icon = Icons.Filled.Description, modifier = Modifier.weight(1f))
                    GlassButton("JSON", onExportJson, icon = Icons.Filled.DataObject, accent = SpectroColors.BackdropMagenta, modifier = Modifier.weight(1f))
                }
            }
            items(finished, key = { "done-${it.id}" }) { item -> FinishedRow(item, onOpen, onRetry) }
            if (finished.isEmpty()) {
                item { Text("No finished file matches this filter.", style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextTertiary) }
            }
        }

        if (snapshot.items.isEmpty() && !listing) {
            item {
                FlatPanel(Modifier.fillMaxWidth()) {
                    Text(
                        "The queue is empty. Scan a folder or pick several files from the home screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SpectroColors.TextSecondary,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

private fun headerSubtitle(snapshot: QueueSnapshot, stats: QueueStats, listing: Boolean): String = when {
    listing -> "Looking for .flac files… ${stats.total} found"
    stats.total == 0 -> "Nothing queued"
    snapshot.isActive -> "${stats.completed} of ${stats.total} files"
    else -> "${stats.total} file${if (stats.total == 1) "" else "s"} analysed"
}

private fun visibleFinished(items: List<QueueItem>, filter: QueueFilter, sort: QueueSort): List<QueueItem> {
    val done = items.filter { it.isFinished }.filter { item ->
        val verdict = item.report?.verdict
        when (filter) {
            QueueFilter.ALL -> true
            QueueFilter.GENUINE -> item.state != ItemState.FAILED && verdict == Verdict.AUTHENTIC
            QueueFilter.SUSPICIOUS -> item.state != ItemState.FAILED && verdict == Verdict.SUSPICIOUS
            QueueFilter.FAKE -> item.state != ItemState.FAILED && (verdict == Verdict.FAKE || verdict == Verdict.NOT_FLAC)
            QueueFilter.DAMAGED -> item.state != ItemState.FAILED && verdict == Verdict.CORRUPT
            QueueFilter.ERRORS -> item.state == ItemState.FAILED
        }
    }
    return when (sort) {
        QueueSort.PROCESSING_ORDER -> done.sortedByDescending { it.completedSeq }
        QueueSort.NAME -> done.sortedBy { it.name.lowercase() }
        QueueSort.VERDICT -> done.sortedWith(compareBy({ severityRank(it) }, { it.name.lowercase() }))
    }
}

private fun severityRank(item: QueueItem): Int = when {
    item.state == ItemState.FAILED -> 0
    else -> when (item.report?.verdict) {
        Verdict.CORRUPT -> 1
        Verdict.FAKE -> 2
        Verdict.NOT_FLAC -> 3
        Verdict.SUSPICIOUS -> 4
        Verdict.ERROR -> 5
        Verdict.AUTHENTIC -> 6
        null -> 7
    }
}

@Composable
private fun Overview(
    snapshot: QueueSnapshot,
    stats: QueueStats,
    listing: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancelAll: () -> Unit,
    onRetryFailed: () -> Unit,
    onClearFinished: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(QueueTags.OVERVIEW),
        cornerRadius = 26.dp,
        refraction = 18.dp,
        tint = 0.10f,
        glow = 0.05f,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format(Locale.US, "%d / %d", stats.completed, stats.total),
                    style = MaterialTheme.typography.displaySmall,
                    color = SpectroColors.TextPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("files", style = MaterialTheme.typography.bodyMedium, color = SpectroColors.TextTertiary, modifier = Modifier.padding(bottom = 5.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    text = String.format(Locale.US, "%d %%", (stats.fraction * 100).toInt()),
                    style = MaterialTheme.typography.titleMedium,
                    color = SpectroColors.BackdropCyan,
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .testTag(QueueTags.GLOBAL_PROGRESS)
                    .semantics {
                        contentDescription = "Overall progress"
                        stateDescription = String.format(Locale.US, "%d percent", (stats.fraction * 100).toInt())
                    },
            ) { GlassProgress(stats.fraction) }
            Spacer(Modifier.height(12.dp))

            val state = overviewState(snapshot, stats, listing)
            Text(
                text = state,
                modifier = Modifier.testTag(QueueTags.ETA),
                style = MaterialTheme.typography.bodyMedium,
                color = if (snapshot.isHeld) SpectroColors.Suspicious else SpectroColors.TextSecondary,
            )
            val details = buildList {
                if (snapshot.isActive) add("${snapshot.parallelism.coerceAtLeast(0)} at a time")
                stats.bytesPerSecond?.let { add(String.format(Locale.US, "%.1f MB/s", it / 1_000_000.0)) }
                if (stats.totalBytes > 0) add("${formatBytes(stats.processedBytes)} of ${formatBytes(stats.totalBytes)}")
            }
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(details.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = SpectroColors.TextTertiary)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (snapshot.isActive || snapshot.paused) {
                    if (snapshot.paused) {
                        GlassButton("Resume", onResume, icon = Icons.Filled.PlayArrow, prominent = true, modifier = Modifier.weight(1f).testTag(QueueTags.PAUSE_RESUME))
                    } else {
                        GlassButton("Pause", onPause, icon = Icons.Filled.Pause, modifier = Modifier.weight(1f).testTag(QueueTags.PAUSE_RESUME))
                    }
                    GlassButton("Cancel all", onCancelAll, icon = Icons.Filled.Close, accent = SpectroColors.Fake, modifier = Modifier.weight(1f).testTag(QueueTags.CANCEL_ALL))
                }
            }
            val failed = stats.failed
            val hasFinished = stats.completed > 0
            if (failed > 0 || (hasFinished && !snapshot.isActive)) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (failed > 0) {
                        GlassButton("Retry $failed failed", onRetryFailed, icon = Icons.Filled.Refresh, accent = SpectroColors.Suspicious, modifier = Modifier.weight(1f))
                    }
                    if (hasFinished) {
                        GlassButton("Clear finished", onClearFinished, icon = Icons.Filled.DeleteSweep, accent = SpectroColors.BackdropViolet, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun overviewState(snapshot: QueueSnapshot, stats: QueueStats, listing: Boolean): String = when {
    snapshot.holdReason != null && snapshot.isActive -> "Waiting: ${snapshot.holdReason}"
    snapshot.paused && snapshot.isActive -> "Paused"
    listing && !snapshot.isActive -> "Looking for files…"
    snapshot.isActive -> stats.etaMillis?.let { formatEta(it) + " left" } ?: "Estimating time left…"
    stats.total > 0 -> "All done"
    else -> "Nothing to do"
}

@Composable
private fun SectionLabel(title: String, count: Int) {
    Text(
        text = "${title.uppercase()}  ·  $count",
        style = MaterialTheme.typography.labelSmall,
        color = SpectroColors.BackdropCyan,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun RunningCard(item: QueueItem, progress: State<Map<Long, Float>>, onCancel: (Long) -> Unit) {
    val fraction = progress.value[item.id] ?: 0f
    FlatPanel(
        Modifier
            .fillMaxWidth()
            .testTag(QueueTags.running(item.name))
            .semantics {
                contentDescription = "${item.name} is being analysed"
                stateDescription = String.format(Locale.US, "%d percent", (fraction * 100).toInt())
            },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, color = SpectroColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        formatBytes(item.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = SpectroColors.TextTertiary,
                    )
                }
                Text(
                    String.format(Locale.US, "%d %%", (fraction * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = SpectroColors.BackdropCyan,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Close, "Stop analysing ${item.name}", tint = SpectroColors.TextTertiary,
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(50)).clickable { onCancel(item.id) }.padding(7.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            SlimProgress(fraction)
        }
    }
}

@Composable
private fun WaitingRow(item: QueueItem, onMoveToTop: (Long) -> Unit, onCancel: (Long) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    FlatPanel(Modifier.fillMaxWidth().testTag(QueueTags.waiting(item.name))) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, color = SpectroColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatBytes(item.sizeBytes), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = SpectroColors.TextTertiary)
            }
            Box {
                Icon(
                    Icons.Filled.MoreVert, "Options for ${item.name}", tint = SpectroColors.TextTertiary,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable { menu = true }.padding(8.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Analyse next") }, onClick = { menu = false; onMoveToTop(item.id) })
                    DropdownMenuItem(text = { Text("Remove from queue") }, onClick = { menu = false; onCancel(item.id) })
                }
            }
        }
    }
}

@Composable
private fun FinishedRow(item: QueueItem, onOpen: (QueueItem) -> Unit, onRetry: (Long) -> Unit) {
    val failed = item.state == ItemState.FAILED
    val verdict = item.report?.verdict
    val accent = if (failed) SpectroColors.Damaged else verdict?.color() ?: SpectroColors.Neutral
    FlatPanel(
        Modifier
            .fillMaxWidth()
            .testTag(QueueTags.finished(item.name))
            .clickable(enabled = !failed) { onOpen(item) },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.75f)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, color = SpectroColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = when {
                    failed -> item.error ?: "Could not be analysed"
                    item.state == ItemState.SKIPPED -> "Already analysed · " + (item.report?.headline ?: "")
                    else -> item.report?.headline ?: ""
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = SpectroColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            if (failed) {
                Text(
                    "RETRY", style = MaterialTheme.typography.labelSmall, color = SpectroColors.Suspicious,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onRetry(item.id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            } else {
                Text(verdict?.short ?: "", style = MaterialTheme.typography.labelSmall, color = accent)
            }
        }
    }
}

@Composable
private fun CountPill(verdict: Verdict, count: Int, modifier: Modifier = Modifier) {
    FlatPanel(modifier, cornerRadius = 16.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = verdict.color())
            Text(verdict.short, style = MaterialTheme.typography.labelSmall, color = SpectroColors.TextTertiary, fontSize = 9.sp)
        }
    }
}
