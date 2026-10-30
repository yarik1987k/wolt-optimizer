package dev.yk.offercollector.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yk.offercollector.core.formatAgorot
import dev.yk.offercollector.data.AppSettings
import dev.yk.offercollector.data.NotificationEventEntity
import dev.yk.offercollector.data.ScreenEventEntity
import dev.yk.offercollector.export.ExportService
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/** The whole row (box + label) is one toggle target. */
@Composable
private fun CheckRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
}

@Composable
fun ConsentScreen(vm: MainViewModel, onDone: () -> Unit) {
    var notif by remember { mutableStateOf(false) }
    var a11y by remember { mutableStateOf(false) }
    var understood by remember { mutableStateOf(false) }
    Page {
        Text("Wolt Offer Collector — הסכמה", style = MaterialTheme.typography.headlineSmall)
        Section("מה האפליקציה עושה") {
            Text("בודקת, באופן פסיבי ורק על המכשיר שלך, האם ניתן לתעד הצעות משלוח שמוצגות באפליקציית המקור שתבחר/י. זהו ניסוי היתכנות (Phase 0).")
            Text("אין שרת, אין חשבון, אין הרשאת אינטרנט — הנתונים לא יוצאים מהמכשיר אלא אם תייצא/י קובץ בעצמך.")
            Text("כמעט שום טקסט לא נשמר כמו שהוא: הכול מומר מיד לסימונים (למשל [AMOUNT ₪23.50], [TEXT]). שמות אנשים, כתובות, טלפונים וטקסט חופשי מוסרים לפני השמירה.")
            Text("החריג היחיד: שם המסעדה (נקודת האיסוף) מכרטיס הצעה נשמר כטקסט — רק אם אין בו טלפון או מספר ארוך. כתובת היעד לא נשמרת בכלל.")
            Text("האפליקציה לעולם לא לוחצת, לא מקבלת/דוחה הזמנות, לא מציגה חלונות מעל אפליקציה אחרת, ולא שולחת התראות.")
            Text("אפשר להפסיק בכל רגע (Stop), לבטל הסכמה ולמחוק את כל הנתונים.")
        }
        Section("⚠ בטיחות") {
            Text("אין להשתמש באפליקציה, להגדיר אותה או לבדוק תוצאות בזמן נהיגה. בדיקות רק כשהרכב חונה.")
        }
        Section("הסכמה 1 — גישה להתראות") {
            Text("Notification Access מאפשר לאפליקציה לקבל את כל ההתראות במכשיר. האפליקציה מתעלמת מכל התראה שאינה מהאפליקציה שבחרת, ושומרת רק סוג אירוע, זמן ותוכן מוסתר.")
            CheckRow(notif, "אני מסכים/ה לאיסוף התראות מהאפליקציה שאבחר") { notif = it }
        }
        Section("הסכמה 2 — קריאת מסך נגישה (אופציונלי)") {
            Text("שירות נגישות (Accessibility) מסוגל לקרוא טקסט מהמסך. כאן הוא מוגבל לאפליקציה שבחרת, לקריאה בלבד, ונועד רק לבדוק אם פרטי ההצעה נגישים בכלל.")
            Text("לפני הפעלה: באחריותך לוודא שהשימוש תואם את תנאי השימוש של Wolt Partner. היתכנות טכנית אינה אישור לשימוש.")
            CheckRow(a11y, "אני מסכים/ה לבדיקת קריאת מסך לקריאה בלבד") { a11y = it }
        }
        CheckRow(understood, "קראתי והבנתי, כולל ההנחיה שלא להשתמש בזמן נהיגה") { understood = it }
        Button(enabled = understood && notif, onClick = { vm.setConsents(notif, a11y); onDone() }, modifier = Modifier.fillMaxWidth()) {
            Text("אישור והמשך")
        }
        if (!notif) Text("נדרשת לפחות הסכמה לאיסוף התראות.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PackagePickerScreen(vm: MainViewModel, onDone: () -> Unit) {
    val apps = remember { vm.installedApps() }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("בחירת אפליקציית מקור", style = MaterialTheme.typography.headlineSmall)
        Text("בחר/י ידנית את אפליקציית השליחים. האפליקציה לא מנחשת — סימון \"נראה כמו Wolt\" הוא רמז בלבד.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it }, label = { Text("חיפוש") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val filtered = apps.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered, key = { it.packageName }) { a ->
                Card(Modifier.fillMaxWidth().clickable {
                    error = vm.selectPackage(a.packageName)
                    if (error == null) onDone()
                }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(a.label + if (a.looksLikeWolt) "  · נראה כמו Wolt" else "")
                        Ltr(a.packageName)
                    }
                }
            }
        }
    }
}

private sealed interface DiagItem {
    val at: Long
    data class N(val e: NotificationEventEntity) : DiagItem { override val at = e.observedAtUtc }
    data class S(val e: ScreenEventEntity) : DiagItem { override val at = e.observedAtUtc }
}

@Composable
fun DiagnosticsScreen(vm: MainViewModel, onOpen: (kind: String, id: String) -> Unit) {
    val notifs by vm.notifications.collectAsStateWithLifecycle()
    val screens by vm.screens.collectAsStateWithLifecycle()
    val cov = remember(screens) { ExportService.coverage(screens) }
    val items = remember(notifs, screens) { (notifs.map { DiagItem.N(it) } + screens.map { DiagItem.S(it) }).sortedByDescending { it.at } }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenTitle("אבחון", "כל מה שהאפליקציה רשמה — מושחר") }
        item { LocationDiagnosticsCard(vm) }
        item { DeliveryTripsCard(vm) }
        item { ShiftLogCard(vm) }
        item { StateTestCard(vm) }
        item {
            Section("סיכום") {
                Text("התראות: ${notifs.count { it.eventType == "POSTED" }} חדשות · ${notifs.count { it.eventType == "UPDATED" }} עדכונים · ${notifs.count { it.eventType == "REMOVED" }} הוסרו")
                Text("צילומי מצב: ${cov.screen_snapshots} · טקסט נגיש ${cov.snapshots_visible} · ללא טקסט ${cov.snapshots_no_accessible_text} · לא ידוע ${cov.snapshots_unknown}")
                Text("מסכים שסווגו כמועמדי הצעה: ${cov.offer_candidate_screens}")
                Text("שדות שנצפו (מספר מסכים): סכום ${cov.screens_with_amount} · מרחק ${cov.screens_with_distance} · דקות ${cov.screens_with_duration} · עצירות ${cov.screens_with_stops_count} · הזמנות ${cov.screens_with_order_count}")
                Text("שם מסעדת האיסוף נשמר מכרטיסי הצעה בלבד; כתובות ויעד לא נשמרים.", style = MaterialTheme.typography.bodySmall)
                Text("הסרת התראה אינה מעידה על קבלה או דחייה של הצעה.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (items.isEmpty()) item { Text("עדיין לא נאספו אירועים.") }
        items(items, key = { (if (it is DiagItem.N) "n" else "s") + (if (it is DiagItem.N) it.e.id else (it as DiagItem.S).e.id) }) { item ->
            when (item) {
                is DiagItem.N -> Card(Modifier.fillMaxWidth().clickable { onOpen("n", item.e.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("התראה · ${item.e.eventType}${item.e.removalReason?.let { " · $it" } ?: ""}")
                        Ltr(localTime(item.e.observedAtUtc))
                        Ltr("title: ${item.e.titleRedacted ?: "—"}  text: ${item.e.textRedacted ?: "—"}")
                    }
                }
                is DiagItem.S -> Card(Modifier.fillMaxWidth().clickable { onOpen("s", item.e.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("מסך · ${item.e.captureStatus} · ${item.e.screenCategory}${item.e.failureReason?.let { " · $it" } ?: ""}")
                        Ltr("${localTime(item.e.observedAtUtc)} · nodes ${item.e.accessibleNodeCount ?: "—"} · text ${item.e.textNodeCount ?: "—"} · ${item.e.snapshotLatencyMs ?: "—"} ms · ×${item.e.coalescedEventCount}")
                        ExportService.parseFields(item.e.redactedCandidateFieldsJson)?.let { f ->
                            Ltr("amounts: ${f.amounts_agorot.joinToString { "₪" + formatAgorot(it) }.ifEmpty { "NULL" }}  dist: ${f.distances_km.joinToString().ifEmpty { "NULL" }}  stops: ${f.stops_counts.joinToString().ifEmpty { "NULL" }}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EventDetailScreen(vm: MainViewModel, kind: String, id: String) {
    val notifs by vm.notifications.collectAsStateWithLifecycle()
    val screens by vm.screens.collectAsStateWithLifecycle()
    Page {
        if (kind == "n") {
            val e = notifs.firstOrNull { it.id == id } ?: return@Page Text("לא נמצא")
            Text("אירוע התראה (מוסתר)", style = MaterialTheme.typography.headlineSmall)
            Ltr("type: ${e.eventType}\nobserved_at_utc: ${ExportService.iso(e.observedAtUtc)}\nlocal: ${localTime(e.observedAtUtc)}\npackage: ${e.sourcePackage}\nkey_hash: ${e.notificationKeyHash?.take(16)}…\nremoval_reason: ${e.removalReason ?: "—"}\ntitle: ${e.titleRedacted ?: "NULL"}\ntext: ${e.textRedacted ?: "NULL"}")
            HorizontalDivider()
            Ltr(ExportService.parseNotifFields(e.fieldsPresentJson)?.let { ExportService.json.encodeToString(dev.yk.offercollector.service.NotificationFieldsPresent.serializer(), it) } ?: e.fieldsPresentJson)
        } else {
            val e = screens.firstOrNull { it.id == id } ?: return@Page Text("לא נמצא")
            Text("צילום מצב נגיש (מוסתר)", style = MaterialTheme.typography.headlineSmall)
            Ltr("event: ${e.eventType}\ncapture_status: ${e.captureStatus}\nfailure_reason: ${e.failureReason ?: "—"}\ncategory: ${e.screenCategory}\nwindow: ${e.windowClass ?: "—"}\nobserved_at_utc: ${ExportService.iso(e.observedAtUtc)}\nnodes: ${e.accessibleNodeCount ?: "NULL"} text_nodes: ${e.textNodeCount ?: "NULL"}\nlatency_ms: ${e.snapshotLatencyMs ?: "NULL"} coalesced: ${e.coalescedEventCount}\nsec_since_notification: ${e.secondsSinceSourceNotification ?: "NULL"}")
            HorizontalDivider()
            val f = ExportService.parseFields(e.redactedCandidateFieldsJson)
            if (f == null) Text("אין שדות (לא היה עץ נגיש).") else {
                Ltr("amounts_agorot: ${f.amounts_agorot.ifEmpty { "NULL" }}\ndistances_km: ${f.distances_km.ifEmpty { "NULL" }}\ndurations_min: ${f.durations_min.ifEmpty { "NULL" }}\nclock_times: ${f.clock_times.ifEmpty { "NULL" }}\nstops_counts: ${f.stops_counts.ifEmpty { "NULL" }}\norder_counts: ${f.order_counts.ifEmpty { "NULL" }}\nui_keywords: ${f.ui_keywords}\nphone_like_count: ${f.phone_like_count}\nother_text_tokens: ${f.other_text_token_count}")
                HorizontalDivider()
                Text("צמתים (${f.nodes.size}${if (f.nodes_truncated) "+, קוצר" else ""})", style = MaterialTheme.typography.titleSmall)
                f.nodes.forEach { n -> Ltr("${n.viewId ?: "(no id)"} · ${n.cls ?: "?"} · ${n.source}\n   ${n.redacted}") }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, settings: AppSettings, onPick: () -> Unit, onConsent: () -> Unit, onDiagnostics: () -> Unit = {}, onOffers: () -> Unit = {}) {
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { vm.export(it) }
    }
    Page {
        ScreenTitle("הגדרות")
        MoreLinks(onDiagnostics, onOffers)
        Section("ייצוא נתונים") {
            Text("שומר קובץ JSON עם כל האירועים (מושחרים) — למשל כדי לשלוח לניתוח. רק כשלא נוהגים.")
            Button(onClick = {
                exportLauncher.launch("offer-collector-diagnostics-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.json")
            }) { Text("ייצוא JSON") }
        }
        LocationSettingsSection(vm)
        Section("מחיקה") {
            Text("מוחק את כל האירועים מהמכשיר ומחליף את ה-salt של המזהים המגובבים (hash), כך שמזהים חדשים לא יקושרו לישנים.")
            Button(onClick = { confirmDelete = true }) { Text("Delete All Data") }
        }
        Section("ביטול הסכמה") {
            Text("מפסיק ניטור ומבטל את ההסכמות באפליקציה. כדי לנתק את השירותים לגמרי יש לכבות גם את ההרשאות בהגדרות המכשיר.")
            OutlinedButton(onClick = { vm.withdrawConsent() }) { Text("ביטול הסכמה והפסקת ניטור") }
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("כיבוי גישה להתראות") }
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("כיבוי שירות נגישות") }
        }
        Section("רקע וסוללה") {
            Text("יצרנים מסוימים (Samsung, Xiaomi ועוד) עלולים לעצור שירותים ברקע. אם אירועים מפסיקים להגיע כשהאפליקציה סגורה — אפשר להחריג אותה מחיסכון בסוללה.")
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("הגדרות אופטימיזציית סוללה") }
        }
        Section("אפליקציית מקור והסכמה") {
            Ltr(settings.selectedPackage ?: "לא נבחרה")
            OutlinedButton(onClick = onPick) { Text("החלפת אפליקציית מקור") }
            OutlinedButton(onClick = onConsent) { Text("הצגת מסך ההסכמה") }
        }
        Section("מצב נוכחי") {
            Ltr("selected_package: ${settings.selectedPackage ?: "NULL"}\nmonitoring: ${settings.monitoringEnabled}\nnotification_consent: ${settings.notificationConsent}\naccessibility_consent: ${settings.accessibilityConsent}")
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("למחוק את כל הנתונים?") },
        text = { Text("פעולה זו בלתי הפיכה.") },
        confirmButton = { TextButton(onClick = { vm.deleteAll(); confirmDelete = false }) { Text("מחק") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ביטול") } },
    )
}
