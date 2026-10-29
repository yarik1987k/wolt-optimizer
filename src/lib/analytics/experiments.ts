import { prisma } from "@/lib/db";
import { getSettings } from "@/lib/settings";
import { weekdayOf } from "@/lib/time";
import { WEEKDAY_LABELS, type ExperimentGroup } from "@/lib/types";
import { computeShiftMetrics } from "./shifts";

export interface ShiftForComparison {
  shiftId: string;
  durationHours: number;
  deliveryAgorot: number;
  vehicleCostAgorot: number | null;
  /** true when the shift has at least one confirmed DELIVERY record. */
  hasLinkedRecords: boolean;
  // Optional shift conditions, used only for comparability notes.
  date?: string;
  startTime?: string;
  areaText?: string;
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
  /** Per-shift ₪/hour spread (descriptive). sd is null with fewer than 2 usable shifts. */
  per_shift_rate_min_agorot: number | null;
  per_shift_rate_max_agorot: number | null;
  per_shift_rate_sd_agorot: number | null;
  /** Standard error of delivery_per_hour (ratio estimator); null with fewer than 2 usable shifts. */
  delivery_per_hour_se_agorot: number | null;
}

export interface Comparison {
  control: GroupStats;
  test: GroupStats;
  pct_diff_delivery_per_hour: number | null;
  pct_diff_contribution_per_hour: number | null;
  /** ≈95% band (±2 standard errors) of the difference, relative to control. Descriptive only. */
  diff_uncertainty_pct: number | null;
  target_improvement_pct: number;
  min_shifts_per_group: number;
  /** Always descriptive: shifts are not randomized, so no causal claim is made. */
  analysis_type: "DESCRIPTIVE";
  can_determine: boolean;
  verdict: string;
  /** Blocking: any warning means no verdict on improvement. */
  warnings: string[];
  /** Non-blocking: differences in conditions between the groups (may be the tested variable itself). */
  comparability_notes: string[];
}

function groupStats(shifts: ShiftForComparison[]): GroupStats {
  const usable = shifts.filter((s) => s.hasLinkedRecords && s.durationHours > 0);
  const hours = usable.reduce((a, s) => a + s.durationHours, 0);
  const delivery = usable.reduce((a, s) => a + s.deliveryAgorot, 0);
  const allCosts = usable.length > 0 && usable.every((s) => s.vehicleCostAgorot != null);
  const cost = usable.reduce((a, s) => a + (s.vehicleCostAgorot ?? 0), 0);
  const rates = usable.map((s) => s.deliveryAgorot / s.durationHours);
  const n = usable.length;
  const rate = hours > 0 ? delivery / hours : null;
  let sd: number | null = null;
  let se: number | null = null;
  if (n >= 2 && rate != null) {
    const mean = rates.reduce((a, r) => a + r, 0) / n;
    sd = Math.sqrt(rates.reduce((a, r) => a + (r - mean) ** 2, 0) / (n - 1));
    // SE of a ratio estimator sum(d)/sum(h): residuals d_i - R*h_i, scaled by mean hours.
    const resid = usable.reduce((a, s) => a + (s.deliveryAgorot - rate * s.durationHours) ** 2, 0);
    se = Math.sqrt(resid / (n * (n - 1))) / (hours / n);
  }
  return {
    shift_count: shifts.length,
    shifts_missing_records: shifts.length - usable.length,
    total_hours: hours,
    total_delivery_ex_vat_agorot: delivery,
    delivery_per_hour_agorot: rate,
    contribution_per_hour_agorot: allCosts && hours > 0 ? (delivery - cost) / hours : null,
    per_shift_rate_min_agorot: n ? Math.min(...rates) : null,
    per_shift_rate_max_agorot: n ? Math.max(...rates) : null,
    per_shift_rate_sd_agorot: sd,
    delivery_per_hour_se_agorot: se,
  };
}

const avg = (xs: number[]) => xs.reduce((a, x) => a + x, 0) / xs.length;
const startHour = (t: string) => {
  const [h, m] = t.split(":").map(Number);
  return h + m / 60;
};

/** Non-blocking notes on how the two groups' conditions differ. */
export function comparabilityNotes(control: ShiftForComparison[], test: ShiftForComparison[]): string[] {
  const c = control.filter((s) => s.hasLinkedRecords && s.durationHours > 0);
  const t = test.filter((s) => s.hasLinkedRecords && s.durationHours > 0);
  if (!c.length || !t.length) return [];
  const notes: string[] = [];
  const suffix = " אם זה המשתנה הנבדק — זה צפוי; אחרת ההשוואה פחות הוגנת.";
  const areas = (g: ShiftForComparison[]) => new Set(g.map((s) => s.areaText?.trim()).filter((a): a is string => !!a));
  const ca = areas(c);
  const ta = areas(t);
  if (ca.size && ta.size && ![...ta].some((a) => ca.has(a)))
    notes.push(`אזורי העבודה שונים בין הקבוצות (ביקורת: ${[...ca].join(", ")}; ניסוי: ${[...ta].join(", ")}).${suffix}`);
  const days = (g: ShiftForComparison[]) => new Set(g.filter((s) => s.date).map((s) => weekdayOf(s.date!)));
  const cd = days(c);
  const td = days(t);
  if (cd.size && td.size && ![...td].some((d) => cd.has(d)))
    notes.push(
      `אין יום בשבוע משותף לקבוצות (ביקורת: ${[...cd].map((d) => WEEKDAY_LABELS[d]).join(", ")}; ניסוי: ${[...td].map((d) => WEEKDAY_LABELS[d]).join(", ")}).${suffix}`,
    );
  const starts = (g: ShiftForComparison[]) => g.filter((s) => s.startTime).map((s) => startHour(s.startTime!));
  const cs = starts(c);
  const ts = starts(t);
  if (cs.length && ts.length && Math.abs(avg(cs) - avg(ts)) >= 2)
    notes.push(`שעת ההתחלה הממוצעת שונה ביותר משעתיים בין הקבוצות.${suffix}`);
  const ch = avg(c.map((s) => s.durationHours));
  const th = avg(t.map((s) => s.durationHours));
  if (Math.abs(ch - th) >= 1) notes.push(`אורך המשמרת הממוצע שונה (ביקורת ${ch.toFixed(1)} ש׳, ניסוי ${th.toFixed(1)} ש׳).${suffix}`);
  return notes;
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
  // Variation between shifts can't be estimated from a single shift, whatever the setting says.
  const minShifts = Math.max(opts.minShiftsPerGroup, 2);
  if (usableC < minShifts || usableT < minShifts)
    warnings.push(`נדרשות לפחות ${minShifts} משמרות מתועדות בכל קבוצה (כרגע: ביקורת ${usableC}, ניסוי ${usableT}).`);
  if (c.delivery_per_hour_agorot != null && c.delivery_per_hour_agorot <= 0) warnings.push("הכנסה לשעה בקבוצת הביקורת היא 0 — אין בסיס להשוואה.");

  let uncertainty: number | null = null;
  if (c.delivery_per_hour_se_agorot != null && t.delivery_per_hour_se_agorot != null && c.delivery_per_hour_agorot)
    uncertainty =
      Math.round(((2 * Math.hypot(c.delivery_per_hour_se_agorot, t.delivery_per_hour_se_agorot)) / c.delivery_per_hour_agorot) * 100 * 100) / 100;
  if (warnings.length === 0 && diff != null && uncertainty != null && Math.abs(diff) <= uncertainty)
    warnings.push(
      `הפער (${diff.toFixed(1)}%) נמצא בתוך טווח השונות בין המשמרות (±${uncertainty.toFixed(1)}%) — אי אפשר להבחין בינו לבין שונות רגילה. נדרשות משמרות נוספות.`,
    );

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
    diff_uncertainty_pct: uncertainty,
    target_improvement_pct: target,
    min_shifts_per_group: opts.minShiftsPerGroup,
    analysis_type: "DESCRIPTIVE",
    can_determine: canDetermine,
    verdict,
    warnings,
    comparability_notes: comparabilityNotes(control, test),
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
      hasLinkedRecords: m.linked_delivery_count > 0,
      date: es.shift.date,
      startTime: es.shift.startTime,
      areaText: es.shift.areaText,
    });
  }
  return {
    experiment: exp,
    comparison: compareGroups(byGroup.CONTROL, byGroup.TEST, { minShiftsPerGroup: settings.minShiftsPerGroup }),
  };
}
