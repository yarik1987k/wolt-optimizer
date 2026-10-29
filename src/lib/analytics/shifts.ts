import { z } from "zod";
import { prisma } from "@/lib/db";
import { addMinutesLocal, computeShiftWindow, weekdayOf } from "@/lib/time";
import type { RecordType } from "@/lib/types";

export const shiftInputSchema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "תאריך לא תקין"),
  startTime: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, "שעת התחלה לא תקינה"),
  endTime: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, "שעת סיום לא תקינה"),
  areaText: z.string().trim().min(1, "יש להזין אזור עבודה"),
  distanceKm: z.number().positive().nullable().optional(),
  vehicleCostPerKmAgorot: z.number().int().nonnegative().nullable().optional(),
  followedRecommendation: z.boolean().optional(),
  notes: z.string().trim().nullable().optional(),
  experimentId: z.string().nullable().optional(),
  experimentGroup: z.enum(["CONTROL", "TEST"]).nullable().optional(),
});
export type ShiftInput = z.infer<typeof shiftInputSchema>;

/** Validates input and derives the stored window (handles midnight crossing). */
export function toShiftData(input: ShiftInput) {
  const w = computeShiftWindow(input.date, input.startTime, input.endTime);
  return {
    date: input.date,
    startTime: input.startTime,
    endTime: input.endTime,
    startedAt: w.startedAt,
    endedAt: w.endedAt,
    areaText: input.areaText,
    distanceKm: input.distanceKm ?? null,
    vehicleCostPerKmAgorot: input.vehicleCostPerKmAgorot ?? null,
    followedRecommendation: input.followedRecommendation ?? false,
    notes: input.notes || null,
  };
}

export interface ShiftMetricsInput {
  date: string;
  startTime: string;
  endTime: string;
  distanceKm: number | null;
  vehicleCostPerKmAgorot: number | null;
  linkedRecords: { type: string; amountAgorot: number }[];
}

export interface ShiftMetrics {
  duration_hours: number;
  linked_record_count: number;
  /** Linked DELIVERY records. Rates are null when 0: extras alone don't tell delivery income. */
  linked_delivery_count: number;
  delivery_amount_ex_vat_agorot: number;
  bonus_amount_agorot: number;
  adjustment_amount_agorot: number;
  unknown_amount_agorot: number; // excluded until classified
  gross_delivery_per_hour_ex_vat_agorot: number | null;
  gross_including_extras_per_hour_ex_vat_agorot: number | null;
  estimated_vehicle_cost_agorot: number | null;
  contribution_per_hour_ex_vat_agorot: number | null; // NOT net profit
  delivery_per_km_agorot: number | null;
}

export function computeShiftMetrics(s: ShiftMetricsInput): ShiftMetrics {
  const hours = computeShiftWindow(s.date, s.startTime, s.endTime).durationHours;
  const sum = (t: RecordType) => s.linkedRecords.filter((r) => r.type === t).reduce((a, r) => a + r.amountAgorot, 0);
  const delivery = sum("DELIVERY");
  const bonus = sum("BONUS");
  const adjustment = sum("ADJUSTMENT");
  const deliveryCount = s.linkedRecords.filter((r) => r.type === "DELIVERY").length;
  // A shift linked only to bonuses/adjustments has unknown delivery income, not ₪0.
  const hasDeliveries = deliveryCount > 0;
  const vehicleCost =
    s.distanceKm != null && s.vehicleCostPerKmAgorot != null ? Math.round(s.distanceKm * s.vehicleCostPerKmAgorot) : null;
  const perHour = (x: number) => (hasDeliveries && hours > 0 ? x / hours : null);
  return {
    duration_hours: hours,
    linked_record_count: s.linkedRecords.length,
    linked_delivery_count: deliveryCount,
    delivery_amount_ex_vat_agorot: delivery,
    bonus_amount_agorot: bonus,
    adjustment_amount_agorot: adjustment,
    unknown_amount_agorot: sum("UNKNOWN"),
    gross_delivery_per_hour_ex_vat_agorot: perHour(delivery),
    gross_including_extras_per_hour_ex_vat_agorot: perHour(delivery + bonus + adjustment),
    estimated_vehicle_cost_agorot: vehicleCost,
    contribution_per_hour_ex_vat_agorot: vehicleCost != null ? perHour(delivery - vehicleCost) : null,
    delivery_per_km_agorot: hasDeliveries && s.distanceKm ? delivery / s.distanceKm : null,
  };
}

const shiftInclude = {
  links: { include: { record: true } },
  experiments: { include: { experiment: { select: { id: true, title: true } } } },
} as const;

export async function listShiftsWithMetrics() {
  const shifts = await prisma.shift.findMany({ include: shiftInclude, orderBy: [{ date: "desc" }, { startTime: "desc" }] });
  return shifts.map((s) => ({
    ...s,
    metrics: computeShiftMetrics({ ...s, linkedRecords: s.links.map((l) => l.record) }),
  }));
}

/** Shift-level scope: a shift is selected by its own date, not by its linked records. */
export interface ShiftScope {
  from?: string;
  to?: string;
  weekdays?: number[];
}

export function shiftInScope(s: { date: string }, scope: ShiftScope = {}): boolean {
  if (scope.from && s.date < scope.from) return false;
  if (scope.to && s.date > scope.to) return false;
  if (scope.weekdays?.length && !scope.weekdays.includes(weekdayOf(s.date))) return false;
  return true;
}

/**
 * KPIs across logged shifts in `scope`. Shifts without a confirmed DELIVERY record are left
 * out: their delivery income is unknown, not zero.
 */
export async function getShiftKpis(scope: ShiftScope = {}) {
  const shifts = (await listShiftsWithMetrics()).filter((s) => shiftInScope(s, scope));
  const withLinks = shifts.filter((s) => s.metrics.linked_delivery_count > 0);
  const hours = withLinks.reduce((a, s) => a + s.metrics.duration_hours, 0);
  const delivery = withLinks.reduce((a, s) => a + s.metrics.delivery_amount_ex_vat_agorot, 0);
  const withKm = withLinks.filter((s) => s.distanceKm && s.distanceKm > 0);
  const km = withKm.reduce((a, s) => a + (s.distanceKm ?? 0), 0);
  const kmDelivery = withKm.reduce((a, s) => a + s.metrics.delivery_amount_ex_vat_agorot, 0);
  const notes: string[] = [];
  const without = shifts.length - withLinks.length;
  const extrasOnly = shifts.filter((s) => s.metrics.linked_record_count > 0 && s.metrics.linked_delivery_count === 0).length;
  if (without > 0)
    notes.push(
      `${without} משמרות ללא רשומות משלוח מאושרות לא נכללו בחישוב ₪/שעה` +
        (extrasOnly > 0 ? ` (מתוכן ${extrasOnly} עם בונוסים/התאמות בלבד).` : "."),
    );
  return {
    shiftCount: shifts.length,
    shiftsWithoutLinks: without,
    deliveryPerShiftHourAgorot: hours > 0 ? delivery / hours : null,
    deliveryPerKmAgorot: km > 0 ? kmDelivery / km : null,
    notes,
  };
}

/** Minutes after shift end within which a report timestamp is still suggested. */
export const MATCH_TOLERANCE_AFTER_END_MINUTES = 30;

/**
 * Suggested (never auto-linked) records for a shift: report timestamps inside the shift
 * window or up to 30 minutes after it ended. The user must confirm each link.
 */
export async function suggestRecordsForShift(shiftId: string) {
  const shift = await prisma.shift.findUniqueOrThrow({ where: { id: shiftId } });
  const from = `${shift.startedAt}:00`;
  const to = `${addMinutesLocal(shift.endedAt, MATCH_TOLERANCE_AFTER_END_MINUTES)}`;
  const records = await prisma.deliveryRecord.findMany({
    where: { eventTimestampLocal: { gte: from, lte: to } },
    include: { shiftLinks: { include: { shift: { select: { id: true, date: true, startTime: true, endTime: true } } } } },
    orderBy: { eventTimestampLocal: "asc" },
  });
  return records.map((r) => {
    const link = r.shiftLinks[0];
    return {
      record: { ...r, shiftLinks: undefined },
      afterShiftEnd: r.eventTimestampLocal > `${shift.endedAt}:59`,
      linkedToShiftId: link?.shiftId ?? null,
      linkedToThisShift: link?.shiftId === shiftId,
      linkedShift: link?.shift ?? null,
    };
  });
}

export class LinkValidationError extends Error {
  constructor(
    message: string,
    public recordIds: string[],
  ) {
    super(message);
  }
}

export class LinkConflictError extends Error {
  constructor(public conflicts: { recordId: string; shiftId: string }[]) {
    super("חלק מהרשומות כבר משויכות למשמרת אחרת — שיוך כפול אסור. בטל קודם את השיוך הקיים.");
  }
}

/**
 * Links records after explicit confirmation. All-or-nothing; refuses double attribution,
 * unknown records and records outside the suggestion window (shift start → end + 30 min).
 * `allowOutsideWindow` is the explicit override for late payouts (e.g. a weekly bonus).
 */
export async function confirmLinks(shiftId: string, recordIdsInput: string[], opts: { allowOutsideWindow?: boolean } = {}) {
  const recordIds = [...new Set(recordIdsInput)];
  return prisma.$transaction(async (tx) => {
    const shift = await tx.shift.findUniqueOrThrow({ where: { id: shiftId } });
    const records = await tx.deliveryRecord.findMany({ where: { id: { in: recordIds } }, select: { id: true, eventTimestampLocal: true } });
    const found = new Set(records.map((r) => r.id));
    const missing = recordIds.filter((id) => !found.has(id));
    if (missing.length) throw new LinkValidationError("חלק מהרשומות לא נמצאו.", missing);
    if (!opts.allowOutsideWindow) {
      const from = `${shift.startedAt}:00`;
      const to = addMinutesLocal(shift.endedAt, MATCH_TOLERANCE_AFTER_END_MINUTES);
      const outside = records.filter((r) => r.eventTimestampLocal < from || r.eventTimestampLocal > to).map((r) => r.id);
      if (outside.length)
        throw new LinkValidationError(
          `${outside.length} רשומות אינן בחלון המשמרת (עד ${MATCH_TOLERANCE_AFTER_END_MINUTES} דקות אחרי הסיום). לשיוך תשלום מאוחר יש לאשר במפורש חריגה מהחלון.`,
          outside,
        );
    }
    const existing = await tx.shiftRecordLink.findMany({ where: { recordId: { in: recordIds } } });
    const conflicts = existing.filter((l) => l.shiftId !== shiftId);
    if (conflicts.length) throw new LinkConflictError(conflicts.map((c) => ({ recordId: c.recordId, shiftId: c.shiftId })));
    const already = new Set(existing.map((l) => l.recordId));
    const toCreate = recordIds.filter((id) => !already.has(id));
    for (const recordId of toCreate) {
      await tx.shiftRecordLink.create({ data: { shiftId, recordId, manuallyConfirmed: true } });
    }
    return { linked: toCreate.length, alreadyLinked: already.size };
  });
}

export async function unlinkRecords(shiftId: string, recordIds: string[]) {
  const r = await prisma.shiftRecordLink.deleteMany({ where: { shiftId, recordId: { in: recordIds } } });
  return { unlinked: r.count };
}
