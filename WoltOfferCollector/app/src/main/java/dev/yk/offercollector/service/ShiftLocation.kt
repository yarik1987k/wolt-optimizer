package dev.yk.offercollector.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.yk.offercollector.App
import dev.yk.offercollector.core.DeliveryEventDetector
import dev.yk.offercollector.core.LocationFix
import dev.yk.offercollector.core.LocationQuality
import dev.yk.offercollector.core.LocationReading
import dev.yk.offercollector.core.LocationSamplingPolicy
import dev.yk.offercollector.data.LocationSampleEntity
import dev.yk.offercollector.data.ShiftSessionEntity
import dev.yk.offercollector.ui.detectorItems
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Location during a session the courier started (Phase 3). Passive and read-only towards Wolt.
 * Exact fixes live only in memory ([recent], ~5 min) to pick the cell; the database keeps the
 * ~500 m cell, unless the explicit debug mode is on (then exact values for 24 h, never exported).
 */
object ShiftLocation {
    private val mutex = Mutex()
    private val _shiftId = MutableStateFlow<String?>(null)
    val shiftId: StateFlow<String?> = _shiftId
    private val lastByTrigger = HashMap<String, Long>()
    private val sampledEventKeys = HashSet<String>()
    private val recent = ArrayDeque<LocationFix>()
    @Volatile private var shiftStartMs = 0L
    @Volatile private var lastPeriodicAtMs: Long? = null
    @Volatile private var lastFixAtMs: Long? = null
    @Volatile private var lastDetectAtMs = 0L
    @Volatile var intervalMs: Long = LocationSamplingPolicy.DEFAULT_BASE_INTERVAL_S * 1000L; private set
    private val _stationary = MutableStateFlow(false)
    val stationary: StateFlow<Boolean> = _stationary

    private const val RECENT_KEEP_MS = 5 * 60_000L
    private const val DEBUG_KEEP_MS = 24 * 60 * 60_000L
    private const val DETECT_THROTTLE_MS = 3_000L

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasFinePermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun battery(ctx: Context): Pair<Int?, Boolean> {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return pct to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }

    suspend fun begin(app: App, baseIntervalS: Int, stationaryIntervalS: Int?): String = mutex.withLock {
        val now = System.currentTimeMillis()
        app.db.shiftSessions().open()?.let { app.db.shiftSessions().end(it.id, it.lastAliveUtc, "SERVICE_STOPPED") }
        val id = UUID.randomUUID().toString()
        val (pct, charging) = battery(app)
        // Planned end = the next occurrence of the chosen hour (local time); null when not chosen.
        val plannedEnd = app.settings.current().plannedEndHour?.let { h ->
            val z = java.time.Instant.ofEpochMilli(now).atZone(dev.yk.offercollector.core.OfferAnalytics.ZONE)
            var end = z.withHour(h).withMinute(0).withSecond(0).withNano(0)
            if (!end.isAfter(z)) end = end.plusDays(1)
            end.toInstant().toEpochMilli()
        }
        app.db.shiftSessions().insert(
            ShiftSessionEntity(
                id, now, null, null, locationConsent = true, lastAliveUtc = now,
                baseIntervalS = baseIntervalS, stationaryIntervalS = stationaryIntervalS,
                batteryStartPct = pct, chargingSeen = charging, plannedEndUtc = plannedEnd,
            ),
        )
        lastByTrigger.clear(); sampledEventKeys.clear(); recent.clear()
        shiftStartMs = now; lastPeriodicAtMs = null; lastFixAtMs = null
        intervalMs = baseIntervalS * 1000L
        _stationary.value = false
        _shiftId.value = id
        id
    }

    suspend fun end(app: App, reason: String) {
        val id = _shiftId.value ?: return
        sample(app, "SHIFT_END", "UNKNOWN", "UNKNOWN", "SESSION", System.currentTimeMillis())
        mutex.withLock {
            app.db.shiftSessions().setBatteryEnd(id, battery(app).first)
            app.db.shiftSessions().end(id, System.currentTimeMillis(), reason)
            _shiftId.value = null
            recent.clear() // exact fixes never outlive the session
        }
    }

    /** Closes a session whose service died without ending it, at its last heartbeat. Call on app start. */
    suspend fun recover(app: App) = mutex.withLock {
        if (_shiftId.value != null) return@withLock
        app.db.shiftSessions().open()?.let { app.db.shiftSessions().end(it.id, it.lastAliveUtc, "SERVICE_STOPPED") }
        app.db.locationSamples().purgeDebugBefore(System.currentTimeMillis() - DEBUG_KEEP_MS)
    }

    /** Every ~10 s from the service: heartbeat, NO_FIX watchdog, debug purge. */
    suspend fun tick(app: App) {
        val id = _shiftId.value ?: return
        val now = System.currentTimeMillis()
        app.db.shiftSessions().heartbeat(id, now, battery(app).second)
        if (LocationSamplingPolicy.noFixDue(now, lastFixAtMs, lastPeriodicAtMs, intervalMs)) {
            lastPeriodicAtMs = now
            insert(app, id, now, "PERIODIC", "UNKNOWN", "UNKNOWN", "PERIODIC", LocationQuality.unknown("NO_FIX"))
        }
        app.db.locationSamples().purgeDebugBefore(now - DEBUG_KEEP_MS)
    }

    // ---- precise track (~5 m) ----

    /** Fixes worse than this are not part of the track. */
    const val TRACK_MAX_ACCURACY_M = 25f
    const val TRACK_MIN_MOVE_M = 5.0
    /** While standing still, still keep one point this often (the track shows waiting, distance stays honest). */
    const val TRACK_KEEP_STILL_MS = 30_000L
    @Volatile private var lastTrack: dev.yk.offercollector.data.TrackPointEntity? = null

    /** Pure: should a new fix be stored after [last]? */
    fun keepTrackFix(lastAtMs: Long?, lastLat: Double?, lastLon: Double?, atMs: Long, lat: Double, lon: Double, accuracyM: Float?, mock: Boolean): Boolean {
        if (mock || accuracyM == null || accuracyM > TRACK_MAX_ACCURACY_M) return false
        if (lastAtMs == null || lastLat == null || lastLon == null) return true
        if (atMs <= lastAtMs) return false
        val moved = LocationSamplingPolicy.metersBetween(LocationFix(lastLat, lastLon, null, lastAtMs, false, null, ""), LocationFix(lat, lon, null, atMs, false, null, ""))
        return moved >= TRACK_MIN_MOVE_M || atMs - lastAtMs >= TRACK_KEEP_STILL_MS
    }

    /** A fix from the track request (only while a shift runs and the courier left the precise track on). */
    suspend fun onTrackFix(app: App, loc: Location) {
        val id = _shiftId.value ?: return
        if (!app.settings.settings.value.preciseTrack) return
        val mock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else @Suppress("DEPRECATION") loc.isFromMockProvider
        val acc = if (loc.hasAccuracy()) loc.accuracy else null
        val last = lastTrack?.takeIf { it.shiftId == id }
        if (!keepTrackFix(last?.observedAtUtc, last?.lat, last?.lon, loc.time, loc.latitude, loc.longitude, acc, mock)) return
        val p = dev.yk.offercollector.data.TrackPointEntity(
            shiftId = id, observedAtUtc = loc.time, lat = loc.latitude, lon = loc.longitude, accuracyM = acc, speedMps = if (loc.hasSpeed()) loc.speed else null,
        )
        lastTrack = p
        app.db.trackPoints().insert(p)
    }

    /** A fix from the continuous request. Returns true when the stationary state changed (service re-requests). */
    suspend fun onFix(app: App, fix: LocationFix, adaptive: Boolean, baseMs: Long, stationaryMs: Long): Boolean {
        val id = _shiftId.value ?: return false
        val now = System.currentTimeMillis()
        val changed: Boolean
        mutex.withLock {
            recent.addLast(fix)
            while (recent.isNotEmpty() && now - recent.first().fixTimeMs > RECENT_KEEP_MS) recent.removeFirst()
            lastFixAtMs = now
            val still = adaptive && LocationSamplingPolicy.isStationary(recent.toList())
            changed = still != _stationary.value
            _stationary.value = still
            intervalMs = if (still) stationaryMs else baseMs
        }
        if (LocationSamplingPolicy.periodicDue(now, lastPeriodicAtMs, intervalMs)) {
            lastPeriodicAtMs = now
            insert(app, id, now, "PERIODIC", "UNKNOWN", "UNKNOWN", "PERIODIC", LocationQuality.evaluate(fix, now))
        }
        return changed
    }

    /** From StateMarkerRecorder: a new candidate state was written. Never blocks the recorder. */
    fun onStateChange(app: App, candidateState: String, evidence: String, source: String) {
        if (_shiftId.value == null) return
        val now = System.currentTimeMillis()
        LocationSamplingPolicy.triggerFor(candidateState, source)?.let { trigger ->
            app.appScope.launch { sample(app, trigger, candidateState, evidence, "EVENT", now) }
        }
        if (now - lastDetectAtMs >= DETECT_THROTTLE_MS) {
            lastDetectAtMs = now
            app.appScope.launch { sampleDetectorEvents(app) }
        }
    }

    /**
     * Accept / pickup / drop-off / completion are known only once the detector sees the confirming
     * screen. Each new one gets a sample located at the EVENT time from the recent-fix buffer — or
     * STALE/NO_FIX if no fix is within 60 s of it. Evidence is copied from the detector, never raised.
     */
    private suspend fun sampleDetectorEvents(app: App) {
        val id = _shiftId.value ?: return
        val items = detectorItems(app.db.stateMarkerEvents().since(shiftStartMs))
        val events = DeliveryEventDetector.detect(items).events.filter { it.type in LocationSamplingPolicy.DETECTOR_EVENT_TRIGGERS }
        if (events.isEmpty()) return
        val known = mutex.withLock { if (sampledEventKeys.isEmpty()) sampledEventKeys += app.db.locationSamples().eventKeys(id); sampledEventKeys.toSet() }
        for (e in events) {
            val key = "${e.type}|${e.action_at_ms}"
            if (key in known) continue
            mutex.withLock { sampledEventKeys += key }
            val fixes = mutex.withLock { recent.toList() }
            val fix = LocationSamplingPolicy.fixForEvent(fixes, e.action_at_ms) ?: fixes.minByOrNull { kotlin.math.abs(it.fixTimeMs - e.action_at_ms) }
            val reading = LocationQuality.evaluate(fix, e.action_at_ms, LocationSamplingPolicy.EVENT_MAX_FIX_DISTANCE_MS)
            insert(app, id, System.currentTimeMillis(), e.type, "UNKNOWN", e.evidence, "EVENT", reading, eventAt = e.action_at_ms, eventKey = key)
        }
    }

    suspend fun sample(app: App, trigger: String, stateCandidate: String, evidence: String, source: String, atMs: Long) {
        val shift: String
        mutex.withLock {
            shift = _shiftId.value ?: return
            if (source == "EVENT" && LocationSamplingPolicy.isDuplicate(lastByTrigger[trigger], atMs)) return
            lastByTrigger[trigger] = atMs
        }
        val fixes = mutex.withLock { recent.toList() }
        // Prefer a buffered fix taken within 15 s of the event; otherwise ask for a fresh one.
        val buffered = fixes.filter { kotlin.math.abs(it.fixTimeMs - atMs) <= 15_000 }.minByOrNull { kotlin.math.abs(it.fixTimeMs - atMs) }
        val reading = if (buffered != null) LocationQuality.evaluate(buffered, atMs, LocationSamplingPolicy.EVENT_MAX_FIX_DISTANCE_MS)
        else fresh(app, atMs)
        insert(app, shift, System.currentTimeMillis(), trigger, stateCandidate, evidence, source, reading)
    }

    private suspend fun insert(
        app: App, shift: String, observedAt: Long, trigger: String, stateCandidate: String, evidence: String, source: String,
        r: LocationReading, eventAt: Long? = null, eventKey: String? = null,
    ) {
        val debug = app.settings.current().locationDebugExact
        app.db.locationSamples().insert(
            LocationSampleEntity(
                id = UUID.randomUUID().toString(), shiftId = shift, observedAtUtc = observedAt, trigger = trigger,
                stateCandidate = stateCandidate, status = r.status, unknownReason = r.unknownReason,
                cellId = r.cell?.id, cellCenterLat = r.cell?.centerLat, cellCenterLon = r.cell?.centerLon,
                accuracyM = r.accuracyM, fixAgeS = r.fixAgeS, provider = r.provider,
                source = source, stateEvidence = evidence, speedMps = r.speedMps?.let { Math.round(it * 10) / 10f },
                locationAgeMs = r.ageMs, eventAtUtc = eventAt, eventKey = eventKey, batteryPct = battery(app).first,
                debugLat = if (debug) r.exact?.lat else null, debugLon = if (debug) r.exact?.lon else null,
            ),
        )
    }

    fun toFix(loc: Location): LocationFix = LocationFix(
        loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, loc.time,
        if (Build.VERSION.SDK_INT >= 31) loc.isMock else @Suppress("DEPRECATION") loc.isFromMockProvider,
        if (loc.hasSpeed()) loc.speed else null, loc.provider ?: "fused",
    )

    @SuppressLint("MissingPermission")
    private suspend fun fresh(app: App, atMs: Long): LocationReading {
        if (!hasPermission(app)) return LocationQuality.unknown("NO_PERMISSION")
        return try {
            val client = LocationServices.getFusedLocationProviderClient(app)
            val req = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(15_000)
                .setDurationMillis(20_000)
                .build()
            val cts = CancellationTokenSource()
            // Outer null = timed out; inner null = no fix available.
            val result: Array<Location?> = withTimeoutOrNull(25_000) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { cts.cancel() }
                    client.getCurrentLocation(req, cts.token)
                        .addOnSuccessListener { cont.resume(arrayOf(it)) }
                        .addOnFailureListener { cont.resume(arrayOf<Location?>(null)) }
                }
            } ?: return LocationQuality.unknown("TIMEOUT")
            val loc = result[0] ?: return LocationQuality.unknown("NO_FIX")
            LocationQuality.evaluate(toFix(loc), atMs, LocationSamplingPolicy.EVENT_MAX_FIX_DISTANCE_MS)
        } catch (e: Exception) {
            LocationQuality.unknown("ERROR")
        }
    }

    /** Debug mode off / "delete exact coordinates": wipe every stored exact value. */
    suspend fun purgeDebug(app: App) = app.db.locationSamples().purgeAllDebug()
}
