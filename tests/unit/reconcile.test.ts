import { beforeEach, describe, expect, it } from "vitest";
import { prisma } from "@/lib/db";
import { reconcile, reconcileDay, recordOfficialDay, type OfficialDay, type TripForReconcile } from "@/lib/reconcile/day";
import { localToUtcMs } from "@/lib/time";
import { resetDb } from "../helpers";

// Wolt's statistics for 29.10.2026 and the six trips the Collector detected that evening (real numbers).
const official: OfficialDay = {
  day: "2026-10-29",
  completedDeliveries: 10,
  earningsAgorot: 22950,
  earningsWithoutTipsAgorot: 21950,
  tipsAgorot: 1000,
  distanceMeters: 37900,
  distanceDefinition: "UNVERIFIED",
  source: "test",
};
const trip = (
  hhmm: string,
  amount: number,
  evidence: string,
  low: number,
  high: number,
  card: number | null = null,
  end = "ESTIMATED",
  chain = hhmm,
  carried = 0,
): TripForReconcile => ({
  startedAtMs: localToUtcMs("2026-10-29", hhmm),
  completedAtMs: end === "UNKNOWN" ? null : localToUtcMs("2026-10-29", hhmm) + 25 * 60_000,
  endEvidence: end,
  offerAmountsAgorot: [amount],
  orderEvidence: evidence,
  ordersLow: low,
  ordersHigh: high,
  cardOrderCount: card,
  chainKey: chain,
  acceptContext: carried ? "ADDED_TO_ACTIVE_ROUTE" : "IDLE",
  carriedOrders: carried,
  carriedLink: carried ? "SHAPE_MATCH" : "NONE",
  // New orders = route drop-offs at acceptance − carried.
  newOrdersLow: low - carried,
  newOrdersHigh: high - carried,
});
// 21:48 offer (A+B) → 22:12 and 22:47 offers accepted while B was still on board: one chain.
const shift = [
  trip("20:10", 3086, "SINGLE_ORDER", 1, 1),
  trip("20:38", 4216, "MULTI_ORDER", 2, 2),
  trip("21:21", 3109, "CHANGED", 1, 2, 2, "UNKNOWN"),
  trip("21:48", 7219, "MULTI_ORDER", 2, 2, null, "ESTIMATED", "21:48"),
  trip("22:12", 2595, "MULTI_ORDER", 3, 3, 2, "UNKNOWN", "21:48", 1),
  trip("22:47", 1725, "MULTI_ORDER", 2, 2, null, "ESTIMATED", "21:48", 1),
];

describe("reconciliation against Wolt's day statistics", () => {
  it("counts a carried order once: offers, orders and chains kept apart", () => {
    const r = reconcile("2026-10-29", official, shift);
    expect(r.detectedTrips).toBe(6);
    expect(r.detectedChains).toBe(4);
    expect([r.detectedOrdersLow, r.detectedOrdersHigh]).toEqual([9, 10]);
    expect(r.deliveryDifference).toEqual({ min: 0, max: 1 });
    expect(r.acceptedOfferEarningsAgorot).toBe(21950);
    expect(r.earningsDifferenceAgorot).toBe(0);
    // Inside the range, but links are shape-matched and one offer is a range → not validated.
    expect(r.status).toBe("CONSISTENT_UNVERIFIED");
    expect(r.unresolved.join(" ")).toContain("צורת התחנה");
    expect(r.findings.join(" ")).toContain("לא משמש לעלות רכב");
  });

  it("counting gross route drop-offs per offer double-counts the carried order (the old 11–12)", () => {
    const gross = shift.map((t) => ({ ...t, newOrdersLow: t.ordersLow, newOrdersHigh: t.ordersHigh }));
    const r = reconcile("2026-10-29", official, gross);
    expect([r.detectedOrdersLow, r.detectedOrdersHigh]).toEqual([11, 12]);
    expect(r.status).toBe("UNRECONCILED");
  });

  it("is RECONCILED only when every trip has order evidence and both totals match exactly", () => {
    const exact = [trip("20:10", 1000, "SINGLE_ORDER", 1, 1), trip("21:00", 2000, "MULTI_ORDER", 2, 2)];
    // Same totals, but one carried link is only shape-matched → consistent, not reconciled.
    const linked = [exact[0], { ...trip("21:00", 2000, "MULTI_ORDER", 3, 3, null, "ESTIMATED", "20:10", 1) }];
    expect(reconcile("d", { ...official, completedDeliveries: 3, earningsWithoutTipsAgorot: 3000 }, linked).status).toBe("CONSISTENT_UNVERIFIED");
    expect(reconcile("d", { ...official, completedDeliveries: 3, earningsWithoutTipsAgorot: 3000 }, exact).status).toBe("RECONCILED");
    const unproven = [exact[0], { ...exact[1], orderEvidence: "NO_TIMELINE", ordersLow: null, ordersHigh: null, newOrdersLow: null, newOrdersHigh: null }];
    const r = reconcile("d", { ...official, completedDeliveries: 3, earningsWithoutTipsAgorot: 3000 }, unproven);
    expect(r.status).toBe("UNRECONCILED");
    expect(r.detectedOrdersLow).toBeNull();
    expect(reconcile("d", null, exact).status).toBe("NO_OFFICIAL_STATS");
  });

  describe("stored", () => {
    beforeEach(resetDb);
    it("stores Wolt's totals as ground truth without creating delivery records", async () => {
      await recordOfficialDay(official);
      await recordOfficialDay({ ...official, tipsAgorot: 1000 }); // re-recording is idempotent
      expect(await prisma.officialDayStat.count()).toBe(1);
      expect(await prisma.collectorTrip.count()).toBe(0);
      const r = await reconcileDay("2026-10-29");
      expect(r.official?.completedDeliveries).toBe(10);
      expect(r.detectedTrips).toBe(0);
      expect(r.status).toBe("UNRECONCILED");
    });
  });
});
