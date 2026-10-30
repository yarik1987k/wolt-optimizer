import { prisma } from "@/lib/db";
import { chainEconomics, type Range } from "@/lib/chains/economics";
import { getSettings, type AppSettings } from "@/lib/settings";
import { clusteredEffectiveN, kishEffectiveN, summarize, unitsPerArm, type Summary } from "./stats";

/**
 * Evidence and Experiment Planning.
 *
 * Findings are labelled:
 *  - OBSERVATION: directly measured, with its period and unit;
 *  - HYPOTHESIS: plausible, not established (the reason and what would establish it are stated);
 *  - VALIDATED_FINDING: repeated across ≥ evidenceMinShiftsForValidation independent work shifts per arm, with a
 *    95% interval that excludes zero (stats.compareArms). None exists until experiments have run.
 *
 * The primary metric is net revenue (accepted-offer amounts − vehicle cost of GPS km) per FULL shift hour; its
 * independent unit is the work shift. Chains inside one shift are not independent evidence for a shift-level claim.
 */

export type FindingKind = "OBSERVATION" | "HYPOTHESIS" | "VALIDATED_FINDING";

export interface Finding {
  kind: FindingKind;
  topic: string;
  statement: string;
  /** Unit, period and the numbers behind it. */
  basis: string;
  /** HYPOTHESIS: what would establish or refute it. */
  needs?: string;
}

// ---- periods: never mix observation periods silently ----

export type PeriodKind = "WORK_SHIFT" | "TEST_OR_SHORT_SESSION" | "OUTSIDE_SHIFT_SESSIONS";

export interface TimeSpan {
  n: number;
  firstMs: number | null;
  lastMs: number | null;
}

export interface Period {
  kind: PeriodKind;
  shiftId: string | null;
  /** Shift boundaries (session start/end); null for offers outside any session. */
  shiftStartMs: number | null;
  shiftEndMs: number | null;
  /** Offer cards first seen. */
  offersSeen: TimeSpan;
  /** Offer acceptances (pickup screen appeared). */
  acceptances: TimeSpan;
  /** OBSERVED completions (drop-off slides). */
  completions: TimeSpan;
  chains: number;
  revenueAgorot: number;
}

const span = (xs: number[]): TimeSpan => ({ n: xs.length, firstMs: xs.length ? Math.min(...xs) : null, lastMs: xs.length ? Math.max(...xs) : null });

// ---- constraints (per work shift) ----

export interface Constraint {
  key: "LOW_REVENUE_PER_ACTIVE_MINUTE" | "IDLE_WAITING" | "STATIONARY_IN_CHAINS" | "LONG_TRIPS_VS_PAY" | "VEHICLE_COST" | "REPOSITIONING";
  label: string;
  /** Measured on the work shift(s) in scope. */
  observed: string;
  /** Share of shift time or of revenue it represents (for ordering only). */
  share: number | null;
  shareOf: "SHIFT_TIME" | "REVENUE" | "GPS_KM" | "NONE";
  /** Why it limits net per shift hour, and how sure we are about the mechanism. */
  interpretation: string;
}

export interface ShiftEvidence {
  shiftId: string;
  startMs: number;
  endMs: number;
  hours: number;
  revenueAgorot: number;
  vehicleCostAgorot: Range | null;
  netPerShiftHourAgorot: Range | null;
  chainMinutes: Range;
  chainStationaryMin: number | null;
  pickupPhaseStationaryMin: number | null;
  deliveryPhaseStationaryMin: number | null;
  chainMovingMin: number | null;
  betweenChainsMin: number | null;
  betweenChainsKm: number | null;
  verifiedIdleMin: number;
  constraints: Constraint[];
  /** Chains of the shift: ₪ per active minute, for the "long trips vs pay" constraint. */
  chainRates: { chainKey: string; offers: number; revenueAgorot: number; minutes: Range; agorotPerMinute: Range; km: Range | null }[];
}

// ---- experiment ----

export interface ExperimentPlan {
  id: string;
  title: string;
  hypothesis: string;
  whyThisFirst: string[];
  baseline: { value: Range | null; unit: string; basis: string; uncertainty: string };
  arms: { name: string; rule: string }[];
  unit: string;
  primaryMetric: string;
  secondaryMetrics: string[];
  dataRequired: { item: string; status: "AVAILABLE" | "NOT_YET_BUILT" }[];
  sampleSize: string[];
  supporting: string;
  contradicting: string[];
  confounders: { factor: string; mitigation: string }[];
  risks: string[];
}

export interface EvidenceReport {
  settings: Pick<AppSettings, "evidenceMinWorkShiftMinutes" | "evidenceMinShiftsForValidation" | "evidenceMinCoverage" | "evidenceSmallestEffectAgorotPerHour" | "vehicleCostPerKmAgorot">;
  periods: Period[];
  workShifts: ShiftEvidence[];
  primaryMetric: { perShift: Summary; effectiveN: number; note: string };
  chainLevel: { chains: number; kishN: number; effectiveN: number; perChainGrossPerActiveHour: Summary; note: string };
  findings: Finding[];
  experiment: ExperimentPlan;
}

const min = (ms: number | null | undefined) => (ms == null ? null : ms / 60_000);

export async function evidenceReport(): Promise<EvidenceReport> {
  const settings = await getSettings();
  const cost = settings.vehicleCostPerKmAgorot;
  const [shifts, offers, chainRows, trips, checks] = await Promise.all([
    prisma.collectorShift.findMany({ orderBy: { startedAtUtc: "asc" } }),
    prisma.offer.findMany({ orderBy: { firstSeenAtUtc: "asc" } }),
    prisma.collectorChain.findMany({ orderBy: { startLatestAtUtc: "asc" } }),
    prisma.collectorTrip.findMany({ orderBy: { startedAtUtc: "asc" } }),
    prisma.collectorEtaCheck.findMany({ orderBy: { completedAtUtc: "asc" } }),
  ]);

  const end = (s: (typeof shifts)[number]) => (s.endedAtUtc ?? s.factsEndedAtUtc ?? s.startedAtUtc).getTime();
  const inShift = (ms: number, s: (typeof shifts)[number]) => ms >= s.startedAtUtc.getTime() && ms <= end(s);
  const completionTimes = [
    ...new Set([...chainRows.flatMap((c) => (c.completionSlidesJson ? (JSON.parse(c.completionSlidesJson) as number[]) : [])), ...checks.map((k) => k.completedAtUtc.getTime())]),
  ];

  // Periods: each shift session, then offers seen outside every session (grouped by local day).
  const periods: Period[] = shifts.map((s) => {
    const minutes = (end(s) - s.startedAtUtc.getTime()) / 60_000;
    const own = chainRows.filter((c) => inShift(c.startLatestAtUtc.getTime(), s));
    return {
      kind: minutes >= settings.evidenceMinWorkShiftMinutes ? "WORK_SHIFT" : "TEST_OR_SHORT_SESSION",
      shiftId: s.id,
      shiftStartMs: s.startedAtUtc.getTime(),
      shiftEndMs: end(s),
      offersSeen: span(offers.map((o) => o.firstSeenAtUtc.getTime()).filter((t) => inShift(t, s))),
      acceptances: span(trips.map((t) => t.startedAtUtc.getTime()).filter((t) => inShift(t, s))),
      completions: span(completionTimes.filter((t) => inShift(t, s))),
      chains: own.length,
      revenueAgorot: own.reduce((a, c) => a + c.revenueAgorot, 0),
    } satisfies Period;
  });
  const outside = offers.map((o) => o.firstSeenAtUtc.getTime()).filter((t) => !shifts.some((s) => inShift(t, s)));
  if (outside.length)
    periods.push({ kind: "OUTSIDE_SHIFT_SESSIONS", shiftId: null, shiftStartMs: null, shiftEndMs: null, offersSeen: span(outside), acceptances: span([]), completions: span([]), chains: 0, revenueAgorot: 0 });

  // Work shifts with complete shift facts.
  const work = shifts.filter((s) => periods.find((p) => p.shiftId === s.id)?.kind === "WORK_SHIFT" && s.factsEndedAtUtc);
  const workShifts: ShiftEvidence[] = work.map((s) => {
    const hours = (end(s) - s.startedAtUtc.getTime()) / 3_600_000;
    const km = s.gpsDistanceReliable && s.gpsPathKm !== null ? { low: Math.min(s.gpsPathKm, s.gpsSpeedKm ?? s.gpsPathKm), high: Math.max(s.gpsPathKm, s.gpsSpeedKm ?? s.gpsPathKm) } : null;
    const vc = km && cost !== null ? { low: km.low * cost, high: km.high * cost } : null;
    const revenue = s.revenueAgorot ?? 0;
    const own = chainRows.filter((c) => inShift(c.startLatestAtUtc.getTime(), s));
    const chainRates = own.flatMap((c) => {
      const e = chainEconomics(
        {
          chainKey: c.chainKey, offers: c.offers, revenueAgorot: c.revenueAgorot, ordersLow: c.ordersLow, ordersHigh: c.ordersHigh,
          startEarliestMs: c.startEarliestAtUtc?.getTime() ?? null, startLatestMs: c.startLatestAtUtc.getTime(),
          endEarliestMs: c.endEarliestAtUtc?.getTime() ?? null, endLatestMs: c.endLatestAtUtc?.getTime() ?? null, endEvidence: c.endEvidence,
          gpsSamples: c.gpsSamples, gpsMaxGapMs: c.gpsMaxGapMs, gpsCoverage: c.gpsCoverage, gpsPathKm: c.gpsPathKm, gpsSpeedKm: c.gpsSpeedKm,
          gpsDistanceReliable: c.gpsDistanceReliable, missing: JSON.parse(c.missingJson) as string[], addedOffers: 0, completionSlides: 0,
        },
        cost,
      );
      return e.durationMin
        ? [{ chainKey: c.chainKey, offers: c.offers, revenueAgorot: c.revenueAgorot, minutes: e.durationMin, agorotPerMinute: { low: c.revenueAgorot / e.durationMin.high, high: c.revenueAgorot / e.durationMin.low }, km: e.distanceKm }]
        : [];
    });
    const chainMinutes = { low: (s.chainMsLow ?? 0) / 60_000, high: (s.chainMsHigh ?? 0) / 60_000 };
    const shiftMin = hours * 60;
    const stationary = own.every((c) => c.gpsStationaryMs !== null) ? own.reduce((a, c) => a + (c.gpsStationaryMs ?? 0), 0) / 60_000 : null;
    const pickStill = own.every((c) => c.pickupPhaseStationaryMs !== null) ? own.reduce((a, c) => a + (c.pickupPhaseStationaryMs ?? 0), 0) / 60_000 : null;
    const dropStill = own.every((c) => c.deliveryPhaseStationaryMs !== null) ? own.reduce((a, c) => a + (c.deliveryPhaseStationaryMs ?? 0), 0) / 60_000 : null;
    const moving = own.every((c) => c.gpsMovingMs !== null) ? own.reduce((a, c) => a + (c.gpsMovingMs ?? 0), 0) / 60_000 : null;
    const between = min(s.betweenChainsMs);
    const idle = (s.verifiedIdleMs ?? 0) / 60_000;
    const perMin = (x: number) => `₪${(x / 100).toFixed(2)}`;
    const avgPerActiveMin = revenue / ((chainMinutes.low + chainMinutes.high) / 2);
    const slowest = [...chainRates].sort((a, b) => (a.agorotPerMinute.low + a.agorotPerMinute.high) / 2 - (b.agorotPerMinute.low + b.agorotPerMinute.high) / 2)[0];
    const constraints: Constraint[] = [
      {
        key: "STATIONARY_IN_CHAINS",
        label: "Stationary time inside chains",
        observed: stationary === null ? "not measured" : `${stationary.toFixed(0)} min of ${shiftMin.toFixed(0)} (to pickup + waiting: ${pickStill?.toFixed(0) ?? "?"} min · delivery: ${dropStill?.toFixed(0) ?? "?"} min) with GPS speed < 1 m/s`,
        share: stationary === null ? null : stationary / shiftMin,
        shareOf: "SHIFT_TIME",
        interpretation:
          "Waiting at restaurants, handover at the door and traffic stops all look the same in GPS speed; which dominates is not known. The split is at the chain's first pickup, so later pickups of added offers fall in the delivery phase.",
      },
      {
        key: "LOW_REVENUE_PER_ACTIVE_MINUTE",
        label: "Revenue per active minute",
        observed: `${perMin(avgPerActiveMin)} per active minute on average; chains ranged ${chainRates.length ? `${perMin(Math.min(...chainRates.map((c) => c.agorotPerMinute.low)))}–${perMin(Math.max(...chainRates.map((c) => c.agorotPerMinute.high)))}` : "—"}`,
        share: null,
        shareOf: "NONE",
        interpretation: "Active time was " + ((chainMinutes.high / shiftMin) * 100).toFixed(0) + "% of the shift, so pay per active minute sets most of the shift result.",
      },
      {
        key: "LONG_TRIPS_VS_PAY",
        label: "Long trips relative to payment",
        observed: slowest
          ? `slowest chain ${perMin(slowest.revenueAgorot)} over ${slowest.minutes.low.toFixed(0)}–${slowest.minutes.high.toFixed(0)} min = ${perMin(slowest.agorotPerMinute.low)}–${perMin(slowest.agorotPerMinute.high)}/min; at the shift average it would have earned ≈${perMin(avgPerActiveMin * ((slowest.minutes.low + slowest.minutes.high) / 2) - slowest.revenueAgorot)} more`
          : "no chain with a known duration",
        share: slowest ? (avgPerActiveMin * ((slowest.minutes.low + slowest.minutes.high) / 2) - slowest.revenueAgorot) / revenue : null,
        shareOf: "REVENUE",
        interpretation: "Whether such trips can be recognised from the card before accepting is not known (one example).",
      },
      {
        key: "VEHICLE_COST",
        label: "Vehicle cost",
        observed: vc ? `₪${(vc.low / 100).toFixed(2)}–₪${(vc.high / 100).toFixed(2)} for ${km!.low.toFixed(1)}–${km!.high.toFixed(1)} GPS km` : "no reliable GPS distance or no cost per km",
        share: vc ? (vc.low + vc.high) / 2 / revenue : null,
        shareOf: "REVENUE",
        interpretation: "Proportional to km driven; GPS distance on the 500 m grid probably understates it.",
      },
      {
        key: "IDLE_WAITING",
        label: "Idle time (online, no accepted offer)",
        observed: `${idle.toFixed(0)} min verified idle; ${between?.toFixed(0) ?? "?"} min between chains in total`,
        share: between === null ? idle / shiftMin : between / shiftMin,
        shareOf: "SHIFT_TIME",
        interpretation: "Small in this shift: the next offer arrived within minutes each time.",
      },
      {
        key: "REPOSITIONING",
        label: "Unproductive repositioning",
        observed: `${s.betweenChainsKm?.toFixed(1) ?? "?"} GPS km and ${min(s.betweenChainsMovingMs)?.toFixed(0) ?? "?"} min moving between chains`,
        share: s.betweenChainsKm !== null && km ? s.betweenChainsKm / ((km.low + km.high) / 2) : null,
        shareOf: "GPS_KM",
        interpretation: "Share of the shift's km driven with no accepted offer.",
      },
    ];
    return {
      shiftId: s.id, startMs: s.startedAtUtc.getTime(), endMs: end(s), hours, revenueAgorot: revenue, vehicleCostAgorot: vc,
      netPerShiftHourAgorot: vc ? { low: (revenue - vc.high) / hours, high: (revenue - vc.low) / hours } : null,
      chainMinutes, chainStationaryMin: stationary, pickupPhaseStationaryMin: pickStill, deliveryPhaseStationaryMin: dropStill, chainMovingMin: moving,
      betweenChainsMin: between, betweenChainsKm: s.betweenChainsKm, verifiedIdleMin: idle, constraints, chainRates,
    };
  });

  // Primary metric across work shifts (unit = shift). Midpoints of each shift's range.
  const perShift = summarize(workShifts.filter((w) => w.netPerShiftHourAgorot).map((w) => (w.netPerShiftHourAgorot!.low + w.netPerShiftHourAgorot!.high) / 2));
  const chainRates = workShifts.flatMap((w) => w.chainRates.map((c) => ({ ...c, shiftId: w.shiftId })));
  const weights = chainRates.map((c) => (c.minutes.low + c.minutes.high) / 2);
  const kish = kishEffectiveN(weights);
  const effectiveN = clusteredEffectiveN(kish, workShifts.length, workShifts.length ? chainRates.length / workShifts.length : 0, null);

  const findings = buildFindings(periods, workShifts, perShift, settings, checks.length);
  return {
    settings: {
      evidenceMinWorkShiftMinutes: settings.evidenceMinWorkShiftMinutes,
      evidenceMinShiftsForValidation: settings.evidenceMinShiftsForValidation,
      evidenceMinCoverage: settings.evidenceMinCoverage,
      evidenceSmallestEffectAgorotPerHour: settings.evidenceSmallestEffectAgorotPerHour,
      vehicleCostPerKmAgorot: cost,
    },
    periods,
    workShifts,
    primaryMetric: {
      perShift,
      effectiveN: perShift.n,
      note: perShift.n < 2 ? "one work shift: between-shift variation is unknown, so no interval and no comparison is possible yet" : "one value per work shift (midpoint of its range)",
    },
    chainLevel: {
      chains: chainRates.length,
      kishN: kish,
      effectiveN,
      perChainGrossPerActiveHour: summarize(chainRates.map((c) => ((c.agorotPerMinute.low + c.agorotPerMinute.high) / 2) * 60)),
      note:
        workShifts.length <= 1
          ? "all chains come from one shift: they describe that evening, not shifts in general (effective n for shift-level claims = 1)"
          : "chains are clustered in shifts; effective n is capped by the number of shifts while the between-shift correlation is unknown",
    },
    findings,
    experiment: firstExperiment(workShifts, perShift, settings),
  };
}

function buildFindings(periods: Period[], work: ShiftEvidence[], perShift: Summary, settings: AppSettings, etaChecks: number): Finding[] {
  const f: Finding[] = [];
  const hm = (ms: number | null) => (ms === null ? "—" : new Date(ms).toLocaleTimeString("en-GB", { timeZone: "Asia/Jerusalem", hour: "2-digit", minute: "2-digit" }));
  const test = periods.filter((p) => p.kind === "TEST_OR_SHORT_SESSION");
  const outside = periods.find((p) => p.kind === "OUTSIDE_SHIFT_SESSIONS");
  f.push({
    kind: "OBSERVATION",
    topic: "Scope",
    statement: `${work.length} work shift(s) in scope; ${test.length} short/test session(s) and ${outside?.offersSeen.n ?? 0} offer(s) outside any session are excluded from shift economics.`,
    basis: `Work shift = session ≥ ${settings.evidenceMinWorkShiftMinutes} min. Test sessions: ${test.map((p) => `${hm(p.shiftStartMs)}–${hm(p.shiftEndMs)} (${p.offersSeen.n} offers)`).join(", ") || "none"}.`,
  });
  for (const w of work) {
    f.push({
      kind: "OBSERVATION",
      topic: "Primary metric",
      statement: `Net revenue per full shift hour: ${w.netPerShiftHourAgorot ? `₪${(w.netPerShiftHourAgorot.low / 100).toFixed(2)}–₪${(w.netPerShiftHourAgorot.high / 100).toFixed(2)}` : "not measurable"}.`,
      basis: `Shift ${hm(w.startMs)}–${hm(w.endMs)} (${w.hours.toFixed(2)} h): accepted offers ₪${(w.revenueAgorot / 100).toFixed(2)} − vehicle cost of GPS km. Range = GPS distance range.`,
    });
    for (const c of w.constraints) f.push({ kind: "OBSERVATION", topic: c.label, statement: c.observed, basis: `Shift ${hm(w.startMs)}–${hm(w.endMs)}; GPS speed sampled every 45–120 s.` });
  }
  f.push({
    kind: "OBSERVATION",
    topic: "Wolt's target times",
    statement: `${etaChecks} completions compared with Wolt's targets: all later than the target (see ETA validation).`,
    basis: "Used only for target-vs-completion analysis, never as a travel-time estimate.",
  });

  const largest = work[0]?.constraints.filter((c) => c.shareOf === "SHIFT_TIME" && c.share !== null).sort((a, b) => b.share! - a.share!)[0];
  if (largest)
    f.push({
      kind: "HYPOTHESIS",
      topic: "Largest constraint",
      statement: `${largest.label} is the largest controllable share of shift time (${(largest.share! * 100).toFixed(0)}% on the one work shift).`,
      basis: "One shift; a busy evening with almost no idle time. On a quieter shift idle time could dominate instead.",
      needs: `The same split on ≥ ${settings.evidenceMinShiftsForValidation} work shifts across different hours/days.`,
    });
  f.push({
    kind: "HYPOTHESIS",
    topic: "What the stationary time is",
    statement: "Most stationary time inside chains is waiting at restaurants and handover, not traffic.",
    basis: "Not separable from GPS speed alone.",
    needs: "Stop-level split: stationary time within ~150 m of the pickup cell vs. the drop-off vs. elsewhere (needs finer GPS than the 500 m grid, or arrival markers).",
  });
  f.push({
    kind: "HYPOTHESIS",
    topic: "Offers added to an active route",
    statement: "Accepting offers while already carrying orders raises net revenue per shift hour.",
    basis: "One chain with two added offers earned more per active hour than the standalone chains of the same evening — one example, same shift, not a comparison of policies.",
    needs: "A planned comparison of shift time with and without added offers (Experiment 1).",
  });
  f.push({
    kind: "HYPOTHESIS",
    topic: "Card characteristics",
    statement: "Price, displayed distance, area or bundling on the card predict net per active hour.",
    basis: "3 standalone chains, all in one price band and one distance band.",
    needs: "Many more standalone chains across bands and shifts; then per-shift replication of any difference.",
  });
  if (perShift.n < settings.evidenceMinShiftsForValidation)
    f.push({
      kind: "HYPOTHESIS",
      topic: "Validated findings",
      statement: "None yet.",
      basis: `A VALIDATED_FINDING needs ≥ ${settings.evidenceMinShiftsForValidation} independent work shifts per compared condition with a 95% interval excluding zero; there ${perShift.n === 1 ? "is 1 work shift" : `are ${perShift.n} work shifts`}.`,
    });
  return f;
}

function firstExperiment(work: ShiftEvidence[], perShift: Summary, settings: AppSettings): ExperimentPlan {
  const w = work[0];
  const smallest = settings.evidenceSmallestEffectAgorotPerHour;
  return {
    id: "EXP-1",
    title: "Offers added to an active route: accept as usual vs. decline while carrying",
    hypothesis:
      "Accepting offers that arrive while you already carry an order (case B) raises net revenue per full shift hour, because added stops fill time you are already driving or waiting, at less extra distance than a separate trip.",
    whyThisFirst: [
      w
        ? `OBSERVATION: on the one work shift, chain time was ${((w.chainMinutes.high / (w.hours * 60)) * 100).toFixed(0)}% of the shift and between-chain time ${(((w.betweenChainsMin ?? 0) / (w.hours * 60)) * 100).toFixed(0)}% — so the lever is what happens inside chains, not finding work.`
        : "No work shift yet.",
      "OBSERVATION: stationary time inside chains was the largest non-driving share of the shift; added offers are the main way extra revenue enters time already spent in a chain.",
      "OBSERVATION: 2 of 6 accepted offers were added to an active route — the policy matters often enough to measure.",
      "HYPOTHESIS (unproven): the one chain with added offers earned more per active hour. It is a single example from one evening; only a planned comparison can tell.",
      "It needs no new data source: chains, added-offer context, completions and GPS are already recorded; only the arm label is missing.",
    ],
    baseline: {
      value: w?.netPerShiftHourAgorot ?? null,
      unit: "₪ net per full shift hour",
      basis: w ? "the one work shift so far (accepting added offers as usual)" : "no work shift yet",
      uncertainty:
        perShift.n < 2
          ? "between-shift spread unknown (one shift) — the baseline itself is not yet reliable; the first arm-A shifts also establish it"
          : `per-shift sd ₪${((perShift.sd ?? 0) / 100).toFixed(2)}`,
    },
    arms: [
      { name: "A (current practice)", rule: "Accept or decline offers as you normally would, including offers that arrive while carrying orders." },
      {
        name: "B (no add-ons)",
        rule: "Decline every offer that arrives while an accepted order is still on board; offers that arrive when idle are handled as usual.",
      },
    ],
    unit: "One whole work shift per arm (shifts alternate A/B; the arm is decided before the shift, by coin flip within pairs of similar shifts — same weekday type and start hour ±1 h).",
    primaryMetric: "Net revenue per full shift hour = (accepted-offer amounts − vehicle cost of GPS km) ÷ shift hours, idle time included.",
    secondaryMetrics: [
      "Net revenue per active (chain) hour",
      "Share of shift time idle / between chains / stationary inside chains",
      "GPS km per ₪ of revenue",
      "Completed deliveries per shift hour (OBSERVED slides), reconciled with Wolt's daily statistics",
      "Offers received per online hour (does declining change what Wolt sends?)",
      "Wolt's target-vs-completion lateness (descriptive only)",
    ],
    dataRequired: [
      { item: "Shift facts, chains, completions, GPS distance (Collector export)", status: "AVAILABLE" },
      { item: "Accept context per offer (IDLE / ADDED_TO_ACTIVE_ROUTE) and decisions", status: "AVAILABLE" },
      { item: "Wolt's daily statistics per shift day (official count and earnings)", status: "AVAILABLE" },
      { item: "Arm label per shift, set before the shift starts", status: "NOT_YET_BUILT" },
      { item: "Notes on surge/bonus banners and weather per shift", status: "NOT_YET_BUILT" },
    ],
    sampleSize: [
      `Minimum before any verdict: ${settings.evidenceMinShiftsForValidation} work shifts per arm (setting evidenceMinShiftsForValidation).`,
      perShift.sd !== null
        ? `With the current per-shift sd, ≈${unitsPerArm(perShift.sd, smallest)} shifts per arm would detect ₪${(smallest / 100).toFixed(0)}/h with ~80% power.`
        : `Per-shift spread is unknown; after 3 shifts per arm compute sd and the required shifts per arm for the smallest effect worth acting on (₪${(smallest / 100).toFixed(0)}/h, setting evidenceSmallestEffectAgorotPerHour) — if it exceeds what is practical, stop and redesign.`,
    ],
    supporting: `A − B > 0 with the 95% interval of the per-shift difference above zero, after ≥ ${settings.evidenceMinShiftsForValidation} shifts per arm, and A ahead in most matched pairs.`,
    contradicting: [
      "The interval lies below zero (declining add-ons earned more per shift hour).",
      `The interval lies within ±₪${(smallest / 100).toFixed(0)}/h — no practically relevant difference; keep whichever is easier.`,
      "Anything else after the planned shifts is INCONCLUSIVE: extend once, then stop.",
    ],
    confounders: [
      { factor: "Demand level (weekday, hour, weather, events)", mitigation: "Pair shifts by weekday type and start hour; flip a coin within each pair; record weather." },
      { factor: "Wolt surge, bonuses and guarantees", mitigation: "Record banners per shift; analyse with and without bonus shifts." },
      { factor: "Wolt dispatch may react to declines (acceptance rate)", mitigation: "Track offers per online hour in both arms; check Wolt's terms before declining systematically — this is a risk you decide on." },
      { factor: "Area / restaurant mix", mitigation: "Start shifts from the same zone in both arms; compare courier-area distribution." },
      { factor: "Fatigue and learning over the weeks", mitigation: "Alternate arms over time rather than running all A first." },
      { factor: "Vehicle cost assumption (₪/km) and coarse GPS distance", mitigation: "Same rule in both arms; report sensitivity at ±20% cost." },
    ],
    risks: [
      "Arm B means declining offers manually while carrying — no automation, no alerts while driving; decide before the shift, not on the road.",
      "Declining may affect your standing with Wolt (acceptance metrics); weigh this before running arm B.",
      "Per-offer economics inside chains stay UNKNOWN; the experiment compares whole shifts only.",
    ],
  };
}

export type { Range };
