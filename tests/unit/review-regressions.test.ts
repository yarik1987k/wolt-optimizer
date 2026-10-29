/**
 * Regression tests for the external code review (WOLT_OPTIMIZER_CODE_REVIEW.md).
 * Each block reproduces one finding and fails on the code before the fix.
 */
import { beforeEach, describe, expect, it } from "vitest";
import { prisma } from "@/lib/db";
import { handle } from "@/lib/api";
import { commitImport, previewImport } from "@/lib/import/service";
import { getOverview } from "@/lib/analytics/metrics";
import { compareGroups, type ShiftForComparison } from "@/lib/analytics/experiments";
import { generateInsights, getInsights, type InsightRecord } from "@/lib/analytics/insights";
import {
  computeShiftMetrics,
  confirmLinks,
  getShiftKpis,
  LinkValidationError,
  suggestRecordsForShift,
  toShiftData,
} from "@/lib/analytics/shifts";
import { FIXTURE_NAME, fixtureText, resetDb } from "../helpers";

beforeEach(async () => {
  await resetDb();
  await commitImport(fixtureText(), FIXTURE_NAME);
});

const recordAt = (eventDate: string, eventTime: string) =>
  prisma.deliveryRecord.findFirstOrThrow({ where: { eventDate, eventTime } });

describe("P1: shifts linked only to extras have unknown delivery income, not ₪0", () => {
  it("computeShiftMetrics returns null rates when no DELIVERY record is linked", () => {
    const m = computeShiftMetrics({
      date: "2026-10-11",
      startTime: "21:00",
      endTime: "22:00",
      distanceKm: 20,
      vehicleCostPerKmAgorot: 50,
      linkedRecords: [{ type: "BONUS", amountAgorot: 30000 }, { type: "ADJUSTMENT", amountAgorot: 700 }],
    });
    expect(m.linked_record_count).toBe(2);
    expect(m.linked_delivery_count).toBe(0);
    expect(m.gross_delivery_per_hour_ex_vat_agorot).toBeNull();
    expect(m.gross_including_extras_per_hour_ex_vat_agorot).toBeNull();
    expect(m.contribution_per_hour_ex_vat_agorot).toBeNull();
    expect(m.delivery_per_km_agorot).toBeNull();
  });

  it("dashboard ₪/shift-hour stays 'no data' for a shift linked only to a bonus", async () => {
    // Fixture row: 11.10.2026 21:53:15, "Funnel boost" bonus of ₪300.
    const bonus = await recordAt("2026-10-11", "21:53:15");
    expect(bonus.type).toBe("BONUS");
    const s = await prisma.shift.create({ data: toShiftData({ date: "2026-10-11", startTime: "21:00", endTime: "22:00", areaText: "ראשון לציון" }) });
    await confirmLinks(s.id, [bonus.id]);
    const k = await getShiftKpis();
    expect(k.deliveryPerShiftHourAgorot).toBeNull();
    expect(k.shiftsWithoutLinks).toBe(1);
    expect(k.notes[0]).toContain("בונוסים/התאמות בלבד");
    const o = await getOverview({});
    expect(o.delivery_per_shift_hour_agorot).toBeNull();
  });
});

describe("P1: insights honour minSampleTestCandidate", () => {
  // 30 baseline records at noon (₪30) and n records at 19:xx (₪40), spread over 5 dates.
  const data = (n: number): InsightRecord[] => [
    ...Array.from({ length: 30 }, (_, i) => ({ eventDate: `2026-10-1${i % 5}`, eventTime: "12:10:00", weekday: 1, merchantRaw: `b${i}`, isBundle: false, amountAgorot: 3000 })),
    ...Array.from({ length: n }, (_, i) => ({ eventDate: `2026-10-1${i % 5}`, eventTime: "19:10:00", weekday: 1, merchantRaw: `g${i}`, isBundle: false, amountAgorot: 4000 })),
  ];
  const opts = { minSampleObservation: 5, minSampleTestCandidate: 10 };

  it("an n=7 group is an early indication, not a test hypothesis", () => {
    const r = generateInsights(data(7), opts);
    expect(r.hypotheses.find((h) => h.key === "HOUR:19:00")).toBeUndefined();
    const o = r.observations.find((h) => h.key === "HOUR:19:00");
    expect(o?.status).toBe("OBSERVATION");
    expect(o?.confidence_label).toBe("אינדיקציה ראשונית");
    expect(o?.limitations[0]).toContain("פחות מ-10 רשומות");
  });

  it("the same gap with n=10 becomes a test candidate", () => {
    const h = generateInsights(data(10), opts).hypotheses.find((x) => x.key === "HOUR:19:00");
    expect(h?.status).toBe("TEST_CANDIDATE");
    expect(h?.confidence_label).toBe("מדגם סביר לניסוי");
  });

  it("on the fixture, the 7-record 19:00 window is no longer promoted", async () => {
    const r = await getInsights({});
    expect(r.hypotheses.every((h) => h.sample_size >= 10)).toBe(true);
    expect(r.hypotheses.some((h) => h.key === "HOUR:19:00")).toBe(false);
    expect(r.observations.some((h) => h.key === "HOUR:19:00")).toBe(true);
  });
});

describe("P1: experiment comparison checks variation and comparability", () => {
  let seq = 0;
  const shift = (hours: number, agorot: number, extra: Partial<ShiftForComparison> = {}): ShiftForComparison => ({
    shiftId: String(seq++),
    durationHours: hours,
    deliveryAgorot: agorot,
    vehicleCostAgorot: null,
    hasLinkedRecords: true,
    ...extra,
  });

  it("is labelled descriptive and reports an uncertainty band", () => {
    const c = compareGroups([shift(2, 6000), shift(2, 6200), shift(2, 5800)], [shift(2, 6600), shift(2, 6800), shift(2, 6400)], { minShiftsPerGroup: 3 });
    expect(c.analysis_type).toBe("DESCRIPTIVE");
    expect(c.diff_uncertainty_pct).not.toBeNull();
    expect(c.can_determine).toBe(true); // +10% vs a ±~5.4% band
    expect(c.verdict).toContain("לא הוכחה סיבתית");
  });

  it("a +10% gap inside the shift-to-shift variation is not a determination", () => {
    // Same group means as above, but per-shift rates swing widely.
    const c = compareGroups([shift(2, 2000), shift(2, 10000), shift(2, 6000)], [shift(2, 2600), shift(2, 10600), shift(2, 6600)], { minShiftsPerGroup: 3 });
    expect(c.pct_diff_delivery_per_hour).toBe(10);
    expect(c.can_determine).toBe(false);
    expect(c.verdict).toBe("לא ניתן לקבוע שיפור");
    expect(c.warnings.some((w) => w.includes("טווח השונות"))).toBe(true);
  });

  it("never determines from a single shift per group, even if the setting allows it", () => {
    const c = compareGroups([shift(4, 12000)], [shift(4, 20000)], { minShiftsPerGroup: 1 });
    expect(c.can_determine).toBe(false);
    expect(c.warnings[0]).toContain("לפחות 2");
  });

  it("notes differing areas and weekdays without blocking", () => {
    const ctl = [0, 1, 2].map(() => shift(3, 9000, { date: "2026-10-18", startTime: "12:00", areaText: "ראשון לציון" })); // Sunday
    const tst = [0, 1, 2].map(() => shift(3, 9900, { date: "2026-10-23", startTime: "19:00", areaText: "חולון" })); // Friday
    const c = compareGroups(ctl, tst, { minShiftsPerGroup: 3 });
    expect(c.comparability_notes.some((n) => n.includes("אזורי העבודה"))).toBe(true);
    expect(c.comparability_notes.some((n) => n.includes("יום בשבוע"))).toBe(true);
    expect(c.comparability_notes.some((n) => n.includes("שעת ההתחלה"))).toBe(true);
  });
});

describe("P2: confirmLinks validates the shift window and input IDs", () => {
  const eveningShift = () =>
    prisma.shift.create({ data: toShiftData({ date: "2026-10-21", startTime: "19:00", endTime: "22:00", areaText: "ראשון לציון" }) });

  it("rejects a record from another day, links nothing, and maps to HTTP 422", async () => {
    const s = await eveningShift();
    const inWindow = (await suggestRecordsForShift(s.id)).map((x) => x.record.id);
    const otherDay = await recordAt("2026-10-11", "21:53:15");
    const attempt = confirmLinks(s.id, [...inWindow, otherDay.id]);
    await expect(attempt).rejects.toBeInstanceOf(LinkValidationError);
    expect(await prisma.shiftRecordLink.count()).toBe(0);
    const res = await handle(() => confirmLinks(s.id, [otherDay.id]));
    expect(res.status).toBe(422);
    expect((await res.json()).recordIds).toEqual([otherDay.id]);
  });

  it("links a late payout only with the explicit override", async () => {
    const s = await eveningShift();
    const otherDay = await recordAt("2026-10-11", "21:53:15");
    const r = await confirmLinks(s.id, [otherDay.id], { allowOutsideWindow: true });
    expect(r.linked).toBe(1);
  });

  it("deduplicates submitted IDs and rejects unknown IDs instead of failing with 500", async () => {
    const s = await eveningShift();
    const [first] = await suggestRecordsForShift(s.id);
    const r = await confirmLinks(s.id, [first.record.id, first.record.id, first.record.id]);
    expect(r).toEqual({ linked: 1, alreadyLinked: 0 });
    await expect(confirmLinks(s.id, ["does-not-exist"])).rejects.toBeInstanceOf(LinkValidationError);
  });
});

describe("P2: dashboard shift KPIs follow the date and weekday filters", () => {
  beforeEach(async () => {
    // 21.10.2026 (Wednesday) evening, linked to its in-window deliveries.
    const s = await prisma.shift.create({ data: toShiftData({ date: "2026-10-21", startTime: "19:00", endTime: "22:00", areaText: "ראשון לציון" }) });
    await confirmLinks(s.id, (await suggestRecordsForShift(s.id)).filter((x) => !x.afterShiftEnd).map((x) => x.record.id));
  });

  it("a date range before the shift excludes it", async () => {
    const all = await getOverview({});
    expect(all.shifts_logged_count).toBe(1);
    expect(all.delivery_per_shift_hour_agorot).not.toBeNull();
    const before = await getOverview({ from: "2026-10-01", to: "2026-10-15" });
    expect(before.shifts_logged_count).toBe(0);
    expect(before.delivery_per_shift_hour_agorot).toBeNull();
  });

  it("a weekday filter applies to the shift date", async () => {
    expect((await getOverview({ weekdays: [3] })).shifts_logged_count).toBe(1); // Wednesday
    expect((await getOverview({ weekdays: [5] })).shifts_logged_count).toBe(0);
  });

  it("says which filters don't apply to shift KPIs", async () => {
    const o = await getOverview({ merchant: "x" });
    expect(o.shifts_logged_count).toBe(1);
    expect(o.notes.some((n) => n.includes("תאריך ויום בשבוע בלבד"))).toBe(true);
  });
});

describe("P2: a renamed export without report numbers isn't re-imported silently", () => {
  // The fixture's first 5 report-116000002 rows (any copy name), without the source-file column, so the
  // report identity can only come from the upload's file name.
  const noSourceCsv = () => {
    const lines = fixtureText().replace(/^﻿/, "").trimEnd().split(/\r?\n/);
    const body = lines.filter((l) => /,116000002[^,]*\.pdf$/.test(l)).slice(0, 5).map((l) => l.replace(/,[^,]*\.pdf$/, ""));
    return [lines[0].replace(/,קובץ מקור$/, ""), ...body].join("\n") + "\n";
  };

  beforeEach(resetDb);

  it("holds back content-identical rows under a new file name", async () => {
    const first = await commitImport(noSourceCsv(), "payments.csv");
    expect(first.insertedCount).toBe(5);
    const p = await previewImport(noSourceCsv(), "payments-renamed.csv");
    expect(p.newRowCount).toBe(0);
    expect(p.possibleDuplicateCount).toBe(5);
    expect(p.findings.some((f) => f.message.includes("זהות בתוכנן"))).toBe(true);
    const second = await commitImport(noSourceCsv(), "payments-renamed.csv");
    expect(second.insertedCount).toBe(0);
    expect(second.skippedPossibleDuplicates).toBe(5);
    expect(await prisma.deliveryRecord.count()).toBe(5);
  });

  it("imports them on explicit confirmation", async () => {
    await commitImport(noSourceCsv(), "payments.csv");
    const r = await commitImport(noSourceCsv(), "payments-renamed.csv", { includePossibleDuplicates: true });
    expect(r.insertedCount).toBe(5);
    expect(await prisma.deliveryRecord.count()).toBe(10);
  });

  it("leaves the 44-row fixture (with report numbers) untouched", async () => {
    const p = await previewImport(fixtureText(), FIXTURE_NAME);
    expect(p.possibleDuplicateCount).toBe(0);
    expect(p.newRowCount).toBe(44);
    expect(p.totalAgorot).toBe(192536);
  });
});
