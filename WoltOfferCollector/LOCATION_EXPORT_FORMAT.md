# Location / shift fields in the Collector export (Hotspot MVP)

Export format stays `wolt-offer-collector/diagnostics-v1`. These top-level fields are **added** (older exports
simply lack them — importers must treat them as empty).

## `location_grid`
```json
{ "scheme": "g500", "cell_size_m": 500, "lat_step_deg": 0.0044915559, "note": "..." }
```
Cell of a point: `y = floor(lat / lat_step_deg)`; row centre latitude `lat_c = (y + 0.5) * lat_step_deg`;
`lon_step_deg = lat_step_deg / cos(lat_c)`; `x = floor(lon / lon_step_deg)`; `cell_id = "g500:<y>:<x>"`.
Cell centre: `(lat_c, (x + 0.5) * lon_step_deg)`, rounded to 5 decimals. **Exact coordinates are never stored or exported.**

## `shift_sessions` — shifts the courier started in the Collector
```json
{
  "id": "uuid",
  "started_at_utc": "2026-10-29T16:00:00.000Z",
  "ended_at_utc": "2026-10-29T20:00:00.000Z",   // null while running
  "end_reason": "USER" | "MAX_DURATION" | "CONSENT_REVOKED" | "SERVICE_STOPPED" | null,
  "location_consent": true
}
```
Location is sampled only between `started_at_utc` and `ended_at_utc`.
If the app process died, `ended_at_utc` is the last moment the shift was known to be running and `end_reason` = `SERVICE_STOPPED`.

## `location_samples`
```json
{
  "id": "uuid",
  "shift_id": "uuid",
  "observed_at_utc": "…",          // when the sample was requested
  "trigger": "SHIFT_START" | "OFFER_PRESENTED" | "DELIVERY_SCREEN" | "AVAILABLE_SCREEN" | "WAIT_PERIODIC" | "SHIFT_END",
  "state_candidate": "OFFER_PRESENTED" | "AVAILABLE" | "DELIVERY_ACTIVE" | "OFFLINE" | "UNKNOWN",
  "status": "OK" | "UNKNOWN",
  "unknown_reason": null | "NO_PERMISSION" | "NO_FIX" | "STALE_FIX" | "LOW_ACCURACY" | "MOCK_LOCATION" | "TIMEOUT" | "ERROR",
  "cell_id": "g500:7058:7345" | null,     // null when status = UNKNOWN
  "cell_center_lat": 31.95213 | null,
  "cell_center_lon": 34.79567 | null,
  "accuracy_m": 18 | null,                // reported horizontal accuracy, rounded
  "fix_age_s": 3 | null,                  // age of the fix when used
  "provider": "fused" | null
}
```
Rules (Collector side):
- `status = OK` only if the fix is ≤ 120 s old, accuracy ≤ 150 m, and not a mock location; otherwise `UNKNOWN` with the reason.
- Triggers come from the live screen/notification state of Wolt (the same signal as `state_marker_events`):
  - `OFFER_PRESENTED`: an offer card or a new-offer notification.
  - `DELIVERY_SCREEN`: a screen the detector would treat as a delivery screen. This is a candidate only; a verified
    accept/finish comes from the post-hoc `delivery_events` (`DELIVERY_ACCEPTED` / `DELIVERY_COMPLETED`). The Optimizer links those events to the
    nearest sample in time.
  - `AVAILABLE_SCREEN`: the home screen while online (start of a waiting period).
  - `WAIT_PERIODIC`: every 5 min while no delivery screen was seen in the last 10 min; at most 48 per shift.
- Duplicates: the same trigger within 30 s is not sampled again.
- There is no periodic sampling during a delivery, and no route or track is stored.

## Phase 3 additions (Collector 0.4.0, `schema_version: 4`, `location_parser_version: "loc-2"`)
All fields above keep their meaning. Added (details: PHASE3_LOCATION.md):
- `location_samples[]`: `source` (EVENT | PERIODIC | SESSION), `capture_status` (OK or the unknown reason),
  `state_evidence`, `location_age_ms` (|sample moment − fix time|), `speed_mps`, `event_at_utc`, `event_key`, `battery_pct`.
- New triggers: `PERIODIC` (every 30/45/60 s through the whole session; **not** a waiting-time signal — the old
  `WAIT_PERIODIC` is no longer produced), `OFFLINE`, and detector events `DELIVERY_ACCEPTED`, `PICKUP_DONE`,
  `DROPOFF_DONE`, `DELIVERY_COMPLETED` (located at the event time; evidence copied from the detector).
- `location_sessions[]`, `offer_locations[]` (derived; `offer_id` = id of the offer's first `screen_events` row),
  `location_quality_summary` (counts, medians, monitoring gaps, device battery drop).
- Exact coordinates are never exported (debug mode keeps them on the device for ≤ 24 h).
