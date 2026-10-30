package dev.yk.offercollector.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yk.offercollector.core.DetectedOffer
import dev.yk.offercollector.core.OfferOutcomes
import dev.yk.offercollector.core.formatAgorot
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val dayFmt = DateTimeFormatter.ofPattern("EEEE, dd.MM", java.util.Locale.forLanguageTag("he")).withZone(ZoneId.systemDefault())

private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

private fun shekel(agorot: Long) = "₪" + formatAgorot(agorot)

/** ₪ per km in agorot, or null when the distance is unknown. */
private fun perKmAgorot(o: DetectedOffer): Long? =
    o.distanceKm?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { km ->
        o.amountAgorot.toBigDecimal().divide(km, 0, RoundingMode.HALF_UP).toLong()
    }

private val Success = Color(0xFF1E9E5A)
private val Warn = Color(0xFFE08A00)

private enum class HeroState { SETUP_NEEDED, STOPPED, RUNNING, RUNNING_DISCONNECTED }

@Composable
fun HomeScreen(vm: MainViewModel, onPick: () -> Unit, planCards: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val st by vm.status.collectAsStateWithLifecycle()

    val sourceOk = s.selectedPackage != null && vm.selectedPackageStillValid()
    val setupDone = s.notificationConsent && sourceOk && st.notificationAccessGranted
    val hero = when {
        !setupDone -> HeroState.SETUP_NEEDED
        !s.monitoringEnabled -> HeroState.STOPPED
        st.notificationListenerConnected && (!s.accessibilityConsent || st.accessibilityServiceConnected) -> HeroState.RUNNING
        else -> HeroState.RUNNING_DISCONNECTED
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("לפני משמרת", "מתי, איפה ואיך לעבוד — לפי המשמרות שלך. כלום לא מוצג בזמן נהיגה.")
        HeroCard(hero, vm.sourceLabel(), onStart = { vm.start() }, onStop = { vm.stop() })

        val steps = listOf(
            SetupStep("בחירת אפליקציית Wolt", "ממנה נאספות ההצעות", sourceOk, "בחירה", onClick = onPick),
            SetupStep("גישה להתראות", "חובה — לזיהוי הצעות שמגיעות כהתראה", st.notificationAccessGranted, "פתיחה") {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            SetupStep("קריאת מסך", "לקריאת המחיר, המרחק והמסעדה מהכרטיס", st.accessibilityEnabledInSettings, "פתיחה", optional = !s.accessibilityConsent) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            SetupStep("סוללה ללא הגבלה", "כדי ש-Samsung לא יעצור את האיסוף", st.batteryUnrestricted, "פתיחה", optional = true) {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            },
        )
        if (steps.any { !it.done }) SetupChecklist(steps)

        ShiftCard(vm)

        planCards()

        PickupAddressGateCard(vm)

        TechnicalDetails(vm)

        Text(
            "⚠ לא להשתמש בזמן נהיגה. האיסוף פועל לבד ברקע.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun HeroCard(state: HeroState, sourceLabel: String?, onStart: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    data class Look(val pill: String, val dot: Color, val title: String, val subtitle: String)
    val look = when (state) {
        HeroState.SETUP_NEEDED -> Look("דרושה הגדרה", Warn, "עוד כמה צעדים", "השלם/י את ההגדרה למטה כדי להתחיל")
        HeroState.STOPPED -> Look("מופסק", cs.outline, "האיסוף כבוי", "הפעל/י כדי לתעד הצעות מ-Wolt")
        HeroState.RUNNING -> Look("פעיל", Success, "אוסף הצעות", "פועל ברקע — אפשר לסגור את האפליקציה")
        HeroState.RUNNING_DISCONNECTED -> Look("לא מחובר", Warn, "שירות לא מחובר", "פתח/י את הפרטים הטכניים לבדיקה")
    }
    AppCard(container = cs.primaryContainer) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusPill(look.pill, look.dot, cs.surface, cs.onSurface)
            if (sourceLabel != null) Text(sourceLabel, style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(look.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
            Text(look.subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer.copy(alpha = 0.8f))
        }
        when (state) {
            HeroState.SETUP_NEEDED -> Unit
            HeroState.STOPPED -> Button(onStart, Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("התחל איסוף", style = MaterialTheme.typography.titleMedium)
            }
            else -> TextButton(onStop) { Text("עצירת האיסוף", color = cs.onPrimaryContainer) }
        }
    }
}

private data class SetupStep(
    val title: String,
    val hint: String,
    val done: Boolean,
    val action: String,
    val optional: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun SetupChecklist(steps: List<SetupStep>) {
    AppCard {
        CardTitle("הגדרה")
        steps.forEachIndexed { i, step ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(if (step.done) Success else MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (step.done) "✓" else "${i + 1}", color = if (step.done) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(step.title + if (step.optional && !step.done) " · מומלץ" else "", fontWeight = FontWeight.Medium)
                    Text(step.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!step.done) FilledTonalButton(step.onClick, contentPadding = PaddingValues(horizontal = 16.dp)) { Text(step.action) }
            }
        }
        Text(
            "מתג אפור (\"Restricted setting\")? פרטי אפליקציה ← ⋮ ← Allow restricted settings",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TodaySection(offers: List<DetectedOffer>, outcomes: Map<Long, OfferOutcomes.Outcome>, onOffers: () -> Unit) {
    val today = offers.filter { localDate(it.firstSeenMs) == LocalDate.now() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CardTitle("היום")
            if (offers.isNotEmpty()) TextButton(onOffers) { Text("כל ההצעות") }
        }
        if (today.isEmpty()) {
            AppCard { Text("עדיין לא זוהו הצעות היום. כשתוצג הצעה ב-Wolt היא תופיע כאן.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val perKm = today.mapNotNull(::perKmAgorot)
            MetricTile(today.size.toString(), "הצעות")
            MetricTile(shekel(today.sumOf { it.amountAgorot } / today.size), "ממוצע להצעה")
            MetricTile(if (perKm.isEmpty()) "—" else shekel(perKm.sum() / perKm.size), "ממוצע לק״מ")
        }
        Text("אחרונה", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        OfferCard(today.first(), outcomes[today.first().firstSeenMs])
    }
}

@Composable
fun OfferCard(o: DetectedOffer, outcome: OfferOutcomes.Outcome? = null) {
    AppCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                shekel(o.amountAgorot),
                style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Ltr),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            OutcomePill(outcome)
            Spacer(Modifier.width(8.dp))
            Text(
                timeFmt.format(Instant.ofEpochMilli(o.firstSeenMs)),
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (o.pickupNames.isEmpty()) "מסעדה לא זוהתה" else o.pickupNames.joinToString(" · "),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = if (o.pickupNames.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        o.orderCount?.takeIf { it > 1 }?.let {
            Text("🧾 $it הזמנות במשלוח אחד", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        o.packageSize?.let {
            Text("📦 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(o.distanceKm ?: "—", "ק״מ")
            MetricTile(perKmAgorot(o)?.let(::shekel) ?: "—", "לק״מ")
            MetricTile(((o.lastSeenMs - o.firstSeenMs) / 1000).coerceAtLeast(1).toString(), "שניות על המסך")
        }
    }
}

/** Pure: badge text for an offer's outcome. "משוער" whenever the detector only saw a screen sequence. */
fun outcomeLabel(o: OfferOutcomes.Outcome): String {
    val est = if (o.evidence == "OBSERVED") "" else " · משוער"
    return when (o.kind) {
        OfferOutcomes.Kind.ACCEPTED -> "התקבלה$est"
        OfferOutcomes.Kind.DECLINED -> "נדחתה$est"
        OfferOutcomes.Kind.DECLINE_DIALOG -> "חלון דחייה הופיע"
        OfferOutcomes.Kind.UNKNOWN -> "לא ידוע · פג תוקף?"
    }
}

@Composable
private fun OutcomePill(outcome: OfferOutcomes.Outcome?) {
    val cs = MaterialTheme.colorScheme
    if (outcome == null) {
        StatusPill("אין נתון", cs.outlineVariant, cs.surfaceContainerHigh, cs.onSurfaceVariant)
        return
    }
    val dot = when (outcome.kind) {
        OfferOutcomes.Kind.ACCEPTED -> Color(0xFF2E9E5B)
        OfferOutcomes.Kind.DECLINED, OfferOutcomes.Kind.DECLINE_DIALOG -> cs.error
        OfferOutcomes.Kind.UNKNOWN -> cs.outline
    }
    StatusPill(outcomeLabel(outcome), dot, cs.surfaceContainerHigh, cs.onSurface)
}

@Composable
private fun TechnicalDetails(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val st by vm.status.collectAsStateWithLifecycle()
    AppCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("פרטים טכניים", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            TextButton(onClick = { open = !open; vm.refreshStatus() }) {
                Text(if (open) "הסתר" else "הצג")
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
            }
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusRow("הסכמה להתראות", s.notificationConsent, "ניתנה", "לא ניתנה")
                StatusRow("הסכמה לקריאת מסך", s.accessibilityConsent, "ניתנה", "לא ניתנה")
                StatusRow("גישה להתראות (מערכת)", st.notificationAccessGranted, "מאושרת", "לא מאושרת")
                StatusRow("שירות ההתראות", st.notificationListenerConnected, "מחובר", "לא מחובר")
                StatusRow("שירות נגישות (מערכת)", st.accessibilityEnabledInSettings, "מופעל", "כבוי")
                StatusRow("שירות נגישות", st.accessibilityServiceConnected, "מחובר", "לא מחובר")
                StatusRow("סוללה ללא הגבלה", st.batteryUnrestricted, "כן", "לא")
                StatusRow("ניטור", s.monitoringEnabled, "פועל", "מופסק")
                s.selectedPackage?.let { Ltr(it) }
            }
        }
    }
}

@Composable
fun OffersScreen(vm: MainViewModel) {
    val offers by vm.offers.collectAsStateWithLifecycle()
    val outcomes by vm.offerOutcomes.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("הצעות", "מה שהוצג על המסך ב-Wolt, ומה קרה לכל הצעה לפי רצף המסכים. \"משוער\" = לא נצפתה לחיצה, רק המסך שאחריה.") }
        if (offers.isEmpty()) item {
            AppCard { Text("עדיין אין הצעות. כשתוצג הצעה ב-Wolt היא תופיע כאן.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        offers.groupBy { localDate(it.firstSeenMs) }.forEach { (day, list) ->
            item(key = "h$day") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (day == LocalDate.now()) "היום" else dayFmt.format(Instant.ofEpochMilli(list.first().firstSeenMs)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${list.size} הצעות · ממוצע ${shekel(list.sumOf { it.amountAgorot } / list.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(list, key = { "o${it.firstSeenMs}" }) { OfferCard(it, outcomes[it.firstSeenMs]) }
        }
    }
}
