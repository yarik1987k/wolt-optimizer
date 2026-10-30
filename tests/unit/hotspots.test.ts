/**
 * Hotspot Intelligence. There is NO real location data yet: every shift / sample / trip below is a
 * small SYNTHETIC input built in this test file only. The real fixture export (no location fields)
 * is used to check that older exports still import.
 */
import { beforeEach, describe, expect, it } from "vitest";
import { prisma } from "@/lib/db";
import {
  analyzeHotspots,
  availabilityPieces,
  AVAILABILITY_CAP_MS,
  compareCells,
  linkToCell,
  presenceByCell,
  lowDataReasons,
  splitByLocalHour,
  UNKNOWN_CELL,
  type CellMetrics,
  type HotspotInput,
  type HotspotSample,
  type HotspotShift,
} from "@/lib/hotspots/analysis";
import { haversineKm, parseCellId } from "@/lib/hotspots/geo";
import { getHotspotView, parseHotspotParams } from "@/lib/hotspots/service";
import { poissonInterval, poissonRate, wilsonInterval } from "@/lib/hotspots/stats";
import { importCollectorExport } from "@/lib/offers/import";
import { offerExportText, resetDb } from "../helpers";

const MIN = 60_000;
// 2026-10-29 14:00 UTC = 16:00 in Asia/Jerusalem (UTC+2 after DST ended on 25.10.2026).
const T0 = Date.parse("2026-10-29T14:00:00.000Z");
const iso = (ms: number) => new Date(ms).toISOString();

// Synthetic cells (cell ids/centres follow the g500 scheme; not real places the courier visited).
const A = { cellId: "g500:7058:7345", lat: 31.70415, lon: 34.79567 };
const B = { cellId: "g500:7059:7345", lat: 31.70864, lon: 34.79567 };

let n = 0;
function sample(atMs: number, trigger: string, cell: typeof A | null, shiftId = "s1"): HotspotSample {
  return {
    id: `x${++n}`,
    shiftId,
    atMs,
    trigger,
    status: cell ? "OK" : "UNKNOWN",
    unknownReason: cell ? null : "NO_FIX",
    cellId: cell?.cellId ?? null,
    lat: cell?.lat ?? null,
    lon: cell?.lon ?? null,
  };
}
const shift = (startMs: number, endMs: number | null, endReason: string | null = "USER", id = "s1"): HotspotShift => ({
  id,
  startMs,
  endMs,
  endReason,
  locationConsent: true,
});
const empty: HotspotInput = { shifts: [], samples: [], offers: [], trips: [], events: [] };

describe("offer → cell linking", () => {
  it("prefers an OFFER_PRESENTED sample within ±90 s over a nearer sample of another trigger", () => {
    const s = [sample(T0 + 10_000, "AVAILABLE_SCREEN", A), sample(T0 + 80_000, "OFFER_PRESENTED", B)];
    expect(linkToCell(T0, s)).toMatchObject({ cellId: B.cellId, evidence: "OBSERVED" });
  });

  it("an OFFER_PRESENTED sample beyond 90 s falls back to the nearest OK sample within 5 min → ESTIMATED", () => {
    const s = [sample(T0 - 4 * MIN, "WAIT_PERIODIC", A), sample(T0 + 100_000, "OFFER_PRESENTED", B)];
    expect(linkToCell(T0, s)).toMatchObject({ cellId: B.cellId, evidence: "ESTIMATED" });
    expect(linkToCell(T0, [sample(T0 - 4 * MIN, "WAIT_PERIODIC", A)])).toMatchObject({ cellId: A.cellId, evidence: "ESTIMATED" });
  });

  it("nothing within 5 min, or only UNKNOWN samples → UNKNOWN cell (never invented)", () => {
    expect(linkToCell(T0, [sample(T0 + 5 * MIN + 1, "OFFER_PRESENTED", A)])).toEqual({ cellId: UNKNOWN_CELL, evidence: "UNKNOWN", sampleId: null });
    expect(linkToCell(T0, [sample(T0, "OFFER_PRESENTED", null)])).toMatchObject({ cellId: UNKNOWN_CELL, evidence: "UNKNOWN" });
  });

  it("offers outside every Collector shift are not attributed to any cell", () => {
    const input: HotspotInput = {
      ...empty,
      shifts: [shift(T0, T0 + 60 * MIN)],
      samples: [sample(T0, "SHIFT_START", A), sample(T0 + 70 * MIN, "OFFER_PRESENTED", A)],
      offers: [{ id: "o1", firstSeenMs: T0 + 70 * MIN, amountAgorot: 3000 }],
    };
    const r = analyzeHotspots(input);
    expect(r.offerLinks[0]).toMatchObject({ inShift: false, evidence: "UNKNOWN" });
    expect(r.cells.find((c) => c.cellId === A.cellId)!.offers).toBe(0);
    expect(r.quality.offersOutsideShifts).toBe(1);
  });
});

describe("time spent per area", () => {
  it("credits each sample's area until the next sample, caps silences, keeps no-fix time apart", () => {
    const shifts = [shift(T0, T0 + 30 * MIN)];
    const samples = [
      sample(T0, "SHIFT_START", A),
      sample(T0 + 1 * MIN, "PERIODIC", A),
      sample(T0 + 2 * MIN, "OFFER_PRESENTED", B),
      sample(T0 + 3 * MIN, "PERIODIC", null), // no fix
      sample(T0 + 4 * MIN, "PERIODIC", B), // then 26 min silence → capped at 6 min
    ];
    const p = Object.fromEntries(presenceByCell(shifts, samples).map((x) => [x.cellId, x.ms / MIN]));
    expect(p[A.cellId]).toBe(2);
    expect(p[B.cellId]).toBe(1 + AVAILABILITY_CAP_MS / MIN);
    expect(p[UNKNOWN_CELL]).toBe(1);
  });
});

describe("available (waiting) time", () => {
  it("credits time until the next sample, capped at 6 min per sample", () => {
    const sh = [shift(T0, T0 + 60 * MIN)];
    const s = [sample(T0, "SHIFT_START", A), sample(T0 + 2 * MIN, "OFFER_PRESENTED", A), sample(T0 + 10 * MIN, "AVAILABLE_SCREEN", B), sample(T0 + 30 * MIN, "SHIFT_END", B)];
    const p = availabilityPieces(sh, s, []);
    expect(p.map((x) => [x.cellId, (x.endMs - x.startMs) / MIN])).toEqual([
      [A.cellId, 2], // until the OFFER_PRESENTED sample
      [B.cellId, AVAILABILITY_CAP_MS / MIN], // 20 min gap capped at 6
    ]);
    expect(p.every((x) => x.evidence === "ESTIMATED")).toBe(true);
  });

  it("OFFER_PRESENTED / DELIVERY_SCREEN / SHIFT_END samples credit no waiting time", () => {
    const s = [sample(T0, "OFFER_PRESENTED", A), sample(T0 + MIN, "DELIVERY_SCREEN", A), sample(T0 + 2 * MIN, "SHIFT_END", A)];
    expect(availabilityPieces([shift(T0, T0 + 10 * MIN)], s, [])).toEqual([]);
  });

  it("excludes time inside a detected trip (accept → complete)", () => {
    const s = [sample(T0, "AVAILABLE_SCREEN", A), sample(T0 + 6 * MIN, "AVAILABLE_SCREEN", A)];
    const trips = [{ startMs: T0 + 2 * MIN, endMs: T0 + 7 * MIN, endEvidence: "ESTIMATED", amountsAgorot: [3000] }];
    const p = availabilityPieces([shift(T0, T0 + 60 * MIN)], s, trips);
    expect(p.map((x) => [(x.startMs - T0) / MIN, (x.endMs - T0) / MIN])).toEqual([
      [0, 2],
      [7, 12],
    ]);
  });

  it("a trip with an unknown end is excluded until the next waiting sample", () => {
    const s = [sample(T0, "AVAILABLE_SCREEN", A), sample(T0 + 3 * MIN, "DELIVERY_SCREEN", A), sample(T0 + 20 * MIN, "AVAILABLE_SCREEN", A)];
    const trips = [{ startMs: T0 + MIN, endMs: null, endEvidence: "UNKNOWN", amountsAgorot: [] }];
    const p = availabilityPieces([shift(T0, T0 + 60 * MIN)], s, trips);
    expect(p.map((x) => [(x.startMs - T0) / MIN, (x.endMs - T0) / MIN])).toEqual([
      [0, 1],
      [20, 26],
    ]);
  });

  it("stays inside the shift: samples outside it are ignored, the last one stops at the shift end", () => {
    const s = [sample(T0 - 10 * MIN, "AVAILABLE_SCREEN", A), sample(T0, "SHIFT_START", A), sample(T0 + 4 * MIN, "WAIT_PERIODIC", A)];
    const p = availabilityPieces([shift(T0, T0 + 5 * MIN, "SERVICE_STOPPED")], s, []);
    expect(p.map((x) => (x.endMs - x.startMs) / MIN)).toEqual([4, 1]);
    // A running shift (no end) ends at its last sample: the last sample gets no time.
    expect(availabilityPieces([shift(T0, null, null)], s, []).map((x) => (x.endMs - x.startMs) / MIN)).toEqual([4]);
  });

  it("waiting time without a fix counts for the UNKNOWN cell", () => {
    const p = availabilityPieces([shift(T0, T0 + 60 * MIN)], [sample(T0, "AVAILABLE_SCREEN", null), sample(T0 + 3 * MIN, "SHIFT_END", A)], []);
    expect(p).toMatchObject([{ cellId: UNKNOWN_CELL }]);
  });

  it("splits intervals at local hour boundaries (Asia/Jerusalem)", () => {
    expect(splitByLocalHour(T0 - 2 * MIN, T0 + 3 * MIN)).toEqual([
      { hour: 15, ms: 2 * MIN },
      { hour: 16, ms: 3 * MIN },
    ]);
  });
});

describe("rates and intervals", () => {
  it("Garwood interval matches the exact chi-square values", () => {
    const close = (i: { lower: number; upper: number }, lo: number, hi: number) => {
      expect(i.lower).toBeCloseTo(lo, 3);
      expect(i.upper).toBeCloseTo(hi, 3);
    };
    close(poissonInterval(0), 0, 3.6889);
    close(poissonInterval(1), 0.0253, 5.5716);
    close(poissonInterval(5), 1.6235, 11.6683);
    close(poissonInterval(10), 4.7954, 18.3904);
  });

  it("rate = count ÷ exposure hours, interval scaled the same way; no exposure → null", () => {
    const r = poissonRate(5, 2)!;
    expect(r.rate).toBe(2.5);
    expect(r.interval.lower).toBeCloseTo(1.6235 / 2, 3);
    expect(r.interval.upper).toBeCloseTo(11.6683 / 2, 3);
    expect(poissonRate(3, 0)).toBeNull();
  });

  it("Wilson interval for the accept share", () => {
    const w = wilsonInterval(5, 10)!;
    expect(w.lower).toBeCloseTo(0.2366, 3);
    expect(w.upper).toBeCloseTo(0.7634, 3);
    expect(wilsonInterval(0, 0)).toBeNull();
  });
});

describe("per-cell metrics, low-data flags and earnings", () => {
  // One synthetic shift: 50 min waiting in A (with 3 offers), then a trip that is completed.
  const build = (completed: boolean): HotspotInput => {
    const samples: HotspotSample[] = [];
    for (let m = 0; m < 50; m += 5) samples.push(sample(T0 + m * MIN, m === 0 ? "SHIFT_START" : "WAIT_PERIODIC", A));
    samples.push(sample(T0 + 50 * MIN, "OFFER_PRESENTED", A), sample(T0 + 52 * MIN, "DELIVERY_SCREEN", A));
    return {
      shifts: [shift(T0, T0 + 120 * MIN)],
      samples,
      offers: [
        { id: "o1", firstSeenMs: T0 + 10 * MIN + 30_000, amountAgorot: 2000 },
        { id: "o2", firstSeenMs: T0 + 20 * MIN, amountAgorot: 3000 },
        { id: "o3", firstSeenMs: T0 + 50 * MIN + 20_000, amountAgorot: 4000 },
      ],
      trips: [{ startMs: T0 + 51 * MIN, endMs: completed ? T0 + 81 * MIN : null, endEvidence: completed ? "ESTIMATED" : "UNKNOWN", amountsAgorot: [4000] }],
      events: [
        { type: "DELIVERY_ACCEPTED", evidence: "ESTIMATED", atMs: T0 + 51 * MIN },
        { type: "OFFER_DECLINED", evidence: "ESTIMATED", atMs: T0 + 11 * MIN },
        { type: "OFFER_ENDED_UNKNOWN", evidence: "UNKNOWN", atMs: T0 + 21 * MIN },
      ],
    };
  };

  it("counts, mean/median payment, rate with interval, low-data flag", () => {
    const r = analyzeHotspots(build(false));
    const a = r.cells.find((c) => c.cellId === A.cellId)!;
    expect(a.offers).toBe(3);
    expect(a.offersObserved).toBe(1); // o3 has an OFFER_PRESENTED sample 20 s away
    expect(a.offersEstimated).toBe(2);
    expect(a.meanAmountAgorot).toBe(3000);
    expect(a.medianAmountAgorot).toBe(3000);
    expect(a.availableHours).toBeCloseTo(50 / 60, 6);
    expect(a.offersPerAvailableHour).toBeCloseTo(3 / (50 / 60), 6);
    expect(a.offerRateInterval!.lower).toBeCloseTo(poissonInterval(3).lower / (50 / 60), 6);
    expect(a.lowData).toBe(true);
    expect(a.lowDataReasons).toHaveLength(2);
    expect(r.acceptShare).toMatchObject({ accepted: 1, decided: 3 });
    expect(r.distinctShifts).toBe(1);
  });

  it("no completed delivery → no earnings figure at all", () => {
    const r = analyzeHotspots(build(false));
    expect(r.completedDeliveries).toBe(0);
    expect(r.cells.every((c) => c.earningsPerHourAgorot === null)).toBe(true);
  });

  it("completed trip → earnings per (available + trip) hour in the trip's start cell", () => {
    const r = analyzeHotspots(build(true));
    const a = r.cells.find((c) => c.cellId === A.cellId)!;
    expect(r.completedDeliveries).toBe(1);
    expect(a.completedTrips).toBe(1);
    expect(a.earningsPerHourAgorot).toBeCloseTo(4000 / (80 / 60), 6);
  });

  it("hour filter keeps only that local hour", () => {
    const input = build(false);
    input.offers.push({ id: "o4", firstSeenMs: T0 + 65 * MIN, amountAgorot: 5000 }); // 17:05 local, no sample within 5 min
    const r16 = analyzeHotspots(input, { hour: 16 });
    const r17 = analyzeHotspots(input, { hour: 17 });
    expect(r16.cells.find((c) => c.cellId === A.cellId)!.offers).toBe(3);
    expect(r17.cells.find((c) => c.cellId === A.cellId)!.offers).toBe(0);
    expect(r17.unknownCell.offers).toBe(1);
    expect(r16.cellHours.map((c) => c.hour)).toEqual([16]);
  });

  it("thresholds: < 5 offers or < 1 available hour", () => {
    expect(lowDataReasons(5, 1)).toEqual([]);
    expect(lowDataReasons(4, 1)).toHaveLength(1);
    expect(lowDataReasons(5, 0.99)).toHaveLength(1);
  });

  it("data quality: UNKNOWN samples by reason and SERVICE_STOPPED shifts are reported", () => {
    const input: HotspotInput = {
      ...empty,
      shifts: [shift(T0, T0 + 30 * MIN, "SERVICE_STOPPED"), shift(T0 + 60 * MIN, null, null, "s2")],
      samples: [
        sample(T0, "SHIFT_START", null),
        { ...sample(T0 + MIN, "WAIT_PERIODIC", null), unknownReason: "LOW_ACCURACY" },
        sample(T0 + 2 * MIN, "WAIT_PERIODIC", null),
        sample(T0 + 3 * MIN, "WAIT_PERIODIC", A, "missing-shift"),
      ],
    };
    const q = analyzeHotspots(input).quality;
    expect(q.unknownByReason).toEqual([
      { reason: "NO_FIX", n: 2 },
      { reason: "LOW_ACCURACY", n: 1 },
    ]);
    expect(q.serviceStoppedShifts).toHaveLength(1);
    expect(q.runningShifts).toBe(1);
    expect(q.samplesOutsideShift).toBe(1);
  });
});

describe("comparison (descriptive)", () => {
  const cell = (c: typeof A, over: Partial<CellMetrics> = {}): CellMetrics => ({
    cellId: c.cellId, ...parseCellId(c.cellId)!, lat: c.lat, lon: c.lon, offers: 6, offersObserved: 6, offersEstimated: 0,
    meanAmountAgorot: 3000, medianAmountAgorot: 3000, availableHours: 2, offersPerAvailableHour: 3, offerRateInterval: { lower: 1, upper: 6 },
    shiftCount: 2, lowData: false, lowDataReasons: [], completedTrips: 0, completedAmountAgorot: 0, earningsPerHourAgorot: null, ...over,
  });

  it("distance between centres, travel time and cost from the assumptions, rate × payment × accept share", () => {
    const rows = compareCells(cell(A), [cell(B), cell(A)], { avgSpeedKmh: 30, costPerKmAgorot: 50, acceptShare: 0.5 });
    expect(rows.map((r) => r.cellId)).toEqual([A.cellId, B.cellId]); // by distance, current first
    const b = rows[1];
    const km = haversineKm(A, B);
    expect(km).toBeCloseTo(0.4993, 3); // one g500 row ≈ 500 m
    expect(b.distanceKm).toBeCloseTo(km, 9);
    expect(b.travelMinutes).toBeCloseTo((km / 30) * 60, 9);
    expect(b.travelCostAgorot).toBeCloseTo(km * 50, 9);
    expect(b.expectedPerHourAgorot).toBe(3 * 3000 * 0.5);
    expect(b.expectedPerHourRangeAgorot).toEqual({ lower: 1500, upper: 9000 });
    expect(rows[0]).toMatchObject({ isCurrent: true, distanceKm: 0 });
  });

  it("missing assumptions stay missing (no defaults invented)", () => {
    const [r] = compareCells(cell(A), [cell(B)], { avgSpeedKmh: null, costPerKmAgorot: null, acceptShare: null });
    expect(r.travelMinutes).toBeNull();
    expect(r.travelCostAgorot).toBeNull();
    expect(r.expectedPerHourAgorot).toBeNull();
    expect(r.expectedPerHourRangeAgorot).toBeNull();
  });

  it("is not ordered by value", () => {
    const far = { cellId: "g500:7070:7345", lat: 31.76, lon: 34.79567 };
    const rows = compareCells(cell(A), [cell(far, { offersPerAvailableHour: 50 }), cell(B, { offersPerAvailableHour: 0.1 })], { avgSpeedKmh: 30, costPerKmAgorot: 50, acceptShare: 1 });
    expect(rows.map((r) => r.cellId)).toEqual([B.cellId, far.cellId]);
  });

  it("URL params are validated", () => {
    expect(parseHotspotParams({ hour: "7", metric: "rate", current: A.cellId })).toEqual({ hour: 7, metric: "rate", current: A.cellId });
    expect(parseHotspotParams({ hour: "24", metric: "restaurants", current: "x" })).toEqual({ hour: null, metric: "offers", current: null });
  });
});

describe("import of location data (DB)", () => {
  beforeEach(resetDb);

  const withLocation = () => {
    const exp = JSON.parse(offerExportText());
    exp.location_grid = { scheme: "g500", cell_size_m: 500, lat_step_deg: 0.0044915559, note: "synthetic test" };
    exp.shift_sessions = [{ id: "test-shift-1", started_at_utc: iso(T0), ended_at_utc: null, end_reason: null, location_consent: true }];
    exp.location_samples = [
      { id: "test-sample-1", shift_id: "test-shift-1", observed_at_utc: iso(T0), trigger: "SHIFT_START", state_candidate: "AVAILABLE", status: "OK", unknown_reason: null, cell_id: A.cellId, cell_center_lat: A.lat, cell_center_lon: A.lon, accuracy_m: 18, fix_age_s: 3, provider: "fused" },
      { id: "test-sample-2", shift_id: "test-shift-1", observed_at_utc: iso(T0 + 5 * MIN), trigger: "WAIT_PERIODIC", state_candidate: "AVAILABLE", status: "UNKNOWN", unknown_reason: "NO_FIX", cell_id: null, cell_center_lat: null, cell_center_lon: null, accuracy_m: null, fix_age_s: null, provider: null },
    ];
    exp.delivery_events = [{ type: "OFFER_ENDED_UNKNOWN", evidence: "UNKNOWN", action_at_ms: T0 + 2 * MIN, action: "SCREEN offer_card→other_screen", confirmed_at_ms: null, confirmation: "x", trip_index: null, rule_validated: false, path: "SCREEN_SEQUENCE" }];
    exp.delivery_trips = [];
    return exp;
  };

  it("an older export without location fields imports as before, with zero location rows", async () => {
    const r = await importCollectorExport(offerExportText(), "old.json");
    expect(r).toMatchObject({ offersTotal: 2, shiftSessionCount: 0, newShiftCount: 0, locationSampleCount: 0, newLocationSampleCount: 0, deliveryEventCount: 0, deliveryTripCount: 0 });
    expect(await prisma.locationSample.count()).toBe(0);
    const v = await getHotspotView(parseHotspotParams({}));
    expect(v.hasLocationData).toBe(false);
    expect(v.analysis.completedDeliveries).toBe(0);
  });

  it("is idempotent: re-import adds nothing; a later export closes the running shift", async () => {
    const exp = withLocation();
    const first = await importCollectorExport(JSON.stringify(exp), "a.json");
    expect(first).toMatchObject({ shiftSessionCount: 1, newShiftCount: 1, locationSampleCount: 2, newLocationSampleCount: 2, deliveryEventCount: 1 });
    const again = await importCollectorExport(JSON.stringify(exp), "a.json");
    expect(again).toMatchObject({ newShiftCount: 0, newLocationSampleCount: 0, newObservationCount: 0 });
    expect(await prisma.collectorShift.count()).toBe(1);
    expect(await prisma.locationSample.count()).toBe(2);
    expect(await prisma.collectorDeliveryEvent.count()).toBe(1);

    exp.shift_sessions[0].ended_at_utc = iso(T0 + 30 * MIN);
    exp.shift_sessions[0].end_reason = "SERVICE_STOPPED";
    await importCollectorExport(JSON.stringify(exp), "b.json");
    const s = await prisma.collectorShift.findUniqueOrThrow({ where: { id: "test-shift-1" } });
    expect(s.endReason).toBe("SERVICE_STOPPED");

    const v = await getHotspotView(parseHotspotParams({}));
    expect(v.hasLocationData).toBe(true);
    expect(v.analysis.cells.map((c) => c.cellId)).toEqual([A.cellId]);
    expect(v.analysis.quality.unknownByReason).toEqual([{ reason: "NO_FIX", n: 1 }]);
    expect(v.analysis.quality.serviceStoppedShifts).toHaveLength(1);
  });

  it("stores only cell-level location (unknown extra fields such as exact coordinates are dropped)", async () => {
    const exp = withLocation();
    exp.location_samples[0].lat = 31.701234;
    exp.location_samples[0].lon = 34.791234;
    await importCollectorExport(JSON.stringify(exp), "a.json");
    const row = await prisma.locationSample.findUniqueOrThrow({ where: { id: "test-sample-1" } });
    expect(JSON.stringify(row)).not.toContain("31.701234");
    expect(row).toMatchObject({ cellId: A.cellId, cellCenterLat: A.lat, cellCenterLon: A.lon });
  });
});
