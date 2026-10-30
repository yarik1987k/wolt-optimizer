package dev.yk.offercollector.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yk.offercollector.core.ShadowModel
import dev.yk.offercollector.core.formatAgorot
import kotlinx.serialization.json.Json

private fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

/** Shadow Performance dashboard. Passive: nothing here is shown or sent during a shift. */
@Composable
fun ShadowSection(vm: MainViewModel) {
    val preds by vm.shadowPredictions.collectAsStateWithLifecycle()
    val ev by vm.shadowEvaluated.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val shifts by vm.shifts.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            CardTitle("מצב צל — ${ShadowModel.RULE_VERSION}")
            Text(
                "לכל הצעה המודל רושם בשקט מה *היה* מסווג, ברגע ההצעה, בלי לשנות את זה אחר כך. ACCEPT/DECLINE הם סיווגים ניסיוניים — לא החלטה מוכחת. " +
                    "אין התראות ואין שום דבר בזמן משמרת. המטרה: הכנסה כוללת למשמרת.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("המלצות חיות: כבויות", fontWeight = FontWeight.Bold, color = Color(0xFFE8590C))
        }

        // Model readiness
        val r = ShadowModel.readiness(ev, shifts.size)
        AppCard {
            CardTitle(if (r.ready) "מוכן לניסוי מבוקר" else "עוד לא מוכן לניסוי מבוקר")
            r.checks.forEach { (label, ok) -> Text("${if (ok) "✅" else "⬜"} $label", style = MaterialTheme.typography.bodySmall) }
            Text("גם כשמוכן — זה רק לניסוי מבוקר שמתכננים מראש, לא להמלצות חיות.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Predictions and missing data
        AppCard {
            CardTitle("תחזיות (${preds.size})")
            val byDecision = preds.groupingBy { it.decision }.eachCount()
            Text(
                "סיווג ניסיוני: ACCEPT ${byDecision["ACCEPT"] ?: 0} · DECLINE ${byDecision["DECLINE"] ?: 0} · אין מספיק נתונים ${byDecision["INSUFFICIENT_DATA"] ?: 0}",
                style = MaterialTheme.typography.bodyMedium,
            )
            val reasons = preds.flatMap { runCatching { Json.decodeFromString<List<String>>(it.reasonsJson) }.getOrDefault(emptyList()) }
                .map { it.substringBefore(" (").substringBefore(" —") }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6)
            if (reasons.isNotEmpty()) {
                Text("מה חסר הכי הרבה:", style = MaterialTheme.typography.labelLarge)
                reasons.forEach { Text("• ${it.key} ×${it.value}", style = MaterialTheme.typography.bodySmall) }
            }
            val conf = preds.mapNotNull { it.durationConfidence }.groupingBy { it }.eachCount()
            Text("ביטחון בהערכת משך: LOW ${conf["LOW"] ?: 0} · FAIR ${conf["FAIR"] ?: 0} · אין הערכה ${preds.count { it.durationMedianMin == null }}", style = MaterialTheme.typography.bodySmall)
            preds.take(5).forEach { p ->
                val t = java.time.Instant.ofEpochMilli(p.offerAtUtc).atZone(dev.yk.offercollector.core.OfferAnalytics.ZONE)
                Text(
                    String.format(java.util.Locale.US, "%02d:%02d", t.hour, t.minute) + " · ₪${formatAgorot(p.amountAgorot)} · ${p.decision}" +
                        (p.durationMedianMin?.let { " · משך ${f1(it)} (${f1(p.durationLowMin!!)}–${f1(p.durationHighMin!!)}) דק׳" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 1. Prediction accuracy
        val acc = ShadowModel.accuracy(ev)
        AppCard {
            CardTitle("1. דיוק התחזית")
            Text("משך שחזה המודל מול המשך בפועל, רק במשלוחים בודדים שהושלמו והייתה להם תחזית.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (acc.n == 0) Text("עדיין אין משלוח שהושלם עם תחזית.")
            else Text("${acc.n} משלוחים · טעות ממוצעת ${f1(acc.maeMin!!)} דק׳ · בתוך הטווח ${(acc.withinRange!! * 100).toInt()}%")
        }

        // 2. Historical policy simulation (hypothetical)
        val ag = ShadowModel.agreement(ev)
        AppCard {
            CardTitle("2. סימולציה היסטורית — היפותטית")
            Text(
                "השוואה בין הסיווג הניסיוני לבין מה שעשית. אי אפשר לדעת מה היה קורה בהצעות שדחית או אילו הצעות היו מגיעות אחרי — לכן אין כאן סכום כסף.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ag.decided == 0) Text("עדיין אין הצעות עם סיווג ועם החלטה ידועה שלך.")
            else Text("${ag.decided} הצעות · זהה להחלטה שלך ${ag.sameAsCourier} · המודל ACCEPT ודחית ${ag.modelAcceptCourierDeclined} · המודל DECLINE וקיבלת ${ag.modelDeclineCourierAccepted}")
        }

        // 3. Prospective experiments
        AppCard {
            CardTitle("3. ניסויים מתוכננים")
            Text(
                "ההשוואה האמיתית: משמרות שמתכננים מראש באסטרטגיות שונות (למשל ״רק מעל ₪35״ מול ״הכל״) ומשווים את ההכנסה בפועל. זה נעשה בעמוד הניסויים של Wolt Optimizer, אחרי ייבוא הנתונים — לא בזמן משמרת.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Settings that the model needs (never defaulted)
        AppCard {
            CardTitle("הגדרות המודל")
            var cost by remember(s.vehicleCostPerKmAgorot) { mutableStateOf(s.vehicleCostPerKmAgorot?.let { formatAgorot(it) } ?: "") }
            OutlinedTextField(
                cost,
                { v -> cost = v; vm.setVehicleCost(v.replace(',', '.').toDoubleOrNull()) },
                label = { Text("עלות רכב לק״מ (₪) — ריק = לא ידוע") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(260.dp),
            )
            Text("סף ניסיוני (לא מאומת) — נשמר עם כל תחזית:", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 15, 20, 25).forEach { m -> FilterChip(selected = s.shadowMarginPct == m, onClick = { vm.setShadowMargin(m) }, label = { Text("±$m%") }) }
            }
            Text("שעת סיום משמרת מתוכננת נקבעת בכרטיס המשמרת בבית.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
