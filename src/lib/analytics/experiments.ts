import { prisma } from "@/lib/db";
import { getSettings } from "@/lib/settings";
import type { ExperimentGroup } from "@/lib/types";
import { computeShiftMetrics } from "./shifts";

export interface ShiftForComparison {
  shiftId: string;
  durationHours: number;
  deliveryAgorot: number;
  vehicleCostAgorot: number | null;
  hasLinkedRecords: boolean;
}

export interface GroupStats {
  shift_count: number;
  shifts_missing_records: number;
  total_hours: number;
  total_delivery_ex_vat_agorot: number;
  /** sum(income) / sum(hours) — not a mean of per-shift rates. */
  delivery_per_hour_agorot: number | null;
  /** null unless every shift in the group has both km and cost per km. */
  contribution_per_hour_agorot: number | null;
}

export interface Comparison {
  control: GroupStats;
  test: GroupStats;
  pct_diff_delivery_per_hour: number | null;
  pct_diff_contribution_per_hour: number | null;
  target_improvement_pct: number;
  min_shifts_per_group: number;
  can_determine: boolean;
  verdict: string;
  warnings: string[];
}

function groupStats(shifts: ShiftForComparison[]): GroupStats {
  const usable = shifts.filter((s) => s.hasLinkedRecords && s.durationHours > 0);
  const hours = usable.reduce((a, s) => a + s.durationHours, 0);
  const delivery = usable.reduce((a, s) => a + s.deliveryAgorot, 0);
  const allCosts = usable.length > 0 && usable.every((s) => s.vehicleCostAgorot != null);
  const cost = usable.reduce((a, s) => a + (s.vehicleCostAgorot ?? 0), 0);
  return {
    shift_count: shifts.length,
    shifts_missing_records: shifts.length - usable.length,
    total_hours: hours,
    total_delivery_ex_vat_agorot: delivery,
    delivery_per_hour_agorot: hours > 0 ? delivery / hours : null,
    contribution_per_hour_agorot: allCosts && hours > 0 ? (delivery - cost) / hours : null,
  };
}

function pctDiff(test: number | null, control: number | null): number | null {
  if (test == null || control == null || control <= 0) return null;
  return Math.round((test / control - 1) * 100 * 100) / 100;
}

export function compareGroups(
  control: ShiftForComparison[],
  test: ShiftForComparison[],
  opts: { minShiftsPerGroup: number; targetImprovementPct?: number } = { minShiftsPerGroup: 3 },
): Comparison {
  const target = opts.targetImprovementPct ?? 10;
  const c = groupStats(control);
  const t = groupStats(test);
  const diff = pctDiff(t.delivery_per_hour_agorot, c.delivery_per_hour_agorot);
  const warnings: string[] = [];
  if (c.shifts_missing_records || t.shifts_missing_records)
    warnings.push("יש משמרות ללא רשומות מאושרות או ללא משך — הן לא נכללו בחישוב.");
  const usableC = c.shift_count - c.shifts_missing_records;
  const usableT = t.shift_count - t.shifts_missing_records;
  if (usableC < opts.minShiftsPerGroup || usableT < opts.minShiftsPerGroup)
    warnings.push(`נדרשות לפחות ${opts.minShiftsPerGroup} משמרות מתועדות בכל קבוצה (כרגע: ביקורת ${usableC}, ניסוי ${usableT}).`);
  if (c.delivery_per_hour_agorot != null && c.delivery_per_hour_agorot <= 0) warnings.push("הכנסה לשעה בקבוצת הביקורת היא 0 — אין בסיס להשוואה.");

  const canDetermine = warnings.length === 0 && diff != null;
  let verdict: string;
  if (!canDetermine) verdict = "לא ניתן לקבוע שיפור";
  else if (diff! >= target)
    verdict = `אינדיקציה לשיפור של ${diff!.toFixed(1)}% (יעד: ${target}%). זו אינדיקציה בלבד ולא הוכחה סיבתית — כדאי להמשיך לתעד משמרות בתנאים דומים.`;
  else if (diff! > 0) verdict = `שיפור של ${diff!.toFixed(1)}% — מתחת ליעד של ${target}%.`;
  else verdict = `לא נמצא שיפור (${diff!.toFixed(1)}%).`;

  return {
    control: c,
    test: t,
    pct_diff_delivery_per_hour: diff,
    pct_diff_contribution_per_hour: pctDiff(t.contribution_per_hour_agorot, c.contribution_per_hour_agorot),
    target_improvement_pct: target,
    min_shifts_per_group: opts.minShiftsPerGroup,
    can_determine: canDetermine,
    verdict,
    warnings,
  };
}

export async function getExperimentWithComparison(id: string) {
  const exp = await prisma.experiment.findUniqueOrThrow({
    where: { id },
    include: { insight: true, shifts: { include: { shift: { include: { links: { include: { record: true } } } } } } },
  });
  const settings = await getSettings();
  const byGroup: Record<ExperimentGroup, ShiftForComparison[]> = { CONTROL: [], TEST: [] };
  for (const es of exp.shifts) {
    const m = computeShiftMetrics({ ...es.shift, linkedRecords: es.shift.links.map((l) => l.record) });
    byGroup[es.group as ExperimentGroup].push({
      shiftId: es.shiftId,
      durationHours: m.duration_hours,
      deliveryAgorot: m.delivery_amount_ex_vat_agorot,
      vehicleCostAgorot: m.estimated_vehicle_cost_agorot,
      hasLinkedRecords: m.linked_record_count > 0,
    });
  }
  return {
    experiment: exp,
    comparison: compareGroups(byGroup.CONTROL, byGroup.TEST, { minShiftsPerGroup: settings.minShiftsPerGroup }),
  };
}
