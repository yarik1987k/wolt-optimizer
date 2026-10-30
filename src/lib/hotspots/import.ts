import type { Prisma } from "@prisma/client";
import type { CollectorExport, DeliveryEventRaw } from "@/lib/offers/collector-format";

export interface LocationImportCounts {
  shiftSessionCount: number;
  newShiftCount: number;
  locationSampleCount: number;
  newLocationSampleCount: number;
  deliveryEventCount: number;
  deliveryTripCount: number;
}

export const deliveryEventKey = (e: DeliveryEventRaw) => `${e.type}|${e.action_at_ms}|${e.action}`;

const d = (s: string | null) => (s === null ? null : new Date(s));
const roundOrNull = (n: number | null) => (n === null ? null : Math.round(n));

/**
 * Stores shift sessions, location samples, delivery events and trips of one export (all optional
 * fields; older exports have none). Keyed by the Collector's ids:
 * - shifts: upserted (a later export may close a shift that was still running);
 * - samples: immutable, an already imported id is skipped;
 * - delivery events / trips: derived post-hoc on the phone, so the latest export's version wins.
 */
export async function importLocationData(tx: Prisma.TransactionClient, exp: CollectorExport): Promise<LocationImportCounts> {
  const shiftIds = exp.shift_sessions.map((s) => s.id);
  const knownShifts = new Set((await tx.collectorShift.findMany({ where: { id: { in: shiftIds } }, select: { id: true } })).map((s) => s.id));
  for (const s of exp.shift_sessions) {
    const data = {
      startedAtUtc: new Date(s.started_at_utc),
      endedAtUtc: d(s.ended_at_utc),
      endReason: s.end_reason,
      locationConsent: s.location_consent,
    };
    await tx.collectorShift.upsert({ where: { id: s.id }, create: { id: s.id, ...data }, update: data });
  }

  const sampleIds = exp.location_samples.map((s) => s.id);
  const knownSamples = new Set((await tx.locationSample.findMany({ where: { id: { in: sampleIds } }, select: { id: true } })).map((s) => s.id));
  const seen = new Set<string>();
  let newSamples = 0;
  for (const s of exp.location_samples) {
    if (knownSamples.has(s.id) && (s.state_evidence !== null || s.source !== null)) {
      // Backfill Phase 3 fields onto samples imported before those columns existed; never overwrite.
      await tx.locationSample.updateMany({ where: { id: s.id, stateEvidence: null }, data: { stateEvidence: s.state_evidence } });
      await tx.locationSample.updateMany({ where: { id: s.id, source: null }, data: { source: s.source } });
    }
    if (knownSamples.has(s.id) || seen.has(s.id)) continue;
    seen.add(s.id);
    const ok = s.status === "OK" && s.cell_id !== null;
    await tx.locationSample.create({
      data: {
        id: s.id,
        shiftId: s.shift_id,
        observedAtUtc: new Date(s.observed_at_utc),
        trigger: s.trigger,
        stateCandidate: s.state_candidate,
        status: ok ? "OK" : "UNKNOWN",
        unknownReason: ok ? null : (s.unknown_reason ?? (s.status === "OK" ? "NO_CELL" : null)),
        cellId: ok ? s.cell_id : null,
        cellCenterLat: ok ? s.cell_center_lat : null,
        cellCenterLon: ok ? s.cell_center_lon : null,
        accuracyM: roundOrNull(s.accuracy_m),
        fixAgeS: roundOrNull(s.fix_age_s),
        provider: s.provider,
        stateEvidence: s.state_evidence,
        source: s.source,
      },
    });
    newSamples++;
  }

  for (const e of exp.delivery_events) {
    const data = {
      type: e.type,
      evidence: e.evidence,
      actionAtUtc: new Date(e.action_at_ms),
      action: e.action,
      confirmedAtUtc: e.confirmed_at_ms === null ? null : new Date(e.confirmed_at_ms),
      confirmation: e.confirmation,
      tripIndex: e.trip_index,
      ruleValidated: e.rule_validated,
      path: e.path,
    };
    const eventKey = deliveryEventKey(e);
    await tx.collectorDeliveryEvent.upsert({ where: { eventKey }, create: { eventKey, ...data }, update: data });
  }

  for (const t of exp.delivery_trips) {
    const o = exp.trip_orders.find((x) => x.trip_index === t.index);
    const a = exp.accepted_offers.find((x) => x.trip_index === t.index);
    const c = exp.offer_contexts.find((x) => x.trip_index === t.index);
    const data = {
      offerSeenAtUtc: c?.offer_seen_at_ms == null ? null : new Date(c.offer_seen_at_ms),
      offerCell: c?.offer_cell ?? null,
      acceptedEarliestAtUtc: c?.accepted_earliest_ms == null ? null : new Date(c.accepted_earliest_ms),
      activeOrdersBefore: c?.active_orders_before ?? null,
      newOrdersConfidence: c?.new_orders_confidence ?? null,
      routeBeforeJson: c?.route_before ? JSON.stringify(c.route_before) : null,
      routeAfterJson: c?.route_after ? JSON.stringify(c.route_after) : null,
      newPickupStops: c?.new_pickup_stops ?? null,
      newDropoffStopsByShape: c?.new_dropoff_stops_by_shape ?? null,
      ambiguousDropoffStops: c?.ambiguous_dropoff_stops ?? null,
      cardDistanceKm: c?.card_distance_km ?? null,
      cardMinutes: c?.card_minutes ?? null,
      routeBeforeAgeMs: c?.route_before_age_ms ?? null,
      incrementalMinutesUpper: null,
      incrementalMinutesWoltEta: null,
      estimateNotesJson: c ? JSON.stringify(c.notes) : null,
      dropScreenBeforeAgeMs: c?.drop_screen_before_age_ms ?? null,
      routeAfterDelayMs: c?.route_after_delay_ms ?? null,
      completionsBetweenBeforeAccept: c?.completions_between_before_and_accept ?? null,
      carriedTargetShiftMin: c?.carried_target_shift_min ?? null,
      routeChangedOtherReasons: c?.route_changed_for_other_reasons ?? null,
      chainKey: a ? String(a.chain_started_at_ms) : null,
      acceptContext: a?.accept_context ?? null,
      carriedOrders: a?.carried_orders ?? null,
      carriedLink: a?.carried_link ?? null,
      newOrdersLow: a?.new_orders_low ?? null,
      newOrdersHigh: a?.new_orders_high ?? null,
      tripBoundaryValid: a ? a.trip_boundary_valid : null,
      chainNotesJson: a ? JSON.stringify(a.notes) : null,
      orderEvidence: o?.status ?? null,
      ordersLow: o?.orders_low ?? null,
      ordersHigh: o?.orders_high ?? null,
      cardOrderCount: o?.card_order_count ?? null,
      timelineChanged: o ? o.timeline_changed : null,
      startedAtUtc: new Date(t.started_at_ms),
      startEvidence: t.start_evidence,
      completedAtUtc: t.completed_at_ms === null ? null : new Date(t.completed_at_ms),
      endEvidence: t.end_evidence,
      orders: t.orders,
      pickups: t.pickups,
      dropoffs: t.dropoffs,
      offerAmountsJson: JSON.stringify(t.offer_amounts_agorot),
      expectedDropoffs: t.expected_dropoffs,
      endNote: t.end_note,
    };
    const tripKey = String(t.started_at_ms);
    await tx.collectorTrip.upsert({ where: { tripKey }, create: { tripKey, ...data }, update: data });
  }

  for (const f of exp.chain_facts) {
    const at = (ms: number | null) => (ms === null ? null : new Date(ms));
    const data = {
      startEarliestAtUtc: at(f.start_earliest_ms),
      startLatestAtUtc: new Date(f.start_latest_ms),
      endEarliestAtUtc: at(f.end_earliest_ms),
      endLatestAtUtc: at(f.end_latest_ms),
      endEvidence: f.end_evidence,
      tripIndexesJson: JSON.stringify(f.trip_indexes),
      offers: f.offers,
      revenueAgorot: f.revenue_agorot,
      ordersLow: f.orders_low,
      ordersHigh: f.orders_high,
      gpsSamples: f.gps_samples,
      gpsMaxGapMs: f.gps_max_gap_ms,
      gpsCoverage: f.gps_coverage,
      gpsPathKm: f.gps_path_km,
      gpsSpeedKm: f.gps_speed_km,
      gpsDistanceReliable: f.gps_distance_reliable,
      completionSlidesJson: JSON.stringify(f.completion_slides_ms),
      gpsStationaryMs: f.gps_stationary_ms,
      gpsMovingMs: f.gps_moving_ms,
      firstPickupDoneAtUtc: at(f.first_pickup_done_ms),
      pickupPhaseStationaryMs: f.pickup_phase_stationary_ms,
      pickupPhaseMovingMs: f.pickup_phase_moving_ms,
      deliveryPhaseStationaryMs: f.delivery_phase_stationary_ms,
      deliveryPhaseMovingMs: f.delivery_phase_moving_ms,
      routeChangesJson: JSON.stringify(f.route_changes),
      markerMinutesJson: JSON.stringify(f.marker_minutes_shown),
      missingJson: JSON.stringify(f.missing),
    };
    const chainKey = String(f.chain_started_at_ms);
    await tx.collectorChain.upsert({ where: { chainKey }, create: { chainKey, ...data }, update: data });
  }

  for (const s of exp.shift_facts) {
    if (!knownShifts.has(s.shift_id) && !shiftIds.includes(s.shift_id)) continue;
    await tx.collectorShift.update({
      where: { id: s.shift_id },
      data: {
        chainMsLow: s.chain_ms_low,
        chainMsHigh: s.chain_ms_high,
        factsChains: s.chains,
        chainsUnknownEnd: s.chains_with_unknown_end,
        verifiedIdleMs: s.verified_idle_ms,
        onlineUnverifiedMs: s.online_unverified_ms,
        offlineMs: s.offline_ms,
        unknownMs: s.unknown_ms,
        revenueAgorot: s.revenue_agorot,
        completionSlides: s.completion_slides,
        factsEndedAtUtc: new Date(s.ended_at_ms),
        gpsPathKm: s.gps_path_km,
        gpsSpeedKm: s.gps_speed_km,
        gpsDistanceReliable: s.gps_distance_reliable,
        factsMissingJson: JSON.stringify(s.missing),
        betweenChainsMs: s.between_chains_ms,
        betweenChainsKm: s.between_chains_km,
        betweenChainsMovingMs: s.between_chains_moving_ms,
        shiftStationaryMs: s.shift_stationary_ms,
        shiftMovingMs: s.shift_moving_ms,
      },
    });
  }

  for (const k of exp.eta_validation?.checks ?? []) {
    const at = (ms: number | null) => (ms === null ? null : new Date(ms));
    const data = {
      identity: k.identity,
      orderTimesJson: JSON.stringify(k.order_times_ms),
      targetAtUtc: at(k.target_at_ms),
      routeFirstSeenAtUtc: at(k.route_first_seen_at_ms),
      routeTargetFirstAtUtc: at(k.route_target_first_ms),
      routeTargetLastAtUtc: at(k.route_target_last_ms),
      errorVsDropScreenMin: k.error_vs_drop_screen_min,
      errorVsFirstRouteMin: k.error_vs_first_route_target_min,
      errorVsLastRouteMin: k.error_vs_last_route_target_min,
      leadMin: k.lead_min,
      notesJson: JSON.stringify(k.notes),
    };
    const completedAtUtc = new Date(k.completed_at_ms);
    await tx.collectorEtaCheck.upsert({ where: { completedAtUtc }, create: { completedAtUtc, ...data }, update: data });
  }

  // Precise track: insert-only, keyed by (shift, time) — a re-import adds nothing and changes nothing.
  for (const shiftId of new Set(exp.track_points.map((p) => p.shift_id))) {
    const known = new Set(
      (await tx.collectorTrackPoint.findMany({ where: { shiftId }, select: { atUtc: true } })).map((p) => p.atUtc.getTime()),
    );
    const fresh = exp.track_points.filter((p) => p.shift_id === shiftId && !known.has(p.t_ms));
    for (let i = 0; i < fresh.length; i += 500) {
      await tx.collectorTrackPoint.createMany({
        data: fresh.slice(i, i + 500).map((p) => ({ shiftId, atUtc: new Date(p.t_ms), lat: p.lat, lon: p.lon, accuracyM: p.accuracy_m, speedMps: p.speed_mps })),
      });
    }
  }

  for (const p of exp.pickup_places) {
    const data = { name: p.name, address: p.address, firstSeenAt: new Date(p.first_seen_ms), geoStatus: p.geo_status, cellId: p.cell_id, geoNote: p.geo_note };
    await tx.collectorPickupPlace.upsert({ where: { key: p.key }, create: { key: p.key, ...data }, update: data });
  }
  // Shadow predictions are immutable: insert unseen ids, never update a stored one.
  const knownPredictions = new Set(
    (await tx.shadowPrediction.findMany({ where: { id: { in: exp.shadow_predictions.map((p) => p.id) } }, select: { id: true } })).map((p) => p.id),
  );
  for (const p of exp.shadow_predictions) {
    if (knownPredictions.has(p.id)) continue;
    await tx.shadowPrediction.create({
      data: {
        id: p.id,
        offerAtUtc: new Date(p.offer_at_utc),
        createdAtUtc: new Date(p.created_at_utc),
        ruleVersion: p.rule_version,
        amountAgorot: p.amount_agorot,
        decision: p.shadow_decision_experimental,
        payloadJson: JSON.stringify(p),
      },
    });
  }

  return {
    shiftSessionCount: exp.shift_sessions.length,
    newShiftCount: new Set(shiftIds.filter((id) => !knownShifts.has(id))).size,
    locationSampleCount: exp.location_samples.length,
    newLocationSampleCount: newSamples,
    deliveryEventCount: exp.delivery_events.length,
    deliveryTripCount: exp.delivery_trips.length,
  };
}
