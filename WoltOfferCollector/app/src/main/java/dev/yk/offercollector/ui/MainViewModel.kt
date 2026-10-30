package dev.yk.offercollector.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.yk.offercollector.App
import dev.yk.offercollector.core.PackageValidator
import dev.yk.offercollector.data.AppSettings
import dev.yk.offercollector.data.NotificationEventEntity
import dev.yk.offercollector.data.ScreenEventEntity
import dev.yk.offercollector.data.StateMarkerEventEntity
import dev.yk.offercollector.data.TestSessionEntity
import dev.yk.offercollector.export.ExportService
import dev.yk.offercollector.service.Monitoring
import dev.yk.offercollector.service.PermissionStatus
import dev.yk.offercollector.service.ServiceState
import dev.yk.offercollector.service.StateMarkerRecorder
import dev.yk.offercollector.service.ShiftLocation
import dev.yk.offercollector.service.ShiftLocationService
import dev.yk.offercollector.data.LocationSampleEntity
import dev.yk.offercollector.data.ShiftSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import dev.yk.offercollector.core.DetectedOffer
import dev.yk.offercollector.core.DetectedOffers
import dev.yk.offercollector.core.OfferScreen
import dev.yk.offercollector.core.DeliveryEventDetector
import dev.yk.offercollector.core.OfferOutcomes
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String, val looksLikeWolt: Boolean)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val settings: StateFlow<AppSettings> = app.settings.settings
    val notifications: StateFlow<List<NotificationEventEntity>> =
        app.db.notificationEvents().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val screens: StateFlow<List<ScreenEventEntity>> =
        app.db.screenEvents().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Offer cards detected on screen, newest first. */
    val offers: StateFlow<List<DetectedOffer>> = screens
        .map { list ->
            DetectedOffers.build(list.mapNotNull { e -> ExportService.parseFields(e.redactedCandidateFieldsJson)?.let { OfferScreen(e.observedAtUtc, it, e.id) } })
                .reversed()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stateEvents: StateFlow<List<StateMarkerEventEntity>> =
        app.db.stateMarkerEvents().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** What happened to each offer (key = firstSeenMs), from the delivery detector. Missing = no event. */
    val offerOutcomes: StateFlow<Map<Long, OfferOutcomes.Outcome>> = combine(offers, stateEvents) { list, events ->
        val r = DeliveryEventDetector.detect(detectorItems(events))
        val out = OfferOutcomes.match(list.map { OfferOutcomes.Window(it.firstSeenMs, it.lastSeenMs) }, r.events)
        list.indices.mapNotNull { i -> out[i]?.let { list[i].firstSeenMs to it } }.toMap()
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val testSessions: StateFlow<List<TestSessionEntity>> =
        app.db.testSessions().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val shifts: StateFlow<List<ShiftSessionEntity>> =
        app.db.shiftSessions().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val locationSamples: StateFlow<List<LocationSampleEntity>> =
        app.db.locationSamples().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeShiftId: StateFlow<String?> = ShiftLocation.shiftId

    /** Location quality report for the diagnostics card (same function as the export). */
    val locationReport: StateFlow<dev.yk.offercollector.export.LocationReport.Result?> =
        combine(shifts, locationSamples, offers) { sh, ls, of ->
            dev.yk.offercollector.export.LocationReport.build(sh, ls, of, ExportService::iso)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Inputs of the area-density screen (refreshed when shifts, samples, markers or offers change). */
    val densityInput: StateFlow<dev.yk.offercollector.export.DensityInput?> =
        combine(shifts, locationSamples, stateEvents, offers) { sh, ls, ev, of ->
            dev.yk.offercollector.export.DensityInput.build(sh, ls, ev, of, System.currentTimeMillis())
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Pickup businesses (name + address) and their cached geocode. */
    val pickupPlaces: StateFlow<List<dev.yk.offercollector.data.PickupPlaceEntity>> =
        app.db.pickupPlaces().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pickupCandidate = dev.yk.offercollector.service.PickupAddresses.candidate
    fun confirmPickupAddress() = viewModelScope.launch { dev.yk.offercollector.service.PickupAddresses.confirm(app) }
    fun rejectPickupAddress() = viewModelScope.launch { dev.yk.offercollector.service.PickupAddresses.reject(app) }

    /** Shadow mode: immutable predictions, and their evaluation against what happened. */
    val shadowPredictions: StateFlow<List<dev.yk.offercollector.data.ShadowPredictionEntity>> =
        app.db.shadowPredictions().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val shadowEvaluated: StateFlow<List<dev.yk.offercollector.core.ShadowModel.Evaluated>> =
        combine(shadowPredictions, stateEvents, screens) { ps, ev, sc ->
            val r = DeliveryEventDetector.detect(detectorItems(ev))
            val shots = sc.mapNotNull { e -> ExportService.parseFields(e.redactedCandidateFieldsJson)?.let { OfferScreen(e.observedAtUtc, it, e.id) } }
            val built = DetectedOffers.build(shots)
            dev.yk.offercollector.export.ShadowEvaluation.evaluate(
                ps, r, dev.yk.offercollector.core.TripOrders.forTrips(r.trips, shots, built),
                dev.yk.offercollector.core.DeliveryChains.build(r.trips, shots, built),
            )
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun setVehicleCost(shekel: Double?) = viewModelScope.launch { app.settings.setVehicleCost(shekel?.let { Math.round(it * 100) }) }
    fun setShadowMargin(pct: Int) = viewModelScope.launch { app.settings.setShadowMargin(pct) }
    fun setPlannedEndHour(h: Int?) = viewModelScope.launch { app.settings.setPlannedEndHour(h) }

    /** User-given names for areas (local only). */
    val areaNames: StateFlow<Map<String, String>> = app.settings.settings
        .map { it.areaNames }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    fun renameArea(cellId: String, name: String) = viewModelScope.launch { app.settings.setAreaName(cellId, name.trim().take(30)) }

    fun setLocationInterval(s: Int) = viewModelScope.launch { app.settings.setLocationInterval(s) }
    /**
     * The route of one shift for the map (after the shift): precise track if recorded, else ~500 m cells.
     * Delivery windows, pickups and drop-off slides come from the same detectors as the export.
     */
    suspend fun loadRoute(shiftId: String): dev.yk.offercollector.core.ShiftRoute.Route = withContext(Dispatchers.Default) {
        val db = app.db
        val shift = db.shiftSessions().all().first { it.id == shiftId }
        val from = shift.startedAtUtc
        val to = shift.endedAtUtc ?: maxOf(shift.lastAliveUtc, System.currentTimeMillis())
        val track = db.trackPoints().forShift(shiftId).map { dev.yk.offercollector.core.ShiftRoute.Point(it.observedAtUtc, it.lat, it.lon, it.speedMps) }
        val coarse = db.locationSamples().all().filter { it.shiftId == shiftId && it.status == "OK" && it.cellCenterLat != null && it.cellCenterLon != null }
            .map { dev.yk.offercollector.core.ShiftRoute.Point(it.observedAtUtc, it.cellCenterLat!!, it.cellCenterLon!!, it.speedMps) }
        val detected = DeliveryEventDetector.detect(detectorItems(db.stateMarkerEvents().all()))
        val shots = db.screenEvents().all().filter { it.observedAtUtc in (from - 10 * 60_000)..(to + 10 * 60_000) }
            .mapNotNull { e -> ExportService.parseFields(e.redactedCandidateFieldsJson)?.let { OfferScreen(e.observedAtUtc, it, e.id) } }
        val built = DetectedOffers.build(shots)
        val chains = dev.yk.offercollector.core.DeliveryChains.build(detected.trips.filter { it.started_at_ms in from..to }, shots, built)
        val facts = dev.yk.offercollector.core.ChainFacts.build(detected, chains, shots, built, emptyList())
        dev.yk.offercollector.core.ShiftRoute.build(
            precise = track,
            coarse = coarse,
            chains = facts.chains.map { (it.start_earliest_ms ?: it.start_latest_ms) to (it.end_latest_ms ?: to) },
            pickupsMs = detected.events.filter { it.type == "PICKUP_DONE" && it.action_at_ms in from..to }.map { it.action_at_ms },
            dropoffsMs = dev.yk.offercollector.core.ChainFacts.slides(shots).filter { it in from..to },
        )
    }

    fun setLocationAdaptive(v: Boolean) = viewModelScope.launch { app.settings.setLocationAdaptive(v) }
    fun setPreciseTrack(v: Boolean) = viewModelScope.launch { app.settings.setPreciseTrack(v) }
    fun setRuleAcceptAdded(v: Boolean?) = viewModelScope.launch { app.settings.setRuleAcceptAdded(v) }
    fun setRuleMinPrice(agorot: Long?) = viewModelScope.launch { app.settings.setRuleMinPrice(agorot) }

    /** Cached history for the Plan / Review / What-works screens; refreshed when a tab opens. */
    val history = kotlinx.coroutines.flow.MutableStateFlow<dev.yk.offercollector.core.Insights.History?>(null)
    private var historyJob: kotlinx.coroutines.Job? = null
    fun refreshHistory() {
        if (historyJob?.isActive == true) return
        historyJob = viewModelScope.launch { history.value = loadHistory() }
    }

    /**
     * Everything the Plan / Review / What-works screens need, rebuilt from raw data with the same functions as the
     * export (so the screens and the computer reports agree). Heavy: call from a coroutine, not on every recomposition.
     */
    suspend fun loadHistory(): dev.yk.offercollector.core.Insights.History = withContext(Dispatchers.Default) {
        val db = app.db
        val s = app.settings.current()
        val now = System.currentTimeMillis()
        val stateEvents = db.stateMarkerEvents().all()
        val items = detectorItems(stateEvents)
        val detected = DeliveryEventDetector.detect(items)
        val shots = db.screenEvents().all().mapNotNull { e -> ExportService.parseFields(e.redactedCandidateFieldsJson)?.let { OfferScreen(e.observedAtUtc, it, e.id) } }
        val offers = DetectedOffers.build(shots)
        val chains = dev.yk.offercollector.core.DeliveryChains.build(detected.trips, shots, offers)
        val samples = db.locationSamples().all()
        val track = db.trackPoints().all()
        val tracked = track.map { it.shiftId }.toSet()
        val gps = track.map { dev.yk.offercollector.core.ChainFacts.GpsPoint(it.observedAtUtc, it.lat, it.lon, it.speedMps, dev.yk.offercollector.core.LocationGrid.cellOf(it.lat, it.lon).id) } +
            samples.filter { it.shiftId !in tracked && it.status == "OK" && it.cellCenterLat != null && it.cellCenterLon != null && it.cellId != null }
                .map { dev.yk.offercollector.core.ChainFacts.GpsPoint(it.observedAtUtc, it.cellCenterLat!!, it.cellCenterLon!!, it.speedMps, it.cellId!!) }
        val facts = dev.yk.offercollector.core.ChainFacts.build(detected, chains, shots, offers, gps)
        val marks = items.filter { it.markers.source == "SCREEN" || it.markers.source == "NOTIFICATION" }
            .map { dev.yk.offercollector.core.StateClassifier.classify(it.markers).let { c -> dev.yk.offercollector.core.AreaDensity.StateMark(it.atMs, c.state.name, c.evidence.name) } }
        val shiftFacts = dev.yk.offercollector.core.ShiftFacts.build(
            db.shiftSessions().all().map { dev.yk.offercollector.core.AreaDensity.Shift(it.id, it.startedAtUtc, it.endedAtUtc ?: maxOf(it.lastAliveUtc, now)) },
            samples.map { dev.yk.offercollector.core.AreaDensity.Sample(it.shiftId, it.observedAtUtc, if (it.status == "OK") it.cellId else null, it.cellCenterLat, it.cellCenterLon, it.accuracyM) },
            marks, facts.chains, gps, now,
        )
        val outcomes = OfferOutcomes.match(offers.map { OfferOutcomes.Window(it.firstSeenMs, it.lastSeenMs) }, detected.events)
        val decisions = offers.mapIndexed { i, o ->
            // Accepted offers: the reconstructed chain says whether an order was really still on board. Others: only
            // the screen at the moment of the card is known (a drop-off screen may be the last order being finished).
            val chained = chains.offers.firstOrNull { it.offer_amount_agorot == o.amountAgorot && it.accepted_at_ms in o.firstSeenMs..(o.firstSeenMs + 5 * 60_000) }
            dev.yk.offercollector.core.Insights.OfferDecision(
                o.firstSeenMs, o.amountAgorot, o.distanceKm?.toDoubleOrNull(),
                when (chained?.accept_context) {
                    "ADDED_TO_ACTIVE_ROUTE" -> "ACTIVE_ROUTE"
                    "IDLE" -> "IDLE"
                    else -> dev.yk.offercollector.core.DeliveryChains.contextAt(items, o.firstSeenMs)
                },
                when (outcomes[i]?.kind) {
                    OfferOutcomes.Kind.ACCEPTED -> "ACCEPTED"
                    OfferOutcomes.Kind.DECLINED -> "DECLINED"
                    else -> "UNKNOWN"
                },
            )
        }
        val reviews = shiftFacts.map { sf ->
            val inShift = decisions.filter { it.seenMs in sf.started_at_ms..sf.ended_at_ms }
            dev.yk.offercollector.core.ShiftReview.build(
                sf, facts.chains, inShift.size, inShift.count { it.decision == "ACCEPTED" }, inShift.count { it.decision == "DECLINED" },
                s.effectiveVehicleCostPerKmAgorot, sf.shift_id in tracked,
            )
        }
        dev.yk.offercollector.core.Insights.History(shiftFacts, facts.chains, decisions, gps, dev.yk.offercollector.core.EtaValidation.build(shots), reviews)
    }
    fun setLocationDebugExact(v: Boolean) = viewModelScope.launch {
        app.settings.setLocationDebugExact(v)
        if (!v) ShiftLocation.purgeDebug(app)
    }
    fun purgeDebugCoordinates() = viewModelScope.launch { ShiftLocation.purgeDebug(app) }
    suspend fun debugCoordinateCount(): Int = withContext(Dispatchers.IO) { app.db.locationSamples().debugCount() }

    fun setLocationConsent(v: Boolean) = viewModelScope.launch {
        app.settings.setLocationConsent(v)
        if (!v) endShift("CONSENT_REVOKED")
    }

    /** Call only after location permission was granted (the screen asks for it). */
    fun startShift() = viewModelScope.launch {
        if (!app.settings.current().locationConsent) { message.value = "חסרה הסכמה לאיסוף מיקום במשמרת"; return@launch }
        if (!ShiftLocation.hasPermission(app)) { message.value = "חסרה הרשאת מיקום"; return@launch }
        ShiftLocationService.start(app)
        message.value = "המשמרת התחילה — נשמר אזור כללי בלבד"
    }

    fun endShift(reason: String = "USER") = viewModelScope.launch {
        if (ShiftLocation.shiftId.value != null) ShiftLocationService.stop(app, reason) else ShiftLocation.recover(app)
    }

    fun startTestSession(label: String) = viewModelScope.launch {
        StateMarkerRecorder.startSession(app, label)
        message.value = "הבדיקה התחילה: $label"
    }

    fun endTestSession() = viewModelScope.launch {
        StateMarkerRecorder.endSession(app)
        message.value = "הבדיקה הסתיימה"
    }

    private val _status = MutableStateFlow(Monitoring.status(app))
    val status: StateFlow<PermissionStatus> = _status
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { ShiftLocation.recover(app) }
        dev.yk.offercollector.service.PickupAddresses.geocodePending(app) // resume any pending/failed lookups
        viewModelScope.launch {
            combine(ServiceState.listenerConnected, ServiceState.accessibilityConnected) { _, _ -> }.collect { refreshStatus() }
        }
    }

    fun refreshStatus() { _status.value = Monitoring.status(app) }

    fun setConsents(notifications: Boolean, accessibility: Boolean) = viewModelScope.launch {
        app.settings.setGeneralConsent(true)
        app.settings.setNotificationConsent(notifications)
        app.settings.setAccessibilityConsent(accessibility)
    }

    fun withdrawConsent() = viewModelScope.launch {
        endShift("CONSENT_REVOKED")
        app.settings.withdrawAll()
        message.value = "ההסכמה בוטלה והניטור הופסק. כדי לנתק לגמרי — בטל גם את ההרשאות בהגדרות המכשיר."
    }

    fun installedApps(): List<InstalledApp> {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != app.packageName }
            .map { (p, l) -> InstalledApp(p, l, PackageValidator.looksLikeWolt(p, l)) }
            .sortedWith(compareByDescending<InstalledApp> { it.looksLikeWolt }.thenBy { it.label.lowercase() })
    }

    private fun isInstalled(pkg: String) = runCatching { app.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    fun selectPackage(pkg: String): String? {
        return when (val r = PackageValidator.validate(pkg, app.packageName, ::isInstalled)) {
            is PackageValidator.Result.Invalid -> r.reasonHe
            PackageValidator.Result.Valid -> {
                viewModelScope.launch { app.settings.setSelectedPackage(pkg) }
                null
            }
        }
    }

    /** Display name of the selected source app (e.g. "Wolt Partner"), or null when none/uninstalled. */
    fun sourceLabel(): String? = settings.value.selectedPackage?.let { pkg ->
        runCatching { app.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrNull()
    }

    fun selectedPackageStillValid(): Boolean = settings.value.selectedPackage?.let(::isInstalled) ?: false

    fun start() = viewModelScope.launch {
        val s = app.settings.current()
        val err = when {
            !s.generalConsent || !s.notificationConsent -> "חסרה הסכמה לאיסוף התראות"
            s.selectedPackage == null || !isInstalled(s.selectedPackage) -> "יש לבחור אפליקציית מקור מותקנת"
            else -> null
        }
        if (err != null) { message.value = err; return@launch }
        Monitoring.start(app)
        refreshStatus()
    }

    fun stop() = viewModelScope.launch {
        Monitoring.stop(app)
        refreshStatus()
    }

    fun deleteAll() = viewModelScope.launch(Dispatchers.IO) {
        if (ShiftLocation.shiftId.value != null) ShiftLocation.end(app, "USER")
        ShiftLocationService.stop(app)
        app.db.locationSamples().deleteAll()
        app.db.trackPoints().deleteAll()
        app.db.pickupPlaces().deleteAll()
        app.db.shadowPredictions().deleteAll()
        app.db.shiftSessions().deleteAll()
        app.db.notificationEvents().deleteAll()
        app.db.screenEvents().deleteAll()
        app.db.offerCandidates().deleteAll()
        app.db.stateMarkerEvents().deleteAll()
        app.db.testSessions().deleteAll()
        StateMarkerRecorder.reset()
        app.settings.rotateSalt()
        message.value = "כל הנתונים המקומיים נמחקו"
    }

    fun export(uri: Uri) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            val db = app.db
            ExportService.toJson(
                ExportService.build(
                    db.notificationEvents().all(), db.screenEvents().all(), db.offerCandidates().all(), settings.value.selectedPackage,
                    stateEvents = db.stateMarkerEvents().all(), testSessions = db.testSessions().all(),
                    shifts = db.shiftSessions().all(), locationSamples = db.locationSamples().all(),
                    shadowPredictions = db.shadowPredictions().all(),
                    pickupPlaces = db.pickupPlaces().all(),
                    trackPoints = db.trackPoints().all(),
                ),
            )
        }
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        message.value = "הקובץ נשמר"
    }
}
