// Synthetic inputs only (test file) — no real or invented data reaches the app.
import { describe, expect, it } from "vitest";
import { UNKNOWN_CELL, type HotspotInput, type HotspotSample } from "@/lib/hotspots/analysis";
import { ALL_DAY, explore, inWindow, metricValue, statePieces, type TimeFilter } from "@/lib/hotspots/explore";
import { cellPolygon, shortCellCode } from "@/lib/hotspots/geo";
import { parseExploreParams } from "@/lib/hotspots/service";

const MIN = 60_000;
// 2026-10-29 18:00 Asia/Jerusalem (UTC+2) = 16:00Z, a Thursday.
const T0 = Date.parse("2026-10-29T16:00:00.000Z");
const A = "g500:7123:6559";
const B = "g500:7124:6559";

let n = 0;
function s(atMs: number, cell: string | null, state: string | null = null, evidence: string | null = null, trigger = "PERIODIC"): HotspotSample {
  return {
    id: `s${++n}`,
    shiftId: "sh",
    atMs,
    trigger,
    status: cell ? "OK" : "UNKNOWN",
    unknownReason: cell ? null : "NO_FIX",
    cellId: cell,
    lat: cell ? 31.99 : null,
    lon: cell ? 34.76 : null,
    stateCandidate: state,
    stateEvidence: evidence,
    accuracyM: cell ? 10 : null,
  };
}
const input = (samples: HotspotSample[], extra: Partial<HotspotInput> = {}): HotspotInput => ({
  shifts: [{ id: "sh", startMs: T0, endMs: T0 + 120 * MIN, endReason: "USER", locationConsent: true }],
  samples,
  offers: [],
  trips: [],
  events: [],
  ...extra,
});
const minutes = (ms: number) => Math.round(ms / MIN);

describe("time window", () => {
  it("filters by hour (with wrap), weekday and dates in Israel time", () => {
    const f = (p: Partial<TimeFilter>): TimeFilter => ({ ...ALL_DAY, ...p });
    expect(inWindow(T0, f({ fromHour: 18, toHour: 19 }))).toBe(true);
    expect(inWindow(T0, f({ fromHour: 19, toHour: 20 }))).toBe(false);
    expect(inWindow(T0, f({ fromHour: 22, toHour: 19 }))).toBe(true); // 22:00–19:00 wraps
    expect(inWindow(T0, f({ weekdays: [4] }))).toBe(true); // Thursday
    expect(inWindow(T0, f({ weekdays: [5, 6] }))).toBe(false);
    expect(inWindow(T0, f({ dateFrom: "2026-10-30" }))).toBe(false);
    expect(inWindow(T0, f({ dateTo: "2026-10-29" }))).toBe(true);
  });
});

describe("time states", () => {
  it("separates GPS time from availability, delivery, offline and unknown", () => {
    const samples = [
      s(T0, A, null, null, "SHIFT_START"), // 0–2: UNKNOWN state
      s(T0 + 2 * MIN, A, "AVAILABLE", "ESTIMATED", "AVAILABLE_SCREEN"), // 2–4: estimated available
      s(T0 + 4 * MIN, A, "AVAILABLE", "OBSERVED", "AVAILABLE_SCREEN"), // 4–6: verified
      s(T0 + 6 * MIN, null), // 6–8: no fix, still verified (state carries)
      s(T0 + 8 * MIN, B, "OFFLINE", "ESTIMATED", "OFFLINE"), // 8–10 offline
      s(T0 + 10 * MIN, B), // 10–16: capped at 6 min (silence until 30)
      s(T0 + 30 * MIN, B, null, null, "SHIFT_END"),
    ];
    const p = statePieces(input(samples, { shifts: [{ id: "sh", startMs: T0, endMs: T0 + 31 * MIN, endReason: "USER", locationConsent: true }] }));
    const sum = (pred: (x: (typeof p)[number]) => boolean) => minutes(p.filter(pred).reduce((n, x) => n + x.endMs - x.startMs, 0));
    expect(sum((x) => x.state === "UNKNOWN")).toBe(2); // before any state was seen
    expect(sum((x) => x.state === "AVAILABLE_ESTIMATED")).toBe(2);
    expect(sum((x) => x.state === "AVAILABLE_VERIFIED")).toBe(4);
    expect(sum((x) => x.state === "OFFLINE")).toBe(2 + 6 + 1); // carries until another state; incl. SHIFT_END minute
    expect(sum((x) => !x.measured)).toBe(2);
    expect(p.find((x) => !x.measured)!.cellId).toBe(UNKNOWN_CELL);
  });

  it("time inside a completed trip is DELIVERY", () => {
    const samples = [s(T0, A, "AVAILABLE", "OBSERVED", "AVAILABLE_SCREEN"), s(T0 + 5 * MIN, A), s(T0 + 10 * MIN, A)];
    const p = statePieces(input(samples, { trips: [{ startMs: T0 + 3 * MIN, endMs: T0 + 8 * MIN, endEvidence: "OBSERVED", amountsAgorot: [3000] }] }));
    const by = (st: string) => minutes(p.filter((x) => x.state === st).reduce((n, x) => n + x.endMs - x.startMs, 0));
    expect(by("DELIVERY")).toBe(5);
    expect(by("AVAILABLE_VERIFIED")).toBe(3 + 2 + 6);
  });
});

describe("area metrics", () => {
  const offersAt = (...mins: [number, number][]) => mins.map(([m, a], i) => ({ id: `o${i}`, firstSeenMs: T0 + m * MIN, amountAgorot: a }));

  it("never divides offers by GPS time when availability is not verified", () => {
    const samples = [s(T0, A, "AVAILABLE", "ESTIMATED", "AVAILABLE_SCREEN"), s(T0 + 1 * MIN, A, "OFFER_PRESENTED", "OBSERVED", "OFFER_PRESENTED"), s(T0 + 11 * MIN, A)];
    // The offer sample is OBSERVED, but the state after it is "AVAILABLE" only through the offer → counts as verified.
    const r = explore(input(samples, { offers: offersAt([1, 3000], [5, 4000]) }), ALL_DAY, 3500);
    const a = r.areas[0];
    expect(a.offers).toBe(2);
    expect(a.measuredMs).toBeGreaterThan(0);
    // 16 min verified < 0.5 h → no rate, even though 11+ minutes of GPS exist.
    expect(a.ratePerVerifiedHour).toBeNull();
    expect(a.confidence).toBe("NONE");
    expect(a.limits.length).toBeGreaterThan(0);
    expect(a.shareAbove).toBe(0.5);
    expect(a.incomePerShiftHourAgorot).toBeNull(); // no completed deliveries
  });

  it("estimated availability alone gives no rate and no wait time", () => {
    const samples = Array.from({ length: 13 }, (_, i) => s(T0 + i * 5 * MIN, A, "AVAILABLE", "ESTIMATED", "AVAILABLE_SCREEN"));
    const r = explore(input(samples, { offers: offersAt([10, 3000]) }), ALL_DAY, 3500);
    expect(r.areas[0].timeMs.AVAILABLE_VERIFIED).toBe(0);
    expect(r.areas[0].ratePerVerifiedHour).toBeNull();
    expect(r.areas[0].waitPerOfferMs).toBeNull();
  });

  it("computes a rate from verified time and income only from completed trips", () => {
    const samples = Array.from({ length: 13 }, (_, i) => s(T0 + i * 5 * MIN, A, "AVAILABLE", "OBSERVED", "AVAILABLE_SCREEN"));
    const r = explore(
      input(samples, {
        offers: offersAt([10, 3000], [20, 4000], [30, 5000]),
        trips: [{ startMs: T0 + 31 * MIN, endMs: T0 + 50 * MIN, endEvidence: "OBSERVED", amountsAgorot: [5000] }],
      }),
      ALL_DAY,
      3500,
    );
    const a = r.areas[0];
    expect(a.ratePerVerifiedHour).not.toBeNull();
    expect(a.completedTrips).toBe(1);
    expect(a.incomePerShiftHourAgorot).toBeGreaterThan(0);
    expect(metricValue(a, "income")).toBe(a.incomePerShiftHourAgorot);
  });

  it("changing the hour window changes the areas and metrics", () => {
    const samples = [s(T0, A, "AVAILABLE", "OBSERVED", "AVAILABLE_SCREEN"), s(T0 + 5 * MIN, A), s(T0 + 65 * MIN, B), s(T0 + 70 * MIN, B)];
    const offers = offersAt([1, 3000], [66, 4000]);
    const all = explore(input(samples, { offers }), ALL_DAY, 3500);
    const at18 = explore(input(samples, { offers }), { ...ALL_DAY, fromHour: 18, toHour: 19 }, 3500);
    const at19 = explore(input(samples, { offers }), { ...ALL_DAY, fromHour: 19, toHour: 20 }, 3500);
    expect(all.areas.map((x) => x.cellId)).toEqual([A, B]);
    expect(at18.areas.map((x) => x.cellId)).toEqual([A]);
    expect(at19.areas.map((x) => x.cellId)).toEqual([B]);
    expect(at19.areas[0].meanAgorot).toBe(4000);
  });

  it("areas are ordered by cell id, never by value", () => {
    const samples = [s(T0, B), s(T0 + 1 * MIN, A, "OFFER_PRESENTED", "OBSERVED", "OFFER_PRESENTED"), s(T0 + 2 * MIN, A)];
    const r = explore(input(samples, { offers: offersAt([1, 9000]) }), ALL_DAY, 3500);
    expect(r.areas.map((a) => a.cellId)).toEqual([A, B].sort());
  });
});

describe("geometry and params", () => {
  it("draws a ~500 m cell polygon and a stable short code", () => {
    const p = cellPolygon(A)!;
    expect(p).toHaveLength(5);
    const heightM = (p[2][1] - p[0][1]) * 111_320;
    expect(Math.round(heightM)).toBe(500);
    expect(shortCellCode(A)).toMatch(/^[A-Z2-9]{3}$/);
    expect(shortCellCode(A)).toBe(shortCellCode(A));
  });

  it("parses the time and threshold controls", () => {
    expect(parseExploreParams({}).filter).toEqual(ALL_DAY);
    const p = parseExploreParams({ from: "18", to: "22", days: "4,5", dateFrom: "2026-10-01", above: "40" });
    expect(p.filter).toEqual({ fromHour: 18, toHour: 22, weekdays: [4, 5], dateFrom: "2026-10-01", dateTo: null });
    expect(p.thresholdAgorot).toBe(4000);
    expect(parseExploreParams({ from: "x", days: "9", above: "-1" }).filter.weekdays).toEqual([]);
  });
});
