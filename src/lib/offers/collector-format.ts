import { z } from "zod";

/**
 * Schema of the Android "Wolt Offer Collector" diagnostics export
 * (export_format "wolt-offer-collector/diagnostics-v1", see
 * WoltOfferCollector/app/src/main/java/dev/yk/offercollector/export/ExportService.kt).
 * Only the fields the importer needs are validated; unknown fields are ignored.
 */
export const COLLECTOR_EXPORT_FORMAT = "wolt-offer-collector/diagnostics-v1";

const nodeSchema = z.object({
  viewId: z.string().nullable().optional(),
  cls: z.string().nullable().optional(),
  source: z.string().optional(),
  redacted: z.string(),
});

export const candidateFieldsSchema = z.object({
  amounts_agorot: z.array(z.number().int()).default([]),
  distances_km: z.array(z.string()).default([]),
  durations_min: z.array(z.number().int()).default([]),
  stops_counts: z.array(z.number().int()).default([]),
  order_counts: z.array(z.number().int()).default([]),
  ui_keywords: z.array(z.string()).default([]),
  nodes: z.array(nodeSchema).default([]),
  // Added in Collector parse version p1-pickup-1; absent in older snapshots.
  pickup_names: z.array(z.string()).default([]),
});
export type CandidateFields = z.infer<typeof candidateFieldsSchema>;

export const screenEventSchema = z.object({
  id: z.string().min(1),
  observed_at_utc: z.string().datetime({ offset: true }),
  source_package: z.string(),
  screen_category: z.string(),
  capture_status: z.string(),
  parse_version: z.string(),
  coalesced_event_count: z.number().int().default(0),
  redacted_candidate_fields: candidateFieldsSchema.nullable(),
});
export type ScreenEvent = z.infer<typeof screenEventSchema>;

// --- Location / shift fields (WoltOfferCollector/LOCATION_EXPORT_FORMAT.md). Older exports lack them. ---

export const locationGridSchema = z.object({
  scheme: z.string(),
  cell_size_m: z.number().positive(),
  lat_step_deg: z.number().positive(),
});

export const shiftSessionSchema = z.object({
  id: z.string().min(1),
  started_at_utc: z.string().datetime({ offset: true }),
  ended_at_utc: z.string().datetime({ offset: true }).nullable().default(null),
  end_reason: z.string().nullable().default(null),
  location_consent: z.boolean(),
});
export type ShiftSession = z.infer<typeof shiftSessionSchema>;

export const CELL_ID_RE = /^g500:-?\d+:-?\d+$/;

// Only cell-level fields are read; any other field (there should be none) is dropped by Zod.
export const locationSampleSchema = z.object({
  id: z.string().min(1),
  shift_id: z.string().min(1),
  observed_at_utc: z.string().datetime({ offset: true }),
  trigger: z.string(),
  state_candidate: z.string().nullable().default(null),
  status: z.enum(["OK", "UNKNOWN"]),
  unknown_reason: z.string().nullable().default(null),
  cell_id: z.string().regex(CELL_ID_RE).nullable().default(null),
  cell_center_lat: z.number().nullable().default(null),
  cell_center_lon: z.number().nullable().default(null),
  accuracy_m: z.number().nullable().default(null),
  fix_age_s: z.number().nullable().default(null),
  provider: z.string().nullable().default(null),
  // Phase 3 (Collector 0.4.0+); absent in older exports.
  state_evidence: z.string().nullable().default(null),
  source: z.string().nullable().default(null),
});
export type LocationSampleRaw = z.infer<typeof locationSampleSchema>;

export const deliveryEventSchema = z.object({
  type: z.string(),
  evidence: z.string(),
  action_at_ms: z.number().int(),
  action: z.string().default(""),
  confirmed_at_ms: z.number().int().nullable().default(null),
  confirmation: z.string().default(""),
  trip_index: z.number().int().nullable().default(null),
  rule_validated: z.boolean().default(false),
  path: z.string().default("ACTION"),
});
export type DeliveryEventRaw = z.infer<typeof deliveryEventSchema>;

export const deliveryTripSchema = z.object({
  index: z.number().int(),
  started_at_ms: z.number().int(),
  start_evidence: z.string(),
  completed_at_ms: z.number().int().nullable().default(null),
  end_evidence: z.string(),
  orders: z.number().int().default(0),
  pickups: z.number().int().default(0),
  dropoffs: z.number().int().default(0),
  offer_amounts_agorot: z.array(z.number().int()).default([]),
  expected_dropoffs: z.number().int().nullable().default(null),
  end_note: z.string().nullable().default(null),
});
export type DeliveryTripRaw = z.infer<typeof deliveryTripSchema>;

const routeSnapshotSchema = z.object({
  at_ms: z.number().int(),
  stops: z.array(
    z.object({
      kind: z.string(),
      shape: z.string(),
      status: z.string().nullable().default(null),
      position: z.number().int().default(0),
      eta_minutes: z.number().int().nullable().default(null),
      target_at_ms: z.number().int().nullable().default(null),
    }),
  ),
  stale: z.boolean().default(false),
});

/** Per-offer context for the incremental-offer model (Collector schema 7, chain-facts-1). */
export const offerContextSchema = z.object({
  trip_index: z.number().int(),
  accept_context: z.string(),
  offer_seen_at_ms: z.number().int().nullable().default(null),
  offer_cell: z.string().nullable().default(null),
  accepted_earliest_ms: z.number().int().nullable().default(null),
  active_orders_before: z.number().int().nullable().default(null),
  new_orders_confidence: z.string(),
  route_before: routeSnapshotSchema.nullable().default(null),
  route_after: routeSnapshotSchema.nullable().default(null),
  new_pickup_stops: z.number().int().nullable().default(null),
  new_dropoff_stops_by_shape: z.number().int().nullable().default(null),
  ambiguous_dropoff_stops: z.number().int().nullable().default(null),
  card_distance_km: z.number().nullable().default(null),
  card_minutes: z.number().int().nullable().default(null),
  route_before_age_ms: z.number().int().nullable().default(null),
  drop_screen_before_age_ms: z.number().int().nullable().default(null),
  route_after_delay_ms: z.number().int().nullable().default(null),
  completions_between_before_and_accept: z.number().int().nullable().default(null),
  carried_target_shift_min: z.number().nullable().default(null),
  route_changed_for_other_reasons: z.boolean().nullable().default(null),
  notes: z.array(z.string()).default([]),
});

/** Chain economics facts: ranges and GPS coverage (Collector schema 7). */
export const chainFactsSchema = z.object({
  chain_started_at_ms: z.number().int(),
  trip_indexes: z.array(z.number().int()),
  offers: z.number().int(),
  revenue_agorot: z.number().int(),
  orders_low: z.number().int().nullable().default(null),
  orders_high: z.number().int().nullable().default(null),
  start_earliest_ms: z.number().int().nullable().default(null),
  start_latest_ms: z.number().int(),
  end_earliest_ms: z.number().int().nullable().default(null),
  end_latest_ms: z.number().int().nullable().default(null),
  end_evidence: z.string(),
  gps_samples: z.number().int().default(0),
  gps_max_gap_ms: z.number().int().nullable().default(null),
  gps_coverage: z.number().nullable().default(null),
  gps_path_km: z.number().nullable().default(null),
  gps_speed_km: z.number().nullable().default(null),
  gps_distance_reliable: z.boolean().default(false),
  completion_slides_ms: z.array(z.number().int()).default([]),
  gps_stationary_ms: z.number().int().nullable().default(null),
  gps_moving_ms: z.number().int().nullable().default(null),
  first_pickup_done_ms: z.number().int().nullable().default(null),
  pickup_phase_stationary_ms: z.number().int().nullable().default(null),
  pickup_phase_moving_ms: z.number().int().nullable().default(null),
  delivery_phase_stationary_ms: z.number().int().nullable().default(null),
  delivery_phase_moving_ms: z.number().int().nullable().default(null),
  route_changes: z.array(z.unknown()).default([]),
  marker_minutes_shown: z.array(z.unknown()).default([]),
  missing: z.array(z.string()).default([]),
});
export type ChainFactsRaw = z.infer<typeof chainFactsSchema>;

/** ETA validation (Collector schema 9): Wolt's route targets vs OBSERVED completions. */
export const etaValidationSchema = z.object({
  checks: z.array(
    z.object({
      completed_at_ms: z.number().int(),
      identity: z.string(),
      order_times_ms: z.array(z.number().int()).default([]),
      target_at_ms: z.number().int().nullable().default(null),
      route_first_seen_at_ms: z.number().int().nullable().default(null),
      route_target_first_ms: z.number().int().nullable().default(null),
      route_target_last_ms: z.number().int().nullable().default(null),
      error_vs_drop_screen_min: z.number().nullable().default(null),
      error_vs_first_route_target_min: z.number().nullable().default(null),
      error_vs_last_route_target_min: z.number().nullable().default(null),
      lead_min: z.number().nullable().default(null),
      notes: z.array(z.string()).default([]),
    }),
  ),
  semantics: z.object({
    target_pairs_compared: z.number().int(),
    target_pairs_stable: z.number().int(),
    stale_frames: z.number().int(),
    marker_checks: z.number().int(),
    marker_matches: z.number().int(),
    conclusions: z.array(z.string()),
  }),
});
export type EtaValidationRaw = z.infer<typeof etaValidationSchema>;

/** Shift facts (Collector schema 8): time split by evidence, revenue, GPS. */
export const shiftFactsSchema = z.object({
  shift_id: z.string(),
  ended_at_ms: z.number().int(),
  chain_ms_low: z.number().int(),
  chain_ms_high: z.number().int(),
  chains: z.number().int(),
  chains_with_unknown_end: z.number().int(),
  verified_idle_ms: z.number().int(),
  online_unverified_ms: z.number().int(),
  offline_ms: z.number().int(),
  unknown_ms: z.number().int(),
  revenue_agorot: z.number().int(),
  between_chains_ms: z.number().int().nullable().default(null),
  between_chains_km: z.number().nullable().default(null),
  between_chains_moving_ms: z.number().int().nullable().default(null),
  shift_stationary_ms: z.number().int().nullable().default(null),
  shift_moving_ms: z.number().int().nullable().default(null),
  completion_slides: z.number().int(),
  gps_path_km: z.number().nullable().default(null),
  gps_speed_km: z.number().nullable().default(null),
  gps_distance_reliable: z.boolean(),
  missing: z.array(z.string()).default([]),
});

/** One accepted offer inside a delivery chain (Collector schema 6, chains-1). */
export const acceptedOfferSchema = z.object({
  trip_index: z.number().int(),
  accepted_at_ms: z.number().int(),
  chain_started_at_ms: z.number().int(),
  accept_context: z.string(),
  carried_orders: z.number().int().nullable().default(null),
  carried_link: z.string().default("NONE"),
  new_orders_low: z.number().int().nullable().default(null),
  new_orders_high: z.number().int().nullable().default(null),
  trip_boundary_valid: z.boolean(),
  notes: z.array(z.string()).default([]),
});

/** Orders per trip from Wolt's route timeline (Collector schema 5). */
export const tripOrdersSchema = z.object({
  trip_index: z.number().int(),
  timeline_snapshots: z.number().int().default(0),
  dropoffs_min: z.number().int().nullable().default(null),
  dropoffs_max: z.number().int().nullable().default(null),
  timeline_changed: z.boolean().default(false),
  card_order_count: z.number().int().nullable().default(null),
  status: z.string(),
  orders_low: z.number().int().nullable().default(null),
  orders_high: z.number().int().nullable().default(null),
});

export const collectorExportSchema = z.object({
  export_format: z.literal(COLLECTOR_EXPORT_FORMAT),
  exported_at_utc: z.string(),
  app_version: z.string(),
  selected_package: z.string().nullable(),
  screen_events: z.array(screenEventSchema),
  location_grid: locationGridSchema.nullable().optional(),
  shift_sessions: z.array(shiftSessionSchema).default([]),
  location_samples: z.array(locationSampleSchema).default([]),
  delivery_events: z.array(deliveryEventSchema).default([]),
  delivery_trips: z.array(deliveryTripSchema).default([]),
  trip_orders: z.array(tripOrdersSchema).default([]),
  accepted_offers: z.array(acceptedOfferSchema).default([]),
  offer_contexts: z.array(offerContextSchema).default([]),
  chain_facts: z.array(chainFactsSchema).default([]),
  shift_facts: z.array(shiftFactsSchema).default([]),
  eta_validation: etaValidationSchema.nullable().default(null),
  track_points: z
    .array(
      z.object({
        shift_id: z.string(),
        t_ms: z.number().int(),
        lat: z.number(),
        lon: z.number(),
        accuracy_m: z.number().nullable().default(null),
        speed_mps: z.number().nullable().default(null),
      }),
    )
    .default([]),
  pickup_places: z
    .array(
      z.object({
        key: z.string(),
        name: z.string(),
        address: z.string(),
        first_seen_ms: z.number().int(),
        geo_status: z.string(),
        cell_id: z.string().nullable().default(null),
        geo_note: z.string().nullable().default(null),
      }),
    )
    .default([]),
  shadow_predictions: z
    .array(
      z
        .object({
          id: z.string(),
          offer_at_utc: z.string(),
          created_at_utc: z.string(),
          rule_version: z.string(),
          amount_agorot: z.number().int(),
          shadow_decision_experimental: z.string(),
        })
        .passthrough(),
    )
    .default([]),
});
export type CollectorExport = z.infer<typeof collectorExportSchema>;
