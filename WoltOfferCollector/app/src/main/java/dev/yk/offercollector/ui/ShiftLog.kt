package dev.yk.offercollector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yk.offercollector.core.DeliveryEventDetector
import dev.yk.offercollector.core.StateMarkers
import dev.yk.offercollector.core.formatAgorot
import dev.yk.offercollector.data.StateMarkerEventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }
private val hms = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun verb(m: StateMarkers) = when (m.tap?.gesture) {
    "SLIDE" -> "החלקת"
    "LONG_TAP" -> "לחיצה ארוכה"
    else -> "לחצת"
}

data class LogLine(val atMs: Long, val icon: String, val text: String, val evidence: String, val important: Boolean)

/** Pure: turns marker rows into plain-language log lines. No inference beyond each row's own rule. */
fun shiftLogLines(events: List<StateMarkerEventEntity>): List<LogLine> = events.mapNotNull { e ->
    val m = runCatching { json.decodeFromString<StateMarkers>(e.markersJson) }.getOrNull() ?: return@mapNotNull null
    val (icon, text, important) = when (e.candidateState) {
        "OFFER_PRESENTED" -> if (m.source == "SCREEN") Triple(
            "📩",
            buildString {
                append("הצעה על המסך")
                m.offer_amount_agorot?.let { append(" · ₪").append(formatAgorot(it)) }
                m.offer_distance_km?.let { append(" · ").append(it).append(" ק״מ") }
                if (m.pickup_names.isNotEmpty()) append(" · ").append(m.pickup_names.joinToString(" / "))
                m.package_size?.let { append(" · 📦 ").append(it) }
            },
            true,
        ) else Triple("🔔", "התראת הצעה חדשה", true)
        "ACCEPT_ACTION" -> Triple("✅", "${verb(m)} \"${m.tap?.word}\" (קבלה) — פעולה בלבד, לא אישור שהמשלוח שובץ", true)
        "DECLINE_ACTION" -> Triple("✖", "${verb(m)} \"${m.tap?.word}\" (דחייה)", true)
        "DELIVERY_STEP_ACTION" -> Triple("🚗", "${verb(m)} \"${m.tap?.word}\" (שלב משלוח)", true)
        "AVAILABILITY_ACTION" -> Triple("🔁", "${verb(m)} \"${m.tap?.word}\" (זמינות)", true)
        "DELIVERY_ACTIVE" -> Triple(
            "🚗",
            if (m.sliders.isNotEmpty()) "כפתור החלקה: ${m.sliders.joinToString { it.label_redacted + (it.progress_pct?.let { p -> " $p%" } ?: "") }} (משוער)"
            else "סימני משלוח פעיל: ${m.delivery_step_words.joinToString()} (משוער)",
            true,
        )
        "AVAILABLE" -> Triple("🟢", "זמין? (משוער: ${e.rule.substringBefore('(')})", false)
        "OFFLINE" -> Triple("⚪", "לא מחובר? (משוער)", false)
        else -> when {
            m.source == "SYSTEM" -> Triple("⏸", "מערכת: ${m.system_event}", true)
            m.source == "ACTION" -> Triple("👆", "${verb(m)} (אחר): ${m.tap?.view_id ?: m.tap?.cls ?: "?"} ${m.tap?.label_redacted}", false)
            m.sliders.isNotEmpty() -> Triple("↔", "כפתור החלקה: ${m.sliders.joinToString { "${it.view_id ?: it.cls}: ${it.label_redacted}${it.progress_pct?.let { p -> " $p%" } ?: ""}" }}", false)
            e.rule == "notif.removed_no_inference" -> Triple("…", "התראה הוסרה (${m.notification_event?.substringAfter(' ')}) — ללא הסקה", false)
            else -> Triple("·", "${m.source}: ${e.rule}", false)
        }
    }
    LogLine(e.observedAtUtc, icon, text, e.evidence, important)
}

/** Marker rows → detector input. */
fun detectorItems(events: List<StateMarkerEventEntity>): List<DeliveryEventDetector.Item> = events.mapNotNull { e ->
    runCatching { json.decodeFromString<StateMarkers>(e.markersJson) }.getOrNull()?.let { DeliveryEventDetector.Item(e.observedAtUtc, e.candidateState, it) }
}

private val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Pure: one readable line per trip. Durations only when start AND end were observed. */
fun tripLines(r: DeliveryEventDetector.Result): List<String> = r.trips.map { t ->
    buildString {
        append("משלוח ${t.index}: התחלה ${hm.format(Instant.ofEpochMilli(t.started_at_ms))}")
        append(if (t.completed_at_ms != null) " → סיום ${hm.format(Instant.ofEpochMilli(t.completed_at_ms))}" else " → סיום לא ידוע")
        append(" · ").append(
            t.duration_ms?.let { "${"%.0f".format(it / 60_000.0)} דק׳ (נצפה)" }
                ?: t.duration_estimated_ms?.let { "~${"%.0f".format(it / 60_000.0)} דק׳ (משוער, לפי רצף מסכים)" }
                ?: "משך לא ידוע",
        )
        if (t.orders > 1) append(" · ${t.orders} הזמנות (מקובץ)")
        if (t.dropoffs > 1 || t.expected_dropoffs != null) append(" · ${t.dropoffs}${t.expected_dropoffs?.let { "/$it" } ?: ""} מסירות")
        t.end_note?.takeIf { t.end_evidence != "OBSERVED" }?.let { append(" · סיום לא ידוע: $it") }
        t.wait_before_ms?.let { append(" · המתנה לפני: ${"%.0f".format(it / 60_000.0)} דק׳${if (t.wait_evidence == "OBSERVED") "" else " (משוער)"}") }
        if (t.offer_amounts_agorot.isNotEmpty()) append(" · " + t.offer_amounts_agorot.joinToString(" + ") { "₪" + formatAgorot(it) })
    }
}

/** Delivery start/end detection for the latest day (post-hoc). */
@Composable
fun DeliveryTripsCard(vm: MainViewModel) {
    val events by vm.stateEvents.collectAsStateWithLifecycle()
    val day = events.firstOrNull()?.let { Instant.ofEpochMilli(it.observedAtUtc).atZone(ZoneId.systemDefault()).toLocalDate() }
    val r = DeliveryEventDetector.detect(
        detectorItems(events.filter { day != null && Instant.ofEpochMilli(it.observedAtUtc).atZone(ZoneId.systemDefault()).toLocalDate() == day }),
    )
    AppCard {
        CardTitle("משלוחים שזוהו")
        Text(
            "Wolt לא מדווח על לחיצות, ולכן התחלה וסיום מזוהים לפי רצף מסכים (משוער): כרטיס הצעה ← מסך משלוח = התחלה; מסך משלוח ← בית/סיכום/הצעה חדשה = סיום. כרטיס שנעלם בלי מסך מזוהה אחריו — לא ידוע. הכללים עדיין לא אומתו על משלוח אמיתי.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val lines = tripLines(r)
        if (lines.isEmpty()) Text("לא זוהו משלוחים.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
        val other = r.events.filter { it.type !in setOf("DELIVERY_ACCEPTED", "PICKUP_DONE", "DROPOFF_DONE", "DELIVERY_COMPLETED") }
        if (other.isNotEmpty()) {
            Text("הצעות שלא הפכו למשלוח", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            other.forEach { e ->
                val (icon, text) = when (e.type) {
                    "DECLINE_DIALOG_SHOWN" -> "✖" to "חלון דחייה הופיע (נצפה)"
                    "OFFER_DECLINED" -> "✖" to "הצעה נדחתה (משוער: חלון דחייה ואחריו ${if (e.confirmation.endsWith("home_screen")) "מסך הבית" else "מסך אחר"})"
                    "DECLINE_CANCELLED" -> "↩" to "דחייה בוטלה — חזרה לכרטיס (משוער)"
                    "OFFER_ENDED_UNKNOWN" -> "?" to "הכרטיס נעלם בלי חלון דחייה ובלי מסך משלוח — לא ידוע (פג תוקף?)"
                    "TRIP_END_CONFLICT" -> "⚠" to "סתירה בסיום: ${e.confirmation}"
                    else -> "?" to "פעולה ללא מסך מאשר: ${e.action} (לא ידוע)"
                }
                Text("${hms.format(Instant.ofEpochMilli(e.action_at_ms))}  $icon  $text", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Readable log of the latest day with events — for checking a shift afterwards (parked). */
@Composable
fun ShiftLogCard(vm: MainViewModel) {
    val events by vm.stateEvents.collectAsStateWithLifecycle()
    var all by remember { mutableStateOf(false) }
    val day = events.firstOrNull()?.let { Instant.ofEpochMilli(it.observedAtUtc).atZone(ZoneId.systemDefault()).toLocalDate() }
    val lines = shiftLogLines(
        events.filter { day != null && Instant.ofEpochMilli(it.observedAtUtc).atZone(ZoneId.systemDefault()).toLocalDate() == day }.reversed(),
    ).filter { all || it.important }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardTitle("יומן משמרת")
                Text(
                    (day?.let { if (it == LocalDate.now()) "היום" else it.toString() } ?: "אין אירועים") + " · מהישן לחדש",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("הכל", style = MaterialTheme.typography.labelLarge)
            Switch(checked = all, onCheckedChange = { all = it }, modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            "לחיצות שלך נקלטות כפי שהן. \"לחצת קבל\" אינו אישור שהמשלוח שובץ — האישור הוא מה שמופיע אחריו. היעלמות התראה או מסך לא נרשמת כהחלטה.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (lines.isEmpty()) Text("אין עדיין אירועים.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        lines.forEachIndexed { i, l ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Ltr(hms.format(Instant.ofEpochMilli(l.atMs)), Modifier.width(64.dp))
                Text(l.icon)
                Text(l.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (l.important) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}
