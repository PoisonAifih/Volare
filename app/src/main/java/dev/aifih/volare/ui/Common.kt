package dev.aifih.volare.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aifih.volare.data.RunStatus
import java.time.Duration
import java.time.Instant

fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

fun formatRelative(isoTimestamp: String?): String {
    val instant = isoTimestamp?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return "unknown time"

    val seconds = Duration.between(instant, Instant.now()).seconds

    return when {
        seconds < 0 -> "just now"
        seconds < 60 -> "just now"
        seconds < 3600 -> ago(seconds / 60, "minute")
        seconds < 86_400 -> ago(seconds / 3600, "hour")
        else -> ago(seconds / 86_400, "day")
    }
}

private fun ago(count: Long, unit: String): String =
    if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"

fun formatDuration(millis: Long?): String? {
    if (millis == null || millis <= 0) return null

    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

@Composable
fun StatusBadge(status: String?, modifier: Modifier = Modifier) {
    val label = status?.uppercase() ?: "UNKNOWN"

    val color = when (label) {
        RunStatus.RUNNING, RunStatus.CREATING -> MaterialTheme.colorScheme.primary
        RunStatus.FINISHED -> Color(0xFF2E7D32)
        RunStatus.ERROR, RunStatus.EXPIRED -> MaterialTheme.colorScheme.error
        RunStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.secondary
    }

    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
