import { z } from "zod";

export const RECORD_TYPES = ["DELIVERY", "BONUS", "ADJUSTMENT", "UNKNOWN"] as const;
export type RecordType = (typeof RECORD_TYPES)[number];
export const recordTypeSchema = z.enum(RECORD_TYPES);

export const RECORD_TYPE_LABELS: Record<RecordType, string> = {
  DELIVERY: "משלוח / מקבץ",
  BONUS: "בונוס",
  ADJUSTMENT: "תוספת",
  UNKNOWN: "לא מסווג",
};

export const EXPERIMENT_STATUSES = ["PLANNED", "RUNNING", "COMPLETE"] as const;
export const experimentStatusSchema = z.enum(EXPERIMENT_STATUSES);
export const EXPERIMENT_STATUS_LABELS: Record<(typeof EXPERIMENT_STATUSES)[number], string> = {
  PLANNED: "מתוכנן",
  RUNNING: "פעיל",
  COMPLETE: "הסתיים",
};

export const EXPERIMENT_GROUPS = ["CONTROL", "TEST"] as const;
export type ExperimentGroup = (typeof EXPERIMENT_GROUPS)[number];
export const experimentGroupSchema = z.enum(EXPERIMENT_GROUPS);
export const EXPERIMENT_GROUP_LABELS: Record<ExperimentGroup, string> = {
  CONTROL: "ביקורת",
  TEST: "ניסוי",
};

export const TARGET_METRICS = {
  DELIVERY_PER_SHIFT_HOUR_EX_EXTRAS: "₪ משלוחים לשעת משמרת (ללא בונוסים ותוספות, ללא מע״מ)",
} as const;

export const WEEKDAY_LABELS = ["ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת"];
