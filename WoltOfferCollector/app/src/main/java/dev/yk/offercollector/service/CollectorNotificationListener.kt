package dev.yk.offercollector.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.yk.offercollector.app
import dev.yk.offercollector.core.Hashing
import dev.yk.offercollector.core.NotificationDeduplicator
import dev.yk.offercollector.core.NotificationEventType
import dev.yk.offercollector.core.RedactedText
import dev.yk.offercollector.core.ActiveNotification
import dev.yk.offercollector.core.StateMarkersExtractor
import dev.yk.offercollector.core.TextClassifier
import dev.yk.offercollector.data.NotificationEventEntity
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NotificationFieldsPresent(
    val has_title: Boolean,
    val has_text: Boolean,
    val has_big_text: Boolean,
    val has_sub_text: Boolean,
    val has_ticker: Boolean,
    val big_text_redacted: String?,
    val sub_text_redacted: String?,
    val token_kinds: List<String>,
    val amount_in_notification: Boolean,
    val channel_id: String?, // developer-defined channel name; not personal
    val category: String?,
    val is_ongoing: Boolean,
    val has_content_intent: Boolean,
    val has_full_screen_intent: Boolean,
    val actions_count: Int,
    val action_titles_redacted: List<String>,
)

/**
 * Passive, read-only listener. Stores events only for the user-selected package, only while
 * monitoring is on, and only in redacted form. Never interacts with notifications.
 */
class CollectorNotificationListener : NotificationListenerService() {
    private val json = Json { encodeDefaults = true }

    private var settingsJob: Job? = null

    override fun onListenerConnected() {
        ServiceState.setListener(true)
        // Stop = let go of the system binding immediately (and whenever consent is withdrawn).
        settingsJob = app.appScope.launch {
            app.settings.settings.drop(1).collect { s ->
                if (!s.monitoringEnabled || !s.notificationConsent) requestUnbind()
            }
        }
        app.appScope.launch {
            val s = app.settings.current()
            if (!s.monitoringEnabled || !s.notificationConsent) requestUnbind()
        }
    }

    override fun onListenerDisconnected() {
        settingsJob?.cancel()
        ServiceState.setListener(false)
        // From here on the notification state is UNKNOWN (no signal), not "unchanged".
        app.appScope.launch { StateMarkerRecorder.record(app, StateMarkersExtractor.system("LISTENER_DISCONNECTED")) }
    }

    /** The selected app's notifications showing right now — metadata and redacted text only. */
    private fun activeFor(pkg: String): List<ActiveNotification> = try {
        activeNotifications.orEmpty().filter { it.packageName == pkg }.map { a ->
            val ex = a.notification.extras
            ActiveNotification(
                channel = a.notification.channelId,
                ongoing = a.isOngoing,
                foreground_service = (a.notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
                title_redacted = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let { TextClassifier.classify(it).display() },
                text_redacted = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { TextClassifier.classify(it).display() },
            )
        }
    } catch (e: SecurityException) {
        emptyList() // listener not bound at this instant
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val receivedAt = System.currentTimeMillis()
        handle(sbn, receivedAt, removalReason = null)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        handle(sbn, System.currentTimeMillis(), removalReason = reasonName(reason))
    }

    private fun handle(sbn: StatusBarNotification, receivedAt: Long, removalReason: String?) {
        // Cheap synchronous filter: never touch content of other apps' notifications.
        val selected = app.settings.settings.value
        if (!selected.monitoringEnabled || !selected.notificationConsent || sbn.packageName != selected.selectedPackage) return
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val ticker = n.tickerText?.toString()
        val actions = n.actions.orEmpty().map { it.title?.toString() }
        // Redact immediately; raw strings go out of scope at the end of this function.
        val rTitle = TextClassifier.classify(title)
        val rText = TextClassifier.classify(text)
        val rBig = TextClassifier.classify(big)
        val rSub = TextClassifier.classify(sub)
        val rActions = actions.map { TextClassifier.classify(it).display() }
        val all = listOf(rTitle, rText, rBig, rSub)
        val kinds = all.flatMap { r -> r.tokens.map { it.kind.name } }.distinct()
        val fields = NotificationFieldsPresent(
            has_title = title != null, has_text = text != null, has_big_text = big != null,
            has_sub_text = sub != null, has_ticker = ticker != null,
            big_text_redacted = big?.let { rBig.display() }, sub_text_redacted = sub?.let { rSub.display() },
            token_kinds = kinds, amount_in_notification = "CURRENCY_AMOUNT" in kinds,
            channel_id = n.channelId, category = n.category,
            is_ongoing = sbn.isOngoing, has_content_intent = n.contentIntent != null,
            has_full_screen_intent = n.fullScreenIntent != null,
            actions_count = actions.size, action_titles_redacted = rActions,
        )
        val contentSignature = Hashing.sha256(all.joinToString("|") { it.display() } + "|" + rActions.joinToString())
        val pkg = sbn.packageName
        val key = sbn.key
        val event = "${if (removalReason != null) "REMOVED" else "POSTED"} ${n.channelId ?: "?"}"
        val active = activeFor(pkg)
        app.appScope.launch {
            mutex.withLock { persist(pkg, key, receivedAt, rTitle, rText, fields, contentSignature, removalReason, title, text) }
            StateMarkerRecorder.record(app, StateMarkersExtractor.fromNotifications(active, event), receivedAt)
        }
    }

    private suspend fun persist(
        pkg: String, key: String, at: Long, rTitle: RedactedText, rText: RedactedText,
        fields: NotificationFieldsPresent, signature: String, removalReason: String?, title: String?, text: String?,
    ) {
        val dao = app.db.notificationEvents()
        val keyHash = Hashing.saltedSha256(app.settings.salt(), key)
        if (!dedup.isKnown(keyHash)) {
            // After a service restart, recover whether this key is still showing.
            dao.latestForKey(keyHash)?.let {
                dedup.seed(keyHash, it.eventType != "REMOVED", it.contentSignatureHash ?: "", it.observedAtUtc)
            }
        }
        val type = (if (removalReason != null) dedup.onRemoved(keyHash, at) else dedup.onPosted(keyHash, signature, at)) ?: return
        dao.insert(
            NotificationEventEntity(
                id = UUID.randomUUID().toString(),
                observedAtUtc = at,
                sourcePackage = pkg,
                notificationKeyHash = keyHash,
                eventType = type.name,
                titleRedacted = title?.let { rTitle.display() },
                textRedacted = text?.let { rText.display() },
                fieldsPresentJson = json.encodeToString(fields),
                removalReason = removalReason,
                contentSignatureHash = signature,
                createdAtUtc = System.currentTimeMillis(),
            ),
        )
        if (type == NotificationEventType.POSTED || type == NotificationEventType.UPDATED) LastSourceNotification.markAt(pkg, at)
    }

    companion object {
        // Process-wide: Android can bind more than one listener instance (seen on One UI after a
        // reinstall); they must share one deduplicator so an event is never written twice.
        private val dedup = NotificationDeduplicator()
        private val mutex = Mutex()

        fun component(ctx: Context) = ComponentName(ctx, CollectorNotificationListener::class.java)

        fun reasonName(reason: Int): String = when (reason) {
            REASON_CLICK -> "REASON_CLICK"
            REASON_CANCEL -> "REASON_CANCEL"
            REASON_CANCEL_ALL -> "REASON_CANCEL_ALL"
            REASON_ERROR -> "REASON_ERROR"
            REASON_PACKAGE_CHANGED -> "REASON_PACKAGE_CHANGED"
            REASON_USER_STOPPED -> "REASON_USER_STOPPED"
            REASON_PACKAGE_BANNED -> "REASON_PACKAGE_BANNED"
            REASON_APP_CANCEL -> "REASON_APP_CANCEL"
            REASON_APP_CANCEL_ALL -> "REASON_APP_CANCEL_ALL"
            REASON_LISTENER_CANCEL -> "REASON_LISTENER_CANCEL"
            REASON_LISTENER_CANCEL_ALL -> "REASON_LISTENER_CANCEL_ALL"
            REASON_GROUP_SUMMARY_CANCELED -> "REASON_GROUP_SUMMARY_CANCELED"
            REASON_GROUP_OPTIMIZATION -> "REASON_GROUP_OPTIMIZATION"
            REASON_PACKAGE_SUSPENDED -> "REASON_PACKAGE_SUSPENDED"
            REASON_PROFILE_TURNED_OFF -> "REASON_PROFILE_TURNED_OFF"
            REASON_UNAUTOBUNDLED -> "REASON_UNAUTOBUNDLED"
            REASON_CHANNEL_BANNED -> "REASON_CHANNEL_BANNED"
            REASON_SNOOZED -> "REASON_SNOOZED"
            REASON_TIMEOUT -> "REASON_TIMEOUT"
            else -> "REASON_$reason"
        }
    }
}

/** In-memory hint for the accessibility branch: when did this package last post a notification? */
object LastSourceNotification {
    private val lastAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun markAt(pkg: String, ms: Long) { lastAtMs[pkg] = ms }
    fun secondsSince(pkg: String, nowMs: Long): Double? = lastAtMs[pkg]?.let { (nowMs - it) / 1000.0 }
}
