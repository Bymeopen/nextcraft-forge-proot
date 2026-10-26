package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.runtime.RuntimeSetupSnapshot
import com.nextcraft.forge.runtime.RuntimeSetupStatus

private const val MIB = 1_048_576.0

/** "12.3 / 71.9 MB" when both sizes are known, otherwise null. */
internal fun formatTransfer(downloadedBytes: Long?, totalBytes: Long?): String? {
    if (downloadedBytes == null || totalBytes == null || totalBytes <= 0L) return null
    return "%.1f / %.1f MB".format(downloadedBytes / MIB, totalBytes / MIB)
}

/**
 * Shared presentation for runtime download / install / uninstall progress: current step, percentage,
 * transferred size and the latest terminal lines, so a long install never looks frozen.
 */
@Composable
internal fun RuntimeProgressPanel(snapshot: RuntimeSetupSnapshot, modifier: Modifier = Modifier) {
    if (snapshot.status == RuntimeSetupStatus.IDLE) return
    val running = snapshot.status == RuntimeSetupStatus.RUNNING
    val failed = snapshot.status == RuntimeSetupStatus.ERROR
    val percent = (snapshot.progress.coerceIn(0f, 1f) * 100).toInt()
    val transfer = formatTransfer(snapshot.downloadedBytes, snapshot.totalBytes)
    // Smoothed download speed from the byte counter between updates.
    var lastSample by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var bytesPerSecond by remember { mutableStateOf(0.0) }
    LaunchedEffect(snapshot.downloadedBytes) {
        val bytes = snapshot.downloadedBytes
        if (bytes == null) {
            lastSample = null
            bytesPerSecond = 0.0
            return@LaunchedEffect
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastSample
        if (previous == null || bytes < previous.first) {
            lastSample = bytes to now
        } else if (now - previous.second >= 500L) {
            val instant = (bytes - previous.first) / ((now - previous.second) / 1000.0)
            bytesPerSecond = if (bytesPerSecond == 0.0) instant else bytesPerSecond * 0.7 + instant * 0.3
            lastSample = bytes to now
        }
    }

    // Steps without a byte counter (apt/SDK installs inside the chroot) show elapsed time instead of a speed.
    var elapsedSeconds by remember(snapshot.message) { mutableStateOf(0L) }
    LaunchedEffect(snapshot.message, running) {
        val started = android.os.SystemClock.elapsedRealtime()
        while (running) {
            elapsedSeconds = (android.os.SystemClock.elapsedRealtime() - started) / 1000
            kotlinx.coroutines.delay(1000)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = runtimeText(snapshot.message),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (running && snapshot.indeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = snapshot.progress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(transfer ?: if (running) "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60) else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!snapshot.indeterminate || !running) {
                    val speed = if (bytesPerSecond > 0.0 && transfer != null) " · %.1f MB/s".format(bytesPerSecond / MIB) else ""
                    Text(
                        text = "%d%%".format(percent) + speed,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (failed) {
                Text(
                    text = runtimeText(snapshot.errorMessage.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.forgeLive(),
                )
            }
            val recentLines = snapshot.logs.takeLast(3).filter { it.isNotBlank() }
            if (recentLines.isNotEmpty()) {
                Text(
                    text = recentLines.map { runtimeText(it) }.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
