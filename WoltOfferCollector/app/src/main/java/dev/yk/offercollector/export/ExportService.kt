package dev.yk.offercollector.export

import android.os.Build
import dev.yk.offercollector.BuildConfig
import dev.yk.offercollector.core.CandidateFields
import dev.yk.offercollector.core.SnapshotAnalyzer
import dev.yk.offercollector.data.NotificationEventEntity
import dev.yk.offercollector.data.OfferCandidateEntity
import dev.yk.offercollector.data.ScreenEventEntity
import dev.yk.offercollector.service.NotificationFieldsPresent
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ExportNotification(
    val id: String,
    val observed_at_utc: String,
    val source_package: String,
    val notification_key_hash: String?,
    val event_type: String,
    val title_redacted: String?,
    val text_redacted: String?,
    val fields_present: NotificationFieldsPresent?,
    val removal_reason: String?,
)

@Serializable
data class ExportScreen(
    val id: String,
    val observed_at_utc: String,
    val source_package: String,
    val event_type: String,
    val screen_category: String,
    val capture_status: String,
    val failure_reason: String?,
    val accessible_node_count: Int?,
    val text_node_count: Int?,
    val window_class: String?,
    val snapshot_latency_ms: Long?,
    val coalesced_event_count: Int,
    val seconds_since_source_notification: Double?,
    val parse_version: String,
    val fingerprint_hash: String?,
    val redacted_candidate_fields: CandidateFields?,
)

@Serializable
data class FieldCoverage(
    val screen_snapshots: Int,
    val snapshots_visible: Int,
    val snapshots_no_accessible_text: Int,
    val snapshots_unknown: Int,
    val offer_candidate_screens: Int,
    val screens_with_amount: Int,
    val screens_with_distance: Int,
    val screens_with_duration: Int,
    val screens_with_stops_count: Int,
    val screens_with_order_count: Int,
    val screens_with_clock_time: Int,
    val view_ids_seen: List<String>,
    val ui_keywords_seen: List<String>,
)

@Serializable
data class ExportSummary(
    val notification_posted: Int,
    val notification_updated: Int,
    val notification_removed: Int,
    val notifications_with_amount: Int,
    val coverage: FieldCoverage,
    val median_snapshot_latency_ms: Long?,
)

@Serializable
data class TrackPointExport(val shift_id: String, val t_ms: Long, val lat: Double, val lon: Double, val accuracy_m: Float? = null, val speed_mps: Float? = null)

/** A business (pickup place) in the export: geocoded only from the business address string (OK = verified cell). */
@Serializable
data class PickupPlaceExport(
    val key: String,
    val name: String,
    val address: String,
    val first_seen_ms: Long,
    /** OK | NOT_FOUND | AMBIGUOUS | FAR_FROM_COURIER | ERROR | PENDING */
    val geo_status: String,
    val cell_id: String? = null,
    val geo_note: String? = null,
)

@Serializable
data class DiagnosticsExport(
    val export_format: String = "wolt-offer-collector/diagnostics-v1",
    val exported_at_utc: String,
    val app_version: String,
    val parse_version: String,
    val android_sdk_int: Int,
    val device_manufacturer: String,
    val device_model: String,
    val selected_package: String?,
    val privacy_note: String = "On-screen and notification text is reduced to typed tokens; only amounts, distances, minutes, clock times, counts and fixed UI vocabulary keep values. Single exception: the pickup restaurant name from an offer card (pickup_names), stored only if it has no phone or long number. Destinations/addresses are never stored.",
    val acceptance_note: String = "A notification being removed does NOT mean the offer was accepted or declined. Offer outcome is unknown unless labeled by the user.",
    val summary: ExportSummary,
    val notification_events: List<ExportNotification>,
    val screen_events: List<ExportScreen>,
    val offer_candidates: List<OfferCandidateEntityExport>,
    /** Courier-state feasibility markers (tests A/B). Additive field; format id unchanged. */
    val state_note: String = "state_marker_events are feasibility markers. Only OBSERVED offer markers are proven; ESTIMATED states are unproven hypotheses; a disappearance is never acceptance or completion.",
    val state_marker_events: List<ExportStateMarkerEvent> = emptyList(),
    val test_sessions: List<ExportTestSession> = emptyList(),
    /** Derived post-hoc from state_marker_events by DeliveryEventDetector (re-computable). */
    val delivery_detector_version: String = dev.yk.offercollector.core.DeliveryEventDetector.VERSION,
    val delivery_events: List<dev.yk.offercollector.core.DeliveryEventDetector.DeliveryEvent> = emptyList(),
    val delivery_trips: List<dev.yk.offercollector.core.DeliveryEventDetector.Trip> = emptyList(),
    /** Orders per trip from Wolt's route timeline (a trip is NOT one order). */
    val trip_orders_version: String = dev.yk.offercollector.core.TripOrders.VERSION,
    val trip_orders: List<dev.yk.offercollector.core.TripOrders.Evidence> = emptyList(),
    /** Overlapping accepted offers: chains, carried orders, new orders per offer (uncertain links marked). */
    val chains_version: String = dev.yk.offercollector.core.DeliveryChains.VERSION,
    val accepted_offers: List<dev.yk.offercollector.core.DeliveryChains.AcceptedOffer> = emptyList(),
    val delivery_chains: List<dev.yk.offercollector.core.DeliveryChains.Chain> = emptyList(),
    /** Chain economics facts + per-offer context for the incremental-offer model (ranges; nothing invented). */
    val chain_facts_version: String = dev.yk.offercollector.core.ChainFacts.VERSION,
    val offer_contexts: List<dev.yk.offercollector.core.ChainFacts.OfferContext> = emptyList(),
    val chain_facts: List<dev.yk.offercollector.core.ChainFacts.ChainEconomicsFacts> = emptyList(),
    /** Shift-level time split (chains / verified idle / unverified / offline / unknown), revenue and GPS. */
    val shift_facts: List<dev.yk.offercollector.core.ShiftFacts.Shift> = emptyList(),
    /** Wolt route numbers vs OBSERVED completions (what the displayed minutes mean, and how far off they are). */
    val eta_validation_version: String = dev.yk.offercollector.core.EtaValidation.VERSION,
    val eta_validation: dev.yk.offercollector.core.EtaValidation.Result? = null,
    /** Businesses (pickup) with the courier-consented address and its geocoded ~500 m cell. No customer data. */
    val pickup_places: List<PickupPlaceExport> = emptyList(),
    /** Precise shift track (~5 m) — the courier's own location; only in the courier's own export. */
    val track_points: List<TrackPointExport> = emptyList(),
    /** Hotspot MVP (LOCATION_EXPORT_FORMAT.md). Cells only — exact coordinates are never stored. */
    val location_grid: ExportLocationGrid = ExportLocationGrid(),
    /** Kept for importers of 0.3.0 exports; same sessions as location_sessions. */
    val shift_sessions: List<ExportShiftSession> = emptyList(),
    val location_samples: List<ExportLocationSample> = emptyList(),
    // Phase 3 (WoltOfferCollector/PHASE3_LOCATION.md). Additive; existing fields keep their meaning.
    val schema_version: Int = 11,
    val location_parser_version: String = dev.yk.offercollector.core.OfferLocations.LOCATION_PARSER_VERSION,
    val location_sessions: List<ExportLocationSession> = emptyList(),
    val offer_locations: List<dev.yk.offercollector.core.OfferLocations.OfferLocation> = emptyList(),
    val location_quality_summary: LocationQualitySummary? = null,
    /** Shadow mode: immutable predictions as made at offer time (experimental classifications). */
    val shadow_predictions: List<kotlinx.serialization.json.JsonObject> = emptyList(),
)

@Serializable
data class ExportStateMarkerEvent(
    val id: String,
    val observed_at_utc: String,
    val source: String,
    val candidate_state: String,
    val evidence: String,
    val rule: String,
    val markers: kotlinx.serialization.json.JsonElement,
    val parse_version: String,
    val test_session_id: String?,
)

@Serializable
data class ExportLocationGrid(
    val scheme: String = dev.yk.offercollector.core.LocationGrid.SCHEME,
    val cell_size_m: Int = dev.yk.offercollector.core.LocationGrid.CELL_SIZE_M,
    val lat_step_deg: Double = dev.yk.offercollector.core.LocationGrid.LAT_STEP_DEG,
    val note: String = "Cell id + cell centre only. Samples exist only inside user-started shifts; status UNKNOWN when the fix was missing, stale (>120 s), mock or less accurate than 150 m.",
)

@Serializable
data class ExportShiftSession(val id: String, val started_at_utc: String, val ended_at_utc: String?, val end_reason: String?, val location_consent: Boolean)

@Serializable
data class ExportLocationSample(
    val id: String, val shift_id: String, val observed_at_utc: String, val trigger: String, val state_candidate: String,
    val status: String, val unknown_reason: String?, val cell_id: String?, val cell_center_lat: Double?, val cell_center_lon: Double?,
    val accuracy_m: Int?, val fix_age_s: Int?, val provider: String?,
    // Phase 3 additions. Exact coordinates are NEVER exported (debug values stay on the device).
    val source: String = "EVENT",
    val capture_status: String = "OK",
    val state_evidence: String = "UNKNOWN",
    val location_age_ms: Long? = null,
    val speed_mps: Float? = null,
    val event_at_utc: String? = null,
    val event_key: String? = null,
    val battery_pct: Int? = null,
)

@Serializable
data class ExportTestSession(val id: String, val label: String, val started_at_utc: String, val ended_at_utc: String?)

@Serializable
data class OfferCandidateEntityExport(val id: String, val first_observed_at_utc: String, val offered_amount_agorot: Long?, val status: String)

object ExportService {
    val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true }
    private val lenient = Json { ignoreUnknownKeys = true }

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    fun parseFields(raw: String?): CandidateFields? = raw?.let { runCatching { lenient.decodeFromString<CandidateFields>(it) }.getOrNull() }
    fun parseNotifFields(raw: String?): NotificationFieldsPresent? = raw?.let { runCatching { lenient.decodeFromString<NotificationFieldsPresent>(it) }.getOrNull() }

    fun coverage(screens: List<ScreenEventEntity>): FieldCoverage {
        val fields = screens.map { parseFields(it.redactedCandidateFieldsJson) }
        return FieldCoverage(
            screen_snapshots = screens.size,
            snapshots_visible = screens.count { it.captureStatus == "VISIBLE" },
            snapshots_no_accessible_text = screens.count { it.captureStatus == "NO_ACCESSIBLE_TEXT" },
            snapshots_unknown = screens.count { it.captureStatus == "UNKNOWN" },
            offer_candidate_screens = screens.count { it.screenCategory == "OFFER_CANDIDATE" },
            screens_with_amount = fields.count { it?.amounts_agorot?.isNotEmpty() == true },
            screens_with_distance = fields.count { it?.distances_km?.isNotEmpty() == true },
            screens_with_duration = fields.count { it?.durations_min?.isNotEmpty() == true },
            screens_with_stops_count = fields.count { it?.stops_counts?.isNotEmpty() == true },
            screens_with_order_count = fields.count { it?.order_counts?.isNotEmpty() == true },
            screens_with_clock_time = fields.count { it?.clock_times?.isNotEmpty() == true },
            view_ids_seen = fields.flatMap { f -> f?.nodes.orEmpty().mapNotNull { it.viewId } }.distinct().sorted(),
            ui_keywords_seen = fields.flatMap { it?.ui_keywords.orEmpty() }.distinct().sorted(),
        )
    }

    fun build(
        notifications: List<NotificationEventEntity>,
        screens: List<ScreenEventEntity>,
        offers: List<OfferCandidateEntity>,
        selectedPackage: String?,
        stateEvents: List<dev.yk.offercollector.data.StateMarkerEventEntity> = emptyList(),
        testSessions: List<dev.yk.offercollector.data.TestSessionEntity> = emptyList(),
        shifts: List<dev.yk.offercollector.data.ShiftSessionEntity> = emptyList(),
        locationSamples: List<dev.yk.offercollector.data.LocationSampleEntity> = emptyList(),
        shadowPredictions: List<dev.yk.offercollector.data.ShadowPredictionEntity> = emptyList(),
        pickupPlaces: List<dev.yk.offercollector.data.PickupPlaceEntity> = emptyList(),
        trackPoints: List<dev.yk.offercollector.data.TrackPointEntity> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        sdkInt: Int = Build.VERSION.SDK_INT,
        manufacturer: String = Build.MANUFACTURER ?: "unknown",
        model: String = Build.MODEL ?: "unknown",
    ): DiagnosticsExport {
        val latencies = screens.mapNotNull { it.snapshotLatencyMs }.sorted()
        val detected = dev.yk.offercollector.core.DeliveryEventDetector.detect(
            stateEvents.mapNotNull { e ->
                runCatching { lenient.decodeFromString<dev.yk.offercollector.core.StateMarkers>(e.markersJson) }.getOrNull()
                    ?.let { dev.yk.offercollector.core.DeliveryEventDetector.Item(e.observedAtUtc, e.candidateState, it) }
            },
        )
        val nFields = notifications.map { parseNotifFields(it.fieldsPresentJson) }
        val offerScreens = screens.mapNotNull { e -> parseFields(e.redactedCandidateFieldsJson)?.let { dev.yk.offercollector.core.OfferScreen(e.observedAtUtc, it, e.id) } }
        val offersDetected = dev.yk.offercollector.core.DetectedOffers.build(offerScreens)
        val chains = dev.yk.offercollector.core.DeliveryChains.build(detected.trips, offerScreens, offersDetected)
        // Distance and motion: the precise track where a shift has one, otherwise the ~500 m cell samples.
        val tracked = trackPoints.map { it.shiftId }.toSet()
        val gpsPoints = trackPoints.map { dev.yk.offercollector.core.ChainFacts.GpsPoint(it.observedAtUtc, it.lat, it.lon, it.speedMps, dev.yk.offercollector.core.LocationGrid.cellOf(it.lat, it.lon).id) } +
            locationSamples.filter { it.shiftId !in tracked }.mapNotNull { l ->
                val lat = l.cellCenterLat
                val lon = l.cellCenterLon
                val cell = l.cellId
                if (l.status == "OK" && lat != null && lon != null && cell != null) dev.yk.offercollector.core.ChainFacts.GpsPoint(l.observedAtUtc, lat, lon, l.speedMps, cell) else null
            }
        val facts = dev.yk.offercollector.core.ChainFacts.build(detected, chains, offerScreens, offersDetected, gpsPoints)
        val loc = LocationReport.build(shifts, locationSamples, offersDetected, ::iso)
        return DiagnosticsExport(
            exported_at_utc = iso(nowMs),
            app_version = BuildConfig.VERSION_NAME,
            parse_version = SnapshotAnalyzer.PARSE_VERSION,
            android_sdk_int = sdkInt,
            device_manufacturer = manufacturer,
            device_model = model,
            selected_package = selectedPackage,
            summary = ExportSummary(
                notification_posted = notifications.count { it.eventType == "POSTED" },
                notification_updated = notifications.count { it.eventType == "UPDATED" },
                notification_removed = notifications.count { it.eventType == "REMOVED" },
                notifications_with_amount = nFields.count { it?.amount_in_notification == true },
                coverage = coverage(screens),
                median_snapshot_latency_ms = latencies.getOrNull(latencies.size / 2),
            ),
            notification_events = notifications.zip(nFields).map { (n, f) ->
                ExportNotification(n.id, iso(n.observedAtUtc), n.sourcePackage, n.notificationKeyHash, n.eventType, n.titleRedacted, n.textRedacted, f, n.removalReason)
            },
            screen_events = screens.map { s ->
                ExportScreen(
                    s.id, iso(s.observedAtUtc), s.sourcePackage, s.eventType, s.screenCategory, s.captureStatus, s.failureReason,
                    s.accessibleNodeCount, s.textNodeCount, s.windowClass, s.snapshotLatencyMs, s.coalescedEventCount,
                    s.secondsSinceSourceNotification, s.parseVersion, s.fingerprintHash, parseFields(s.redactedCandidateFieldsJson),
                )
            },
            offer_candidates = offers.map { OfferCandidateEntityExport(it.id, iso(it.firstObservedAtUtc), it.offeredAmountAgorot, it.status) },
            state_marker_events = stateEvents.map {
                ExportStateMarkerEvent(
                    it.id, iso(it.observedAtUtc), it.source, it.candidateState, it.evidence, it.rule,
                    kotlinx.serialization.json.Json.parseToJsonElement(it.markersJson), it.parseVersion, it.testSessionId,
                )
            },
            test_sessions = testSessions.map { ExportTestSession(it.id, it.label, iso(it.startedAtUtc), it.endedAtUtc?.let(::iso)) },
            delivery_events = detected.events,
            delivery_trips = detected.trips,
            trip_orders = dev.yk.offercollector.core.TripOrders.forTrips(detected.trips, offerScreens, offersDetected),
            accepted_offers = chains.offers,
            delivery_chains = chains.chains,
            eta_validation = dev.yk.offercollector.core.EtaValidation.build(offerScreens),
            track_points = trackPoints.map { TrackPointExport(it.shiftId, it.observedAtUtc, it.lat, it.lon, it.accuracyM, it.speedMps) },
            pickup_places = pickupPlaces.map { PickupPlaceExport(it.key, it.name, it.address, it.firstSeenUtc, it.geoStatus, it.cellId, it.geoNote) },
            offer_contexts = facts.offers,
            chain_facts = facts.chains,
            shift_facts = dev.yk.offercollector.core.ShiftFacts.build(
                shifts.map { dev.yk.offercollector.core.AreaDensity.Shift(it.id, it.startedAtUtc, it.endedAtUtc ?: maxOf(it.lastAliveUtc, nowMs)) },
                locationSamples.map { dev.yk.offercollector.core.AreaDensity.Sample(it.shiftId, it.observedAtUtc, if (it.status == "OK") it.cellId else null, it.cellCenterLat, it.cellCenterLon, it.accuracyM) },
                stateEvents.mapNotNull { e ->
                    runCatching { lenient.decodeFromString<dev.yk.offercollector.core.StateMarkers>(e.markersJson) }.getOrNull()
                        ?.takeIf { it.source == "SCREEN" || it.source == "NOTIFICATION" }
                        ?.let { m -> dev.yk.offercollector.core.StateClassifier.classify(m).let { c -> dev.yk.offercollector.core.AreaDensity.StateMark(e.observedAtUtc, c.state.name, c.evidence.name) } }
                },
                facts.chains,
                gpsPoints,
                nowMs,
            ),
            shift_sessions = shifts.map { ExportShiftSession(it.id, iso(it.startedAtUtc), it.endedAtUtc?.let(::iso), it.endReason, it.locationConsent) },
            location_samples = locationSamples.map {
                ExportLocationSample(
                    it.id, it.shiftId, iso(it.observedAtUtc), it.trigger, it.stateCandidate, it.status, it.unknownReason,
                    it.cellId, it.cellCenterLat, it.cellCenterLon, it.accuracyM, it.fixAgeS, it.provider,
                    source = it.source, capture_status = LocationReport.captureStatus(it), state_evidence = it.stateEvidence,
                    location_age_ms = it.locationAgeMs, speed_mps = it.speedMps, event_at_utc = it.eventAtUtc?.let(::iso),
                    event_key = it.eventKey, battery_pct = it.batteryPct,
                )
            },
            location_sessions = loc.sessions,
            offer_locations = loc.offerLocations,
            location_quality_summary = loc.summary,
            shadow_predictions = shadowPredictions.map { p ->
                kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(p.id))
                    put("offer_at_utc", kotlinx.serialization.json.JsonPrimitive(iso(p.offerAtUtc)))
                    put("created_at_utc", kotlinx.serialization.json.JsonPrimitive(iso(p.createdAtUtc)))
                    put("rule_version", kotlinx.serialization.json.JsonPrimitive(p.ruleVersion))
                    put("margin_pct", kotlinx.serialization.json.JsonPrimitive(p.marginPct))
                    put("amount_agorot", kotlinx.serialization.json.JsonPrimitive(p.amountAgorot))
                    put("distance_km", kotlinx.serialization.json.JsonPrimitive(p.distanceKm))
                    put("courier_cell", kotlinx.serialization.json.JsonPrimitive(p.courierCell))
                    put("pickup_cell", kotlinx.serialization.json.JsonPrimitive(p.pickupCell))
                    put("pickup_basis", kotlinx.serialization.json.JsonPrimitive(p.pickupBasis))
                    put("remaining_shift_min", kotlinx.serialization.json.JsonPrimitive(p.remainingShiftMin))
                    put("duration_median_min", kotlinx.serialization.json.JsonPrimitive(p.durationMedianMin))
                    put("duration_low_min", kotlinx.serialization.json.JsonPrimitive(p.durationLowMin))
                    put("duration_high_min", kotlinx.serialization.json.JsonPrimitive(p.durationHighMin))
                    put("duration_confidence", kotlinx.serialization.json.JsonPrimitive(p.durationConfidence))
                    put("vehicle_cost_agorot", kotlinx.serialization.json.JsonPrimitive(p.vehicleCostAgorot))
                    put("net_per_hour_median_agorot", kotlinx.serialization.json.JsonPrimitive(p.netPerHourMedianAgorot))
                    put("historical_shift_hourly_rate_agorot", kotlinx.serialization.json.JsonPrimitive(p.historicalShiftHourlyRateAgorot))
                    put("baseline_time_value_agorot", kotlinx.serialization.json.JsonPrimitive(p.baselineTimeValueAgorot))
                    put("shadow_decision_experimental", kotlinx.serialization.json.JsonPrimitive(p.decision))
                    put("reasons", kotlinx.serialization.json.Json.parseToJsonElement(p.reasonsJson))
                    put("inputs", kotlinx.serialization.json.Json.parseToJsonElement(p.inputsJson))
                }
            },
        )
    }

    fun toJson(export: DiagnosticsExport): String = json.encodeToString(export)
}
