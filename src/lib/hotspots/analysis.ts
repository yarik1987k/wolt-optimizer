import { summarize } from "@/lib/analytics/stats";
import { jerusalemParts } from "@/lib/offers/analytics";
import { haversineKm, parseCellId } from "./geo";
import { poissonRate, wilsonInterval, type Interval } from "./stats";

// ---------------------------------------------------------------------------
// Rules and thresholds (named so the page can show them).
// ---------------------------------------------------------------------------

export const UNKNOWN_CELL = "UNKNOWN";
/** Offer → cell: an OK OFFER_PRESENTED sample within this window of the offer's first sighting. */
export const OFFER_LINK_OBSERVED_MS = 90_000;
/** Fallback: the nearest OK sample (any trigger) within this window → ESTIMATED. */
export const OFFER_LINK_ESTIMATED_MS = 5 * 60_000;
/** Each waiting sample is credited at most this much time. */
export const AVAILABILITY_CAP_MS = 6 * 60_000;
export const AVAILABILITY_TRIGGERS: ReadonlySet<string> = new Set(["AVAILABLE_SCREEN", "WAIT_PERIODIC", "SHIFT_START"]);
/** Below either threshold a cell (or cell × hour) is flagged "נתונים מעטים". */
export const LOW_DATA_MIN_OFFERS = 5;
export const LOW_DATA_MIN_AVAILABLE_HOURS = 1;
/** Shown on the page: no recommendation before this many distinct shifts (and even then only as a hypothesis). */
export const MIN_SHIFTS_FOR_RECOMMENDATION = 5;

const HOUR_MS = 3_600_000;

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

export interface HotspotShift {
  id: string;
  startMs: number;
  endMs: number | null; // null = still running at export time
  endReason: string | null;
  locationConsent: boolean;
}
export interface HotspotSample {
  id: string;
  shiftId: string;
  atMs: number;
  trigger: string;
  status: "OK" | "UNKNOWN";
  unknownReason: string | null;
  cellId: string | null;
  lat: number | null; // cell centre
  lon: number | null;
  /** Phase 3 fields (absent for older samples). */
  stateCandidate?: string | null;
  stateEvidence?: string | null;
  accuracyM?: number | null;
}
export interface HotspotOffer {
  id: string;
  firstSeenMs: number;
  amountAgorot: number;
}
export interface HotspotTrip {
  startMs: number;
  endMs: number | null;
  endEvidence: string; // OBSERVED | ESTIMATED | UNKNOWN
  amountsAgorot: number[];
}
export interface HotspotDeliveryEvent {
  type: string;
  evidence: string;
  atMs: number;
}
export interface HotspotInput {
  shifts: HotspotShift[];
  samples: HotspotSample[];
  offers: HotspotOffer[];
  trips: HotspotTrip[];
  events: HotspotDeliveryEvent[];
}

// ---------------------------------------------------------------------------
// Shift windows
// ---------------------------------------------------------------------------

export interface ShiftWindow {
  id: string;
  startMs: number;
  endMs: number;
}

/** A running shift (no end) is bounded by its last sample — never extended to "now". */
export function shiftWindows(shifts: HotspotShift[], samples: HotspotSample[]): ShiftWindow[] {
  return shifts.map((s) => {
    let end = s.endMs;
    if (end === null) {
      const own = samples.filter((x) => x.shiftId === s.id && x.atMs >= s.startMs).map((x) => x.atMs);
      end = own.length ? Math.max(...own) : s.startMs;
    }
    return { id: s.id, startMs: s.startMs, endMs: Math.max(s.startMs, end) };
  });
}

const inAnyWindow = (ms: number, windows: ShiftWindow[]) => windows.some((w) => ms >= w.startMs && ms <= w.endMs);

// ---------------------------------------------------------------------------
// Time spent per area (plain "where was I" — any state, not "available" time)
// ---------------------------------------------------------------------------

export interface CellPresence {
  cellId: string; // UNKNOWN_CELL when the sample had no valid location
  ms: number;
}

/**
 * Each sample's cell gets the time until the next sample of the same shift (or the shift end),
 * at most AVAILABILITY_CAP_MS — a silence longer than that is not credited to any area.
 */
export function presenceByCell(shifts: HotspotShift[], samples: HotspotSample[]): CellPresence[] {
  const totals = new Map<string, number>();
  for (const w of shiftWindows(shifts, samples)) {
    const own = samples.filter((x) => x.shiftId === w.id && x.atMs >= w.startMs && x.atMs <= w.endMs).sort((a, b) => a.atMs - b.atMs);
    own.forEach((smp, i) => {
      const next = i + 1 < own.length ? own[i + 1].atMs : w.endMs;
      const ms = Math.min(Math.max(0, next - smp.atMs), AVAILABILITY_CAP_MS);
      const cell = smp.status === "OK" && smp.cellId ? smp.cellId : UNKNOWN_CELL;
      if (ms > 0) totals.set(cell, (totals.get(cell) ?? 0) + ms);
    });
  }
  return [...totals.entries()].map(([cellId, ms]) => ({ cellId, ms })).sort((a, b) => b.ms - a.ms);
}

// ---------------------------------------------------------------------------
// Offer (or any instant) → cell
// ---------------------------------------------------------------------------

export type LinkEvidence = "OBSERVED" | "ESTIMATED" | "UNKNOWN";
export interface CellLink {
  cellId: string; // UNKNOWN_CELL when no sample qualifies
  evidence: LinkEvidence;
  sampleId: string | null;
}

const isOk = (s: HotspotSample): s is HotspotSample & { cellId: string } => s.status === "OK" && s.cellId !== null;

function nearest(atMs: number, samples: HotspotSample[], windowMs: number): HotspotSample | null {
  let best: HotspotSample | null = null;
  for (const s of samples) {
    const d = Math.abs(s.atMs - atMs);
    if (d > windowMs) continue;
    if (!best || d < Math.abs(best.atMs - atMs) || (d === Math.abs(best.atMs - atMs) && s.atMs < best.atMs)) best = s;
  }
  return best;
}

/**
 * 1. the OK sample with trigger OFFER_PRESENTED nearest to `atMs` within ±90 s → OBSERVED;
 * 2. else the nearest OK sample (any trigger) within ±5 min → ESTIMATED;
 * 3. else UNKNOWN. A link is never invented from anything else.
 */
export function linkToCell(atMs: number, samples: HotspotSample[]): CellLink {
  const ok = samples.filter(isOk);
  const direct = nearest(atMs, ok.filter((s) => s.trigger === "OFFER_PRESENTED"), OFFER_LINK_OBSERVED_MS);
  if (direct) return { cellId: direct.cellId!, evidence: "OBSERVED", sampleId: direct.id };
  const near = nearest(atMs, ok, OFFER_LINK_ESTIMATED_MS);
  if (near) return { cellId: near.cellId!, evidence: "ESTIMATED", sampleId: near.id };
  return { cellId: UNKNOWN_CELL, evidence: "UNKNOWN", sampleId: null };
}

// ---------------------------------------------------------------------------
// Available (waiting) time
// ---------------------------------------------------------------------------

export interface AvailabilityPiece {
  shiftId: string;
  sampleId: string;
  cellId: string; // UNKNOWN_CELL when the sample had no fix
  startMs: number;
  endMs: number;
  evidence: "ESTIMATED";
}

function subtract(start: number, end: number, cuts: [number, number][]): [number, number][] {
  let parts: [number, number][] = [[start, end]];
  for (const [cs, ce] of cuts) {
    const next: [number, number][] = [];
    for (const [s, e] of parts) {
      if (ce <= s || cs >= e) {
        next.push([s, e]);
        continue;
      }
      if (cs > s) next.push([s, cs]);
      if (ce < e) next.push([ce, e]);
    }
    parts = next;
  }
  return parts.filter(([s, e]) => e > s);
}

/**
 * Each AVAILABLE_SCREEN / WAIT_PERIODIC / SHIFT_START sample credits its cell with the time until the
 * next sample of the same shift, capped at AVAILABILITY_CAP_MS and at the shift end. Samples outside
 * their shift are ignored. Time inside a detected trip (accept → complete) is removed; a trip whose end
 * is unknown is removed until the next waiting sample (the earliest point we know the courier waited again).
 * All of it is an estimate.
 */
export function availabilityPieces(shifts: HotspotShift[], samples: HotspotSample[], trips: HotspotTrip[]): AvailabilityPiece[] {
  const out: AvailabilityPiece[] = [];
  for (const w of shiftWindows(shifts, samples)) {
    const own = samples.filter((s) => s.shiftId === w.id && s.atMs >= w.startMs && s.atMs <= w.endMs).sort((a, b) => a.atMs - b.atMs);
    const cuts: [number, number][] = trips
      .filter((t) => t.startMs <= w.endMs && (t.endMs === null || t.endMs >= w.startMs))
      .map((t) => {
        if (t.endMs !== null) return [t.startMs, t.endMs];
        const resume = own.find((s) => s.atMs > t.startMs && AVAILABILITY_TRIGGERS.has(s.trigger));
        return [t.startMs, resume ? resume.atMs : w.endMs];
      });
    own.forEach((s, i) => {
      if (!AVAILABILITY_TRIGGERS.has(s.trigger)) return;
      const next = own[i + 1]?.atMs ?? w.endMs;
      const end = Math.min(next, s.atMs + AVAILABILITY_CAP_MS, w.endMs);
      for (const [a, b] of subtract(s.atMs, end, cuts)) {
        out.push({ shiftId: w.id, sampleId: s.id, cellId: isOk(s) ? s.cellId : UNKNOWN_CELL, startMs: a, endMs: b, evidence: "ESTIMATED" });
      }
    });
  }
  return out;
}

/** Splits [start, end) at local hour boundaries (Israel's UTC offsets are whole hours, so UTC hours line up). */
export function splitByLocalHour(startMs: number, endMs: number): { hour: number; ms: number }[] {
  const out: { hour: number; ms: number }[] = [];
  let t = startMs;
  while (t < endMs) {
    const boundary = Math.floor(t / HOUR_MS) * HOUR_MS + HOUR_MS;
    const e = Math.min(boundary, endMs);
    out.push({ hour: jerusalemParts(t).hour, ms: e - t });
    t = e;
  }
  return out;
}

// ---------------------------------------------------------------------------
// Aggregation
// ---------------------------------------------------------------------------

interface Bucket {
  cellId: string;
  hour: number;
  amounts: number[];
  observed: number;
  estimated: number;
  availableMs: number;
  shiftIds: Set<string>;
  completedTrips: number;
  completedAmountAgorot: number;
  tripMs: number;
}

export interface CellMetrics {
  cellId: string;
  y: number | null;
  x: number | null;
  lat: number | null; // cell centre
  lon: number | null;
  offers: number;
  offersObserved: number; // linked by an OFFER_PRESENTED sample
  offersEstimated: number; // linked by the ±5 min fallback
  meanAmountAgorot: number | null;
  medianAmountAgorot: number | null;
  availableHours: number; // ESTIMATED
  offersPerAvailableHour: number | null;
  offerRateInterval: Interval | null; // Garwood 95%
  shiftCount: number;
  lowData: boolean;
  lowDataReasons: string[];
  completedTrips: number;
  completedAmountAgorot: number; // offer amounts as displayed, completed trips only
  /** completed trip amounts ÷ (available + trip hours) in the cell; null without completed trips. */
  earningsPerHourAgorot: number | null;
}

export function lowDataReasons(offers: number, availableHours: number): string[] {
  const r: string[] = [];
  if (offers < LOW_DATA_MIN_OFFERS) r.push(`פחות מ-${LOW_DATA_MIN_OFFERS} הצעות`);
  if (availableHours < LOW_DATA_MIN_AVAILABLE_HOURS) r.push(`פחות מ-${LOW_DATA_MIN_AVAILABLE_HOURS} שעת זמינות`);
  return r;
}

function toMetrics(cellId: string, buckets: Bucket[], centres: Map<string, { lat: number; lon: number }>): CellMetrics {
  const amounts = buckets.flatMap((b) => b.amounts);
  const availableMs = buckets.reduce((a, b) => a + b.availableMs, 0);
  const tripMs = buckets.reduce((a, b) => a + b.tripMs, 0);
  const completedTrips = buckets.reduce((a, b) => a + b.completedTrips, 0);
  const completedAmountAgorot = buckets.reduce((a, b) => a + b.completedAmountAgorot, 0);
  const s = summarize(amounts);
  const availableHours = availableMs / HOUR_MS;
  const rate = poissonRate(amounts.length, availableHours);
  const reasons = lowDataReasons(amounts.length, availableHours);
  const yx = parseCellId(cellId);
  const c = centres.get(cellId) ?? null;
  return {
    cellId,
    y: yx?.y ?? null,
    x: yx?.x ?? null,
    lat: c?.lat ?? null,
    lon: c?.lon ?? null,
    offers: amounts.length,
    offersObserved: buckets.reduce((a, b) => a + b.observed, 0),
    offersEstimated: buckets.reduce((a, b) => a + b.estimated, 0),
    meanAmountAgorot: s.mean,
    medianAmountAgorot: s.median,
    availableHours,
    offersPerAvailableHour: rate?.rate ?? null,
    offerRateInterval: rate?.interval ?? null,
    shiftCount: new Set(buckets.flatMap((b) => [...b.shiftIds])).size,
    lowData: reasons.length > 0,
    lowDataReasons: reasons,
    completedTrips,
    completedAmountAgorot,
    earningsPerHourAgorot: completedTrips > 0 && availableMs + tripMs > 0 ? completedAmountAgorot / ((availableMs + tripMs) / HOUR_MS) : null,
  };
}

export interface OfferLinkRow extends CellLink {
  offerId: string;
  firstSeenMs: number;
  amountAgorot: number;
  hour: number;
  inShift: boolean;
}

export interface DataQuality {
  samples: number;
  okSamples: number;
  unknownSamples: number;
  unknownByReason: { reason: string; n: number }[];
  samplesOutsideShift: number; // includes samples whose shift is missing from the data
  shifts: number;
  shiftsByEndReason: { reason: string; n: number }[];
  serviceStoppedShifts: { id: string; startMs: number; endMs: number | null }[];
  runningShifts: number;
  noConsentShifts: number;
  offersInShifts: number;
  offersOutsideShifts: number;
  offersUnknownCell: number; // within shifts
  offersEstimated: number;
  unknownCellAvailableHours: number;
}

export interface AcceptShare {
  accepted: number;
  decided: number; // accepted + declined + ended-unknown
  share: number | null;
  interval: Interval | null; // Wilson 95%
}

export interface HotspotAnalysis {
  hour: number | null;
  cells: CellMetrics[]; // known cells only, sorted by cell id (never by a value)
  unknownCell: CellMetrics; // offers / waiting time without a cell
  cellHours: (CellMetrics & { hour: number })[]; // every cell × hour bucket with data
  offerLinks: OfferLinkRow[];
  acceptShare: AcceptShare;
  completedDeliveries: number; // completed trips + DELIVERY_COMPLETED events without a trip
  distinctShifts: number;
  quality: DataQuality;
}

const countBy = (values: string[]) => {
  const m = new Map<string, number>();
  for (const v of values) m.set(v, (m.get(v) ?? 0) + 1);
  return [...m.entries()].sort((a, b) => b[1] - a[1]).map(([reason, n]) => ({ reason, n }));
};

/** Pure: everything the /hotspots page shows. `hour` (0–23) filters by local hour of day. */
export function analyzeHotspots(input: HotspotInput, opts: { hour?: number | null } = {}): HotspotAnalysis {
  const hour = opts.hour ?? null;
  const windows = shiftWindows(input.shifts, input.samples);
  const shiftOf = (ms: number) => windows.find((w) => ms >= w.startMs && ms <= w.endMs)?.id ?? null;

  const centres = new Map<string, { lat: number; lon: number }>();
  for (const s of input.samples) if (isOk(s) && s.lat !== null && s.lon !== null && !centres.has(s.cellId)) centres.set(s.cellId, { lat: s.lat, lon: s.lon });

  const buckets = new Map<string, Bucket>();
  const bucket = (cellId: string, h: number) => {
    const k = `${cellId}|${h}`;
    if (!buckets.has(k))
      buckets.set(k, { cellId, hour: h, amounts: [], observed: 0, estimated: 0, availableMs: 0, shiftIds: new Set(), completedTrips: 0, completedAmountAgorot: 0, tripMs: 0 });
    return buckets.get(k)!;
  };

  // Offers — only those first seen inside a Collector shift (no location is sampled outside one).
  const offerLinks: OfferLinkRow[] = input.offers.map((o) => {
    const shiftId = shiftOf(o.firstSeenMs);
    const link: CellLink = shiftId ? linkToCell(o.firstSeenMs, input.samples) : { cellId: UNKNOWN_CELL, evidence: "UNKNOWN", sampleId: null };
    const h = jerusalemParts(o.firstSeenMs).hour;
    if (shiftId) {
      const b = bucket(link.cellId, h);
      b.amounts.push(o.amountAgorot);
      if (link.evidence === "OBSERVED") b.observed++;
      if (link.evidence === "ESTIMATED") b.estimated++;
      b.shiftIds.add(shiftId);
    }
    return { ...link, offerId: o.id, firstSeenMs: o.firstSeenMs, amountAgorot: o.amountAgorot, hour: h, inShift: shiftId !== null };
  });

  for (const p of availabilityPieces(input.shifts, input.samples, input.trips)) {
    for (const part of splitByLocalHour(p.startMs, p.endMs)) {
      const b = bucket(p.cellId, part.hour);
      b.availableMs += part.ms;
      b.shiftIds.add(p.shiftId);
    }
  }

  // Completed trips only (an end that is UNKNOWN is not a completion).
  const completed = input.trips.filter((t) => t.endMs !== null && t.endEvidence !== "UNKNOWN" && shiftOf(t.startMs) !== null);
  for (const t of completed) {
    const link = linkToCell(t.startMs, input.samples);
    const b = bucket(link.cellId, jerusalemParts(t.startMs).hour);
    b.completedTrips++;
    b.completedAmountAgorot += t.amountsAgorot.reduce((a, x) => a + x, 0);
    b.tripMs += t.endMs! - t.startMs;
  }
  const orphanCompletions = input.events.filter(
    (e) => e.type === "DELIVERY_COMPLETED" && shiftOf(e.atMs) !== null && !completed.some((t) => t.endMs === e.atMs),
  ).length;

  const all = [...buckets.values()];
  const selected = all.filter((b) => hour === null || b.hour === hour);
  const byCell = new Map<string, Bucket[]>();
  for (const b of selected) byCell.set(b.cellId, [...(byCell.get(b.cellId) ?? []), b]);
  // Every known cell stays on the map even when the hour filter leaves it empty.
  for (const b of all) if (!byCell.has(b.cellId)) byCell.set(b.cellId, []);

  const cells = [...byCell.entries()]
    .filter(([id]) => id !== UNKNOWN_CELL)
    .map(([id, bs]) => toMetrics(id, bs, centres))
    .sort((a, b) => (a.y ?? 0) - (b.y ?? 0) || (a.x ?? 0) - (b.x ?? 0));
  const unknownCell = toMetrics(UNKNOWN_CELL, byCell.get(UNKNOWN_CELL) ?? [], centres);
  const cellHours = all
    .filter((b) => b.cellId !== UNKNOWN_CELL)
    .map((b) => ({ ...toMetrics(b.cellId, [b], centres), hour: b.hour }))
    .sort((a, b) => a.cellId.localeCompare(b.cellId) || a.hour - b.hour);

  const inShiftEvents = input.events.filter((e) => shiftOf(e.atMs) !== null);
  const accepted = inShiftEvents.filter((e) => e.type === "DELIVERY_ACCEPTED").length;
  const decided = accepted + inShiftEvents.filter((e) => e.type === "OFFER_DECLINED" || e.type === "OFFER_ENDED_UNKNOWN").length;

  const sampleOutside = input.samples.filter((s) => {
    const w = windows.find((x) => x.id === s.shiftId);
    return !w || s.atMs < w.startMs || s.atMs > w.endMs;
  }).length;
  const unknownSamples = input.samples.filter((s) => !isOk(s));
  const inShiftLinks = offerLinks.filter((l) => l.inShift);

  return {
    hour,
    cells,
    unknownCell,
    cellHours,
    offerLinks,
    acceptShare: { accepted, decided, share: decided ? accepted / decided : null, interval: wilsonInterval(accepted, decided) },
    completedDeliveries: completed.length + orphanCompletions,
    distinctShifts: new Set([...all.flatMap((b) => [...b.shiftIds])]).size,
    quality: {
      samples: input.samples.length,
      okSamples: input.samples.length - unknownSamples.length,
      unknownSamples: unknownSamples.length,
      unknownByReason: countBy(unknownSamples.map((s) => s.unknownReason ?? "לא צוין")),
      samplesOutsideShift: sampleOutside,
      shifts: input.shifts.length,
      shiftsByEndReason: countBy(input.shifts.map((s) => s.endReason ?? (s.endMs === null ? "RUNNING" : "לא צוין"))),
      serviceStoppedShifts: input.shifts.filter((s) => s.endReason === "SERVICE_STOPPED").map((s) => ({ id: s.id, startMs: s.startMs, endMs: s.endMs })),
      runningShifts: input.shifts.filter((s) => s.endMs === null).length,
      noConsentShifts: input.shifts.filter((s) => !s.locationConsent).length,
      offersInShifts: inShiftLinks.length,
      offersOutsideShifts: offerLinks.length - inShiftLinks.length,
      offersUnknownCell: inShiftLinks.filter((l) => l.evidence === "UNKNOWN").length,
      offersEstimated: inShiftLinks.filter((l) => l.evidence === "ESTIMATED").length,
      unknownCellAvailableHours: all.filter((b) => b.cellId === UNKNOWN_CELL).reduce((a, b) => a + b.availableMs, 0) / HOUR_MS,
    },
  };
}

// ---------------------------------------------------------------------------
// Descriptive comparison of a "current" cell to alternatives. Never a recommendation.
// ---------------------------------------------------------------------------

export interface ComparisonAssumptions {
  avgSpeedKmh: number | null; // user setting; null = not set
  costPerKmAgorot: number | null; // user setting; null = not set
  acceptShare: number | null; // user setting, or the observed share; null = unknown
}

export interface ComparisonRow {
  cellId: string;
  isCurrent: boolean;
  distanceKm: number; // straight line between cell centres (a road route is longer)
  travelMinutes: number | null;
  travelCostAgorot: number | null;
  offersPerAvailableHour: number | null;
  offerRateInterval: Interval | null;
  meanAmountAgorot: number | null;
  acceptShare: number | null;
  /** rate × mean payment × accept share — a descriptive product of components, not a forecast. */
  expectedPerHourAgorot: number | null;
  /** Same product over the rate interval only (payment and accept-share uncertainty not included). */
  expectedPerHourRangeAgorot: Interval | null;
  lowData: boolean;
}

export function compareCells(current: CellMetrics, cells: CellMetrics[], a: ComparisonAssumptions): ComparisonRow[] {
  if (current.lat === null || current.lon === null) return [];
  const from = { lat: current.lat, lon: current.lon };
  return cells
    .filter((c): c is CellMetrics & { lat: number; lon: number } => c.lat !== null && c.lon !== null)
    .map((c) => {
      const km = c.cellId === current.cellId ? 0 : haversineKm(from, { lat: c.lat, lon: c.lon });
      const factor = c.meanAmountAgorot !== null && a.acceptShare !== null ? c.meanAmountAgorot * a.acceptShare : null;
      return {
        cellId: c.cellId,
        isCurrent: c.cellId === current.cellId,
        distanceKm: km,
        travelMinutes: a.avgSpeedKmh ? (km / a.avgSpeedKmh) * 60 : null,
        travelCostAgorot: a.costPerKmAgorot === null ? null : km * a.costPerKmAgorot,
        offersPerAvailableHour: c.offersPerAvailableHour,
        offerRateInterval: c.offerRateInterval,
        meanAmountAgorot: c.meanAmountAgorot,
        acceptShare: a.acceptShare,
        expectedPerHourAgorot: factor !== null && c.offersPerAvailableHour !== null ? c.offersPerAvailableHour * factor : null,
        expectedPerHourRangeAgorot:
          factor !== null && c.offerRateInterval ? { lower: c.offerRateInterval.lower * factor, upper: c.offerRateInterval.upper * factor } : null,
        lowData: c.lowData,
      };
    })
    // Ordered by distance only — deliberately not by any value.
    .sort((x, y) => x.distanceKm - y.distanceKm || x.cellId.localeCompare(y.cellId));
}
