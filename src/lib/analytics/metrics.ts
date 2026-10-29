import { prisma } from "@/lib/db";
import { WEEKDAY_LABELS } from "@/lib/types";
import { formatDateHe } from "@/lib/time";
import { buildRecordWhere, type Filters } from "./filters";
import { summarize } from "./stats";
import { getShiftKpis } from "./shifts";

export interface Overview {
  delivery_income_ex_vat_agorot: number;
  delivery_record_count: number;
  avg_amount_per_record_agorot: number | null;
  median_amount_per_record_agorot: number | null;
  bonus_total_agorot: number;
  bonus_record_count: number;
  adjustment_total_agorot: number;
  adjustment_record_count: number;
  unknown_record_count: number;
  all_rows_total_agorot: number;
  all_rows_count: number;
  bundle_record_count: number;
  shifts_logged_count: number;
  delivery_per_shift_hour_agorot: number | null;
  delivery_per_km_agorot: number | null;
  shifts_without_links_count: number;
  notes: string[];
}

/** KPI definitions are fixed by type; the type filter itself is ignored here. */
export async function getOverview(filters: Filters): Promise<Overview> {
  const rows = await prisma.deliveryRecord.findMany({
    where: buildRecordWhere(filters, null),
    select: { type: true, amountAgorot: true, isBundle: true },
  });
  const deliveries = rows.filter((r) => r.type === "DELIVERY");
  const s = summarize(deliveries.map((r) => r.amountAgorot));
  const sumOf = (t: string) => rows.filter((r) => r.type === t).reduce((a, r) => a + r.amountAgorot, 0);
  const shift = await getShiftKpis();
  return {
    delivery_income_ex_vat_agorot: s.sum,
    delivery_record_count: s.n,
    avg_amount_per_record_agorot: s.mean,
    median_amount_per_record_agorot: s.median,
    bonus_total_agorot: sumOf("BONUS"),
    bonus_record_count: rows.filter((r) => r.type === "BONUS").length,
    adjustment_total_agorot: sumOf("ADJUSTMENT"),
    adjustment_record_count: rows.filter((r) => r.type === "ADJUSTMENT").length,
    unknown_record_count: rows.filter((r) => r.type === "UNKNOWN").length,
    all_rows_total_agorot: rows.reduce((a, r) => a + r.amountAgorot, 0),
    all_rows_count: rows.length,
    bundle_record_count: deliveries.filter((r) => r.isBundle).length,
    shifts_logged_count: shift.shiftCount,
    delivery_per_shift_hour_agorot: shift.deliveryPerShiftHourAgorot,
    delivery_per_km_agorot: shift.deliveryPerKmAgorot,
    shifts_without_links_count: shift.shiftsWithoutLinks,
    notes: shift.notes,
  };
}

export type GroupBy = "hour" | "weekday" | "date" | "merchant";

export interface Bucket {
  key: string;
  label: string;
  n_records: number;
  sum_agorot: number;
  avg_amount_per_record_agorot: number | null;
  median_amount_per_record_agorot: number | null;
}

export interface BucketsResult {
  groupBy: GroupBy;
  window_minutes: number;
  record_types: string[];
  overall_n_records: number;
  overall_avg_amount_per_record_agorot: number | null;
  buckets: Bucket[];
  /** merchant only: multi-merchant records, kept as one category and never split. */
  bundles: Bucket | null;
}

const pad = (n: number) => String(n).padStart(2, "0");

export function hourBucketKey(eventTime: string, windowMinutes: number): { key: string; label: string } {
  const [h, m] = eventTime.split(":").map(Number);
  const start = Math.floor((h * 60 + m) / windowMinutes) * windowMinutes;
  const end = Math.min(start + windowMinutes, 24 * 60);
  const fmt = (x: number) => `${pad(Math.floor(x / 60) % 24)}:${pad(x % 60)}`;
  return { key: fmt(start), label: `${fmt(start)}–${fmt(end)}` };
}

function toBucket(key: string, label: string, values: number[]): Bucket {
  const s = summarize(values);
  return {
    key,
    label,
    n_records: s.n,
    sum_agorot: s.sum,
    avg_amount_per_record_agorot: s.mean,
    median_amount_per_record_agorot: s.median,
  };
}

/** Default record type for revenue charts is DELIVERY only (no bonuses/adjustments/tips). */
export async function getBuckets(groupBy: GroupBy, filters: Filters, windowMinutes = 60): Promise<BucketsResult> {
  const types = filters.types?.length ? filters.types : (["DELIVERY"] as const);
  const rows = await prisma.deliveryRecord.findMany({
    where: buildRecordWhere(filters, [...types]),
    select: { eventDate: true, eventTime: true, weekday: true, merchantRaw: true, isBundle: true, amountAgorot: true },
  });
  const groups = new Map<string, { label: string; values: number[] }>();
  const add = (key: string, label: string, v: number) => {
    if (!groups.has(key)) groups.set(key, { label, values: [] });
    groups.get(key)!.values.push(v);
  };
  const bundleValues: number[] = [];
  for (const r of rows) {
    if (groupBy === "hour") {
      const b = hourBucketKey(r.eventTime, windowMinutes);
      add(b.key, b.label, r.amountAgorot);
    } else if (groupBy === "weekday") add(String(r.weekday), WEEKDAY_LABELS[r.weekday], r.amountAgorot);
    else if (groupBy === "date") add(r.eventDate, formatDateHe(r.eventDate), r.amountAgorot);
    else if (r.isBundle) bundleValues.push(r.amountAgorot);
    else add(r.merchantRaw.trim(), r.merchantRaw.trim(), r.amountAgorot);
  }
  let buckets = [...groups.entries()].map(([k, g]) => toBucket(k, g.label, g.values));
  if (groupBy === "merchant") buckets.sort((a, b) => b.sum_agorot - a.sum_agorot || b.n_records - a.n_records);
  else buckets = buckets.sort((a, b) => (groupBy === "weekday" ? Number(a.key) - Number(b.key) : a.key.localeCompare(b.key)));
  const overall = summarize(rows.map((r) => r.amountAgorot));
  return {
    groupBy,
    window_minutes: windowMinutes,
    record_types: [...types],
    overall_n_records: overall.n,
    overall_avg_amount_per_record_agorot: overall.mean,
    buckets,
    bundles: groupBy === "merchant" ? toBucket("__bundles__", "מקבצים (כמה בתי עסק)", bundleValues) : null,
  };
}
