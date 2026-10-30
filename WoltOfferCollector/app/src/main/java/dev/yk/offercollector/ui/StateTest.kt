package dev.yk.offercollector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yk.offercollector.core.StateMarkers
import dev.yk.offercollector.data.StateMarkerEventEntity
import kotlinx.serialization.json.Json

private val lenient = Json { ignoreUnknownKeys = true }

private fun markers(e: StateMarkerEventEntity): StateMarkers? = runCatching { lenient.decodeFromString<StateMarkers>(e.markersJson) }.getOrNull()

/** One short line describing the markers (no raw text — everything here is already redacted). */
private fun summary(m: StateMarkers): String = buildList {
    if (m.offer_card) add("כרטיס הצעה")
    if (m.home_screen) add("מסך בית")
    m.status_button?.let { add("כפתור סטטוס: $it") }
    if (m.availability_words.isNotEmpty()) add("זמינות: ${m.availability_words.joinToString()}")
    if (m.delivery_step_words.isNotEmpty()) add("משלוח: ${m.delivery_step_words.joinToString()}")
    m.notification_event?.let { add("אירוע: $it") }
    if (m.source == "NOTIFICATION") add(
        "פעילות: " + m.active_notifications.joinToString { n ->
            "${n.channel}${if (n.foreground_service) " [FG]" else ""}${n.title_redacted?.let { " $it" } ?: ""}"
        }.ifEmpty { "אין" },
    )
    m.system_event?.let { add(it) }
}.joinToString(" · ")

/** What changed vs the previous event of the same source. */
private fun diff(prev: StateMarkers?, cur: StateMarkers): String? {
    if (prev == null) return null
    val out = mutableListOf<String>()
    if (prev.status_button != cur.status_button) out += "כפתור: ${prev.status_button} → ${cur.status_button}"
    if (prev.home_screen != cur.home_screen) out += "מסך בית: ${prev.home_screen} → ${cur.home_screen}"
    if (prev.offer_card != cur.offer_card) out += "כרטיס הצעה: ${prev.offer_card} → ${cur.offer_card}"
    val pc = prev.active_notifications.map { "${it.channel}${if (it.foreground_service) "[FG]" else ""}" }.toSet()
    val cc = cur.active_notifications.map { "${it.channel}${if (it.foreground_service) "[FG]" else ""}" }.toSet()
    (cc - pc).forEach { out += "+התראה $it" }
    (pc - cc).forEach { out += "−התראה $it" }
    val added = cur.view_ids.toSet() - prev.view_ids.toSet()
    val removed = prev.view_ids.toSet() - cur.view_ids.toSet()
    if (added.isNotEmpty()) out += "+רכיבים: ${added.take(4).joinToString()}${if (added.size > 4) "…" else ""}"
    if (removed.isNotEmpty()) out += "−רכיבים: ${removed.take(4).joinToString()}${if (removed.size > 4) "…" else ""}"
    return out.joinToString(" · ").ifEmpty { null }
}

@Composable
private fun EvidenceChip(evidence: String) {
    val (bg, label) = when (evidence) {
        "OBSERVED" -> Color(0xFF1E9E5A) to "נצפה"
        "ESTIMATED" -> Color(0xFFE08A00) to "משוער"
        else -> MaterialTheme.colorScheme.outline to "לא ידוע"
    }
    Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** Test A/B controls + marker timeline. Use only while parked / at home. */
@Composable
fun StateTestCard(vm: MainViewModel) {
    val sessions by vm.testSessions.collectAsStateWithLifecycle()
    val events by vm.stateEvents.collectAsStateWithLifecycle()
    val open = sessions.firstOrNull { it.endedAtUtc == null }
    val shown = (if (open != null) events.filter { it.testSessionId == open.id } else events).take(40)

    AppCard {
        CardTitle("בדיקת מצבי שליח (A/B)")
        Text(
            "רק כשחונים או בבית. האפליקציה רק רושמת שינויים בסמנים — לא לוחצת ולא מסיקה קבלה או סיום מהיעלמות מסך או התראה.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (open == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.startTestSession("A: Online/Offline") }) { Text("התחל בדיקה A") }
                FilledTonalButton(onClick = { vm.startTestSession("B: משלוח אמיתי") }) { Text("התחל בדיקה B") }
            }
        } else {
            Text("בדיקה פעילה: ${open.label} · מ-${localTime(open.startedAtUtc)}", fontWeight = FontWeight.Medium)
            OutlinedButton(onClick = { vm.endTestSession() }) { Text("סיום הבדיקה") }
        }
        if (sessions.isNotEmpty()) {
            Text("בדיקות: " + sessions.take(3).joinToString(" · ") { "${it.label} (${localTime(it.startedAtUtc)}${it.endedAtUtc?.let { e -> "–" + localTime(e).takeLast(8) } ?: ", פעילה"})" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (open != null) "סמנים בבדיקה הנוכחית (${shown.size})" else "שינויי סמנים אחרונים (${shown.size})", fontWeight = FontWeight.Medium)
        if (shown.isEmpty()) Text("אין עדיין שינויים.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        shown.forEachIndexed { i, e ->
            val cur = markers(e) ?: return@forEachIndexed
            val prev = shown.drop(i + 1).firstOrNull { it.source == e.source }?.let(::markers)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EvidenceChip(e.evidence)
                    Text(e.candidateState, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(localTime(e.observedAtUtc).takeLast(8), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Ltr(e.rule)
                Ltr(summary(cur))
                diff(prev, cur)?.let { Ltr("Δ $it") }
            }
        }
    }
}
