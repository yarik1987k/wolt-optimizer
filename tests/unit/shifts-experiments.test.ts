import { beforeEach, describe, expect, it } from "vitest";
import { prisma } from "@/lib/db";
import { commitImport } from "@/lib/import/service";
import { getOverview } from "@/lib/analytics/metrics";
import { compareGroups } from "@/lib/analytics/experiments";
import { computeShiftMetrics, confirmLinks, LinkConflictError, suggestRecordsForShift, toShiftData } from "@/lib/analytics/shifts";
import { listDeliveries } from "@/lib/deliveries";
import { FIXTURE_NAME, fixtureText, resetDb } from "../helpers";

beforeEach(async () => {
  await resetDb();
  await commitImport(fixtureText(), FIXTURE_NAME);
});

describe("KPIs without shifts (acceptance 7)", () => {
  it("₪/hour and ₪/km are null (rendered 'אין נתונים'), not 0", async () => {
    const o = await getOverview({});
    expect(o.shifts_logged_count).toBe(0);
    expect(o.delivery_per_shift_hour_agorot).toBeNull();
    expect(o.delivery_per_km_agorot).toBeNull();
  });
});

describe("shift record linking (acceptance 9)", () => {
  it("suggestions are not links; linking needs explicit confirmation and forbids double attribution", async () => {
    // A real evening from the fixture: 21.10.2026 had records 19:58–22:15.
    const a = await prisma.shift.create({ data: toShiftData({ date: "2026-10-21", startTime: "19:00", endTime: "22:00", areaText: "ראשון לציון" }) });
    const suggestions = await suggestRecordsForShift(a.id);
    expect(suggestions.length).toBeGreaterThan(0);
    // 22:15:20 is within the 30-minute tolerance after the shift end and is flagged as such.
    expect(suggestions.find((s) => s.record.eventTime === "22:15:20")?.afterShiftEnd).toBe(true);
    // Suggesting creates nothing.
    expect(await prisma.shiftRecordLink.count()).toBe(0);
    let o = await getOverview({});
    expect(o.shifts_logged_count).toBe(1);
    expect(o.delivery_per_shift_hour_agorot).toBeNull(); // no confirmed links yet -> unknown

    const inWindow = suggestions.filter((s) => !s.afterShiftEnd).map((s) => s.record.id);
    await confirmLinks(a.id, inWindow);
    expect(await prisma.shiftRecordLink.count()).toBe(inWindow.length);

    const b = await prisma.shift.create({ data: toShiftData({ date: "2026-10-21", startTime: "20:30", endTime: "23:00", areaText: "ראשון לציון" }) });
    const overlap = (await suggestRecordsForShift(b.id)).filter((s) => s.linkedToShiftId === a.id);
    expect(overlap.length).toBeGreaterThan(0);
    await expect(confirmLinks(b.id, overlap.map((s) => s.record.id))).rejects.toBeInstanceOf(LinkConflictError);
    expect(await prisma.shiftRecordLink.count({ where: { shiftId: b.id } })).toBe(0);

    o = await getOverview({});
    expect(o.delivery_per_shift_hour_agorot).not.toBeNull();
  });

  it("computes shift metrics; contribution only with km and cost", () => {
    const base = { date: "2026-10-21", startTime: "19:00", endTime: "22:00" };
    const linkedRecords = [
      { type: "DELIVERY", amountAgorot: 9000 },
      { type: "DELIVERY", amountAgorot: 9000 },
      { type: "BONUS", amountAgorot: 10000 },
    ];
    const m = computeShiftMetrics({ ...base, distanceKm: null, vehicleCostPerKmAgorot: null, linkedRecords });
    expect(m.duration_hours).toBe(3);
    expect(m.gross_delivery_per_hour_ex_vat_agorot).toBe(6000);
    expect(m.gross_including_extras_per_hour_ex_vat_agorot).toBeCloseTo(28000 / 3);
    expect(m.contribution_per_hour_ex_vat_agorot).toBeNull();
    const m2 = computeShiftMetrics({ ...base, distanceKm: 60, vehicleCostPerKmAgorot: 50, linkedRecords });
    expect(m2.estimated_vehicle_cost_agorot).toBe(3000);
    expect(m2.contribution_per_hour_ex_vat_agorot).toBe(5000);
  });
});

describe("experiment comparison (acceptance 10)", () => {
  const shift = (hours: number, agorot: number) => ({
    shiftId: Math.random().toString(),
    durationHours: hours,
    deliveryAgorot: agorot,
    vehicleCostAgorot: null,
    hasLinkedRecords: true,
  });

  it("control 6h/₪180 vs test 6h/₪198 shows +10% without declaring success", () => {
    const c = compareGroups([shift(6, 18000)], [shift(6, 19800)], { minShiftsPerGroup: 3 });
    expect(c.control.delivery_per_hour_agorot).toBe(3000);
    expect(c.test.delivery_per_hour_agorot).toBe(3300);
    expect(c.pct_diff_delivery_per_hour).toBe(10);
    expect(c.can_determine).toBe(false);
    expect(c.verdict).toBe("לא ניתן לקבוע שיפור");
    expect(c.verdict).not.toMatch(/הצלחה|מובהק/);
  });

  it("uses sum(income)/sum(hours), not a mean of shift rates", () => {
    // Control: 1h ₪60 and 5h ₪100 -> 160/6 = 26.67/h (mean of rates would be 40/h).
    const c = compareGroups([shift(1, 6000), shift(5, 10000), shift(0.0, 0)], [shift(3, 9000)], { minShiftsPerGroup: 1 });
    expect(c.control.delivery_per_hour_agorot).toBeCloseTo(16000 / 6);
  });

  it("with enough shifts reports an indication, never proof", () => {
    const c = compareGroups(
      [shift(2, 6000), shift(2, 6000), shift(2, 6000)],
      [shift(2, 6600), shift(2, 6600), shift(2, 6600)],
      { minShiftsPerGroup: 3 },
    );
    expect(c.can_determine).toBe(true);
    expect(c.verdict).toContain("אינדיקציה");
    expect(c.verdict).toContain("לא הוכחה סיבתית");
  });

  it("handles empty groups and zero control", () => {
    expect(compareGroups([], [], { minShiftsPerGroup: 3 }).pct_diff_delivery_per_hour).toBeNull();
    expect(compareGroups([shift(2, 0)], [shift(2, 100)], { minShiftsPerGroup: 1 }).pct_diff_delivery_per_hour).toBeNull();
  });
});

describe("deliveries table (acceptance 12)", () => {
  it("sorts by amount descending by default and can separate extras by type filter", async () => {
    const all = await listDeliveries({});
    const amounts = all.rows.map((r) => r.amountAgorot);
    expect(amounts).toEqual([...amounts].sort((a, b) => b - a));
    expect(all.total).toBe(44);
    const deliveriesOnly = await listDeliveries({ types: ["DELIVERY"] });
    expect(deliveriesOnly.total).toBe(41);
    expect(deliveriesOnly.rows[0].amountAgorot).toBe(7142);
    const extras = await listDeliveries({ types: ["BONUS", "ADJUSTMENT"] });
    expect(extras.total).toBe(3);
    expect(extras.filtered_sum_agorot).toBe(40700);
  });
});
