package dev.yk.offercollector.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yk.offercollector.core.AreaDensity
import dev.yk.offercollector.core.AreaDensity.Quality
import dev.yk.offercollector.core.AreaDensity.RateKind
import dev.yk.offercollector.core.AreaDensity.TimeState
import dev.yk.offercollector.core.OfferAnalytics
import dev.yk.offercollector.core.PickupDemand
import dev.yk.offercollector.core.formatAgorot
import java.time.LocalDate
import kotlin.math.roundToLong

private fun shekel(a: Double?) = a?.let { "₪" + formatAgorot(it.roundToLong()) } ?: "—"
private fun dur(ms: Long): String {
    val m = Math.round(ms / 60_000.0)
    return if (m < 60) "$m דק׳" else "${m / 60} ש׳ ${m % 60} דק׳"
}

/** Pure: how a rate is written. A verified value is plain; an estimate is marked; no data says so. */
fun rateText(r: AreaDensity.Rate): String = when (r.kind) {
    RateKind.VERIFIED -> String.format(java.util.Locale.US, "%.1f לשעה (%.1f–%.1f)", r.perHour, r.low, r.high)
    RateKind.ESTIMATE -> String.format(java.util.Locale.US, "~%.1f לשעה · אומדן (Online לא מאומת)", r.perHour)
    RateKind.NONE -> "אין נתונים"
}

private val STATE_LABEL = mapOf(
    TimeState.VERIFIED to "זמין מאומת",
    TimeState.ONLINE to "Online לא מאומת",
    TimeState.DELIVERY to "במשלוח",
    TimeState.OFFLINE to "Offline",
    TimeState.UNKNOWN to "לא ידוע",
)

@Composable
private fun stateColor(s: TimeState): Color = when (s) {
    TimeState.VERIFIED -> MaterialTheme.colorScheme.primary
    TimeState.ONLINE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    TimeState.DELIVERY -> Color(0xFF2E9E5B)
    TimeState.OFFLINE -> Color(0xFF868E96)
    TimeState.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
}

@Composable
private fun StateBreakdown(time: Map<TimeState, Long>) {
    val total = time.values.sum().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
        TimeState.entries.forEach { s -> val v = time[s] ?: 0; if (v > 0) Box(Modifier.weight(v.toFloat() / total).fillMaxHeight().background(stateColor(s))) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TimeState.entries.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(s)))
                Text("  ${STATE_LABEL[s]}: ${dur(time[s] ?: 0)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun qualityLabel(q: Quality) = when (q) {
    Quality.NONE -> "אין מספיק נתונים"
    Quality.LOW -> "ראשוני"
    Quality.FAIR -> "מדגם סביר"
}

@Composable
private fun QualityChip(q: Quality) {
    val c = when (q) {
        Quality.NONE -> Color(0xFFE8590C)
        Quality.LOW -> MaterialTheme.colorScheme.primary
        Quality.FAIR -> Color(0xFF2E9E5B)
    }
    Text(
        qualityLabel(q),
        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp),
        color = c, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun AreaDetails(a: AreaDensity.Area, name: String, onRename: () -> Unit) {
    val ctx = LocalContext.current
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("ריבוע של כ-500 מ׳ · מיקום השליח בעת ההצעה", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            QualityChip(a.quality)
        }
        Text("צפיפות", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text("הצעות לשעת זמינות: ${rateText(a.rate)}", fontWeight = FontWeight.Medium)
        Text("${a.offers} הצעות · זמינות מאומתת ${dur(a.verifiedMs)} · Online לא מאומת ${dur(a.onlineUnverifiedMs)}", style = MaterialTheme.typography.bodySmall)
        Text("ערך", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text("ממוצע ${shekel(a.meanAgorot)} · חציון ${shekel(a.medianAgorot)}")
        Text(
            AreaDensity.THRESHOLDS_SHEKEL.joinToString(" · ") { t -> "מעל ₪$t: " + (a.shareAbove[t]?.let { "${Math.round(it * 100)}%" } ?: "—") },
            style = MaterialTheme.typography.bodySmall,
        )
        Text("מדגם: ${a.shifts} משמרות · דיוק GPS חציוני ${a.medianAccuracyM?.let { "$it מ׳" } ?: "—"}", style = MaterialTheme.typography.bodySmall)
        if (a.restaurants.isNotEmpty()) {
            Text(
                "מסעדות האיסוף בהצעות האלה (מיקום המסעדה — לא המדד): " + a.restaurants.joinToString(", ") { "${it.first} ×${it.second}" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        a.limits.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE8590C)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRename) { Text("שינוי שם") }
            if (a.centerLat != null && a.centerLon != null) {
                OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${a.centerLat},${a.centerLon}?q=${a.centerLat},${a.centerLon}(${Uri.encode(name)})")))
                    }
                }) { Text("פתח במפות") }
            }
        }
    }
}

@Composable
private fun Compare(areas: List<AreaDensity.Area>, name: (String) -> String) {
    AppCard {
        CardTitle("השוואה באותו חלון שעות")
        val rows: List<Pair<String, (AreaDensity.Area) -> String>> = listOf(
            "הצעות לשעת זמינות" to { a -> rateText(a.rate) },
            "זמינות מאומתת" to { a -> dur(a.verifiedMs) },
            "Online לא מאומת" to { a -> dur(a.onlineUnverifiedMs) },
            "הצעות" to { a -> a.offers.toString() },
            "ממוצע / חציון" to { a -> "${shekel(a.meanAgorot)} / ${shekel(a.medianAgorot)}" },
            "מעל ₪35 / ₪40" to { a -> listOf(35, 40).joinToString(" / ") { t -> a.shareAbove[t]?.let { "${Math.round(it * 100)}%" } ?: "—" } },
            "משמרות במדגם" to { a -> a.shifts.toString() },
            "איכות" to { a -> qualityLabel(a.quality) },
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(120.dp)) {
                Text(" ", fontWeight = FontWeight.Bold)
                rows.forEach { Text(it.first, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            areas.forEach { a ->
                Column(Modifier.width(150.dp).padding(start = 8.dp)) {
                    Text(name(a.cellId), fontWeight = FontWeight.Bold, maxLines = 1)
                    rows.forEach { Text(it.second(a), Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Text("עמודות בסדר הבחירה. אף אזור לא מוכרז כמומלץ.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Area demand density by courier position and hour window (spec: Area Demand Density). */
@Composable
fun DensitySection(vm: MainViewModel) {
    val input by vm.densityInput.collectAsStateWithLifecycle()
    val names by vm.areaNames.collectAsStateWithLifecycle()
    var windowIdx by rememberSaveable { mutableStateOf(0) }
    var lastWeek by rememberSaveable { mutableStateOf(false) }
    var layer by rememberSaveable { mutableStateOf(Layer.RECEPTION) }
    var minPrice by rememberSaveable { mutableStateOf<Long?>(3000L) }
    var maxKm by rememberSaveable { mutableStateOf<Double?>(8.0) }
    var minPerKm by rememberSaveable { mutableStateOf<Long?>(null) }
    val places by vm.pickupPlaces.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val compared = remember { mutableStateListOf<String>() }
    var renaming by remember { mutableStateOf<String?>(null) }
    val inp = input ?: run { Text("טוען…"); return }
    if (inp.samples.isEmpty()) {
        AppCard { Text("עדיין אין נתוני מיקום. הצפיפות נבנית ממשמרות שמתחילים בבית ← ״התחלת משמרת״.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val base = AreaDensity.PRESET_WINDOWS[windowIdx]
    val window = base.copy(fromDate = if (lastWeek) LocalDate.now(OfferAnalytics.ZONE).minusDays(6) else null)
    val r = remember(inp, window) { AreaDensity.analyze(inp.shifts, inp.samples, inp.marks, inp.trips, inp.offers, window) }
    val perWindow = remember(inp) { AreaDensity.shiftsPerWindow(inp.shifts, inp.samples, inp.marks, inp.trips) }
    val terms = PickupDemand.Terms(minPrice, maxKm, minPerKm)
    val acc = remember(inp, window, terms) {
        val ok = inp.offerDetails.filter(terms::accepts).map { it.firstSeenMs }.toSet()
        AreaDensity.analyze(inp.shifts, inp.samples, inp.marks, inp.trips, inp.offers.filter { it.firstSeenMs in ok }, window)
    }
    val geocoded = remember(places) { places.filter { it.geoStatus == "OK" && it.cellId != null }.associate { it.key to it.cellId!! } }
    val pk = remember(inp, window, geocoded) { PickupDemand.pickupDensity(PickupDemand.assign(inp.offerDetails, inp.pickups, geocoded), window) }
    var placeNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val unnamed = (r.areas.map { it.cellId } + pk.areas.map { it.cellId }).distinct().filter { it !in names && it !in placeNames }.sorted()
    // Your own name first, then the neighbourhood from the map; otherwise a plain numbered label — never an internal code.
    val nameOf = { id: String -> names[id] ?: placeNames[id] ?: "אזור ללא שם ${unnamed.indexOf(id) + 1}" }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AreaDensity.PRESET_WINDOWS.forEachIndexed { i, w ->
                FilterChip(selected = i == windowIdx, onClick = { windowIdx = i }, label = {
                    Text("${w.label} · ${perWindow[w] ?: 0}", style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Rtl))
                })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !lastWeek, onClick = { lastWeek = false }, label = { Text("כל התקופה") })
            FilterChip(selected = lastWeek, onClick = { lastWeek = true }, label = { Text("7 ימים") })
        }
        Text("המספר על כל חלון = כמה משמרות נמדדו בו.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        AppCard {
            CardTitle("${window.label} · ${r.shifts} משמרות · ${r.offersInWindow} הצעות")
            StateBreakdown(r.totalsMs)
            if ((r.totalsMs[TimeState.VERIFIED] ?: 0) == 0L) {
                Text("אין עדיין זמן זמינות מאומת — הקצב מוצג כאומדן או ״אין נתונים״.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE8590C))
            }
            if (r.offersWithoutArea > 0) Text("${r.offersWithoutArea} הצעות בלי מיקום שליח — לא נספרו באף אזור.", style = MaterialTheme.typography.bodySmall)
        }

        PickupAddressGateCard(vm)

        AppCard {
            CardTitle("מה להציג על המפה")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Layer.entries.forEach { l -> FilterChip(selected = layer == l, onClick = { layer = l }, label = { Text(l.label) }) }
            }
            Text(layer.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (layer == Layer.ACCESSIBLE) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null, 3000L, 3500L, 4000L).forEach { v -> FilterChip(selected = minPrice == v, onClick = { minPrice = v }, label = { Text(v?.let { "מ-₪${it / 100}" } ?: "כל מחיר") }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null, 5.0, 8.0, 12.0).forEach { v -> FilterChip(selected = maxKm == v, onClick = { maxKm = v }, label = { Text(v?.let { "עד ${it.toInt()} ק״מ" } ?: "כל מרחק") }) }
                    listOf(null, 400L, 500L).forEach { v -> FilterChip(selected = minPerKm == v, onClick = { minPerKm = v }, label = { Text(v?.let { "≥₪${it / 100} לק״מ" } ?: "כל ₪/ק״מ") }) }
                }
                Text("${acc.offersInWindow} מתוך ${r.offersInWindow} הצעות עומדות בתנאים (${terms.label}). הצעה בלי מרחק ידוע לא נחשבת עומדת בתנאי מרחק.", style = MaterialTheme.typography.bodySmall)
            }
        }

        val mapAreas = when (layer) {
            Layer.RECEPTION -> r.areas.map { MapArea(it.cellId, it.rate.perHour, nameOf(it.cellId)) }
            Layer.VALUE -> r.areas.map { MapArea(it.cellId, it.medianAgorot, nameOf(it.cellId)) }
            Layer.ACCESSIBLE -> acc.areas.map { MapArea(it.cellId, it.rate.perHour, nameOf(it.cellId)) }
            Layer.PICKUP -> pk.areas.map { MapArea(it.cellId, it.offers.toDouble(), nameOf(it.cellId)) }
        }
        if (mapAreas.isNotEmpty()) {
            AppCard {
                CardTitle(layer.label)
                AreaMap(mapAreas, selected ?: mapAreas.first().cellId, onSelect = { selected = it }, onPlaceNames = { found -> if (found + placeNames != placeNames) placeNames = placeNames + found }, pickupLayer = layer == Layer.PICKUP)
                Text(layer.legend, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (layer == Layer.PICKUP) {
            AppCard {
                CardTitle("נקודות איסוף")
                Text(
                    "עדיין אין אף הצעה עם מיקום איסוף ידוע. מיקום עסק נקבע רק מכתובת שאישרת (ממופה פעם אחת) או מה-GPS ברגע האיסוף — לא משם בלבד.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (layer == Layer.PICKUP) {
            PickupPanel(pk, nameOf, places)
        } else {
            val list = if (layer == Layer.ACCESSIBLE) acc.areas else r.areas
            val selA = list.firstOrNull { it.cellId == selected } ?: list.firstOrNull()
            selA?.let { AreaDetails(it, nameOf(it.cellId)) { renaming = it.cellId } }
            if (list.isNotEmpty()) AppCard {
                CardTitle(if (layer == Layer.ACCESSIBLE) "אזורים — הצעות כדאיות" else "אזורים בחלון הזה (מיקום השליח)")
                Text("לפי קצב שנמדד (מאומת, אחר כך אומדן, אחר כך בלי נתונים) — לא לפי כמות הצעות.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.forEach { a ->
                    Row(Modifier.fillMaxWidth().clickable { selected = a.cellId }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = a.cellId in compared, onCheckedChange = { on -> if (on && compared.size < 3) compared += a.cellId else compared -= a.cellId })
                        Column(Modifier.weight(1f)) {
                            Text(nameOf(a.cellId), fontWeight = if (a.cellId == selA?.cellId) FontWeight.Bold else FontWeight.Medium)
                            Text("${rateText(a.rate)} · חציון ${shekel(a.medianAgorot)} · ${a.offers} הצעות · ${a.shifts} משמרות", style = MaterialTheme.typography.bodySmall)
                        }
                        QualityChip(a.quality)
                    }
                }
            }
            val cmp = compared.mapNotNull { id -> list.firstOrNull { it.cellId == id } }
            if (cmp.size >= 2) Compare(cmp, nameOf) else Text("סמנו 2–3 אזורים ברשימה כדי להשוות אותם באותו חלון שעות.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AppCard {
            CardTitle("מה למדוד בהמשך")
            r.toMeasure.forEach { line ->
                // Area lines come as "@<cellId>|<reason>" so the screen can show the area's readable name.
                val text = if (line.startsWith("@")) line.drop(1).split("|", limit = 2).let { (id, why) -> "${nameOf(id)}: $why" } else line
                Text("• $text", style = MaterialTheme.typography.bodySmall)
            }
            val empty = perWindow.filter { (w, n) -> w.fromHour != 0 && n < AreaDensity.FAIR_SHIFTS }.keys
            if (empty.isNotEmpty()) Text("• חלונות עם פחות מ-${AreaDensity.FAIR_SHIFTS} משמרות: " + empty.joinToString(", ") { it.label }, style = MaterialTheme.typography.bodySmall)
            Text("לתכנון לפני משמרת או כשחונים — לא בזמן נהיגה.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    renaming?.let { id ->
        var text by remember(id) { mutableStateOf(names[id] ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("שם לאזור") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("למשל: בית, מרכז בת ים") }) },
            confirmButton = { TextButton(onClick = { vm.renameArea(id, text); renaming = null }) { Text("שמור") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("ביטול") } },
        )
    }
}


/** Map layers — never mixed: where offers reached the courier, where orders start, which ones were worth it, and their value. */
enum class Layer(val label: String, val note: String, val legend: String) {
    RECEPTION(
        "קבלת הצעות",
        "כמה הצעות קיבלת לשעת זמינות, לפי איפה *את/ה* היית. זה לא בהכרח מקום ההזמנה.",
        "ריבוע = מיקום השליח (≈500 מ׳). כהה = יותר הצעות לשעת זמינות. אפור = אין מספיק זמן מאומת. לחיצה = פרטים.",
    ),
    PICKUP(
        "נקודות איסוף",
        "מאיפה יצאו ההזמנות בהצעות שקיבלת (מיקום העסק). מדגם ההצעות שנצפו אצלך — לא כל הביקוש של Wolt.",
        "ריבוע כתום = אזור עסקים (≈500 מ׳). כהה = יותר הצעות שנצפו מהאזור. ספירה, לא קצב. לחיצה = פרטים.",
    ),
    ACCESSIBLE(
        "הצעות כדאיות",
        "הצעות שעומדות בתנאים שלך (מחיר, מרחק, ₪/ק״מ), לשעת זמינות, לפי איפה היית.",
        "ריבוע = מיקום השליח. כהה = יותר הצעות כדאיות לשעת זמינות. אפור = אין מספיק זמן מאומת.",
    ),
    VALUE(
        "ערך",
        "התשלום החציוני בהצעות שקיבלת, לפי איפה היית. ערך — לא צפיפות.",
        "כהה = תשלום חציוני גבוה יותר. אפור = אין הצעות.",
    ),
}

private fun basisLabel(b: PickupDemand.Basis) = when (b) {
    PickupDemand.Basis.ADDRESS_AND_GPS -> "כתובת + GPS באיסוף"
    PickupDemand.Basis.ADDRESS -> "כתובת מאומתת"
    PickupDemand.Basis.GPS_AT_PICKUP -> "GPS באיסוף"
    PickupDemand.Basis.NEEDS_REVIEW -> "סתירה — לבדיקה"
    PickupDemand.Basis.UNKNOWN -> "לא ידוע"
}

@Composable
private fun PickupPanel(pk: PickupDemand.PickupResult, nameOf: (String) -> String, places: List<dev.yk.offercollector.data.PickupPlaceEntity>) {
    AppCard {
        CardTitle("אזורי איסוף בחלון הזה")
        Text("מדגם ההצעות שנצפו אצלך, לפי מיקום העסק — לא הביקוש הכולל של Wolt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("מיקום איסוף ידוע: ${pk.known} · לא ידוע: ${pk.unknown}" + if (pk.needsReview > 0) " · לבדיקה: ${pk.needsReview}" else "", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        pk.areas.forEach { a ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("${nameOf(a.cellId)} · ${a.offers} הצעות · חציון ${shekel(a.medianAgorot)}", fontWeight = FontWeight.Medium)
                Text(a.businesses.joinToString(", ") { "${it.first} ×${it.second}" }, style = MaterialTheme.typography.bodySmall)
                Text("מקור המיקום: " + a.bases.entries.joinToString(", ") { "${basisLabel(it.key)} ×${it.value}" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val hours = a.byHour.withIndex().filter { it.value > 0 }.joinToString(" · ") { "%02d:00 ×%d".format(it.index, it.value) }
                if (hours.isNotEmpty()) Text("לפי שעה: $hours", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (places.isNotEmpty()) AppCard {
        CardTitle("עסקים שנשמרו (שם + כתובת)")
        val byStatus = places.groupingBy { it.geoStatus }.eachCount()
        Text(
            "ממופים: ${byStatus["OK"] ?: 0} · ממתינים: ${byStatus["PENDING"] ?: 0} · לא נמצאו: ${byStatus["NOT_FOUND"] ?: 0} · כמה מקומות אפשריים: ${byStatus["AMBIGUOUS"] ?: 0} · שגיאה: ${byStatus["ERROR"] ?: 0}",
            style = MaterialTheme.typography.bodySmall,
        )
        places.takeLast(8).reversed().forEach { p ->
            Text("• ${p.name} — ${p.address} (${when (p.geoStatus) { "OK" -> "ממופה"; "PENDING" -> "ממתין"; "NOT_FOUND" -> "לא נמצא"; "AMBIGUOUS" -> "לא חד-משמעי — לא סומן"; else -> "שגיאה, ינוסה שוב" }})", style = MaterialTheme.typography.bodySmall)
        }
        Text("למיפוי נשלחת רק כתובת העסק (OpenStreetMap), פעם אחת לכל כתובת. לא נשלחים כתובות לקוחות או המיקום שלך.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One-time confirmation on a REAL card that the stored line is the restaurant's address (spec 1). */
@Composable
fun PickupAddressGateCard(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val cand by vm.pickupCandidate.collectAsStateWithLifecycle()
    if (s.pickupAddressMode != dev.yk.offercollector.service.PickupAddresses.UNVERIFIED) return
    AppCard {
        CardTitle("אישור כתובת איסוף")
        if (cand == null) {
            Text("בהצעה הבאה תוצג כאן השורה שנראית ככתובת העסק, לאישור חד-פעמי שלך. עד אז היא לא נשמרת.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("בכרטיס ההצעה האחרון, מתחת לשם העסק:", style = MaterialTheme.typography.bodySmall)
            Text(cand!!.name, fontWeight = FontWeight.Bold)
            Text(cand!!.address, fontWeight = FontWeight.Medium)
            Text("האם זו הכתובת של המסעדה/העסק (ולא של הלקוח)? רק כשחונים.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.confirmPickupAddress() }) { Text("כן, זו כתובת העסק") }
                OutlinedButton(onClick = { vm.rejectPickupAddress() }) { Text("לא") }
            }
        }
    }
}
