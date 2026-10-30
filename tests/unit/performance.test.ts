import { describe, expect, it } from "vitest";
import { chainEconomics, type ChainInput } from "@/lib/chains/economics";
import {
  analyzeDimension,
  DIMENSIONS,
  distanceBand,
  evaluateStrategy,
  hourBand,
  MIN_SEGMENT_CHAINS,
  priceBand,
  segmentStats,
  type ChainUnit,
  type DecisionRecord,
  type OfferAttr,
} from "@/lib/history/performance";
import { localToUtcMs } from "@/lib/time";

const MIN = 60_000;
const t0 = localToUtcMs("2026-10-29", "20:10");

function chain(key: string, revenue: number, minutes: number, km: number, offer: Partial<OfferAttr> = {}, extra: Partial<OfferAttr>[] = []): ChainUnit {
  const input: ChainInput = {
    chainKey: key, offers: 1 + extra.length, revenueAgorot: revenue, ordersLow: 1, ordersHigh: 1,
    startEarliestMs: t0, startLatestMs: t0, endEarliestMs: t0 + minutes * MIN, endLatestMs: t0 + minutes * MIN, endEvidence: "OBSERVED_SLIDE",
    gpsSamples: 50, gpsMaxGapMs: 60_000, gpsCoverage: 1, gpsPathKm: km, gpsSpeedKm: km, gpsDistanceReliable: true, missing: [],
    addedOffers: extra.length, completionSlides: 1,
  };
  const e = chainEconomics(input, 110);
  const base: OfferAttr = {
    tripKey: key, offerId: key, acceptedAtMs: t0, amountAgorot: revenue, distanceKm: km, courierCell: "g500:1:1",
    pickupCells: ["g500:1:2"], pickupVerified: true, newOrdersLow: 1, newOrdersHigh: 1, context: "IDLE",
  };
  return {
    chainKey: key,
    economics: e,
    offers: [{ ...base, ...offer }, ...extra.map((x, i) => ({ ...base, tripKey: `${key}.${i}`, context: "ADDED_TO_ACTIVE_ROUTE", ...x }))],
    standalone: extra.length === 0,
    complete: e.netPerHourAgorot !== null,
  };
}

describe("historical offer performance", () => {
  it("bands", () => {
    expect([priceBand(1725), priceBand(2595), priceBand(4216), priceBand(7219), priceBand(null)]).toEqual(["< ₪20", "₪20–30", "₪30–45", "≥ ₪45", "unknown"]);
    expect([distanceBand(1.6), distanceBand(4.5), distanceBand(9.9), distanceBand(16.1)]).toEqual(["< 3 km", "3–6 km", "6–10 km", "≥ 10 km"]);
    expect(hourBand(t0)).toBe("18–21");
  });

  it("never ranks a segment with too few complete chains", () => {
    const few = segmentStats("x", [chain("1", 3086, 25, 3.5)]);
    expect(few.status).toBe("INSUFFICIENT_DATA");
    expect(few.reasons[0]).toContain(`needs ≥ ${MIN_SEGMENT_CHAINS}`);
    expect(few.netPerActiveHourAgorot).not.toBeNull(); // shown, but not comparable
    const price = analyzeDimension(DIMENSIONS.find((d) => d.key === "price")!, [chain("1", 3086, 25, 3.5), chain("2", 7219, 25, 16)]);
    expect(price.establishedDifferences).toEqual([]);
    expect(price.notEstablished[0]).toContain("no ranking");
  });

  it("establishes a difference only between comparable segments whose ranges do not overlap", () => {
    const high = Array.from({ length: 10 }, (_, i) => chain(`h${i}`, 5000, 30, 5));
    const low = Array.from({ length: 10 }, (_, i) => chain(`l${i}`, 1800, 30, 5));
    const r = analyzeDimension(DIMENSIONS.find((d) => d.key === "price")!, [...high, ...low]);
    expect(r.segments.every((s) => s.status === "COMPARABLE")).toBe(true);
    expect(r.establishedDifferences).toHaveLength(1);
    expect(r.establishedDifferences[0]).toContain('"≥ ₪45" earned more');
    expect(r.establishedDifferences[0]).toContain("not cause");
  });

  it("measures overlapping offers only at chain level", () => {
    const added = chain("c4", 11539, 82, 20, { amountAgorot: 7219 }, [{ amountAgorot: 2595 }, { amountAgorot: 1725 }]);
    // Offer-characteristic dimensions ignore it (no per-offer duration); the context dimension keeps it whole.
    const price = analyzeDimension(DIMENSIONS.find((d) => d.key === "price")!, [added]);
    expect(price.segments).toHaveLength(0);
    const ctx = analyzeDimension(DIMENSIONS.find((d) => d.key === "context")!, [added, chain("1", 3086, 25, 3.5)]);
    expect(ctx.segments.map((s) => [s.bucket, s.chains, s.revenueAgorot])).toEqual([
      ["standalone (IDLE)", 1, 3086],
      ["with ADDED_TO_ACTIVE_ROUTE offers", 1, 11539],
    ]);
  });

  it("evaluates a strategy only where it matches history; counterfactual decisions stay unknown", () => {
    const rec = (amount: number, decision: DecisionRecord["decision"]): DecisionRecord => ({
      offerId: `${amount}`, seenAtMs: t0, amountAgorot: amount, distanceKm: 3, decision, standaloneNetAgorot: null,
    });
    const history = [rec(3086, "ACCEPTED"), rec(1725, "ACCEPTED"), rec(6681, "DECLINED"), rec(1500, "DECLINED"), rec(2000, "UNKNOWN")];
    const e = evaluateStrategy({ name: "min-₪25", description: "", accepts: (o) => o.amountAgorot >= 2500 }, history);
    expect([e.agreeAccept, e.agreeDecline, e.wouldDeclineAccepted, e.wouldAcceptDeclined, e.unknownDecision]).toEqual([1, 1, 1, 1, 1]);
    expect(e.verdict).toBe("NOT_EVALUABLE");
    expect(e.observedAgreedRevenueAgorot).toBe(3086);
    expect(e.limitations.join(" ")).toContain("only a planned experiment");
  });
});
