package dev.yk.offercollector.ui

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.horizontalScroll

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yk.offercollector.core.DetectedOffer
import dev.yk.offercollector.core.OfferAnalytics
import dev.yk.offercollector.core.OfferOutcomes
import dev.yk.offercollector.core.formatAgorot
import java.time.LocalDate
import kotlin.math.roundToLong

/** Period × hour window for the analysis tab. Pure, so it is unit-tested. */
enum class Period(val label: String) { TODAY("היום"), WEEK("7 ימים"), ALL("הכל") }
enum class Hours(val label: String, val from: Int, val to: Int) { ALL("כל השעות", 0, 24), EVENING("\u206618–22\u2069", 18, 22), MIDDAY("\u206611–15\u2069", 11, 15) }

fun filterOffers(offers: List<DetectedOffer>, period: Period, hours: Hours, today: LocalDate): List<DetectedOffer> = offers.filter { o ->
    val d = OfferAnalytics.localDate(o.firstSeenMs)
    val h = OfferAnalytics.localHour(o.firstSeenMs)
    val inPeriod = when (period) {
        Period.TODAY -> d == today
        Period.WEEK -> !d.isBefore(today.minusDays(6)) && !d.isAfter(today)
        Period.ALL -> true
    }
    inPeriod && h >= hours.from && h < hours.to
}

/** Pure: how the shown offers ended, from the detector (phone-only — the web engine can't see this). */
data class OutcomeCounts(val declined: Int, val accepted: Int, val unknown: Int, val noData: Int)

fun outcomeCounts(offers: List<DetectedOffer>, outcomes: Map<Long, OfferOutcomes.Outcome>): OutcomeCounts {
    val kinds = offers.map { outcomes[it.firstSeenMs]?.kind }
    return OutcomeCounts(
        declined = kinds.count { it == OfferOutcomes.Kind.DECLINED || it == OfferOutcomes.Kind.DECLINE_DIALOG },
        accepted = kinds.count { it == OfferOutcomes.Kind.ACCEPTED },
        unknown = kinds.count { it == OfferOutcomes.Kind.UNKNOWN },
        noData = kinds.count { it == null },
    )
}

private fun shekel(agorot: Double?) = agorot?.let { "₪" + formatAgorot(it.roundToLong()) } ?: "—"

@Composable
private fun Bars(values: List<Int>, labels: List<String>, highlight: (Int) -> Boolean = { false }) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, v ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (v > 0) Text("$v", style = MaterialTheme.typography.labelSmall)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((72f * v / max).dp.coerceAtLeast(if (v > 0) 3.dp else 0.dp))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (highlight(i)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            labels.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr), maxLines = 1) }
        }
    }
}

@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { FilterChip(selected = it == selected, onClick = { onSelect(it) }, label = { Text(label(it)) }) }
    }
}

/** The engine's offer analysis, on the phone, from the offers this phone collected. */
@Composable
fun AnalysisScreen(vm: MainViewModel) {
    val all by vm.offers.collectAsStateWithLifecycle()
    val outcomes by vm.offerOutcomes.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(Period.ALL) }
    var hours by rememberSaveable { mutableStateOf(Hours.ALL) }
    val today = remember { LocalDate.now(OfferAnalytics.ZONE) }
    val shown = remember(all, period, hours) { filterOffers(all, period, hours, today) }
    val r = remember(shown) { OfferAnalytics.analyze(shown.map(OfferAnalytics::point)) }
    val oc = remember(shown, outcomes) { outcomeCounts(shown, outcomes) }

    var view by rememberSaveable { mutableStateOf(3) } // 3 route (default) · 0 offers · 2 shadow
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenTitle(
                listOf("ניתוח הצעות", "צפיפות לפי אזור", "מצב צל", "מסלול משמרת")[view],
                listOf(
                    "אותו חישוב כמו ב-Wolt Optimizer, על ההצעות שנאספו בטלפון. תיאור בלבד — לא המלצה.",
                    "כמה הצעות לשעת זמינות בכל אזור (מיקום השליח), לפי חלון שעות. תיאור בלבד — לא המלצה.",
                    "תחזיות שקטות שנשמרות ברגע ההצעה, לבדיקה אחרי משמרות. בלי התראות.",
                    "איפה נסעת במשמרת: קו המסלול, איסופים ומסירות. לצפייה אחרי המשמרת.",
                )[view],
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The area-density view was removed from the app (it gave the courier no usable information);
                // its data is still collected and exported for the engine.
                FilterChip(selected = view == 3, onClick = { view = 3 }, label = { Text("מסלול משמרת") })
                FilterChip(selected = view == 0, onClick = { view = 0 }, label = { Text("הצעות") })
                FilterChip(selected = view == 2, onClick = { view = 2 }, label = { Text("מצב צל") })
            }
        }
        if (view == 2) {
            item { ShadowSection(vm) }
            return@LazyColumn
        }
        if (view == 3) {
            item { RouteSection(vm) }
            return@LazyColumn
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipRow(Period.entries, period, Period::label) { period = it }
                ChipRow(Hours.entries, hours, Hours::label) { hours = it }
            }
        }
        if (r.count == 0) {
            item { AppCard { Text("אין הצעות בתקופה ובשעות שנבחרו.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            return@LazyColumn
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(r.count.toString(), "הצעות")
                MetricTile(shekel(r.amount.mean), "ממוצע")
                MetricTile(shekel(r.amount.median), "חציון")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(r.distanceM.mean?.let { String.format(java.util.Locale.US, "%.1f", it / 1000) } ?: "—", "ק״מ ממוצע")
                MetricTile(shekel(r.agorotPerKmAggregate), "₪ לק״מ")
                MetricTile(r.gapsMinutes.median?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—", "דק׳ בין הצעות")
            }
        }
        item {
            AppCard {
                CardTitle("מה קרה להצעות")
                Text("לפי רצף המסכים בטלפון (משוער). זה מידע שיש רק באפליקציה.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile(oc.declined.toString(), "נדחו")
                    MetricTile(oc.accepted.toString(), "התקבלו")
                    MetricTile(oc.unknown.toString(), "לא ידוע")
                    MetricTile(oc.noData.toString(), "אין נתון")
                }
            }
        }
        item {
            AppCard {
                CardTitle("הצעות מעל סף מחיר")
                r.thresholds.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("₪${t.shekel} ומעלה", Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
                        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((t.share ?: 0.0).toFloat()).background(MaterialTheme.colorScheme.primary))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${t.atOrAbove} · ${((t.share ?: 0.0) * 100).roundToLong()}%", Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        item {
            AppCard {
                CardTitle("הצעות לפי שעה")
                Text("ספירה, לא קצב — לא ידוע כמה זמן היית זמין/ה בכל שעה.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val from = r.byHour.minOf { it.hour }
                val to = r.byHour.maxOf { it.hour }
                val byHour = r.byHour.associateBy { it.hour }
                Bars((from..to).map { byHour[it]?.n ?: 0 }, (from..to).map { String.format(java.util.Locale.US, "%02d", it) })
                r.byHour.forEach { h ->
                    Text(String.format(java.util.Locale.US, "%02d:00 · %d הצעות · ממוצע %s", h.hour, h.n, shekel(h.meanAgorot)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            AppCard {
                CardTitle("התפלגות סכומים")
                Text("טווחים של ₪5.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val lo = r.distribution.first().fromAgorot
                val hi = r.distribution.last().fromAgorot
                val bins = r.distribution.associateBy { it.fromAgorot }
                val keys = generateSequence(lo) { it + 500 }.takeWhile { it <= hi }.toList()
                Bars(keys.map { bins[it]?.n ?: 0 }, keys.map { "${it / 100}" })
            }
        }
    }
}
