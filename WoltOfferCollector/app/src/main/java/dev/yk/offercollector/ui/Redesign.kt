package dev.yk.offercollector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yk.offercollector.core.Insights
import dev.yk.offercollector.core.Insights.Label
import dev.yk.offercollector.core.OfferAnalytics
import dev.yk.offercollector.core.ShiftReview
import dev.yk.offercollector.core.formatAgorot
import java.time.Instant

// ---- shared ----

private fun hm(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(OfferAnalytics.ZONE).let { z -> String.format(java.util.Locale.US, "%02d:%02d", z.hour, z.minute) } } ?: "—"
private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(OfferAnalytics.ZONE).let { "${it.dayOfMonth}.${it.monthValue}" }
private fun shekel(a: Long) = "₪" + formatAgorot(a)
private fun range(m: ShiftReview.Money) = if (m.exact || kotlin.math.abs(m.high - m.low) < 100) shekel(m.mid) else "${shekel(m.low)}–${shekel(m.high)}"
/** Isolates a number range inside right-to-left text. */
private fun ltr(s: String) = "⁦$s⁩"

/** How much a number can be trusted — shown next to every figure. */
@Composable
fun EvidenceTag(label: Label) {
    val (text, bg, fg) = when (label) {
        Label.MEASURED -> Triple("נמדד", Color(0xFFEAF3DE), Color(0xFF27500A))
        Label.REPEATED -> Triple("חוזר על עצמו", Color(0xFFEAF3DE), Color(0xFF27500A))
        Label.TESTED -> Triple("נבדק", Color(0xFFEAF3DE), Color(0xFF27500A))
        Label.HYPOTHESIS -> Triple("השערה", Color(0xFFFAEEDA), Color(0xFF633806))
        Label.NOT_ENOUGH_DATA -> Triple("חסרים נתונים", Color(0xFFF1EFE8), Color(0xFF444441))
    }
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp), color = fg, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun EstimateTag() =
    Text("הערכה", Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFAEEDA)).padding(horizontal = 8.dp, vertical = 2.dp), color = Color(0xFF633806), style = MaterialTheme.typography.labelMedium)

@Composable
private fun Line(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(ltr(value), style = MaterialTheme.typography.bodyMedium, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun rememberHistory(vm: MainViewModel): Insights.History? {
    val h by vm.history.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshHistory() }
    return h
}

// ---- before the shift ----

/** Plan cards under the shift card on the "לפני" tab. */
@Composable
fun PlanCards(vm: MainViewModel) {
    val h = rememberHistory(vm) ?: run { AppCard { Text("טוען היסטוריה…") }; return }
    val s by vm.settings.collectAsStateWithLifecycle()
    val windows = Insights.timeWindows(h)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("מתי לעבוד")
            EvidenceTag(if (windows.any { it.evidence == Label.REPEATED }) Label.REPEATED else if (windows.isEmpty()) Label.NOT_ENOUGH_DATA else Label.HYPOTHESIS)
        }
        if (windows.isEmpty()) Note("עוד אין משמרת עבודה שלמה (שעה ומעלה).")
        windows.forEach { w ->
            Line("${w.label} · ${w.shifts} ${if (w.shifts == 1) "משמרת" else "משמרות"}", w.mean?.let { "${shekel(it)}/ש׳ נטו" } ?: "—")
        }
        Note(
            if (windows.size < 2 || windows.none { it.evidence == Label.REPEATED }) "צריך כמה משמרות בכל חלון זמן (בערך 5) לפני שאפשר לומר איזה חלון עדיף. עד אז זה רק מה שקרה."
            else "חלונות עם 5 משמרות ומעלה. עדיין כדאי לבדוק שהטווחים לא חופפים.",
        )
    }
    val spots = Insights.waitSpots(h)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("איפה לחכות")
            EvidenceTag(if (spots.any { it.evidence == Label.REPEATED }) Label.REPEATED else if (spots.isEmpty()) Label.NOT_ENOUGH_DATA else Label.HYPOTHESIS)
        }
        if (spots.isEmpty()) Note("עוד אין המתנות בין משלוחים.")
        else {
            val shown = spots.take(5)
            var placeNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            var selected by remember { mutableStateOf<String?>(null) }
            // Names: the courier's own names first, then the neighbourhood printed on the map tiles (no geocoding).
            val nameOf = { id: String, i: Int -> s.areaNames[id] ?: placeNames[id] ?: "מקום ${i + 1}" }
            AreaMap(
                shown.mapIndexed { i, w -> MapArea(w.cellId, w.waits.toDouble(), "${i + 1}") },
                selected,
                onSelect = { selected = it },
                onPlaceNames = { found -> if (found + placeNames != placeNames) placeNames = placeNames + found },
            )
            shown.forEachIndexed { i, w ->
                Line(
                    "${i + 1}. ${nameOf(w.cellId, i)} · ${w.waits} ${if (w.waits == 1) "המתנה" else "המתנות"}",
                    String.format(java.util.Locale.US, "%.0f דק׳ עד הצעה", w.medianMinutes),
                    strong = w.cellId == selected,
                )
            }
        }
        Note("המספרים על המפה = המקומות ברשימה. הזמן = כמה דקות (חציון) עברו מסוף משלוח או תחילת משמרת ועד ההצעה הבאה, לפי המקום שבו התחלת לחכות. צריך כמה המתנות באותו מקום בשתי משמרות לפחות כדי להשוות.")
    }
    OfferRulesCard(vm, h)
    val hours = Insights.byShiftHour(h)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("מתי לסיים")
            EvidenceTag(if (hours.isEmpty()) Label.NOT_ENOUGH_DATA else if (hours.all { it.evidence == Label.REPEATED }) Label.REPEATED else Label.HYPOTHESIS)
        }
        hours.forEach { r -> Line("שעה ${r.index} במשמרת · ${r.shifts} ${if (r.shifts == 1) "משמרת" else "משמרות"}", "${shekel(r.mean)} לשעה") }
        Note("הכנסה מהצעות שהתחילו בכל שעה של המשמרת, לפני דלק. אם השעות האחרונות חלשות באופן קבוע — שם כדאי לסיים.")
    }
}

@Composable
private fun OfferRulesCard(vm: MainViewModel, h: Insights.History) {
    val s by vm.settings.collectAsStateWithLifecycle()
    AppCard {
        CardTitle("הכללים שלך להצעות")
        Note("כללים שאת/ה קובע/ת לפני משמרת. האפליקציה לא מתריעה ולא מחליטה — רק מראה איך הכלל היה מתנהג על ההיסטוריה שלך.")
        Text("הצעה נוספת בזמן משלוח:", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = s.ruleAcceptAddedOffers == null, onClick = { vm.setRuleAcceptAdded(null) }, label = { Text("בלי כלל") })
            FilterChip(selected = s.ruleAcceptAddedOffers == true, onClick = { vm.setRuleAcceptAdded(true) }, label = { Text("לקבל") })
            FilterChip(selected = s.ruleAcceptAddedOffers == false, onClick = { vm.setRuleAcceptAdded(false) }, label = { Text("לדחות") })
        }
        var price by remember(s.ruleMinPriceAgorot) { mutableStateOf(s.ruleMinPriceAgorot?.let { formatAgorot(it) } ?: "") }
        OutlinedTextField(
            price,
            { v -> price = v; vm.setRuleMinPrice(v.replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 100) }) },
            label = { Text("מחיר מינימום (₪) — ריק = בלי") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(240.dp),
        )
        Insights.ruleCheck(h, s.ruleAcceptAddedOffers, s.ruleMinPriceAgorot)?.let { r ->
            Line("הצעות במשמרות עבודה", "${r.offers}")
            Line("הכלל מסכים עם מה שעשית", "${r.agree}")
            Line("היה דוחה הצעות שקיבלת", "${r.wouldDeclineAccepted}")
            Line("היה מקבל הצעות שדחית", "${r.wouldAcceptDeclined}")
            Note("מה היה קורה במקום ההצעות האלה — לא ידוע (הצעות אחרות, המתנה). לכן זו לא תחזית רווח.")
        }
    }
}

// ---- after the shift ----

@Composable
fun ReviewScreen(vm: MainViewModel) {
    val h = rememberHistory(vm)
    val offers by vm.offers.collectAsStateWithLifecycle()
    val outcomes by vm.offerOutcomes.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("אחרי משמרת", "כמה באמת הרווחת לשעה, אחרי דלק ובלאי, ולאן הלך הזמן.")
        if (h == null) { AppCard { Text("טוען…") }; return@Column }
        val reviews = h.reviews.filter { it.endMs - it.startMs >= 5 * 60_000 }.sortedByDescending { it.startMs }
        if (reviews.isEmpty()) { AppCard { Text("עדיין אין משמרת. אחרי המשמרת הראשונה יופיע כאן הסיכום.") }; return@Column }
        val r = reviews.firstOrNull { it.shiftId == selected } ?: reviews.first()
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            reviews.take(10).forEach { x ->
                val short = x.endMs - x.startMs < Insights.MIN_WORK_SHIFT_MS
                FilterChip(selected = x.shiftId == r.shiftId, onClick = { selected = x.shiftId }, label = { Text("${day(x.startMs)} ${hm(x.startMs)}${if (short) " (קצרה)" else ""}") })
            }
        }
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("נטו לשעת משמרת", style = MaterialTheme.typography.titleMedium)
                if (r.netPerShiftHourAgorot != null) EstimateTag() else EvidenceTag(Label.NOT_ENOUGH_DATA)
            }
            Text(r.netPerShiftHourAgorot?.let { ltr(range(it)) } ?: "—", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Note("${hm(r.startMs)}–${hm(r.endMs)} · ${String.format(java.util.Locale.US, "%.1f", r.hours)} שעות, כולל המתנות. ההכנסה נמדדה; הדלק מוערך מק״מ × עלות לק״מ.")
            r.netPerActiveHourAgorot?.let { Line("נטו לשעת משלוחים בלבד", range(it)) }
        }
        AppCard {
            CardTitle("כסף")
            Line("הכנסה מהצעות שקיבלת", shekel(r.revenueAgorot))
            Line("דלק ובלאי (${r.km?.let { String.format(java.util.Locale.US, "%.1f", (it.first + it.second) / 2) } ?: "?"} ק״מ)", r.vehicleCostAgorot?.let { "−" + range(it) } ?: "—")
            r.netAgorot?.let { Line("נטו", range(it), strong = true) }
            if (r.best != null && r.worst != null) {
                Line("₪ לדקת משלוח — הכי טוב", "${shekel(Math.round((r.best.perMinuteLow + r.best.perMinuteHigh) / 2))} (${shekel(r.best.revenueAgorot)} ב-${Math.round(r.best.minutesHigh)} דק׳)")
                Line("₪ לדקת משלוח — הכי חלש", "${shekel(Math.round((r.worst.perMinuteLow + r.worst.perMinuteHigh) / 2))} (${shekel(r.worst.revenueAgorot)} ב-${Math.round(r.worst.minutesHigh)} דק׳)")
            }
            Note("טיפים לא נכללים (הם לא מופיעים בכרטיס ההצעה).${if (!r.precise) " המרחק מחושב מריבועי 500 מ׳ ולכן כנראה נמוך מהאמת." else ""}")
        }
        r.time?.let { t ->
            AppCard {
                CardTitle("לאן הלך הזמן")
                val parts = listOf(
                    Triple("נסיעה בזמן משלוח", t.driving, Color(0xFF378ADD)),
                    Triple("עמידה בזמן משלוח", t.standingInDeliveries, Color(0xFFEF9F27)),
                    Triple("המתנה בין משלוחים", t.betweenDeliveries, Color(0xFF888780)),
                    Triple("לא ידוע", t.unknown, Color(0xFFD3D1C7)),
                ).filter { it.second > 0.005 }
                // The bar and the rows below are the same parts, in the same order and colours.
                Row(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp))) {
                    parts.forEach { (_, share, c) -> Box(Modifier.weight(share.toFloat()).fillMaxHeight().background(c)) }
                }
                parts.forEach { (label, share, c) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(14.dp).height(14.dp).clip(RoundedCornerShape(3.dp)).background(c))
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(ltr("${Math.round(share * r.hours * 60)} דק׳"), style = MaterialTheme.typography.bodyMedium)
                        Text(ltr("${Math.round(share * 100)}%"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                    }
                }
                Note("עמידה = מהירות GPS מתחת ל-1 מ׳/ש׳: המתנה במסעדה, מסירה ללקוח או רמזור — עוד לא אפשר להפריד ביניהם.")
            }
        }
        if (r.missing.isNotEmpty()) AppCard { CardTitle("מה חסר"); r.missing.forEach { Note("• $it") } }
        val own = offers.filter { it.firstSeenMs in r.startMs..r.endMs }.sortedByDescending { it.firstSeenMs }
        if (own.isNotEmpty()) {
            var filter by rememberSaveable { mutableStateOf("ALL") }
            fun kind(o: dev.yk.offercollector.core.DetectedOffer) = when (outcomes[o.firstSeenMs]?.kind) {
                dev.yk.offercollector.core.OfferOutcomes.Kind.ACCEPTED -> "ACCEPTED"
                dev.yk.offercollector.core.OfferOutcomes.Kind.DECLINED, dev.yk.offercollector.core.OfferOutcomes.Kind.DECLINE_DIALOG -> "DECLINED"
                else -> "UNKNOWN"
            }
            CardTitle("ההצעות במשמרת")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL" to "הכל", "ACCEPTED" to "התקבלו", "DECLINED" to "נדחו", "UNKNOWN" to "לא ידוע").forEach { (k, label) ->
                    val n = if (k == "ALL") own.size else own.count { kind(it) == k }
                    if (k == "UNKNOWN" && n == 0) return@forEach
                    FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text("$label ($n)") })
                }
            }
            own.filter { filter == "ALL" || kind(it) == filter }.forEach { OfferCard(it, outcomes[it.firstSeenMs]) }
        }
    }
}

// ---- what works ----

@Composable
fun LearnScreen(vm: MainViewModel) {
    val h = rememberHistory(vm)
    var showShadow by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("מה עובד", "מה כבר הוכח, מה רק השערה, ומה עוד לא ידוע — מהמשמרות שלך.")
        if (h == null) { AppCard { Text("טוען…") }; return@Column }
        Note("${h.workReviews.size} משמרות עבודה עד עכשיו. ממצא מסומן \"חוזר על עצמו\" רק אחרי 5 משמרות לפחות.")
        Insights.findings(h).forEach { f ->
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    EvidenceTag(f.label)
                }
                Text(f.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        AppCard {
            CardTitle("ניסוי 1: הצעה נוספת בזמן משלוח")
            Text(
                "השאלה: כשכבר יש לך הזמנה ברכב ומגיעה הצעה נוספת — לקבל אותה מכניס יותר לשעה, או פחות?\n" +
                    "איך בודקים: חלק מהמשמרות כרגיל (לקבל), חלק עם כלל אחד — לדחות כל הצעה שמגיעה בזמן משלוח. הטלת מטבע לפני המשמרת קובעת איזה סוג. אחרי כ-5 משמרות מכל סוג משווים נטו לשעת משמרת.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Line("משמרות רגילות", "0 מתוך 5")
            Line("משמרות בלי הצעות נוספות", "0 מתוך 5")
            Note("לא התחיל. הניסוי מתחיל רק אם תחליט/י — דחייה של הצעות עלולה להשפיע על המעמד שלך ב-Wolt.")
        }
        TextButton(onClick = { showShadow = !showShadow }) { Text(if (showShadow) "הסתרת מצב צל" else "מצב צל (מתקדם)") }
        if (showShadow) ShadowSection(vm)
    }
}

// ---- route ----

@Composable
fun RouteScreen(vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("מסלול", "איפה נסעת: קו המסלול, איסופים ומסירות. לצפייה אחרי המשמרת.")
        RouteSection(vm)
    }
}

/** Settings tab: the old Settings, plus the technical screens moved out of the main tabs. */
@Composable
fun MoreLinks(onDiagnostics: () -> Unit, onOffers: () -> Unit) {
    AppCard {
        CardTitle("עוד")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOffers) { Text("כל ההצעות") }
            OutlinedButton(onClick = onDiagnostics) { Text("אבחון טכני") }
        }
    }
}
