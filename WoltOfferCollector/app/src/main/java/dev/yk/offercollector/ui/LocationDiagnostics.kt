package dev.yk.offercollector.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yk.offercollector.core.LocationSamplingPolicy
import dev.yk.offercollector.export.LocationReport
import dev.yk.offercollector.service.ShiftLocation

/** Pure: diagnostics lines (spec §10) from a location report. */
fun locationDiagnosticsLines(r: LocationReport.Result): List<String> {
    val s = r.summary
    val statuses = s.samples_by_capture_status.entries.joinToString(", ") { "${it.key}×${it.value}" }
    return listOf(
        "משמרות: ${s.sessions} · דגימות: ${s.samples} (תקינות ${s.samples_ok}${if (statuses.isNotEmpty()) "; $statuses" else ""})",
        "הצעות בזמן משמרת: ${s.offers_in_sessions} · עם מיקום תקין: ${s.offers_with_location} · בלי מיקום: ${s.offers_without_location}",
        "דיוק חציוני: ${s.median_accuracy_m?.let { "$it מ׳" } ?: "—"} · גיל מיקום חציוני בהצעה: ${s.median_location_age_at_offer_ms?.let { "${it / 1000} שנ׳" } ?: "—"}",
        "אזורים (תאים) שונים: ${s.distinct_cells} · פערי ניטור: ${s.monitoring_gaps.size}",
        "סוללה: " + (s.battery_drop_pct_per_hour?.let { "ירידה של כ-$it% לשעה (כל המכשיר, לא רק האפליקציה)" }
            ?: "עוד לא נמדד (צריך משמרת של 30+ דק׳ בלי טעינה)"),
    )
}

@Composable
fun LocationDiagnosticsCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val report by vm.locationReport.collectAsStateWithLifecycle()
    val active by vm.activeShiftId.collectAsStateWithLifecycle()
    val samples by vm.locationSamples.collectAsStateWithLifecycle()
    val stationary by ShiftLocation.stationary.collectAsStateWithLifecycle()
    val fine = ShiftLocation.hasFinePermission(ctx)
    val coarse = ShiftLocation.hasPermission(ctx)
    val notif = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    AppCard {
        CardTitle("מיקום ואזורים")
        Text(
            "הרשאת מיקום: ${if (fine) "מדויק ✓" else if (coarse) "משוער בלבד — רוב הדגימות יסומנו לא ידוע" else "לא ניתנה"}" +
                " · התראה קבועה: ${if (notif) "מוצגת" else "מוסתרת (אין הרשאת התראות)"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (active != null) "שירות המיקום: פועל · מרווח ${ShiftLocation.intervalMs / 1000} שנ׳${if (stationary) " (המכשיר עומד)" else ""}"
            else "שירות המיקום: כבוי — אין איסוף",
            fontWeight = FontWeight.Medium,
        )
        report?.let { r -> locationDiagnosticsLines(r).forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
        report?.summary?.monitoring_gaps?.takeLast(3)?.forEach {
            Text("⚠ פער: ${it.from_utc.substring(11, 16)}–${it.to_utc.substring(11, 16)} UTC (${it.duration_s / 60} דק׳, ${it.kind})", style = MaterialTheme.typography.bodySmall)
        }
        CellMap(samples.filter { it.status == "OK" }.mapNotNull { it.cellId }, report?.offerLocations?.mapNotNull { it.grid_cell_id } ?: emptyList())
    }
}

/** Simple local grid of visited cells (darker = more samples, dot = offers). No base map, no network. */
@Composable
private fun CellMap(sampleCells: List<String>, offerCells: List<String>) {
    val counts = sampleCells.groupingBy { it }.eachCount()
    if (counts.isEmpty()) {
        Text("מפת אזורים תופיע אחרי דגימות תקינות.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    fun yx(id: String) = id.split(":").let { it[1].toLong() to it[2].toLong() }
    val pts = counts.keys.map(::yx)
    val minY = pts.minOf { it.first }; val maxY = pts.maxOf { it.first }
    val minX = pts.minOf { it.second }; val maxX = pts.maxOf { it.second }
    val offers = offerCells.groupingBy { it }.eachCount()
    val maxCount = counts.values.max()
    val fill = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.error
    val outline = MaterialTheme.colorScheme.outlineVariant
    Text("מפת אזורים (כל ריבוע ≈ 500 מ׳; צפון למעלה)", style = MaterialTheme.typography.labelMedium)
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val cols = (maxX - minX + 1).toInt(); val rows = (maxY - minY + 1).toInt()
        val cell = minOf(size.width / cols, size.height / rows, 48f * density)
        counts.forEach { (id, n) ->
            val (y, x) = yx(id)
            val tl = Offset((x - minX) * cell, (maxY - y) * cell)
            drawRect(fill.copy(alpha = 0.15f + 0.75f * n / maxCount), tl, Size(cell - 2, cell - 2))
            drawRect(outline, tl, Size(cell - 2, cell - 2), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            offers[id]?.let { drawCircle(dot, radius = minOf(cell / 4, 6f * density), center = tl + Offset(cell / 2, cell / 2)) }
        }
    }
}

/** Settings: sampling interval, adaptive stretch, debug exact coordinates (spec §6, §8). */
@Composable
fun LocationSettingsSection(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val active by vm.activeShiftId.collectAsStateWithLifecycle()
    var debugCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.locationDebugExact) { debugCount = vm.debugCoordinateCount() }
    AppCard {
        CardTitle("איסוף מיקום")
        Text("מרווח דגימה בזמן משמרת${if (active != null) " (ישתנה במשמרת הבאה)" else ""}:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocationSamplingPolicy.BASE_INTERVAL_CHOICES_S.forEach { sec ->
                val sel = s.locationBaseIntervalS == sec
                if (sel) androidx.compose.material3.Button(onClick = {}) { Text("$sec שנ׳") }
                else OutlinedButton(onClick = { vm.setLocationInterval(sec) }) { Text("$sec שנ׳") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("להאריך ל-${LocationSamplingPolicy.DEFAULT_STATIONARY_INTERVAL_S} שנ׳ כשהמכשיר עומד (חוסך סוללה)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Switch(checked = s.locationAdaptive, onCheckedChange = { vm.setLocationAdaptive(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "מסלול מדויק (~5 מ׳) בזמן משמרת, למפת המסלול ולמרחק. כולל את מיקום הבית ואת דלתות הלקוחות — נשמר על המכשיר ובייצוא שלך בלבד, לא נשלח לשום מקום אחר.",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            )
            Switch(checked = s.preciseTrack, onCheckedChange = { vm.setPreciseTrack(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "מצב Debug: לשמור קואורדינטות מדויקות על המכשיר ל-24 שעות לבדיקת איכות. לא נכללות בייצוא. כיבוי = מחיקה.",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            )
            Switch(checked = s.locationDebugExact, onCheckedChange = { vm.setLocationDebugExact(it) })
        }
        if (debugCount > 0) {
            TextButton(onClick = { vm.purgeDebugCoordinates(); debugCount = 0 }) { Text("מחיקת $debugCount קואורדינטות מדויקות עכשיו") }
        }
    }
}
