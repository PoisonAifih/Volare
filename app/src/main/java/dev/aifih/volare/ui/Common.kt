package dev.aifih.volare.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aifih.volare.data.CursorApiException
import dev.aifih.volare.data.RunStatus
import java.io.IOException
import java.time.Duration
import java.time.Instant

private val RETRYABLE_PHRASES = listOf(
    "unable to resolve host",
    "no address associated with hostname",
    "failed to connect",
    "connection reset",
    "connection refused",
    "network is unreachable",
    "timeout",
    "timed out",
    "stream connection was lost",
    "resource_exhausted",
    "could not reach",
    "software caused connection abort",
)

fun Throwable.isRetryableNetworkFailure(): Boolean {
    if (this is CursorApiException && isAgentBusy) return false
    if (this is IOException) return true
    return message.isRetryableNetworkFailure()
}

fun String?.isRetryableNetworkFailure(): Boolean {
    if (this == null) return false
    val lower = lowercase()
    return RETRYABLE_PHRASES.any { lower.contains(it) }
}

fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

fun Context.copyText(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
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
fun RetryableError(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
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
