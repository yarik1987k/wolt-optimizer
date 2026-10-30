/**
 * Shift Revenue Simulator. Revenue = NumberOfDeliveries × AveragePayment.
 * Pure arithmetic in agorot. All money inputs must share ONE basis (ex-VAT from reports, or
 * as-displayed offer prices); the result is labeled with that basis and never mixes the two.
 */
export type AmountBasis = "EX_VAT" | "AS_DISPLAYED";

export const BASIS_LABEL: Record<AmountBasis, string> = {
  EX_VAT: "ללא מע״מ (כמו בדוחות התשלום)",
  AS_DISPLAYED: "כפי שמוצג בהצעה (לא ידוע אם כולל מע״מ)",
};

export interface SimulationInput {
  basis: AmountBasis;
  deliveries: number;
  avgPaymentAgorot: number;
  shiftHours: number;
  targetAgorot: number; // same basis as avgPayment
  avgMinutesPerDelivery: number | null; // null = unknown
  avgKmPerDelivery: number | null; // null = unknown
  vehicleCostPerKmAgorot: number | null; // null = unknown
}

export type Feasibility = "MATH_ONLY" | "FITS_IN_SHIFT" | "EXCEEDS_SHIFT";

export interface SimulationResult {
  basis: AmountBasis;
  gross_agorot: number;
  gross_per_hour_agorot: number | null;
  estimated_vehicle_cost_agorot: number | null;
  /** Gross minus vehicle cost. Not net profit: no income tax, insurance, fixed costs. */
  contribution_agorot: number | null;
  contribution_per_hour_agorot: number | null;
  gap_to_target_agorot: number; // > 0: below target
  target_reached: boolean;
  deliveries_needed_at_this_avg: number | null;
  avg_needed_at_this_count_agorot: number | null;
  feasibility: Feasibility;
  required_minutes: number | null;
  available_minutes: number;
  time_utilization: number | null;
  notes: string[];
}

export class SimulationInputError extends Error {}

export function simulateShift(i: SimulationInput): SimulationResult {
  const bad = (cond: boolean, msg: string) => {
    if (cond) throw new SimulationInputError(msg);
  };
  bad(!Number.isFinite(i.deliveries) || i.deliveries < 0, "מספר משלוחים חייב להיות 0 ומעלה");
  bad(!Number.isFinite(i.avgPaymentAgorot) || i.avgPaymentAgorot < 0, "תשלום ממוצע חייב להיות 0 ומעלה");
  bad(!Number.isFinite(i.shiftHours) || i.shiftHours <= 0, "משך משמרת חייב להיות גדול מ-0");
  bad(i.avgMinutesPerDelivery !== null && !(i.avgMinutesPerDelivery > 0), "זמן ממוצע למשלוח חייב להיות גדול מ-0");
  bad(i.avgKmPerDelivery !== null && !(i.avgKmPerDelivery >= 0), "ק״מ למשלוח לא יכול להיות שלילי");
  bad(i.vehicleCostPerKmAgorot !== null && !(i.vehicleCostPerKmAgorot >= 0), "עלות לק״מ לא יכולה להיות שלילית");

  const gross = Math.round(i.deliveries * i.avgPaymentAgorot);
  const vehicle =
    i.avgKmPerDelivery !== null && i.vehicleCostPerKmAgorot !== null
      ? Math.round(i.deliveries * i.avgKmPerDelivery * i.vehicleCostPerKmAgorot)
      : null;
  const contribution = vehicle === null ? null : gross - vehicle;
  const available = Math.round(i.shiftHours * 60);
  const required = i.avgMinutesPerDelivery === null ? null : i.deliveries * i.avgMinutesPerDelivery;
  const feasibility: Feasibility = required === null ? "MATH_ONLY" : required <= available ? "FITS_IN_SHIFT" : "EXCEEDS_SHIFT";

  const notes: string[] = [];
  if (feasibility === "MATH_ONLY") notes.push("זמן ממוצע למשלוח לא ידוע — זו סימולציה מתמטית בלבד, לא תרחיש שהוכח כאפשרי בפועל.");
  if (vehicle === null) notes.push("ללא ק״מ למשלוח ועלות לק״מ — עלות הרכב לא חושבה.");
  if (i.basis === "AS_DISPLAYED") notes.push("סכומי ההצעות מוצגים כפי שמופיעים באפליקציה; לא ידוע אם כוללים מע״מ, ולכן אין להשוות אותם ישירות לדוחות (ללא מע״מ).");

  return {
    basis: i.basis,
    gross_agorot: gross,
    gross_per_hour_agorot: gross / i.shiftHours,
    estimated_vehicle_cost_agorot: vehicle,
    contribution_agorot: contribution,
    contribution_per_hour_agorot: contribution === null ? null : contribution / i.shiftHours,
    gap_to_target_agorot: i.targetAgorot - gross,
    target_reached: gross >= i.targetAgorot,
    deliveries_needed_at_this_avg: i.avgPaymentAgorot > 0 ? Math.ceil(i.targetAgorot / i.avgPaymentAgorot) : null,
    avg_needed_at_this_count_agorot: i.deliveries > 0 ? Math.ceil(i.targetAgorot / i.deliveries) : null,
    feasibility,
    required_minutes: required,
    available_minutes: available,
    time_utilization: required === null ? null : required / available,
    notes,
  };
}
