package com.nextcraft.forge.ui

import android.os.SystemClock
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.delay

private const val BYTES_PER_MIB = 1_048_576.0

internal fun formatTransfer(downloadedBytes: Long?, totalBytes: Long?): String? {
    if (downloadedBytes == null || totalBytes == null || totalBytes <= 0L) return null
    return "%.1f / %.1f MB".format(downloadedBytes / BYTES_PER_MIB, totalBytes / BYTES_PER_MIB)
}

@Composable
internal fun RuntimeProgressPanel(
    snapshot: RuntimeSetupSnapshot,
    modifier: Modifier = Modifier,
) {
    if (snapshot.status == RuntimeSetupStatus.IDLE) return
    val isRunning = snapshot.status == RuntimeSetupStatus.RUNNING
    val hasError = snapshot.status == RuntimeSetupStatus.ERROR
    val clampedProgress = snapshot.progress.coerceIn(0f, 1f)
    val percentageInt = (clampedProgress * 100f).toInt()
    val transferSummary = formatTransfer(snapshot.downloadedBytes, snapshot.totalBytes)

    var prevCheckpoint by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var emaBytesPerSec by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(snapshot.downloadedBytes) {
        val currentBytes = snapshot.downloadedBytes
        if (currentBytes == null) {
            prevCheckpoint = null
            emaBytesPerSec = 0.0
            return@LaunchedEffect
        }
        val timestampMs = SystemClock.elapsedRealtime()
        val prev = prevCheckpoint
        if (prev == null || currentBytes < prev.first) {
            prevCheckpoint = currentBytes to timestampMs
        } else {
            val deltaMs = timestampMs - prev.second
            if (deltaMs >= 500L) {
                val sampleRate = (currentBytes - prev.first) / (deltaMs / 1000.0)
                emaBytesPerSec = if (emaBytesPerSec == 0.0) sampleRate else (emaBytesPerSec * 0.7) + (sampleRate * 0.3)
                prevCheckpoint = currentBytes to timestampMs
            }
        }
    }

    var stepElapsedSec by remember(snapshot.message) { mutableLongStateOf(0L) }
    LaunchedEffect(snapshot.message, isRunning) {
        val t0 = SystemClock.elapsedRealtime()
        while (isRunning) {
            stepElapsedSec = (SystemClock.elapsedRealtime() - t0) / 1000L
            delay(1000L)
        }
    }

    val palette = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = palette.surfaceVariant.copy(alpha = 0.4f),
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
            if (isRunning && snapshot.indeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = clampedProgress, modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val leftCaption = transferSummary
                    ?: if (isRunning) "%d:%02d".format(stepElapsedSec / 60L, stepElapsedSec % 60L) else ""
                Text(
                    text = leftCaption,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.onSurfaceVariant,
                )
                if (!snapshot.indeterminate || !isRunning) {
                    val rateSuffix = if (emaBytesPerSec > 0.0 && transferSummary != null) {
                        " · %.1f MB/s".format(emaBytesPerSec / BYTES_PER_MIB)
                    } else {
                        ""
                    }
                    val rightCaption = percentageInt.toString() + "%" + rateSuffix
                    Text(
                        text = rightCaption,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (hasError) {
                Text(
                    text = runtimeText(snapshot.errorMessage.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.error,
                    modifier = Modifier.forgeLive(),
                )
            }
            val tailLogLines = snapshot.logs.takeLast(3).filter(String::isNotBlank)
            if (tailLogLines.isNotEmpty()) {
                val localizedTail = buildString {
                    tailLogLines.forEachIndexed { idx, line ->
                        if (idx > 0) append('\n')
                        append(runtimeText(line))
                    }
                }
                Text(
                    text = localizedTail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = palette.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
