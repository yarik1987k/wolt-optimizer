package dev.yk.offercollector.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yk.offercollector.data.LocationSampleEntity
import dev.yk.offercollector.service.ShiftLocation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Pure: one-line summary of a shift's samples ("12 דגימות · 10 אזור ידוע · 2 לא ידוע (LOW_ACCURACY×2)"). */
fun shiftSampleSummary(samples: List<LocationSampleEntity>): String {
    if (samples.isEmpty()) return "עדיין אין דגימות"
    val ok = samples.count { it.status == "OK" }
    val unknown = samples.filter { it.status != "OK" }
    val reasons = unknown.groupingBy { it.unknownReason ?: "?" }.eachCount().entries.joinToString(", ") { "${it.key}×${it.value}" }
    val cells = samples.mapNotNull { it.cellId }.distinct().size
    return "${samples.size} דגימות · $ok באזור ידוע ($cells אזורים)" + if (unknown.isEmpty()) "" else " · ${unknown.size} לא ידוע ($reasons)"
}

/** Shift = the only time location is sampled. Started and ended only here, never while driving. */
@Composable
fun ShiftCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val active by vm.activeShiftId.collectAsStateWithLifecycle()
    val shifts by vm.shifts.collectAsStateWithLifecycle()
    val samples by vm.locationSamples.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ShiftLocation.hasPermission(ctx)) vm.startShift() else vm.message.value = "בלי הרשאת מיקום אי אפשר לשמור אזור — המשמרת לא התחילה"
    }

    AppCard {
        CardTitle("משמרת ואזורים")
        Text(
            "בזמן משמרת שתתחיל/י כאן נשמר אזור כללי (תא של כ-500 מ׳): בהצעה, בקבלה, באיסוף, במסירה, בסיום, במסך הבית, ב-Offline, " +
                "וכל ${s.locationBaseIntervalS} שניות לאורך המשמרת. " +
                (if (s.preciseTrack) "בנוסף נשמר מסלול מדויק (~5 מ׳, כל כמה שניות) למפת המסלול ולמרחק — כולל הבית ודלתות הלקוחות; נשאר במכשיר ובייצוא שלך. ניתן לכבות בהגדרות. "
                else "לא נשמר מיקום מדויק (המסלול המדויק כבוי בהגדרות). ") +
                "לא נשמרים כתובות או שמות של לקוחות. בזמן איסוף מוצגת התראה קבועה של Android. האיסוף נעצר בסיום המשמרת (ולכל היותר אחרי 6 שעות).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = s.locationConsent, onCheckedChange = { vm.setLocationConsent(it) }, enabled = active == null || s.locationConsent)
            Text("אני מסכים/ה לאיסוף אזור כללי בזמן משמרת", style = MaterialTheme.typography.bodyMedium)
        }
        val current = shifts.firstOrNull { it.id == active }
        if (active != null && current != null) {
            Text("משמרת פעילה מ-${hm.format(Instant.ofEpochMilli(current.startedAtUtc))}", fontWeight = FontWeight.SemiBold)
            Text(shiftSampleSummary(samples.filter { it.shiftId == active }), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.endShift() }) { Text("סיום משמרת") }
        } else {
            Text("שעת סיום מתוכננת (לחישוב הזמן שנשאר במשמרת):", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf<Int?>(null, 20, 21, 22, 23, 0).forEach { h ->
                    FilterChip(selected = s.plannedEndHour == h, onClick = { vm.setPlannedEndHour(h) }, label = { Text(h?.let { String.format(java.util.Locale.US, "%02d:00", it) } ?: "לא נקבע") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = s.locationConsent,
                    onClick = {
                        if (ShiftLocation.hasPermission(ctx)) vm.startShift()
                        else permission.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
                                (if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()),
                        )
                    },
                ) { Text("התחלת משמרת") }
            }
            shifts.firstOrNull()?.let { last ->
                val endText = last.endedAtUtc?.let { hm.format(Instant.ofEpochMilli(it)) } ?: "?"
                val reason = when (last.endReason) {
                    "USER" -> "סיום ידני"
                    "MAX_DURATION" -> "הגיע ל-6 שעות"
                    "CONSENT_REVOKED" -> "ההסכמה בוטלה"
                    "SERVICE_STOPPED" -> "השירות נעצר — פער בניטור"
                    else -> last.endReason ?: ""
                }
                Text(
                    "משמרת אחרונה: ${hm.format(Instant.ofEpochMilli(last.startedAtUtc))}–$endText ($reason) · " +
                        shiftSampleSummary(samples.filter { it.shiftId == last.id }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "להתחיל ולסיים רק כשחונים. אם Android מציע \"מיקום משוער\" — האזור לרוב לא יהיה מדויק מספיק ויסומן כלא ידוע.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

