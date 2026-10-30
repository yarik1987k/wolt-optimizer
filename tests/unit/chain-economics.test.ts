import { describe, expect, it } from "vitest";
import { chainEconomics, counterfactual, readiness, shiftSummary, type ChainInput } from "@/lib/chains/economics";

const MIN = 60_000;
const base: ChainInput = {
  chainKey: "1",
  offers: 3,
  revenueAgorot: 11539, // ₪72.19 + ₪25.95 + ₪17.25, each counted once
  ordersLow: 5,
  ordersHigh: 5,
  startEarliestMs: 0,
  startLatestMs: 1 * MIN,
  endEarliestMs: 75 * MIN,
  endLatestMs: 82 * MIN,
  endEvidence: "ESTIMATED",
  gpsSamples: 100,
  gpsMaxGapMs: 120_000,
  gpsCoverage: 1,
  gpsPathKm: 20,
  gpsSpeedKm: 22,
  gpsDistanceReliable: true,
  missing: [],
  addedOffers: 2,
  completionSlides: 5,
};

describe("chain economics", () => {
  it("builds every figure as a range from the evidence ranges", () => {
    const e = chainEconomics(base, 110);
    expect(e.kind).toBe("WITH_ADDED_OFFERS");
    expect(e.durationMin).toEqual({ low: 74, high: 82 }); // latest start → earliest end … earliest start → latest end
    expect(e.distanceKm).toEqual({ low: 20, high: 22 });
    expect(e.vehicleCostAgorot).toEqual({ low: 2200, high: 2420 });
    // Gross: revenue ÷ longest … ÷ shortest duration.
    expect(e.grossPerHourAgorot!.low).toBeCloseTo(11539 / (82 / 60));
    expect(e.grossPerHourAgorot!.high).toBeCloseTo(11539 / (74 / 60));
    // Net: worst = highest cost over longest time; best = lowest cost over shortest time.
    expect(e.netPerHourAgorot!.low).toBeCloseTo((11539 - 2420) / (82 / 60));
    expect(e.netPerHourAgorot!.high).toBeCloseTo((11539 - 2200) / (74 / 60));
  });

  it("leaves a figure empty, with the reason, when its input is missing", () => {
    const noEnd = chainEconomics({ ...base, endEarliestMs: null, endLatestMs: null, missing: ["end unknown: x"] }, 110);
    expect(noEnd.durationMin).toBeNull();
    expect(noEnd.grossPerHourAgorot).toBeNull();
    expect(noEnd.missing).toContain("end unknown: x");
    const badGps = chainEconomics({ ...base, gpsDistanceReliable: false, missing: ["GPS not reliable: gap of 400 s"] }, 110);
    expect(badGps.distanceKm).toBeNull();
    expect(badGps.vehicleCostAgorot).toBeNull();
    expect(badGps.netPerHourAgorot).toBeNull();
    expect(badGps.grossPerHourAgorot).not.toBeNull(); // gross needs no distance
    const noCost = chainEconomics(base, null);
    expect(noCost.netPerHourAgorot).toBeNull();
    expect(noCost.missing).toContain("vehicle cost per km not set");
  });

  it("says how far the incremental question is from an answer", () => {
    const single = chainEconomics({ ...base, chainKey: "2", addedOffers: 0, offers: 1 }, 110);
    const lines = readiness([chainEconomics(base, 110), single], []);
    expect(lines[0]).toContain("single-offer 1, with added offers 1");
    expect(lines[0]).toContain("At least 10");
  });

  it("leaves the chain without an added offer UNKNOWN except its revenue — no bound, no marginal rate", () => {
    const chain = chainEconomics(base, 110);
    const cf = counterfactual(
      {
        tripKey: "t", chainKey: "1", amountAgorot: 1725,
        routeBeforeAgeMs: 15 * MIN, dropScreenBeforeAgeMs: 20_000, routeAfterDelayMs: 40_000,
        completionsBetween: 1, carriedTargetShiftMin: 0, routeChangedForOtherReasons: true, notes: [],
      },
      chain,
    );
    expect(cf.chainWithout).toEqual({ revenueAgorot: 11539 - 1725, duration: "UNKNOWN", distance: "UNKNOWN" });
    expect(cf.routeBeforeAgeMin).toBe(15);
    expect(cf.routeChangedForOtherReasons).toBe(true);
    expect(Object.keys(cf).some((k) => /marginal|bound|eta/i.test(k))).toBe(false);
  });

  it("measures the shift per SHIFT hour, idle time included", () => {
    const H = 3_600_000;
    const s = shiftSummary(
      {
        shiftId: "s", startMs: 0, endMs: 3 * H, revenueAgorot: 21950, completionSlides: 10,
        chainMsLow: 2.4 * H, chainMsHigh: 2.5 * H, verifiedIdleMs: 0.3 * H, onlineUnverifiedMs: 0.1 * H, offlineMs: 0.05 * H, unknownMs: 0.05 * H,
        gpsPathKm: 37, gpsSpeedKm: 40, gpsDistanceReliable: true, missing: [],
      },
      110,
    );
    expect(s.grossPerShiftHourAgorot).toBeCloseTo(21950 / 3);
    expect(s.vehicleCostAgorot).toEqual({ low: 4070, high: 4400 });
    expect(s.netPerShiftHourAgorot!.low).toBeCloseTo((21950 - 4400) / 3);
    expect(s.netPerShiftHourAgorot!.high).toBeCloseTo((21950 - 4070) / 3);
    // Per active hour is always higher than per shift hour here — idle time is the difference.
    expect(s.netPerActiveHourAgorot!.low).toBeGreaterThan(s.netPerShiftHourAgorot!.high);
    const noGps = shiftSummary({ ...s, shiftId: "x", startMs: 0, endMs: 3 * H, chainMsLow: 0, chainMsHigh: 0, verifiedIdleMs: 0, onlineUnverifiedMs: 0, offlineMs: 0, unknownMs: 0, gpsPathKm: null, gpsSpeedKm: null, gpsDistanceReliable: false, missing: [] }, 110);
    expect(noGps.netPerShiftHourAgorot).toBeNull();
    expect(noGps.missing).toContain("no reliable shift GPS distance");
  });
});
