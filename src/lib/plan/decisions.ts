import { prisma } from "@/lib/db";
import { shiftSummary, type ShiftSummary } from "@/lib/chains/economics";
import { getSettings } from "@/lib/settings";
import { TIME_ZONE } from "@/lib/time";

/**
 * The four courier decisions (when to work, where to wait, which offers, when to stop), from the courier's own
 * history. Same rules as the phone app (core/Insights.kt): every item carries an evidence label; nothing is called
 * better until the pattern repeated on enough shifts. Descriptive only — no live recommendation.
 */

export type Evidence = "MEASURED" | "REPEATED" | "TESTED" | "HYPOTHESIS" | "NOT_ENOUGH_DATA" | "ESTIMATE";

export const MIN_SHIFTS_REPEATED = 5;

export interface WorkShift {
  id: string;
  startMs: number;
  endMs: number;
  summary: ShiftSummary;
  precise: boolean;
}

/** Work shifts (≥ evidenceMinWorkShiftMinutes) with shift facts, newest first; short/test sessions are separate. */
export async function workShifts(): Promise<{ work: WorkShift[]; short: { id: string; startMs: number; endMs: number }[]; costPerKmAgorot: number | null }> {
  const settings = await getSettings();
  const cost = settings.vehicleCostPerKmAgorot;
  const [rows, tracked] = await Promise.all([
    prisma.collectorShift.findMany({ orderBy: { startedAtUtc: "desc" } }),
    prisma.collectorTrackPoint.groupBy({ by: ["shiftId"], _count: true }),
  ]);
  const precise = new Set(tracked.filter((t) => t._count >= 2).map((t) => t.shiftId));
  const end = (s: (typeof rows)[number]) => (s.endedAtUtc ?? s.factsEndedAtUtc ?? s.startedAtUtc).getTime();
  const work: WorkShift[] = [];
  const short: { id: string; startMs: number; endMs: number }[] = [];
  for (const s of rows) {
    const minutes = (end(s) - s.startedAtUtc.getTime()) / 60_000;
    if (minutes < settings.evidenceMinWorkShiftMinutes || !s.factsEndedAtUtc) {
      short.push({ id: s.id, startMs: s.startedAtUtc.getTime(), endMs: end(s) });
      continue;
    }
    work.push({
      id: s.id,
      startMs: s.startedAtUtc.getTime(),
      endMs: end(s),
      precise: precise.has(s.id),
      summary: shiftSummary(
        {
          shiftId: s.id,
          startMs: s.startedAtUtc.getTime(),
          endMs: end(s),
          revenueAgorot: s.revenueAgorot ?? 0,
          completionSlides: s.completionSlides ?? 0,
          chainMsLow: s.chainMsLow ?? 0,
          chainMsHigh: s.chainMsHigh ?? 0,
          verifiedIdleMs: s.verifiedIdleMs ?? 0,
          onlineUnverifiedMs: s.onlineUnverifiedMs ?? 0,
          offlineMs: s.offlineMs ?? 0,
          unknownMs: s.unknownMs ?? 0,
          gpsPathKm: s.gpsPathKm,
          gpsSpeedKm: s.gpsSpeedKm,
          gpsDistanceReliable: s.gpsDistanceReliable ?? false,
          missing: s.factsMissingJson ? (JSON.parse(s.factsMissingJson) as string[]) : [],
        },
        cost,
      ),
    });
  }
  return { work, short, costPerKmAgorot: cost };
}

// ---- when to work ----

const DAYS = ["א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳"];

function local(ms: number) {
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone: TIME_ZONE, weekday: "short", hour: "2-digit", hour12: false }).formatToParts(new Date(ms));
  const wd = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].indexOf(parts.find((p) => p.type === "weekday")!.value);
  return { weekday: wd, hour: Number(parts.find((p) => p.type === "hour")!.value) % 24 };
}

export const hourBand = (h: number) => (h < 6 ? "00-06" : h < 11 ? "06-11" : h < 15 ? "11-15" : h < 18 ? "15-18" : h < 21 ? "18-21" : "21-24");

export interface TimeWindow {
  label: string;
  shifts: number;
  /** Midpoint net per shift hour of each shift (agorot). */
  values: number[];
  mean: number;
  low: number;
  high: number;
  evidence: Evidence;
}

export function timeWindows(work: WorkShift[]): TimeWindow[] {
  const groups = new Map<string, number[]>();
  for (const w of work) {
    const n = w.summary.netPerShiftHourAgorot;
    if (!n) continue;
    const l = local(w.startMs);
    // The hour range is isolated (LRI…PDI) so it reads left-to-right inside Hebrew text.
    const key = `${DAYS[l.weekday]} \u2066${hourBand(l.hour)}\u2069`;
    groups.set(key, [...(groups.get(key) ?? []), (n.low + n.high) / 2]);
  }
  return [...groups.entries()]
    .map(([label, values]) => ({
      label,
      shifts: values.length,
      values,
      mean: values.reduce((a, b) => a + b, 0) / values.length,
      low: Math.min(...values),
      high: Math.max(...values),
      evidence: (values.length >= MIN_SHIFTS_REPEATED ? "REPEATED" : "HYPOTHESIS") as Evidence,
    }))
    .sort((a, b) => b.mean - a.mean);
}

// ---- where to wait ----

export interface WaitSpot {
  cellId: string;
  waits: number;
  shifts: number;
  medianMinutes: number;
  evidence: Evidence;
}

/** A wait = from the shift start or a chain end until the next offer card, placed where it began. */
export async function waitSpots(work: WorkShift[]): Promise<WaitSpot[]> {
  if (!work.length) return [];
  const from = new Date(Math.min(...work.map((w) => w.startMs)));
  const [chains, offers, samples, track] = await Promise.all([
    prisma.collectorChain.findMany({ where: { startLatestAtUtc: { gte: from } } }),
    prisma.offer.findMany({ where: { firstSeenAtUtc: { gte: from } }, select: { firstSeenAtUtc: true } }),
    prisma.locationSample.findMany({ where: { observedAtUtc: { gte: from }, status: "OK" }, select: { observedAtUtc: true, cellId: true } }),
    prisma.collectorTrackPoint.findMany({ where: { atUtc: { gte: from } }, select: { atUtc: true, lat: true, lon: true } }),
  ]);
  const { cellOf } = await import("./cells");
  const points = [
    ...samples.filter((s) => s.cellId).map((s) => ({ t: s.observedAtUtc.getTime(), cell: s.cellId! })),
    ...track.map((p) => ({ t: p.atUtc.getTime(), cell: cellOf(p.lat, p.lon) })),
  ].sort((a, b) => a.t - b.t);
  const seen = offers.map((o) => o.firstSeenAtUtc.getTime()).sort((a, b) => a - b);
  const waits: { cell: string; shift: string; minutes: number }[] = [];
  for (const w of work) {
    const starts = [w.startMs, ...chains.filter((c) => c.startLatestAtUtc.getTime() >= w.startMs && c.startLatestAtUtc.getTime() <= w.endMs && c.endLatestAtUtc).map((c) => c.endLatestAtUtc!.getTime())];
    for (const s of starts) {
      const next = seen.find((t) => t > s && t <= w.endMs);
      if (next === undefined) continue;
      const near = points.filter((p) => Math.abs(p.t - s) <= 120_000).sort((a, b) => Math.abs(a.t - s) - Math.abs(b.t - s))[0];
      if (!near) continue;
      waits.push({ cell: near.cell, shift: w.id, minutes: (next - s) / 60_000 });
    }
  }
  const byCell = new Map<string, typeof waits>();
  for (const x of waits) byCell.set(x.cell, [...(byCell.get(x.cell) ?? []), x]);
  return [...byCell.entries()]
    .map(([cellId, xs]) => {
      const m = xs.map((x) => x.minutes).sort((a, b) => a - b);
      const shifts = new Set(xs.map((x) => x.shift)).size;
      return { cellId, waits: xs.length, shifts, medianMinutes: m[Math.floor(m.length / 2)], evidence: (xs.length >= 5 && shifts >= 2 ? "REPEATED" : "HYPOTHESIS") as Evidence };
    })
    .sort((a, b) => b.waits - a.waits);
}

// ---- when to stop ----

export interface ShiftHour {
  index: number;
  shifts: number;
  revenuePerHour: number[];
  mean: number;
  evidence: Evidence;
}

/**
 * Accepted-offer revenue by hour of the shift, before vehicle cost. Each accepted offer counts in the hour it was
 * accepted — an offer added to an active route counts when it was added, not when its chain began.
 */
export async function byShiftHour(work: WorkShift[]): Promise<ShiftHour[]> {
  if (!work.length) return [];
  const trips = await prisma.collectorTrip.findMany({ where: { startedAtUtc: { gte: new Date(Math.min(...work.map((w) => w.startMs))) } } });
  const accepted = trips.map((t) => ({ t: t.startedAtUtc.getTime(), amount: (JSON.parse(t.offerAmountsJson) as number[]).reduce((a, b) => a + b, 0) }));
  const rows = new Map<number, number[]>();
  for (const w of work) {
    const hours = Math.floor((w.endMs - w.startMs) / 3_600_000);
    for (let i = 0; i < hours; i++) {
      const a = w.startMs + i * 3_600_000;
      const rev = accepted.filter((x) => x.t >= a && x.t < a + 3_600_000).reduce((s, x) => s + x.amount, 0);
      rows.set(i, [...(rows.get(i) ?? []), rev]);
    }
  }
  return [...rows.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([i, xs]) => ({ index: i + 1, shifts: xs.length, revenuePerHour: xs, mean: xs.reduce((a, b) => a + b, 0) / xs.length, evidence: (xs.length >= MIN_SHIFTS_REPEATED ? "REPEATED" : "HYPOTHESIS") as Evidence }));
}
