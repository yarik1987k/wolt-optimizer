package dev.yk.offercollector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val localFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault())

/** UTC is stored; local time is for display only. */
fun localTime(ms: Long): String = localFmt.format(Instant.ofEpochMilli(ms))

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    AppCard {
        CardTitle(title)
        content()
    }
}

/** Status line with an explicit word, never color alone. */
@Composable
fun StatusRow(label: String, ok: Boolean, okText: String = "פעיל", badText: String = "לא פעיל") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (ok) "✓ $okText" else "✗ $badText",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun Ltr(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
}
