package dev.yk.offercollector.service

import dev.yk.offercollector.App
import dev.yk.offercollector.core.SnapshotAnalyzer
import dev.yk.offercollector.core.StateClassifier
import dev.yk.offercollector.core.StateMarkers
import dev.yk.offercollector.data.StateMarkerEventEntity
import dev.yk.offercollector.data.TestSessionEntity
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Writes a state_marker_events row only when the markers of a source change. Passive: it never
 * acts on Wolt and never turns a disappearance into acceptance or completion.
 */
object StateMarkerRecorder {
    private val json = Json { encodeDefaults = false }
    private val mutex = Mutex()
    private val last = HashMap<String, StateMarkers>()
    @Volatile private var sessionLoaded = false
    @Volatile private var openSessionId: String? = null
    private var lastTap: Pair<StateMarkers, Long>? = null

    /** Same tap reported twice by Android within this window = one tap. */
    const val DUPLICATE_TAP_MS = 1_000L

    /** Pure rule, exposed for tests. */
    fun isDuplicateTap(prev: Pair<StateMarkers, Long>?, cur: StateMarkers, atMs: Long) =
        prev != null && prev.first == cur && atMs - prev.second in 0..DUPLICATE_TAP_MS

    suspend fun record(app: App, markers: StateMarkers, observedAtMs: Long = System.currentTimeMillis()) = mutex.withLock {
        if (markers.source == "ACTION") {
            if (isDuplicateTap(lastTap, markers, observedAtMs)) return@withLock
            lastTap = markers to observedAtMs
        }
        if (!StateClassifier.materiallyDifferent(last[markers.source], markers)) return@withLock
        last[markers.source] = markers
        val c = StateClassifier.classify(markers)
        app.db.stateMarkerEvents().insert(
            StateMarkerEventEntity(
                id = UUID.randomUUID().toString(),
                observedAtUtc = observedAtMs,
                source = markers.source,
                candidateState = c.state.name,
                evidence = c.evidence.name,
                rule = c.rule,
                markersJson = json.encodeToString(markers),
                parseVersion = SnapshotAnalyzer.PARSE_VERSION,
                testSessionId = currentSession(app),
            ),
        )
        ShiftLocation.onStateChange(app, c.state.name, c.evidence.name, markers.source)
    }

    private suspend fun currentSession(app: App): String? {
        if (!sessionLoaded) {
            openSessionId = app.db.testSessions().open()?.id
            sessionLoaded = true
        }
        return openSessionId
    }

    suspend fun startSession(app: App, label: String): String = mutex.withLock {
        app.db.testSessions().open()?.let { app.db.testSessions().end(it.id, System.currentTimeMillis()) }
        val id = UUID.randomUUID().toString()
        app.db.testSessions().insert(TestSessionEntity(id, label, System.currentTimeMillis(), null))
        openSessionId = id
        sessionLoaded = true
        // Force the next marker of every source to be written, so the session starts with a baseline.
        last.clear()
        id
    }

    suspend fun endSession(app: App) = mutex.withLock {
        app.db.testSessions().open()?.let { app.db.testSessions().end(it.id, System.currentTimeMillis()) }
        openSessionId = null
        sessionLoaded = true
    }

    /** After Delete All: forget cached markers and session. */
    suspend fun reset() = mutex.withLock {
        last.clear()
        openSessionId = null
        sessionLoaded = true
    }
}
