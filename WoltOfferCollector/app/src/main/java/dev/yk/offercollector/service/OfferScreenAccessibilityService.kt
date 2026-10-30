package dev.yk.offercollector.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.yk.offercollector.app
import dev.yk.offercollector.core.CandidateFields
import dev.yk.offercollector.core.DebounceWindow
import dev.yk.offercollector.core.RawNode
import dev.yk.offercollector.core.SnapshotAnalyzer
import dev.yk.offercollector.core.StateMarkersExtractor
import dev.yk.offercollector.core.UserActions
import dev.yk.offercollector.data.ScreenEventEntity
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * READ-ONLY feasibility probe. It never calls performAction, dispatchGesture,
 * performGlobalAction, never draws overlays and never changes anything in the source app.
 * It only reads the accessible text tree of the user-selected package, redacts it in memory
 * and stores a minimal summary.
 */
class OfferScreenAccessibilityService : AccessibilityService() {
    private val json = Json { encodeDefaults = true }
    private val debounce = DebounceWindow()
    private var pendingJob: Job? = null
    private var pendingEventType: String = "WINDOW_CONTENT_CHANGED"
    /** Recently stored screen states by fingerprint (small LRU): a screen that cycles A→B→A stays two rows. */
    private val recent = object : LinkedHashMap<String, StoredSnapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StoredSnapshot>) = size > RECENT_STATES
    }
    private var settingsJob: Job? = null
    private val snapshotMutex = Mutex()

    override fun onServiceConnected() {
        ServiceState.setAccessibility(true)
        settingsJob = app.appScope.launch {
            app.settings.settings.collectLatest { applyScope(it.selectedPackage, it.monitoringEnabled && it.accessibilityConsent) }
        }
    }

    /** Restricts the system-delivered events to the selected package; none at all when stopped. */
    private fun applyScope(pkg: String?, active: Boolean) {
        val info = serviceInfo ?: return
        if (active && pkg != null) {
            info.packageNames = arrayOf(pkg)
            // All types: slide gestures can arrive as any of several event types depending on how the
            // app built its slider; only window changes, taps and slider-element events are used.
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        } else {
            info.packageNames = arrayOf(packageName) // our own package: effectively silent
            info.eventTypes = 0
            pendingJob?.cancel()
        }
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val s = app.settings.settings.value
        val pkg = event.packageName?.toString() ?: return
        if (!s.monitoringEnabled || !s.accessibilityConsent || pkg != s.selectedPackage) return
        val type = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> { recordAction(event, "TAP"); return }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> { recordAction(event, "LONG_TAP"); return }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
            else -> {
                // Any other event (scrolled, selected, text/state changed, …) counts only when it
                // comes from a swipe-to-confirm control. Maps and lists are ignored.
                if (!recordAction(event, "SLIDE")) return
                "WINDOW_CONTENT_CHANGED" // also re-read the screen so the slider position is captured
            }
        }
        val now = SystemClock.uptimeMillis()
        if (type == "WINDOW_STATE_CHANGED") pendingEventType = type
        val wait = debounce.onEvent(now, type == "WINDOW_STATE_CHANGED")
        val windowClass = event.className?.toString()
        pendingJob?.cancel()
        pendingJob = app.appScope.launch {
            delay(wait) // only the waiting is cancellable by newer events
            // Once a snapshot starts it must finish: cancelling it after the insert but before
            // lastStored is updated made identical screens get stored again (seen on Wolt, ~1 row/s).
            withContext(NonCancellable) { snapshotMutex.withLock { snapshot(pkg, windowClass) } }
        }
    }

    /**
     * The courier tapped something in Wolt (Android reports it; we only listen). The label is
     * redacted immediately; only fixed-vocabulary words survive.
     */
    /** @return true when an action was recorded. */
    private fun recordAction(event: AccessibilityEvent, gesture: String): Boolean {
        val at = System.currentTimeMillis()
        // Cheap class check first so map/list events never cost an IPC call for the source node.
        if (gesture == "SLIDE" && !UserActions.isSlider(null, event.className?.toString()) &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED && event.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) return false
        val src = try { event.source } catch (e: Exception) { null }
        if (gesture == "SLIDE" && !UserActions.isSlider(src?.viewIdResourceName, event.className?.toString()) && src?.rangeInfo == null) return false
        val labels = event.text.map { it?.toString() } +
            listOf(event.contentDescription?.toString(), src?.text?.toString(), src?.contentDescription?.toString())
        val tap = UserActions.classify(labels, src?.viewIdResourceName, event.className?.toString(), gesture, AccessibilityEvent.eventTypeToString(event.eventType))
        app.appScope.launch { StateMarkerRecorder.record(app, StateMarkersExtractor.fromTap(tap), at) }
        return true
    }

    private suspend fun snapshot(pkg: String, windowClass: String?) {
        val burstStart = debounce.burstStartMs() ?: SystemClock.uptimeMillis()
        val coalesced = debounce.eventsInBurst
        val eventType = pendingEventType
        debounce.reset()
        pendingEventType = "WINDOW_CONTENT_CHANGED"

        val observedAt = System.currentTimeMillis()
        val sinceNotif = LastSourceNotification.secondsSince(pkg, observedAt)
        var failure: String? = null
        val nodes = mutableListOf<RawNode>()
        val root: AccessibilityNodeInfo? = try { rootInActiveWindow } catch (e: Exception) { failure = "ROOT_EXCEPTION"; null }
        if (root == null && failure == null) failure = "ROOT_NULL"
        if (root != null && root.packageName?.toString() != pkg) {
            // The active window belongs to another app (e.g. the user already switched away).
            failure = "ACTIVE_WINDOW_OTHER_PACKAGE"
        } else if (root != null) {
            try { collect(root, 0, nodes) } catch (e: Exception) { failure = "TRAVERSAL_ERROR" }
        }
        val latency = SystemClock.uptimeMillis() - burstStart
        val analysis = if (failure == null) SnapshotAnalyzer.analyze(windowClass, nodes, sinceNotif != null && sinceNotif <= 20.0) else null
        nodes.clear() // raw text is discarded here

        val status = when {
            failure == "ACTIVE_WINDOW_OTHER_PACKAGE" -> "UNKNOWN"
            failure != null -> "NO_ACCESSIBLE_TEXT"
            analysis!!.hasText -> "VISIBLE"
            else -> "NO_ACCESSIBLE_TEXT"
        }
        val reason = failure ?: if (analysis?.hasText == false) "NO_TEXT_NODES" else null

        // Courier-state markers (tests A/B): written only when they change.
        analysis?.let { StateMarkerRecorder.record(app, StateMarkersExtractor.fromScreen(it.fields), observedAt) }
        // Shadow mode: silent, immutable prediction for a NEW offer card (background; no alert).
        analysis?.let { ShadowRecorder.onScreen(app, PickupAddresses.gate(app.settings.settings.value.pickupAddressMode, it.fields, observedAt).first, observedAt) }

        val dao = app.db.screenEvents()
        val fp = analysis?.fingerprint
        // An unchanged screen is ONE observation for as long as it stays up: count repeats on the
        // existing row. A new row only when the content changes, after a quiet gap, or if the row
        // was deleted (Delete All).
        val seen = fp?.let { recent[it] }
        if (seen != null && seen.isContinuation(observedAt) && dao.addCoalesced(seen.rowId, coalesced) > 0) {
            seen.lastSeenMs = observedAt
            return
        }
        val id = UUID.randomUUID().toString()
        dao.insert(
            ScreenEventEntity(
                id = id,
                observedAtUtc = observedAt,
                sourcePackage = pkg,
                eventType = eventType,
                screenCategory = analysis?.category?.name ?: "UNKNOWN",
                accessibleNodeCount = analysis?.accessibleNodeCount,
                textNodeCount = analysis?.textNodeCount,
                // Business address passes only through the consent gate (memory-only until confirmed).
                redactedCandidateFieldsJson = analysis?.fields?.let { json.encodeToString<CandidateFields>(PickupAddresses.filter(app, it, observedAt)) },
                captureStatus = status,
                failureReason = reason,
                parseVersion = SnapshotAnalyzer.PARSE_VERSION,
                fingerprintHash = fp,
                windowClass = windowClass?.substringAfterLast('.'),
                snapshotLatencyMs = latency,
                coalescedEventCount = coalesced,
                secondsSinceSourceNotification = sinceNotif,
            ),
        )
        if (fp != null) recent[fp] = StoredSnapshot(id, observedAt)
    }

    private fun collect(node: AccessibilityNodeInfo, depth: Int, out: MutableList<RawNode>) {
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
        out += RawNode(
            className = node.className?.toString(),
            viewId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            clickable = node.isClickable,
            depth = depth,
            rangePct = node.rangeInfo?.let { r ->
                if (r.max > r.min) (((r.current - r.min) / (r.max - r.min)) * 100).toInt().coerceIn(0, 100) else null
            },
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, depth + 1, out)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        ServiceState.setAccessibility(false)
        settingsJob?.cancel()
        pendingJob?.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ServiceState.setAccessibility(false)
        return super.onUnbind(intent)
    }

    private class StoredSnapshot(val rowId: String, var lastSeenMs: Long) {
        fun isContinuation(nowMs: Long) = nowMs - lastSeenMs <= QUIET_GAP_MS
    }

    companion object {
        /** Same screen after this long without any snapshot counts as a new observation. */
        const val QUIET_GAP_MS = 30_000L
        const val RECENT_STATES = 8
        const val MAX_NODES = 600
        const val MAX_DEPTH = 40
    }
}
