package dev.yk.offercollector.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** All timestamps are UTC epoch milliseconds. No raw text is ever stored. */
@Entity(tableName = "notification_events", indices = [Index("observed_at_utc"), Index("notification_key_hash")])
data class NotificationEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "observed_at_utc") val observedAtUtc: Long,
    @ColumnInfo(name = "source_package") val sourcePackage: String,
    @ColumnInfo(name = "notification_key_hash") val notificationKeyHash: String?,
    @ColumnInfo(name = "event_type") val eventType: String, // POSTED | UPDATED | REMOVED
    @ColumnInfo(name = "title_redacted") val titleRedacted: String?,
    @ColumnInfo(name = "text_redacted") val textRedacted: String?,
    /** Flags + non-personal metadata only (channel id, category, token kinds present). */
    @ColumnInfo(name = "fields_present_json") val fieldsPresentJson: String,
    /** REMOVED only: Android removal reason (e.g. REASON_CLICK). Never implies accept/decline. */
    @ColumnInfo(name = "removal_reason") val removalReason: String?,
    @ColumnInfo(name = "content_signature_hash") val contentSignatureHash: String?,
    @ColumnInfo(name = "created_at_utc") val createdAtUtc: Long,
)

@Entity(tableName = "screen_events", indices = [Index("observed_at_utc"), Index("fingerprint_hash")])
data class ScreenEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "observed_at_utc") val observedAtUtc: Long,
    @ColumnInfo(name = "source_package") val sourcePackage: String,
    @ColumnInfo(name = "event_type") val eventType: String, // WINDOW_STATE_CHANGED | WINDOW_CONTENT_CHANGED
    @ColumnInfo(name = "screen_category") val screenCategory: String, // OFFER_CANDIDATE | OTHER | UNKNOWN
    @ColumnInfo(name = "accessible_node_count") val accessibleNodeCount: Int?,
    @ColumnInfo(name = "text_node_count") val textNodeCount: Int?,
    @ColumnInfo(name = "redacted_candidate_fields_json") val redactedCandidateFieldsJson: String?,
    @ColumnInfo(name = "capture_status") val captureStatus: String, // VISIBLE | NO_ACCESSIBLE_TEXT | PERMISSION_MISSING | UNKNOWN | NOT_ALLOWED
    @ColumnInfo(name = "failure_reason") val failureReason: String?,
    @ColumnInfo(name = "parse_version") val parseVersion: String,
    @ColumnInfo(name = "fingerprint_hash") val fingerprintHash: String?,
    @ColumnInfo(name = "window_class") val windowClass: String?,
    /** Time from the first window event of the burst until the snapshot was read. */
    @ColumnInfo(name = "snapshot_latency_ms") val snapshotLatencyMs: Long?,
    /** How many raw accessibility events were coalesced into this row. */
    @ColumnInfo(name = "coalesced_event_count") val coalescedEventCount: Int,
    @ColumnInfo(name = "seconds_since_source_notification") val secondsSinceSourceNotification: Double?,
)

/** Phase 1 table. Defined now; nothing writes to it until the Phase 0 gate passes. */
@Entity(tableName = "offer_candidates", indices = [Index("first_observed_at_utc")])
data class OfferCandidateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "first_observed_at_utc") val firstObservedAtUtc: Long,
    @ColumnInfo(name = "last_observed_at_utc") val lastObservedAtUtc: Long,
    @ColumnInfo(name = "notification_event_id") val notificationEventId: String?,
    @ColumnInfo(name = "screen_event_id") val screenEventId: String?,
    @ColumnInfo(name = "offered_amount_agorot") val offeredAmountAgorot: Long?,
    @ColumnInfo(name = "merchant_names_redacted_or_business_only") val merchantNames: String?,
    @ColumnInfo(name = "pickup_area") val pickupArea: String?,
    @ColumnInfo(name = "dropoff_area") val dropoffArea: String?,
    @ColumnInfo(name = "stops_count") val stopsCount: Int?,
    @ColumnInfo(name = "status") val status: String, // UNKNOWN | USER_CONFIRMED_ACCEPTED | USER_CONFIRMED_DECLINED | USER_CONFIRMED_EXPIRED
    @ColumnInfo(name = "parse_confidence") val parseConfidence: Float?,
    @ColumnInfo(name = "correlation_confidence") val correlationConfidence: Float?,
    @ColumnInfo(name = "source_type") val sourceType: String, // NOTIFICATION | ACCESSIBILITY | MANUAL
    @ColumnInfo(name = "parser_version") val parserVersion: String,
    @ColumnInfo(name = "created_at_utc") val createdAtUtc: Long,
)

/**
 * A CHANGE in courier-state markers (feasibility tests A/B). Rows are written only when the
 * markers change, so this is a compact timeline. candidate_state/evidence come from
 * StateClassifier; nothing here is ever derived from something disappearing.
 */
@Entity(tableName = "state_marker_events", indices = [Index("observed_at_utc"), Index("test_session_id")])
data class StateMarkerEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "observed_at_utc") val observedAtUtc: Long,
    @ColumnInfo(name = "source") val source: String, // SCREEN | NOTIFICATION | SYSTEM
    @ColumnInfo(name = "candidate_state") val candidateState: String, // CourierState
    @ColumnInfo(name = "evidence") val evidence: String, // OBSERVED | ESTIMATED | UNKNOWN
    @ColumnInfo(name = "rule") val rule: String,
    @ColumnInfo(name = "markers_json") val markersJson: String,
    @ColumnInfo(name = "parse_version") val parseVersion: String,
    @ColumnInfo(name = "test_session_id") val testSessionId: String?,
)

/** A user-started diagnostic window, e.g. "Test A: offline → online". */
@Entity(tableName = "test_sessions", indices = [Index("started_at_utc")])
data class TestSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "started_at_utc") val startedAtUtc: Long,
    @ColumnInfo(name = "ended_at_utc") val endedAtUtc: Long?,
)

/** A shift the courier started in the Collector. Location is sampled only while one is open. */
@Entity(tableName = "shift_sessions", indices = [Index("started_at_utc")])
data class ShiftSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at_utc") val startedAtUtc: Long,
    @ColumnInfo(name = "ended_at_utc") val endedAtUtc: Long?,
    @ColumnInfo(name = "end_reason") val endReason: String?, // USER | MAX_DURATION | CONSENT_REVOKED | SERVICE_STOPPED
    @ColumnInfo(name = "location_consent") val locationConsent: Boolean,
    /** Heartbeat of the location service; if the process dies, the shift is closed at this time. */
    @ColumnInfo(name = "last_alive_utc") val lastAliveUtc: Long,
    // v4 (Phase 3)
    @ColumnInfo(name = "base_interval_s") val baseIntervalS: Int? = null,
    @ColumnInfo(name = "stationary_interval_s") val stationaryIntervalS: Int? = null,
    /** Device battery % at start/end — whole device, not this app alone. */
    @ColumnInfo(name = "battery_start_pct") val batteryStartPct: Int? = null,
    @ColumnInfo(name = "battery_end_pct") val batteryEndPct: Int? = null,
    @ColumnInfo(name = "charging_seen") val chargingSeen: Boolean = false,
    // v6: when the courier planned to finish (for "remaining shift time"); null = not set.
    @ColumnInfo(name = "planned_end_utc") val plannedEndUtc: Long? = null,
)

/** One coarse location sample: a ~500 m cell (id + centre) only, never the exact fix. */
@Entity(tableName = "location_samples", indices = [Index("observed_at_utc"), Index("shift_id")])
data class LocationSampleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "shift_id") val shiftId: String,
    @ColumnInfo(name = "observed_at_utc") val observedAtUtc: Long,
    @ColumnInfo(name = "trigger") val trigger: String,
    @ColumnInfo(name = "state_candidate") val stateCandidate: String,
    @ColumnInfo(name = "status") val status: String, // OK | UNKNOWN
    @ColumnInfo(name = "unknown_reason") val unknownReason: String?,
    @ColumnInfo(name = "cell_id") val cellId: String?,
    @ColumnInfo(name = "cell_center_lat") val cellCenterLat: Double?,
    @ColumnInfo(name = "cell_center_lon") val cellCenterLon: Double?,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Int?,
    @ColumnInfo(name = "fix_age_s") val fixAgeS: Int?,
    @ColumnInfo(name = "provider") val provider: String?,
    // v4 (Phase 3)
    @ColumnInfo(name = "source") val source: String = "EVENT", // EVENT | PERIODIC | SESSION
    /** Evidence of the state/event this sample belongs to (OBSERVED | ESTIMATED | UNKNOWN). Never upgraded by sampling. */
    @ColumnInfo(name = "state_evidence") val stateEvidence: String = "UNKNOWN",
    @ColumnInfo(name = "speed_mps") val speedMps: Float? = null,
    /** |sample moment − fix time| in ms. */
    @ColumnInfo(name = "location_age_ms") val locationAgeMs: Long? = null,
    /** Detector event time (accept/pickup/drop-off/completion) this sample stands for. */
    @ColumnInfo(name = "event_at_utc") val eventAtUtc: Long? = null,
    @ColumnInfo(name = "event_key") val eventKey: String? = null,
    @ColumnInfo(name = "battery_pct") val batteryPct: Int? = null,
    /** Exact fix — ONLY in explicit debug mode, purged after 24 h, never exported. */
    @ColumnInfo(name = "debug_lat") val debugLat: Double? = null,
    @ColumnInfo(name = "debug_lon") val debugLon: Double? = null,
)

/**
 * A pickup business, identified by name + address (branches stay apart). Business data only — never a
 * customer. The address is geocoded ONCE (background); the result is cached here.
 */
@Entity(tableName = "pickup_places", indices = [Index("geo_status")])
data class PickupPlaceEntity(
    @PrimaryKey val key: String, // "<name>|<address>"
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "first_seen_utc") val firstSeenUtc: Long,
    @ColumnInfo(name = "geo_status") val geoStatus: String, // PENDING | OK | NOT_FOUND | AMBIGUOUS | ERROR
    @ColumnInfo(name = "cell_id") val cellId: String? = null,
    @ColumnInfo(name = "center_lat") val centerLat: Double? = null,
    @ColumnInfo(name = "center_lon") val centerLon: Double? = null,
    @ColumnInfo(name = "geo_at_utc") val geoAtUtc: Long? = null,
    @ColumnInfo(name = "geo_note") val geoNote: String? = null,
)

/**
 * One shadow prediction, written ONCE when an offer appeared and never updated (insert-only DAO),
 * so what the model said at that moment can be compared with what actually happened.
 * ACCEPT / DECLINE are experimental shadow classifications — never shown during a shift.
 */
@Entity(tableName = "shadow_predictions", indices = [Index("offer_at_utc")])
data class ShadowPredictionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at_utc") val createdAtUtc: Long,
    @ColumnInfo(name = "offer_at_utc") val offerAtUtc: Long,
    @ColumnInfo(name = "rule_version") val ruleVersion: String,
    @ColumnInfo(name = "margin_pct") val marginPct: Int,
    @ColumnInfo(name = "amount_agorot") val amountAgorot: Long,
    @ColumnInfo(name = "distance_km") val distanceKm: Double?,
    @ColumnInfo(name = "courier_cell") val courierCell: String?,
    @ColumnInfo(name = "pickup_cell") val pickupCell: String?,
    @ColumnInfo(name = "pickup_basis") val pickupBasis: String,
    @ColumnInfo(name = "shift_id") val shiftId: String?,
    @ColumnInfo(name = "planned_shift_end_utc") val plannedShiftEndUtc: Long?,
    @ColumnInfo(name = "remaining_shift_min") val remainingShiftMin: Double?,
    @ColumnInfo(name = "duration_median_min") val durationMedianMin: Double?,
    @ColumnInfo(name = "duration_low_min") val durationLowMin: Double?,
    @ColumnInfo(name = "duration_high_min") val durationHighMin: Double?,
    @ColumnInfo(name = "duration_confidence") val durationConfidence: String?,
    @ColumnInfo(name = "duration_distance_matched") val durationDistanceMatched: Boolean?,
    @ColumnInfo(name = "vehicle_cost_per_km_agorot") val vehicleCostPerKmAgorot: Long?,
    @ColumnInfo(name = "vehicle_cost_agorot") val vehicleCostAgorot: Long?,
    @ColumnInfo(name = "net_per_hour_median_agorot") val netPerHourMedianAgorot: Long?,
    @ColumnInfo(name = "net_per_hour_slow_agorot") val netPerHourSlowAgorot: Long?,
    @ColumnInfo(name = "net_per_hour_fast_agorot") val netPerHourFastAgorot: Long?,
    /** HistoricalShiftHourlyRate — a baseline, not the true opportunity cost. */
    @ColumnInfo(name = "historical_shift_hourly_rate_agorot") val historicalShiftHourlyRateAgorot: Long?,
    @ColumnInfo(name = "baseline_time_value_agorot") val baselineTimeValueAgorot: Long?,
    @ColumnInfo(name = "decision") val decision: String,
    @ColumnInfo(name = "reasons_json") val reasonsJson: String,
    /** Which historical records were used: {"duration":[trip ids], "hourlyRate":[trip ids], "shiftHours":x}. */
    @ColumnInfo(name = "inputs_json") val inputsJson: String,
)

/**
 * Precise shift track (~5 m GPS), recorded every few seconds during a shift — for the route map, distance and
 * stop analysis. The courier asked for this precision (30.10.2026): it includes home and customer locations,
 * stays on the device and in the courier's own exports, and is never sent anywhere else.
 */
@Entity(tableName = "track_points", indices = [Index("shift_id"), Index("observed_at_utc")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "shift_id") val shiftId: String,
    @ColumnInfo(name = "observed_at_utc") val observedAtUtc: Long,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lon") val lon: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float?,
    @ColumnInfo(name = "speed_mps") val speedMps: Float?,
)
