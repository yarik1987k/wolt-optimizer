/**
 * Hotspot explorer (UI v2): per-area metrics for a chosen time window, with an explicit split of
 * time into states. Pure functions; no recommendations are produced here.
 *
 * Time states (spec §6) — each stretch between two location samples of a shift (≤ 6 min) gets ONE state:
 *  DELIVERY            inside a detected trip, or the last state seen was a delivery screen;
 *  AVAILABLE_VERIFIED  last state AVAILABLE / OFFER_PRESENTED with evidence OBSERVED;
 *  AVAILABLE_ESTIMATED same, but evidence ESTIMATED/UNKNOWN (e.g. the unproven status-button rule);
 *  OFFLINE             last state OFFLINE (any evidence);
 *  UNKNOWN             nothing known.
 * "Measured location time" is independent: the stretch had a valid fix (status OK).
 * Rates use VERIFIED available time only — never GPS time.
 */
import {
  AVAILABILITY_CAP_MS,
  linkToCell,
  shiftWindows,
  UNKNOWN_CELL,
  type HotspotInput,
  type HotspotSample,
} from "./analysis";
import { poissonRate, type Interval } from "./stats";
import { jerusalemParts } from "@/lib/offers/analytics";

export type TimeState = "AVAILABLE_VERIFIED" | "AVAILABLE_ESTIMATED" | "DELIVERY" | "OFFLINE" | "UNKNOWN";
export const TIME_STATES: TimeState[] = ["AVAILABLE_VERIFIED", "AVAILABLE_ESTIMATED", "DELIVERY", "OFFLINE", "UNKNOWN"];

export const MIN_VERIFIED_HOURS_FOR_RATE = 0.5;
export const CONFIDENCE_MIN = { offers: 5, verifiedHours: 1, shifts: 2 };
export const CONFIDENCE_FAIR = { offers: 20, verifiedHours: 3, shifts: 5 };
export const PLANNER_MIN_SHIFTS = 5;

export interface TimeFilter {
  /** Local (Asia/Jerusalem) hour window [fromHour, toHour); wraps past midnight when toHour ≤ fromHour. 0/24 = whole day. */
  fromHour: number;
  toHour: number;
  /** 0 = Sunday … 6 = Saturday; empty = every day. */
  weekdays: number[];
  dateFrom: string | null; // YYYY-MM-DD, inclusive
  dateTo: string | null;
}
export const ALL_DAY: TimeFilter = { fromHour: 0, toHour: 24, weekdays: [], dateFrom: null, dateTo: null };

export function weekdayOf(date: string): number {
  return new Date(`${date}T12:00:00Z`).getUTCDay();
}

export function inWindow(ms: number, f: TimeFilter): boolean {
  const { date, hour } = jerusalemParts(ms);
  if (f.dateFrom && date < f.dateFrom) return false;
  if (f.dateTo && date > f.dateTo) return false;
  if (f.weekdays.length && !f.weekdays.includes(weekdayOf(date))) return false;
  if (f.fromHour === 0 && f.toHour === 24) return true;
  return f.fromHour < f.toHour ? hour >= f.fromHour && hour < f.toHour : hour >= f.fromHour || hour < f.toHour;
}

export interface StatePiece {
  shiftId: string;
  cellId: string; // UNKNOWN_CELL when the stretch had no valid fix
  measured: boolean; // valid GPS fix
  state: TimeState;
  startMs: number;
  endMs: number;
  accuracyM: number | null;
}

const STATEFUL = new Set(["AVAILABLE", "OFFER_PRESENTED", "DELIVERY_ACTIVE", "OFFLINE"]);

function stateOf(last: HotspotSample | null): TimeState {
  if (!last?.stateCandidate) return "UNKNOWN";
  if (last.stateCandidate === "DELIVERY_ACTIVE") return "DELIVERY";
  if (last.stateCandidate === "OFFLINE") return "OFFLINE";
  return last.stateEvidence === "OBSERVED" ? "AVAILABLE_VERIFIED" : "AVAILABLE_ESTIMATED";
}

/** Splits every shift into ≤ 6-min stretches between samples, each with a cell and one time state. Hour-aligned. */
export function statePieces(input: HotspotInput): StatePiece[] {
  const out: StatePiece[] = [];
  const HOUR = 3_600_000;
  for (const w of shiftWindows(input.shifts, input.samples)) {
    const own = input.samples.filter((s) => s.shiftId === w.id && s.atMs >= w.startMs && s.atMs <= w.endMs).sort((a, b) => a.atMs - b.atMs);
    let lastState: HotspotSample | null = null;
    own.forEach((s, i) => {
      if (s.stateCandidate && STATEFUL.has(s.stateCandidate)) lastState = s;
      const end = Math.min(own[i + 1]?.atMs ?? w.endMs, s.atMs + AVAILABILITY_CAP_MS, w.endMs);
      if (end <= s.atMs) return;
      const base = stateOf(lastState);
      const ok = s.status === "OK" && s.cellId !== null;
      // Cut at hour boundaries and at trip edges so filters and the DELIVERY override are exact.
      const cuts = new Set<number>([s.atMs, end]);
      for (let h = Math.ceil(s.atMs / HOUR) * HOUR; h < end; h += HOUR) cuts.add(h);
      for (const t of input.trips) for (const e of [t.startMs, t.endMs]) if (e !== null && e > s.atMs && e < end) cuts.add(e);
      const pts = [...cuts].sort((a, b) => a - b);
      for (let k = 0; k + 1 < pts.length; k++) {
        const [a, b] = [pts[k], pts[k + 1]];
        const inTrip = input.trips.some((t) => a >= t.startMs && (t.endMs === null ? false : a < t.endMs));
        out.push({
          shiftId: w.id,
          cellId: ok ? s.cellId! : UNKNOWN_CELL,
          measured: ok,
          state: inTrip ? "DELIVERY" : base,
          startMs: a,
          endMs: b,
          accuracyM: ok ? (s.accuracyM ?? null) : null,
        });
      }
    });
  }
  return out;
}

export type Confidence = "NONE" | "LOW" | "FAIR";

export interface AreaMetrics {
  cellId: string;
  offers: number;
  amountsAgorot: number[];
  meanAgorot: number | null;
  medianAgorot: number | null;
  shareAbove: number | null; // share of offers ≥ threshold
  timeMs: Record<TimeState, number>;
  measuredMs: number;
  /** Offers per VERIFIED available hour; null below MIN_VERIFIED_HOURS_FOR_RATE. */
  ratePerVerifiedHour: number | null;
  rateInterval: Interval | null;
  /** Verified available time ÷ offers; null without verified time or offers. */
  waitPerOfferMs: number | null;
  completedTrips: number;
  /** Completed trip amounts ÷ shift time spent in the area; null without completed trips. */
  incomePerShiftHourAgorot: number | null;
  shifts: number;
  medianAccuracyM: number | null;
  offersByHour: number[]; // 24
  timeByHour: number[]; // 24, measured ms
  confidence: Confidence;
  limits: string[];
}

export interface ExploreResult {
  filter: TimeFilter;
  thresholdAgorot: number;
  areas: AreaMetrics[]; // known cells, sorted by cell id (never by value)
  totals: {
    timeMs: Record<TimeState, number>;
    measuredMs: number;
    noFixMs: number;
    shiftMs: number;
    shifts: number;
    offersInShifts: number;
    offersNoCell: number;
    offersOutsideShifts: number;
    completedTrips: number;
  };
  planner: { enabled: false; shiftsInWindow: number; needShifts: number; completedTrips: number; hasTravelCost: boolean };
}

const median = (xs: number[]) => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : Math.round((s[m - 1] + s[m]) / 2);
};
const zeroStates = (): Record<TimeState, number> => ({ AVAILABLE_VERIFIED: 0, AVAILABLE_ESTIMATED: 0, DELIVERY: 0, OFFLINE: 0, UNKNOWN: 0 });

function confidenceOf(offers: number, verifiedHours: number, shifts: number): { c: Confidence; limits: string[] } {
  const limits: string[] = [];
  if (offers < CONFIDENCE_MIN.offers) limits.push(`רק ${offers} הצעות (צריך לפחות ${CONFIDENCE_MIN.offers})`);
  if (verifiedHours < CONFIDENCE_MIN.verifiedHours)
    limits.push(
      verifiedHours === 0
        ? "אין זמן זמינות מאומת — אי אפשר לחשב הצעות לשעה"
        : `רק ${verifiedHours * 60 < 1 ? "פחות מדקה" : `${Math.round(verifiedHours * 60)} דק׳`} של זמינות מאומתת (צריך לפחות ${CONFIDENCE_MIN.verifiedHours} ש׳)`,
    );
  if (shifts < CONFIDENCE_MIN.shifts) limits.push(`נתונים ממשמרת אחת בלבד`);
  if (limits.length) return { c: "NONE", limits };
  const fair = offers >= CONFIDENCE_FAIR.offers && verifiedHours >= CONFIDENCE_FAIR.verifiedHours && shifts >= CONFIDENCE_FAIR.shifts;
  if (!fair) limits.push(`ראשוני — פחות מ-${CONFIDENCE_FAIR.shifts} משמרות או ${CONFIDENCE_FAIR.verifiedHours} ש׳ מאומתות`);
  return { c: fair ? "FAIR" : "LOW", limits };
}

export function explore(input: HotspotInput, filter: TimeFilter, thresholdAgorot: number, hasTravelCost = false): ExploreResult {
  const windows = shiftWindows(input.shifts, input.samples);
  const inShift = (ms: number) => windows.some((w) => ms >= w.startMs && ms <= w.endMs);
  const shiftOf = (ms: number) => windows.find((w) => ms >= w.startMs && ms <= w.endMs)?.id ?? null;

  const pieces = statePieces(input).filter((p) => inWindow(p.startMs, filter));
  const offers = input.offers.filter((o) => inWindow(o.firstSeenMs, filter));
  const trips = input.trips.filter((t) => t.endMs !== null && t.endEvidence !== "UNKNOWN" && inWindow(t.startMs, filter));

  const byCell = new Map<string, { pieces: StatePiece[]; offers: { amount: number; ms: number; shift: string | null }[]; trips: typeof trips }>();
  const bucket = (id: string) => {
    if (!byCell.has(id)) byCell.set(id, { pieces: [], offers: [], trips: [] });
    return byCell.get(id)!;
  };
  for (const p of pieces) bucket(p.cellId).pieces.push(p);
  let offersInShifts = 0;
  let offersNoCell = 0;
  for (const o of offers) {
    if (!inShift(o.firstSeenMs)) continue;
    offersInShifts++;
    const link = linkToCell(o.firstSeenMs, input.samples);
    if (link.cellId === UNKNOWN_CELL) offersNoCell++;
    bucket(link.cellId).offers.push({ amount: o.amountAgorot, ms: o.firstSeenMs, shift: shiftOf(o.firstSeenMs) });
  }
  for (const t of trips) {
    if (!inShift(t.startMs)) continue;
    const link = linkToCell(t.startMs, input.samples);
    bucket(link.cellId).trips.push(t);
  }

  const areas: AreaMetrics[] = [...byCell.entries()]
    .filter(([id]) => id !== UNKNOWN_CELL)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([cellId, b]) => {
      const timeMs = zeroStates();
      const timeByHour = Array(24).fill(0);
      for (const p of b.pieces) {
        timeMs[p.state] += p.endMs - p.startMs;
        timeByHour[jerusalemParts(p.startMs).hour] += p.endMs - p.startMs;
      }
      const measuredMs = b.pieces.reduce((n, p) => n + p.endMs - p.startMs, 0);
      const amounts = b.offers.map((o) => o.amount);
      const verifiedH = timeMs.AVAILABLE_VERIFIED / 3_600_000;
      const rate = verifiedH >= MIN_VERIFIED_HOURS_FOR_RATE ? poissonRate(amounts.length, verifiedH) : null;
      const shifts = new Set([...b.pieces.map((p) => p.shiftId), ...b.offers.map((o) => o.shift).filter((x): x is string => x !== null)]).size;
      const income = b.trips.reduce((n, t) => n + t.amountsAgorot.reduce((m, a) => m + a, 0), 0);
      const offersByHour = Array(24).fill(0);
      for (const o of b.offers) offersByHour[jerusalemParts(o.ms).hour]++;
      const { c, limits } = confidenceOf(amounts.length, verifiedH, shifts);
      return {
        cellId,
        offers: amounts.length,
        amountsAgorot: [...amounts].sort((x, y) => x - y),
        meanAgorot: amounts.length ? Math.round(amounts.reduce((n, a) => n + a, 0) / amounts.length) : null,
        medianAgorot: median(amounts),
        shareAbove: amounts.length ? amounts.filter((a) => a >= thresholdAgorot).length / amounts.length : null,
        timeMs,
        measuredMs,
        ratePerVerifiedHour: rate?.rate ?? null,
        rateInterval: rate?.interval ?? null,
        waitPerOfferMs: timeMs.AVAILABLE_VERIFIED > 0 && amounts.length > 0 ? timeMs.AVAILABLE_VERIFIED / amounts.length : null,
        completedTrips: b.trips.length,
        incomePerShiftHourAgorot: b.trips.length > 0 && measuredMs > 0 ? Math.round(income / (measuredMs / 3_600_000)) : null,
        shifts,
        medianAccuracyM: median(b.pieces.map((p) => p.accuracyM).filter((x): x is number => x !== null)),
        offersByHour,
        timeByHour,
        confidence: c,
        limits,
      };
    });

  const totalsTime = zeroStates();
  for (const p of pieces) totalsTime[p.state] += p.endMs - p.startMs;
  const shiftMs = pieces.reduce((n, p) => n + p.endMs - p.startMs, 0);
  const shiftsInWindow = new Set(pieces.map((p) => p.shiftId)).size;
  return {
    filter,
    thresholdAgorot,
    areas,
    totals: {
      timeMs: totalsTime,
      measuredMs: pieces.filter((p) => p.measured).reduce((n, p) => n + p.endMs - p.startMs, 0),
      noFixMs: pieces.filter((p) => !p.measured).reduce((n, p) => n + p.endMs - p.startMs, 0),
      shiftMs,
      shifts: shiftsInWindow,
      offersInShifts,
      offersNoCell,
      offersOutsideShifts: offers.filter((o) => !inShift(o.firstSeenMs)).length,
      completedTrips: trips.filter((t) => inShift(t.startMs)).length,
    },
    planner: { enabled: false, shiftsInWindow, needShifts: PLANNER_MIN_SHIFTS, completedTrips: trips.length, hasTravelCost },
  };
}

export const MAP_METRICS = ["offers", "rate", "mean", "median", "shareAbove", "income", "wait"] as const;
export type MapMetric = (typeof MAP_METRICS)[number];

/** Metric value for colouring; null = no data for this metric (drawn grey, not ranked). */
export function metricValue(a: AreaMetrics, m: MapMetric): number | null {
  switch (m) {
    case "offers":
      return a.offers;
    case "rate":
      return a.ratePerVerifiedHour;
    case "mean":
      return a.meanAgorot;
    case "median":
      return a.medianAgorot;
    case "shareAbove":
      return a.shareAbove;
    case "income":
      return a.incomePerShiftHourAgorot;
    case "wait":
      return a.waitPerOfferMs;
  }
}
