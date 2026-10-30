import { beforeEach, describe, expect, it } from "vitest";
import { prisma } from "@/lib/db";
import { collectorExportSchema } from "@/lib/offers/collector-format";
import { cardAmount, groupObservations, kmToMeters, toObservation, type OfferObservationInput } from "@/lib/offers/detect";
import { applyVerifications, importCollectorExport, OfferImportError } from "@/lib/offers/import";
import { analyzeOffers, applyOfferFilters, type OfferPoint } from "@/lib/offers/analytics";
import { generateOfferHypotheses } from "@/lib/offers/hypotheses";
import { getOfferAnalytics, updateOffer, OfferUpdateError } from "@/lib/offers/service";
import { commitImport } from "@/lib/import/service";
import { toShiftData } from "@/lib/analytics/shifts";
import { FIXTURE_NAME, fixtureText, OFFER_EXPORT_NAME, offerExportText, offerVerifications, resetDb } from "../helpers";

const realExport = () => collectorExportSchema.parse(JSON.parse(offerExportText()));
const observations = () => realExport().screen_events.map(toObservation).filter((o): o is OfferObservationInput => o !== null);

describe("detection on the REAL Collector export (pure)", () => {
  it("1. finds exactly the two verified offers with their amounts and distances", () => {
    const offers = groupObservations(observations());
    expect(offers.map((o) => [o.amountAgorot, o.distanceMeters, o.detection])).toEqual([
      [2749, 4500, "CONFIRMED_CARD"],
      [3502, 6800, "CONFIRMED_CARD"],
    ]);
    expect(new Date(offers[0].firstSeenMs).toISOString()).toBe("2026-10-29T07:40:27.925Z");
  });

  it("3. repeated screen snapshots of one card are one offer", () => {
    const offers = groupObservations(observations());
    expect(observations()).toHaveLength(8);
    expect(offers.map((o) => o.observations.length)).toEqual([4, 4]);
    expect(offers.map((o) => Math.round((o.lastSeenMs - o.firstSeenMs) / 1000))).toEqual([6, 5]); // 09:40:27.9→09:40:34.3 and 09:49:28.7→09:49:33.3
  });

  it("3b. a bundle card whose pickup line changes stays one offer (real 29.10 21:21)", () => {
    const base = observations()[0];
    const at = (ms: number, names: string[], km: number | null = 4200) => ({ ...base, screenEventId: `b${ms}`, observedAtMs: ms, amountAgorot: 3109, distanceMeters: km, pickupNames: names });
    const offers = groupObservations([at(0, ["Tiv Ta'am"]), at(14_000, ["Moses Shop"]), at(19_000, [], null)]);
    expect(offers).toHaveLength(1);
    expect(offers[0].pickupNames).toEqual(["Tiv Ta'am", "Moses Shop"]);
    // Same price but another distance is another card.
    expect(groupObservations([at(0, ["A"]), at(5_000, ["A"], 2000)])).toHaveLength(2);
  });

  it("4. bonus amounts are never offers", () => {
    // The real export contains the bonus banner (₪200 / ₪250) — none of it becomes an offer.
    const amounts = groupObservations(observations()).map((o) => o.amountAgorot);
    expect(amounts).not.toContain(20000);
    expect(amounts).not.toContain(25000);
    // A card whose first ₪ amount sits in a bonus element takes the card price, not the bonus.
    const fields = {
      amounts_agorot: [20000, 3100], distances_km: ["3.0"], durations_min: [], stops_counts: [], order_counts: [],
      ui_keywords: ["бонус", "принять"], pickup_names: [],
      nodes: [
        { viewId: "UpcomingBonusesBanner.BonusOpportunityText", cls: "TextView", redacted: "[UI:бонус] [AMOUNT ₪200.00]" },
        { viewId: null, cls: "TextView", redacted: "[AMOUNT ₪31.00]" },
      ],
    };
    expect(cardAmount(fields)).toBe(3100);
    // Bonus amount + accept word but no other amount: not an offer.
    expect(cardAmount({ ...fields, amounts_agorot: [20000], nodes: [fields.nodes[0]] })).toBeNull();
  });

  it("5. a missing distance stays NULL and the card is PARTIAL", () => {
    const exp = realExport();
    for (const e of exp.screen_events) if (e.redacted_candidate_fields?.amounts_agorot.includes(3502)) e.redacted_candidate_fields.distances_km = [];
    const offers = groupObservations(exp.screen_events.map(toObservation).filter((o): o is OfferObservationInput => o !== null));
    const partial = offers.find((o) => o.amountAgorot === 3502)!;
    expect(partial.distanceMeters).toBeNull();
    expect(partial.detection).toBe("PARTIAL_CARD");
    expect(partial.durationMin).toBeNull();
  });

  it("drops a package-size tag that an older Collector stored as the restaurant name", () => {
    const exp = realExport();
    const e = exp.screen_events.find((x) => x.redacted_candidate_fields?.amounts_agorot.includes(3502))!;
    e.redacted_candidate_fields!.pickup_names = ["Тяжелая (более 13 кг)"];
    expect(toObservation(e)!.pickupNames).toEqual([]);
    e.redacted_candidate_fields!.pickup_names = ["Light Burger | Holon"];
    expect(toObservation(e)!.pickupNames).toEqual(["Light Burger | Holon"]);
    for (const label of ["Заберите заказы: 2", "Выдача покупателю", "Проверка документов"]) {
      e.redacted_candidate_fields!.pickup_names = [label];
      expect(toObservation(e)!.pickupNames).toEqual([]);
    }
  });

  it("parses km exactly without floats", () => {
    expect(kmToMeters("4.5")).toBe(4500);
    expect(kmToMeters("6.8")).toBe(6800);
    expect(kmToMeters("0.75")).toBe(750);
    expect(kmToMeters("abc")).toBeNull();
  });
});

describe("analytics (pure)", () => {
  const verified: OfferPoint[] = [
    { id: "a", firstSeenMs: Date.parse("2026-10-29T07:40:27.925Z"), amountAgorot: 2749, distanceMeters: 4500, onScreenSeconds: 6, detection: "CONFIRMED_CARD", verification: "USER_VERIFIED" },
    { id: "b", firstSeenMs: Date.parse("2026-10-29T07:49:28.715Z"), amountAgorot: 3502, distanceMeters: 6800, onScreenSeconds: 4, detection: "CONFIRMED_CARD", verification: "USER_VERIFIED" },
  ];
  const partial: OfferPoint = { id: "c", firstSeenMs: Date.parse("2026-10-29T15:10:00Z"), amountAgorot: 5000, distanceMeters: null, onScreenSeconds: 3, detection: "PARTIAL_CARD", verification: "UNVERIFIED" };

  it("7. metrics are correct for the verified fixtures", () => {
    const a = analyzeOffers(verified);
    expect(a.observed_offer_count).toBe(2);
    expect(a.amount.mean).toBe(3125.5);
    expect(a.amount.median).toBe(3125.5);
    expect(a.distance_m.mean).toBe(5650);
    expect(a.agorot_per_km_aggregate).toBeCloseTo((2749 + 3502) / 11.3, 6);
    expect(a.agorot_per_km.median).toBeCloseTo((2749 / 4.5 + 3502 / 6.8) / 2, 6);
    expect(a.by_hour).toEqual([{ hour: 9, n: 2, mean_amount_agorot: 3125.5, median_amount_agorot: 3125.5, sum_agorot: 6251 }]);
    expect(a.gaps_minutes.n).toBe(1);
    expect(a.gaps_minutes.mean).toBeCloseTo(9.013, 2);
    expect(a.thresholds.map((t) => [t.threshold_shekel, t.n_at_or_above])).toEqual([[25, 2], [30, 1], [35, 1], [40, 0], [45, 0], [50, 0], [60, 0]]);
    expect(a.distribution).toEqual([{ from_agorot: 2500, to_agorot: 3000, n: 1 }, { from_agorot: 3500, to_agorot: 4000, n: 1 }]);
  });

  it("6. averages use only valid fields (distance-less offers excluded from km metrics)", () => {
    const a = analyzeOffers([...verified, partial]);
    expect(a.amount.n).toBe(3);
    expect(a.offers_with_distance).toBe(2);
    expect(a.distance_m.mean).toBe(5650);
    expect(a.partial_card_count).toBe(1);
  });

  it("filters: confirmed-only by default, amount/hour/distance ranges", () => {
    expect(applyOfferFilters([...verified, partial], {})).toHaveLength(2);
    expect(applyOfferFilters([...verified, partial], { detection: "ALL" })).toHaveLength(3);
    expect(applyOfferFilters(verified, { minAmountAgorot: 3000 }).map((o) => o.id)).toEqual(["b"]);
    expect(applyOfferFilters(verified, { hourFrom: 10 })).toHaveLength(0);
    expect(applyOfferFilters(verified, { maxDistanceMeters: 5000 }).map((o) => o.id)).toEqual(["a"]);
    expect(applyOfferFilters([...verified, partial], { detection: "ALL", minDistanceMeters: 0 })).toHaveLength(2);
  });

  it("9. with a small sample nothing is presented as proven", () => {
    const h = generateOfferHypotheses(verified, { minSampleObservation: 5, minSampleTestCandidate: 10 });
    expect(h.hypotheses.every((x) => !x.testable)).toBe(true);
    expect(h.hypotheses.every((x) => x.confidence_label === "מדגם קטן — לא להמלצה")).toBe(true);
    expect(h.explanation).toContain("תצפיות ולא המלצות");
    const volume = h.hypotheses.find((x) => x.key === "VOLUME_VS_TARGET")!;
    expect(volume.evidence).toContain("~13 הצעות");
    expect(volume.limitations[0]).toContain("מתמטי בלבד");
    expect(JSON.stringify(h)).not.toMatch(/מוכח|מובהק|בוודאות/);
  });
});

describe("import into the DB (integration)", () => {
  beforeEach(resetDb);

  it("1+2. imports the real export and re-import creates no duplicates", async () => {
    const r1 = await importCollectorExport(offerExportText(), OFFER_EXPORT_NAME);
    expect(r1).toMatchObject({ screenEventCount: 759, offerScreenCount: 8, newObservationCount: 8, skippedObservationCount: 0, offersTotal: 2 });
    const r2 = await importCollectorExport(offerExportText(), OFFER_EXPORT_NAME);
    expect(r2).toMatchObject({ newObservationCount: 0, skippedObservationCount: 8, offersTotal: 2, offersCreated: 0 });
    const offers = await prisma.offer.findMany({ orderBy: { firstSeenAtUtc: "asc" } });
    expect(offers.map((o) => [o.amountAgorot, o.distanceMeters, o.observationCount, o.verification, o.outcome])).toEqual([
      [2749, 4500, 4, "UNVERIFIED", "UNKNOWN"],
      [3502, 6800, 4, "UNVERIFIED", "UNKNOWN"],
    ]);
    expect(await prisma.offerObservation.count()).toBe(8);
  });

  it("verification only from an explicit source, kept across re-imports", async () => {
    await importCollectorExport(offerExportText(), OFFER_EXPORT_NAME);
    const res = await applyVerifications(offerVerifications());
    expect(res.every((r) => r.offerId)).toBe(true);
    await importCollectorExport(offerExportText(), OFFER_EXPORT_NAME);
    const offers = await prisma.offer.findMany();
    expect(offers.every((o) => o.verification === "USER_VERIFIED" && o.verificationSource?.includes("אימות ידני"))).toBe(true);
    // A verification that does not match price+distance+time verifies nothing.
    const miss = await applyVerifications([{ firstSeenAtUtc: "2026-10-29T07:40:27.925Z", amountAgorot: 2749, distanceKm: "9.9", source: "x" }]);
    expect(miss[0].offerId).toBeNull();
  });

  it("rejects files that are not a Collector export", async () => {
    await expect(importCollectorExport("{}", "x.json")).rejects.toBeInstanceOf(OfferImportError);
    await expect(importCollectorExport("not json", "x.json")).rejects.toBeInstanceOf(OfferImportError);
    await expect(importCollectorExport(fixtureText(), FIXTURE_NAME)).rejects.toBeInstanceOf(OfferImportError);
  });

  it("Offer / Accepted / Completed / Payment are separate and manual", async () => {
    await commitImport(fixtureText(), FIXTURE_NAME);
    await importCollectorExport(offerExportText(), OFFER_EXPORT_NAME);
    const offer = (await prisma.offer.findFirstOrThrow({ where: { amountAgorot: 2749 } }));
    const payment = await prisma.deliveryRecord.findFirstOrThrow({ where: { type: "DELIVERY" } });
    const bonus = await prisma.deliveryRecord.findFirstOrThrow({ where: { type: "BONUS" } });

    await expect(updateOffer(offer.id, { verification: "USER_VERIFIED" })).rejects.toBeInstanceOf(OfferUpdateError); // no source
    await expect(updateOffer(offer.id, { completed: true })).rejects.toBeInstanceOf(OfferUpdateError); // not accepted
    await expect(updateOffer(offer.id, { paymentRecordId: payment.id })).rejects.toBeInstanceOf(OfferUpdateError); // not completed
    await updateOffer(offer.id, { outcome: "ACCEPTED" });
    await updateOffer(offer.id, { completed: true });
    await expect(updateOffer(offer.id, { paymentRecordId: bonus.id })).rejects.toBeInstanceOf(OfferUpdateError); // not a delivery
    await updateOffer(offer.id, { paymentRecordId: payment.id });

    const other = await prisma.offer.findFirstOrThrow({ where: { amountAgorot: 3502 } });
    await updateOffer(other.id, { outcome: "ACCEPTED", completed: true });
    await expect(updateOffer(other.id, { paymentRecordId: payment.id })).rejects.toMatchObject({ status: 409 });

    const shift = await prisma.shift.create({ data: toShiftData({ date: "2026-10-29", startTime: "09:00", endTime: "10:00", areaText: "בדיקה" }) });
    await updateOffer(offer.id, { shiftId: shift.id });
    // Labels survive a re-import.
    await importCollectorExport(offerExportText(), OFFER_EXPORT_NAME);
    const after = await prisma.offer.findUniqueOrThrow({ where: { id: offer.id } });
    expect(after).toMatchObject({ outcome: "ACCEPTED", outcomeSource: "USER_LABEL", completed: true, paymentRecordId: payment.id, shiftId: shift.id });
  });

  it("10. existing report data is untouched by offer imports", async () => {
    await commitImport(fixtureText(), FIXTURE_NAME);
    await importCollectorExport(offerExportText(), OFFER_EXPORT_NAME);
    const sum = await prisma.deliveryRecord.aggregate({ _sum: { amountAgorot: true }, _count: true });
    expect(sum).toMatchObject({ _count: 44, _sum: { amountAgorot: 192536 } });
    const a = await getOfferAnalytics({});
    expect(a.analytics.observed_offer_count).toBe(2);
    expect(a.totals).toEqual({ confirmed: 2, partial: 0 });
  });
});
