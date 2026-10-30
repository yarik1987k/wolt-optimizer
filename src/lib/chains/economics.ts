import { prisma } from "@/lib/db";
import { getSettings } from "@/lib/settings";
import { addDays, localToUtcMs } from "@/lib/time";

/**
 * Chain economics: what a delivery chain (overlapping accepted offers) earned per hour, before and after
 * vehicle cost. Everything is a RANGE built from the Collector's evidence ranges; a missing input makes
 * the result null with the reason — never a guess.
 *
 * The target is earnings after variable vehicle cost per SHIFT hour (idle time included). Comparing whole
 * chains does not prove an added offer's value: that needs the counterfactual route without it, which stays
 * UNKNOWN until a justified model exists.
 *
 * Labels: OBSERVED (seen with a time), WOLT_DISPLAY (a number Wolt showed, meaning unverified),
 * ESTIMATE (ours, with its method).
 */

export interface Range {
  low: number;
  high: number;
}

export interface ChainInput {
  chainKey: string;
  offers: number;
  revenueAgorot: number;
  ordersLow: number | null;
  ordersHigh: number | null;
  startEarliestMs: number | null;
  startLatestMs: number;
  endEarliestMs: number | null;
  endLatestMs: number | null;
  endEvidence: string;
  gpsSamples: number;
  gpsMaxGapMs: number | null;
  gpsCoverage: number | null;
  gpsPathKm: number | null;
  gpsSpeedKm: number | null;
  gpsDistanceReliable: boolean;
  missing: string[];
  /** Offers in the chain accepted while orders were on board (case B). */
  addedOffers: number;
  /** OBSERVED drop-off slides (one per completed delivery). */
  completionSlides: number;
}

export interface ChainEconomics {
  chainKey: string;
  kind: "SINGLE_OFFER" | "WITH_ADDED_OFFERS";
  endEvidence: string;
  completionSlides: number;
  offers: number;
  addedOffers: number;
  revenueAgorot: number;
  orders: Range | null;
  durationMin: Range | null;
  distanceKm: Range | null;
  vehicleCostAgorot: Range | null;
  grossPerHourAgorot: Range | null;
  netPerHourAgorot: Range | null;
  /** Why a figure is missing or weak. */
  missing: string[];
}

const r = (low: number, high: number): Range => ({ low: Math.min(low, high), high: Math.max(low, high) });

export function chainEconomics(c: ChainInput, costPerKmAgorot: number | null): ChainEconomics {
  const missing = [...c.missing];
  // Duration: shortest = latest start → earliest end; longest = earliest start → latest end.
  let durationMin: Range | null = null;
  if (c.endEarliestMs !== null && c.endLatestMs !== null) {
    durationMin = r((c.endEarliestMs - c.startLatestMs) / 60_000, (c.endLatestMs - (c.startEarliestMs ?? c.startLatestMs)) / 60_000);
    if (durationMin.low <= 0) {
      missing.push("earliest end before latest start — duration range invalid");
      durationMin = null;
    }
  }
  const distanceKm =
    c.gpsDistanceReliable && c.gpsPathKm !== null ? r(c.gpsPathKm, c.gpsSpeedKm ?? c.gpsPathKm) : null;
  if (distanceKm === null && !c.missing.some((m) => m.startsWith("GPS"))) missing.push("no reliable GPS distance");
  if (costPerKmAgorot === null) missing.push("vehicle cost per km not set");
  const vehicleCostAgorot = distanceKm && costPerKmAgorot !== null ? r(distanceKm.low * costPerKmAgorot, distanceKm.high * costPerKmAgorot) : null;
  const grossPerHourAgorot = durationMin ? r(c.revenueAgorot / (durationMin.high / 60), c.revenueAgorot / (durationMin.low / 60)) : null;
  const netPerHourAgorot =
    durationMin && vehicleCostAgorot
      ? r((c.revenueAgorot - vehicleCostAgorot.high) / (durationMin.high / 60), (c.revenueAgorot - vehicleCostAgorot.low) / (durationMin.low / 60))
      : null;
  return {
    chainKey: c.chainKey,
    kind: c.addedOffers > 0 ? "WITH_ADDED_OFFERS" : "SINGLE_OFFER",
    endEvidence: c.endEvidence,
    completionSlides: c.completionSlides,
    offers: c.offers,
    addedOffers: c.addedOffers,
    revenueAgorot: c.revenueAgorot,
    orders: c.ordersLow !== null && c.ordersHigh !== null ? r(c.ordersLow, c.ordersHigh) : null,
    durationMin,
    distanceKm,
    vehicleCostAgorot,
    grossPerHourAgorot,
    netPerHourAgorot,
    missing,
  };
}

/**
 * An offer added to an active route, against the chain WITHOUT it. Only the revenue is known (exact). The
 * duration and distance without it are UNKNOWN: Wolt's route numbers are fixed target times that did not
 * move when work was added (eta-validation-1), and no justified route model exists. No bound, no estimate,
 * no marginal rate — and no assumption that the added time or distance is non-negative.
 */
export interface AddedOfferCounterfactual {
  tripKey: string;
  chainKey: string;
  amountAgorot: number;
  chainWithout: { revenueAgorot: number; duration: "UNKNOWN"; distance: "UNKNOWN" };
  /** Snapshot facts around acceptance (OBSERVED / WOLT_DISPLAY), for a future model. */
  routeBeforeAgeMin: number | null;
  dropScreenBeforeAgeMin: number | null;
  routeAfterDelayMin: number | null;
  completionsBetweenBeforeAndAccept: number | null;
  carriedTargetShiftMin: number | null;
  routeChangedForOtherReasons: boolean | null;
  notes: string[];
}

export function counterfactual(
  o: {
    tripKey: string;
    chainKey: string;
    amountAgorot: number;
    routeBeforeAgeMs: number | null;
    dropScreenBeforeAgeMs: number | null;
    routeAfterDelayMs: number | null;
    completionsBetween: number | null;
    carriedTargetShiftMin: number | null;
    routeChangedForOtherReasons: boolean | null;
    notes: string[];
  },
  chain: ChainEconomics,
): AddedOfferCounterfactual {
  const min = (ms: number | null) => (ms === null ? null : ms / 60_000);
  return {
    tripKey: o.tripKey,
    chainKey: o.chainKey,
    amountAgorot: o.amountAgorot,
    chainWithout: { revenueAgorot: chain.revenueAgorot - o.amountAgorot, duration: "UNKNOWN", distance: "UNKNOWN" },
    routeBeforeAgeMin: min(o.routeBeforeAgeMs),
    dropScreenBeforeAgeMin: min(o.dropScreenBeforeAgeMs),
    routeAfterDelayMin: min(o.routeAfterDelayMs),
    completionsBetweenBeforeAndAccept: o.completionsBetween,
    carriedTargetShiftMin: o.carriedTargetShiftMin,
    routeChangedForOtherReasons: o.routeChangedForOtherReasons,
    notes: o.notes,
  };
}

/** Earnings after variable vehicle cost per SHIFT hour (idle included) — the optimisation target. */
export interface ShiftSummary {
  shiftId: string;
  startMs: number;
  endMs: number;
  shiftHours: number;
  revenueAgorot: number;
  completionSlides: number;
  chainHours: Range;
  verifiedIdleHours: number;
  onlineUnverifiedHours: number;
  offlineHours: number;
  unknownHours: number;
  distanceKm: Range | null;
  vehicleCostAgorot: Range | null;
  grossPerShiftHourAgorot: number;
  netPerShiftHourAgorot: Range | null;
  netPerActiveHourAgorot: Range | null;
  missing: string[];
}

export function shiftSummary(
  s: {
    shiftId: string;
    startMs: number;
    endMs: number;
    revenueAgorot: number;
    completionSlides: number;
    chainMsLow: number;
    chainMsHigh: number;
    verifiedIdleMs: number;
    onlineUnverifiedMs: number;
    offlineMs: number;
    unknownMs: number;
    gpsPathKm: number | null;
    gpsSpeedKm: number | null;
    gpsDistanceReliable: boolean;
    missing: string[];
  },
  costPerKmAgorot: number | null,
): ShiftSummary {
  const h = (ms: number) => ms / 3_600_000;
  const shiftHours = h(s.endMs - s.startMs);
  const missing = [...s.missing];
  const distanceKm = s.gpsDistanceReliable && s.gpsPathKm !== null ? r(s.gpsPathKm, s.gpsSpeedKm ?? s.gpsPathKm) : null;
  if (!distanceKm) missing.push("no reliable shift GPS distance");
  if (costPerKmAgorot === null) missing.push("vehicle cost per km not set");
  const cost = distanceKm && costPerKmAgorot !== null ? r(distanceKm.low * costPerKmAgorot, distanceKm.high * costPerKmAgorot) : null;
  const chainHours = r(h(s.chainMsLow), h(s.chainMsHigh));
  return {
    shiftId: s.shiftId,
    startMs: s.startMs,
    endMs: s.endMs,
    shiftHours,
    revenueAgorot: s.revenueAgorot,
    completionSlides: s.completionSlides,
    chainHours,
    verifiedIdleHours: h(s.verifiedIdleMs),
    onlineUnverifiedHours: h(s.onlineUnverifiedMs),
    offlineHours: h(s.offlineMs),
    unknownHours: h(s.unknownMs),
    distanceKm,
    vehicleCostAgorot: cost,
    grossPerShiftHourAgorot: s.revenueAgorot / shiftHours,
    netPerShiftHourAgorot: cost ? r((s.revenueAgorot - cost.high) / shiftHours, (s.revenueAgorot - cost.low) / shiftHours) : null,
    netPerActiveHourAgorot:
      cost && chainHours.low > 0 ? r((s.revenueAgorot - cost.high) / chainHours.high, (s.revenueAgorot - cost.low) / chainHours.low) : null,
    missing,
  };
}

export interface OfferContextRow {
  tripKey: string;
  offerSeenAtMs: number | null;
  acceptedEarliestMs: number | null;
  acceptedLatestMs: number;
  amountAgorot: number | null;
  acceptContext: string | null;
  activeOrdersBefore: number | null;
  newOrdersLow: number | null;
  newOrdersHigh: number | null;
  newOrdersConfidence: string | null;
  offerCell: string | null;
  hasRouteBefore: boolean;
  hasRouteAfter: boolean;
  newPickupStops: number | null;
  newDropoffStopsByShape: number | null;
  ambiguousDropoffStops: number | null;
  chainKey: string | null;
  /** Single-order training sample: proven single order, alone in its chain, accepted idle. */
  trainingEligible: boolean;
}

export interface ChainReport {
  day: string;
  costPerKmAgorot: number | null;
  shifts: ShiftSummary[];
  chains: ChainEconomics[];
  offers: OfferContextRow[];
  counterfactuals: AddedOfferCounterfactual[];
  /** What the incremental question still needs. */
  incrementalReadiness: string[];
}

export async function chainReport(day: string): Promise<ChainReport> {
  const from = new Date(localToUtcMs(day, "00:00"));
  const to = new Date(localToUtcMs(addDays(day, 1), "00:00"));
  const [settings, chains, trips] = await Promise.all([
    getSettings(),
    prisma.collectorChain.findMany({ where: { startLatestAtUtc: { gte: from, lt: to } }, orderBy: { startLatestAtUtc: "asc" } }),
    prisma.collectorTrip.findMany({ where: { startedAtUtc: { gte: from, lt: to } }, orderBy: { startedAtUtc: "asc" } }),
  ]);
  const cost = settings.vehicleCostPerKmAgorot;
  const econ = chains.map((c) =>
    chainEconomics(
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
        addedOffers: trips.filter((t) => t.chainKey === c.chainKey && t.acceptContext === "ADDED_TO_ACTIVE_ROUTE").length,
        completionSlides: c.completionSlidesJson ? (JSON.parse(c.completionSlidesJson) as number[]).length : 0,
      },
      cost,
    ),
  );
  const offers: OfferContextRow[] = trips.map((t) => ({
    tripKey: t.tripKey,
    offerSeenAtMs: t.offerSeenAtUtc?.getTime() ?? null,
    acceptedEarliestMs: t.acceptedEarliestAtUtc?.getTime() ?? null,
    acceptedLatestMs: t.startedAtUtc.getTime(),
    amountAgorot: (JSON.parse(t.offerAmountsJson) as number[])[0] ?? null,
    acceptContext: t.acceptContext,
    activeOrdersBefore: t.activeOrdersBefore,
    newOrdersLow: t.newOrdersLow,
    newOrdersHigh: t.newOrdersHigh,
    newOrdersConfidence: t.newOrdersConfidence,
    offerCell: t.offerCell,
    hasRouteBefore: t.routeBeforeJson !== null,
    hasRouteAfter: t.routeAfterJson !== null,
    newPickupStops: t.newPickupStops,
    newDropoffStopsByShape: t.newDropoffStopsByShape,
    ambiguousDropoffStops: t.ambiguousDropoffStops,
    chainKey: t.chainKey,
    trainingEligible:
      t.orderEvidence === "SINGLE_ORDER" && t.acceptContext === "IDLE" && t.tripBoundaryValid === true && t.endEvidence !== "UNKNOWN",
  }));
  const counterfactuals = trips
    .filter((t) => t.acceptContext === "ADDED_TO_ACTIVE_ROUTE" && t.chainKey)
    .flatMap((t) => {
      const chain = econ.find((c) => c.chainKey === t.chainKey);
      if (!chain) return [];
      return [
        counterfactual(
          {
            tripKey: t.tripKey,
            chainKey: t.chainKey!,
            amountAgorot: (JSON.parse(t.offerAmountsJson) as number[])[0] ?? 0,
            routeBeforeAgeMs: t.routeBeforeAgeMs,
            dropScreenBeforeAgeMs: t.dropScreenBeforeAgeMs,
            routeAfterDelayMs: t.routeAfterDelayMs,
            completionsBetween: t.completionsBetweenBeforeAccept,
            carriedTargetShiftMin: t.carriedTargetShiftMin,
            routeChangedForOtherReasons: t.routeChangedOtherReasons,
            notes: t.estimateNotesJson ? (JSON.parse(t.estimateNotesJson) as string[]) : [],
          },
          chain,
        ),
      ];
    });
  const shiftRows = await prisma.collectorShift.findMany({
    where: { startedAtUtc: { gte: from, lt: to }, factsEndedAtUtc: { not: null } },
    orderBy: { startedAtUtc: "asc" },
  });
  const shifts = shiftRows.map((x) =>
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
  ).filter((x) => x.revenueAgorot > 0 || x.shiftHours > 0.25);
  return { day, costPerKmAgorot: cost, shifts, chains: econ, offers, counterfactuals, incrementalReadiness: readiness(econ, offers) };
}

/** Minimum data before comparing chains with and without added offers (not a statistical guarantee). */
export const MIN_CHAINS_PER_KIND = 10;

export function readiness(chains: ChainEconomics[], offers: OfferContextRow[]): string[] {
  const usable = chains.filter((c) => c.netPerHourAgorot !== null);
  const single = usable.filter((c) => c.kind === "SINGLE_OFFER").length;
  const added = usable.filter((c) => c.kind === "WITH_ADDED_OFFERS").length;
  const caseB = offers.filter((o) => o.acceptContext === "ADDED_TO_ACTIVE_ROUTE");
  const out = [
    `Chains with complete data (duration + reliable GPS distance): ${usable.length} of ${chains.length} — single-offer ${single}, with added offers ${added}. At least ${MIN_CHAINS_PER_KIND} of each kind are needed before comparing them.`,
    `Offers added to an active route (case B): ${caseB.length}; with a route snapshot before and after: ${caseB.filter((o) => o.hasRouteBefore && o.hasRouteAfter).length}.`,
    "Incremental time and distance of a single added offer are not measured: offers inside a chain have no completion times of their own. Only comparing chains answers the question.",
  ];
  return out;
}
