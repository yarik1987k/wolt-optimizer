import { prisma } from "@/lib/db";
import { chainEconomics, shiftSummary, type ChainEconomics, type Range, type ShiftSummary } from "@/lib/chains/economics";
import { getSettings } from "@/lib/settings";
import { TIME_ZONE } from "@/lib/time";

/**
 * Historical Offer Performance Model — descriptive, from completed delivery chains only.
 *
 * Unit of analysis = the CHAIN (accepted offers that overlapped). A standalone chain (one offer) carries that
 * offer's economics; a chain with added offers is measured only as a whole — no duration or distance is
 * assigned to an individual added offer. Offer-characteristic segments therefore use standalone chains;
 * the context dimension compares standalone chains with chains that contain added offers.
 *
 * Wolt's displayed target times are never used here (they are not travel-time estimates). Results are
 * correlations in the courier's own history, not causal effects; a segment with too little data is shown
 * but never ranked.
 */

/** A segment needs this many chains with complete economics before it may be compared. */
export const MIN_SEGMENT_CHAINS = 10;
/** …and at least this share of its chains must have complete economics. */
export const MIN_COVERAGE = 0.8;

export interface OfferAttr {
  tripKey: string;
  offerId: string | null;
  acceptedAtMs: number;
  amountAgorot: number | null;
  distanceKm: number | null;
  courierCell: string | null;
  /** Pickup cells verified from the business address (OK geocode); empty = unverified. */
  pickupCells: string[];
  pickupVerified: boolean;
  newOrdersLow: number | null;
  newOrdersHigh: number | null;
  context: string | null; // IDLE | ADDED_TO_ACTIVE_ROUTE | UNKNOWN
}

export interface ChainUnit {
  chainKey: string;
  economics: ChainEconomics;
  offers: OfferAttr[];
  standalone: boolean;
  complete: boolean; // duration + reliable GPS distance + cost → net figures exist
}

// ---- bands ----

export const priceBand = (a: number | null) => (a === null ? "unknown" : a < 2000 ? "< ₪20" : a < 3000 ? "₪20–30" : a < 4500 ? "₪30–45" : "≥ ₪45");
export const distanceBand = (km: number | null) => (km === null ? "unknown" : km < 3 ? "< 3 km" : km < 6 ? "3–6 km" : km < 10 ? "6–10 km" : "≥ 10 km");
export function hourBand(ms: number) {
  const h = Number(new Date(ms).toLocaleString("en-GB", { timeZone: TIME_ZONE, hour: "2-digit", hour12: false })) % 24;
  return h < 6 ? "00–06" : h < 11 ? "06–11" : h < 15 ? "11–15" : h < 18 ? "15–18" : h < 21 ? "18–21" : "21–24";
}
const ordersBand = (o: OfferAttr) =>
  o.newOrdersLow === null || o.newOrdersHigh === null ? "unknown" : o.newOrdersHigh === 1 ? "single order" : o.newOrdersLow > 1 ? "bundled (2+ orders)" : "1–2 orders (uncertain)";

export interface Dimension {
  key: string;
  label: string;
  /** Applies to standalone chains only (an offer characteristic), or to every chain (chain-level). */
  scope: "STANDALONE" | "ALL_CHAINS";
  bucket: (c: ChainUnit) => string;
}

export const DIMENSIONS: Dimension[] = [
  { key: "price", label: "Offer price band", scope: "STANDALONE", bucket: (c) => priceBand(c.offers[0].amountAgorot) },
  { key: "distance", label: "Displayed distance band", scope: "STANDALONE", bucket: (c) => distanceBand(c.offers[0].distanceKm) },
  { key: "hour", label: "Time of day (acceptance)", scope: "STANDALONE", bucket: (c) => hourBand(c.offers[0].acceptedAtMs) },
  { key: "courier_area", label: "Courier area at the offer (~500 m)", scope: "STANDALONE", bucket: (c) => c.offers[0].courierCell ?? "unknown" },
  {
    key: "pickup_area",
    label: "Verified pickup area (~500 m)",
    scope: "STANDALONE",
    bucket: (c) => (c.offers[0].pickupVerified ? c.offers[0].pickupCells.join(" + ") : "unverified"),
  },
  { key: "orders", label: "Single vs bundled offer", scope: "STANDALONE", bucket: (c) => ordersBand(c.offers[0]) },
  {
    key: "context",
    label: "Chain context (IDLE start only vs. offers added to an active route)",
    scope: "ALL_CHAINS",
    bucket: (c) => (c.offers.some((o) => o.context === "ADDED_TO_ACTIVE_ROUTE") ? "with ADDED_TO_ACTIVE_ROUTE offers" : c.standalone ? "standalone (IDLE)" : "unknown"),
  },
];

export interface SegmentStats {
  bucket: string;
  chains: number;
  completeChains: number;
  coverage: number;
  revenueAgorot: number;
  activeHours: Range | null;
  /** Pooled over complete chains: Σ(revenue − cost) ÷ Σ hours, worst … best reading of the ranges. */
  netPerActiveHourAgorot: Range | null;
  grossPerActiveHourAgorot: Range | null;
  /** Per-chain net/h midpoints — the spread inside the segment. */
  chainNetPerHourSpread: Range | null;
  status: "COMPARABLE" | "INSUFFICIENT_DATA";
  reasons: string[];
}

export interface DimensionResult {
  key: string;
  label: string;
  scope: Dimension["scope"];
  segments: SegmentStats[];
  /** Only between COMPARABLE segments whose pooled net/h ranges do not overlap. */
  establishedDifferences: string[];
  notEstablished: string[];
}

const mid = (x: Range) => (x.low + x.high) / 2;

export function segmentStats(bucket: string, chains: ChainUnit[]): SegmentStats {
  const complete = chains.filter((c) => c.complete);
  const reasons: string[] = [];
  const coverage = chains.length ? complete.length / chains.length : 0;
  if (complete.length < MIN_SEGMENT_CHAINS) reasons.push(`${complete.length} complete chain(s) — needs ≥ ${MIN_SEGMENT_CHAINS}`);
  if (coverage < MIN_COVERAGE) reasons.push(`coverage ${(coverage * 100).toFixed(0)}% — needs ≥ ${MIN_COVERAGE * 100}%`);
  let activeHours: Range | null = null;
  let net: Range | null = null;
  let gross: Range | null = null;
  let spread: Range | null = null;
  if (complete.length) {
    const hLow = complete.reduce((s, c) => s + c.economics.durationMin!.low / 60, 0);
    const hHigh = complete.reduce((s, c) => s + c.economics.durationMin!.high / 60, 0);
    const rev = complete.reduce((s, c) => s + c.economics.revenueAgorot, 0);
    const costLow = complete.reduce((s, c) => s + c.economics.vehicleCostAgorot!.low, 0);
    const costHigh = complete.reduce((s, c) => s + c.economics.vehicleCostAgorot!.high, 0);
    activeHours = { low: hLow, high: hHigh };
    net = { low: (rev - costHigh) / hHigh, high: (rev - costLow) / hLow };
    gross = { low: rev / hHigh, high: rev / hLow };
    const mids = complete.map((c) => mid(c.economics.netPerHourAgorot!));
    spread = { low: Math.min(...mids), high: Math.max(...mids) };
  }
  return {
    bucket,
    chains: chains.length,
    completeChains: complete.length,
    coverage,
    revenueAgorot: chains.reduce((s, c) => s + c.economics.revenueAgorot, 0),
    activeHours,
    netPerActiveHourAgorot: net,
    grossPerActiveHourAgorot: gross,
    chainNetPerHourSpread: spread,
    status: reasons.length ? "INSUFFICIENT_DATA" : "COMPARABLE",
    reasons,
  };
}

export function analyzeDimension(d: Dimension, chains: ChainUnit[]): DimensionResult {
  const pool = d.scope === "STANDALONE" ? chains.filter((c) => c.standalone) : chains;
  const groups = new Map<string, ChainUnit[]>();
  for (const c of pool) groups.set(d.bucket(c), [...(groups.get(d.bucket(c)) ?? []), c]);
  const segments = [...groups.entries()].map(([b, cs]) => segmentStats(b, cs)).sort((a, b) => a.bucket.localeCompare(b.bucket));
  const ok = segments.filter((s) => s.status === "COMPARABLE" && s.netPerActiveHourAgorot);
  const established: string[] = [];
  for (const a of ok)
    for (const b of ok)
      if (a !== b && a.netPerActiveHourAgorot!.low > b.netPerActiveHourAgorot!.high)
        established.push(`"${a.bucket}" earned more net per active hour than "${b.bucket}" (ranges do not overlap; correlation in own history, not cause)`);
  const notEstablished: string[] = [];
  if (ok.length < 2) notEstablished.push(`fewer than two segments with enough data (${ok.length} comparable of ${segments.length}) — no ranking`);
  else if (!established.length) notEstablished.push("comparable segments overlap — no difference established");
  return { key: d.key, label: d.label, scope: d.scope, segments, establishedDifferences: established, notEstablished };
}

// ---- strategies (prepared; evaluated against actual decisions, counterfactuals marked) ----

export interface DecisionRecord {
  offerId: string;
  seenAtMs: number;
  amountAgorot: number;
  distanceKm: number | null;
  decision: "ACCEPTED" | "DECLINED" | "UNKNOWN";
  /** Present when the accepted offer formed a standalone chain with complete economics. */
  standaloneNetAgorot: Range | null;
}

export interface Strategy {
  name: string;
  description: string;
  accepts: (o: DecisionRecord) => boolean;
}

export const CANDIDATE_STRATEGIES: Strategy[] = [
  { name: "accept-all", description: "accept every offer", accepts: () => true },
  { name: "min-₪25", description: "accept offers paying at least ₪25", accepts: (o) => o.amountAgorot >= 2500 },
  {
    name: "min-₪6/km",
    description: "accept offers paying at least ₪6 per displayed km",
    accepts: (o) => o.distanceKm !== null && o.distanceKm > 0 && o.amountAgorot / 100 / o.distanceKm >= 6,
  },
];

export interface StrategyEvaluation {
  name: string;
  description: string;
  offers: number;
  agreeAccept: number;
  agreeDecline: number;
  wouldDeclineAccepted: number;
  wouldAcceptDeclined: number;
  unknownDecision: number;
  /** Observed revenue of offers both you and the strategy accepted. */
  observedAgreedRevenueAgorot: number;
  verdict: "NOT_EVALUABLE" | "MATCHES_HISTORY";
  limitations: string[];
}

export function evaluateStrategy(s: Strategy, records: DecisionRecord[]): StrategyEvaluation {
  const known = records.filter((r) => r.decision !== "UNKNOWN");
  const aa = known.filter((r) => r.decision === "ACCEPTED" && s.accepts(r));
  const dd = known.filter((r) => r.decision === "DECLINED" && !s.accepts(r));
  const da = known.filter((r) => r.decision === "ACCEPTED" && !s.accepts(r));
  const ad = known.filter((r) => r.decision === "DECLINED" && s.accepts(r));
  const limitations: string[] = [];
  if (da.length) limitations.push(`${da.length} accepted offer(s) it would decline — what would have come instead is unknown (later offers, idle time)`);
  if (ad.length) limitations.push(`${ad.length} declined offer(s) it would accept — their outcome was never observed`);
  if (records.length - known.length) limitations.push(`${records.length - known.length} offer(s) with no observed decision`);
  limitations.push("Offers depend on earlier decisions (being busy changes what is offered), so history cannot replay a different policy; only a planned experiment can.");
  return {
    name: s.name,
    description: s.description,
    offers: records.length,
    agreeAccept: aa.length,
    agreeDecline: dd.length,
    wouldDeclineAccepted: da.length,
    wouldAcceptDeclined: ad.length,
    unknownDecision: records.length - known.length,
    observedAgreedRevenueAgorot: aa.reduce((t, r) => t + r.amountAgorot, 0),
    verdict: da.length || ad.length ? "NOT_EVALUABLE" : "MATCHES_HISTORY",
    limitations,
  };
}

// ---- loading ----

export interface PerformanceReport {
  costPerKmAgorot: number | null;
  /** Work shifts in scope (sessions ≥ evidenceMinWorkShiftMinutes): boundaries, acceptances and completions kept apart. */
  scope: {
    shiftBoundaries: { startMs: number; endMs: number }[];
    acceptances: { firstMs: number; lastMs: number } | null;
    completions: { firstMs: number; lastMs: number } | null;
    excludedSessions: number;
  };
  chains: ChainUnit[];
  shifts: ShiftSummary[];
  /** Pooled over shifts with reliable GPS: Σ(revenue − cost) ÷ Σ shift hours. */
  netPerShiftHourAgorot: Range | null;
  netPerActiveHourAgorot: Range | null;
  dimensions: DimensionResult[];
  decisions: DecisionRecord[];
  strategies: StrategyEvaluation[];
  shadowPredictions: number;
  coverageNotes: string[];
}

export async function performanceReport(): Promise<PerformanceReport> {
  const settings = await getSettings();
  const cost = settings.vehicleCostPerKmAgorot;
  const [chainRows, trips, offers, places, shifts, declines, shadowCount] = await Promise.all([
    prisma.collectorChain.findMany({ orderBy: { startLatestAtUtc: "asc" } }),
    prisma.collectorTrip.findMany({ orderBy: { startedAtUtc: "asc" } }),
    prisma.offer.findMany({ orderBy: { firstSeenAtUtc: "asc" } }),
    prisma.collectorPickupPlace.findMany(),
    prisma.collectorShift.findMany({ where: { factsEndedAtUtc: { not: null } }, orderBy: { startedAtUtc: "asc" } }).then((xs) =>
      xs.filter((s) => ((s.endedAtUtc ?? s.factsEndedAtUtc!).getTime() - s.startedAtUtc.getTime()) / 60_000 >= settings.evidenceMinWorkShiftMinutes),
    ),
    prisma.collectorDeliveryEvent.findMany({ where: { type: "OFFER_DECLINED" } }),
    prisma.shadowPrediction.count(),
  ]);
  const placeByName = new Map<string, typeof places>();
  for (const p of places) placeByName.set(p.name, [...(placeByName.get(p.name) ?? []), p]);
  const offerOf = (t: (typeof trips)[number]) => (t.offerSeenAtUtc ? offers.find((o) => o.firstSeenAtUtc.getTime() === t.offerSeenAtUtc!.getTime()) ?? null : null);

  const attr = (t: (typeof trips)[number]): OfferAttr => {
    const o = offerOf(t);
    const names = o ? (JSON.parse(o.pickupNamesJson) as string[]) : [];
    // A name maps to a verified cell only when exactly one stored place has that name and it geocoded OK.
    const cells = names.map((n) => {
      const ps = placeByName.get(n) ?? [];
      return ps.length === 1 && ps[0].geoStatus === "OK" ? ps[0].cellId : null;
    });
    return {
      tripKey: t.tripKey,
      offerId: o?.id ?? null,
      acceptedAtMs: t.startedAtUtc.getTime(),
      amountAgorot: (JSON.parse(t.offerAmountsJson) as number[])[0] ?? null,
      distanceKm: t.cardDistanceKm ?? (o?.distanceMeters != null ? o.distanceMeters / 1000 : null),
      courierCell: t.offerCell,
      pickupCells: cells.filter((c): c is string => c !== null),
      pickupVerified: names.length > 0 && cells.every((c) => c !== null),
      newOrdersLow: t.newOrdersLow,
      newOrdersHigh: t.newOrdersHigh,
      context: t.acceptContext,
    };
  };

  const chains: ChainUnit[] = chainRows.map((c) => {
    const members = trips.filter((t) => t.chainKey === c.chainKey).map(attr);
    const e = chainEconomics(
      {
        chainKey: c.chainKey,
        offers: c.offers,
        revenueAgorot: c.revenueAgorot,
        ordersLow: c.ordersLow,
        ordersHigh: c.ordersHigh,
        startEarliestMs: c.startEarliestAtUtc?.getTime() ?? null,
        startLatestMs: c.startLatestAtUtc.getTime(),
        endEarliestMs: c.endEarliestAtUtc?.getTime() ?? null,
        endLatestMs: c.endLatestAtUtc?.getTime() ?? null,
        endEvidence: c.endEvidence,
        gpsSamples: c.gpsSamples,
        gpsMaxGapMs: c.gpsMaxGapMs,
        gpsCoverage: c.gpsCoverage,
        gpsPathKm: c.gpsPathKm,
        gpsSpeedKm: c.gpsSpeedKm,
        gpsDistanceReliable: c.gpsDistanceReliable,
        missing: JSON.parse(c.missingJson) as string[],
        addedOffers: members.filter((m) => m.context === "ADDED_TO_ACTIVE_ROUTE").length,
        completionSlides: c.completionSlidesJson ? (JSON.parse(c.completionSlidesJson) as number[]).length : 0,
      },
      cost,
    );
    return { chainKey: c.chainKey, economics: e, offers: members, standalone: members.length === 1, complete: e.netPerHourAgorot !== null };
  }).filter((c) => c.offers.length > 0);

  const shiftSummaries = shifts
    .map((x) =>
      shiftSummary(
        {
          shiftId: x.id,
          startMs: x.startedAtUtc.getTime(),
          endMs: (x.endedAtUtc ?? x.factsEndedAtUtc!).getTime(),
          revenueAgorot: x.revenueAgorot ?? 0,
          completionSlides: x.completionSlides ?? 0,
          chainMsLow: x.chainMsLow ?? 0,
          chainMsHigh: x.chainMsHigh ?? 0,
          verifiedIdleMs: x.verifiedIdleMs ?? 0,
          onlineUnverifiedMs: x.onlineUnverifiedMs ?? 0,
          offlineMs: x.offlineMs ?? 0,
          unknownMs: x.unknownMs ?? 0,
          gpsPathKm: x.gpsPathKm,
          gpsSpeedKm: x.gpsSpeedKm,
          gpsDistanceReliable: x.gpsDistanceReliable ?? false,
          missing: x.factsMissingJson ? (JSON.parse(x.factsMissingJson) as string[]) : [],
        },
        cost,
      ),
    )
    .filter((s) => s.revenueAgorot > 0);

  const pooled = (xs: { rev: number; cost: Range; hours: Range }[]): Range | null =>
    xs.length
      ? {
          low: xs.reduce((s, x) => s + x.rev - x.cost.high, 0) / xs.reduce((s, x) => s + x.hours.high, 0),
          high: xs.reduce((s, x) => s + x.rev - x.cost.low, 0) / xs.reduce((s, x) => s + x.hours.low, 0),
        }
      : null;
  const okShifts = shiftSummaries.filter((s) => s.vehicleCostAgorot);
  const netPerShiftHour = pooled(okShifts.map((s) => ({ rev: s.revenueAgorot, cost: s.vehicleCostAgorot!, hours: { low: s.shiftHours, high: s.shiftHours } })));
  const netPerActiveHour = pooled(
    chains.filter((c) => c.complete).map((c) => ({ rev: c.economics.revenueAgorot, cost: c.economics.vehicleCostAgorot!, hours: { low: c.economics.durationMin!.low / 60, high: c.economics.durationMin!.high / 60 } })),
  );

  // Decisions: accepted = linked to a trip; declined = a decline within 2 min of the card and before the next card; else unknown.
  const acceptedOfferIds = new Map(trips.map((t) => [offerOf(t)?.id, t] as const).filter(([id]) => id));
  const decisions: DecisionRecord[] = offers
    .filter((o) => shifts.some((s) => o.firstSeenAtUtc >= s.startedAtUtc && o.firstSeenAtUtc <= (s.endedAtUtc ?? s.factsEndedAtUtc!)))
    .map((o) => {
      const t = acceptedOfferIds.get(o.id);
      // A decline belongs to the last card seen before it (one event never counts for two offers).
      const next = offers.find((x) => x.firstSeenAtUtc > o.firstSeenAtUtc);
      const declined = declines.some(
        (d) => d.actionAtUtc >= o.firstSeenAtUtc && d.actionAtUtc.getTime() - o.lastSeenAtUtc.getTime() <= 120_000 && (!next || d.actionAtUtc < next.firstSeenAtUtc),
      );
      const chain = t ? chains.find((c) => c.chainKey === t.chainKey) : undefined;
      const net =
        chain && chain.standalone && chain.complete
          ? { low: chain.economics.revenueAgorot - chain.economics.vehicleCostAgorot!.high, high: chain.economics.revenueAgorot - chain.economics.vehicleCostAgorot!.low }
          : null;
      return {
        offerId: o.id,
        seenAtMs: o.firstSeenAtUtc.getTime(),
        amountAgorot: o.amountAgorot,
        distanceKm: o.distanceMeters === null ? null : o.distanceMeters / 1000,
        decision: t ? "ACCEPTED" : declined ? "DECLINED" : "UNKNOWN",
        standaloneNetAgorot: net,
      } satisfies DecisionRecord;
    });

  const coverageNotes: string[] = [];
  const total = chains.length;
  coverageNotes.push(`${total} chains (${chains.filter((c) => c.standalone).length} standalone), ${chains.filter((c) => c.complete).length} with complete economics.`);
  coverageNotes.push(`${shiftSummaries.length} shift(s) with shift facts; ${okShifts.length} with reliable shift GPS.`);
  const unverified = chains.filter((c) => c.standalone && !c.offers[0].pickupVerified).length;
  if (unverified) coverageNotes.push(`${unverified} standalone chain(s) without a verified pickup area (address not geocoded, ambiguous name, or wrong match rejected).`);
  coverageNotes.push(`Shadow-mode predictions stored (immutable): ${shadowCount}.`);
  const acc = trips.filter((t) => shifts.some((s) => t.startedAtUtc >= s.startedAtUtc && t.startedAtUtc <= (s.endedAtUtc ?? s.factsEndedAtUtc!))).map((t) => t.startedAtUtc.getTime());
  const done = chainRows.flatMap((c) => (c.completionSlidesJson ? (JSON.parse(c.completionSlidesJson) as number[]) : []));
  const allSessions = await prisma.collectorShift.count();
  coverageNotes.unshift(`Scope: work shifts only (sessions ≥ ${settings.evidenceMinWorkShiftMinutes} min); ${allSessions - shifts.length} shorter/test session(s) excluded.`);
  return {
    costPerKmAgorot: cost,
    scope: {
      shiftBoundaries: shifts.map((s) => ({ startMs: s.startedAtUtc.getTime(), endMs: (s.endedAtUtc ?? s.factsEndedAtUtc!).getTime() })),
      acceptances: acc.length ? { firstMs: Math.min(...acc), lastMs: Math.max(...acc) } : null,
      completions: done.length ? { firstMs: Math.min(...done), lastMs: Math.max(...done) } : null,
      excludedSessions: allSessions - shifts.length,
    },
    chains,
    shifts: shiftSummaries,
    netPerShiftHourAgorot: netPerShiftHour,
    netPerActiveHourAgorot: netPerActiveHour,
    dimensions: DIMENSIONS.map((d) => analyzeDimension(d, chains)),
    decisions,
    strategies: CANDIDATE_STRATEGIES.map((s) => evaluateStrategy(s, decisions)),
    shadowPredictions: shadowCount,
    coverageNotes,
  };
}
