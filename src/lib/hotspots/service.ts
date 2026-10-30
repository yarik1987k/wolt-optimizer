import { prisma } from "@/lib/db";
import { CELL_ID_RE } from "@/lib/offers/collector-format";
import { getSettings } from "@/lib/settings";
import { analyzeHotspots, compareCells, presenceByCell, shiftWindows, UNKNOWN_CELL, type HotspotInput } from "./analysis";
import { explore, type TimeFilter } from "./explore";
import { cellPolygon, shortCellCode } from "./geo";
import { jerusalemParts } from "@/lib/offers/analytics";

export const HOTSPOT_METRICS = ["offers", "rate", "mean", "available"] as const;
export type HotspotMetric = (typeof HOTSPOT_METRICS)[number];

export interface HotspotParams {
  hour: number | null;
  metric: HotspotMetric;
  current: string | null;
}

export function parseHotspotParams(sp: Record<string, string | string[] | undefined>): HotspotParams {
  const get = (k: string) => {
    const v = sp[k];
    return Array.isArray(v) ? v[0] : v;
  };
  const h = Number(get("hour"));
  const hourRaw = get("hour");
  const metric = get("metric");
  const current = get("current");
  return {
    hour: hourRaw !== undefined && hourRaw !== "" && Number.isInteger(h) && h >= 0 && h <= 23 ? h : null,
    metric: (HOTSPOT_METRICS as readonly string[]).includes(metric ?? "") ? (metric as HotspotMetric) : "offers",
    current: current && CELL_ID_RE.test(current) ? current : null,
  };
}

/** Everything the analysis needs, from the database. Offers: confirmed cards only (as on /offers by default). */
export async function loadHotspotInput(): Promise<HotspotInput> {
  const [shifts, samples, offers, trips, events] = await Promise.all([
    prisma.collectorShift.findMany({ orderBy: { startedAtUtc: "asc" } }),
    prisma.locationSample.findMany({ orderBy: { observedAtUtc: "asc" } }),
    prisma.offer.findMany({ where: { detection: "CONFIRMED_CARD" }, select: { id: true, firstSeenAtUtc: true, amountAgorot: true } }),
    prisma.collectorTrip.findMany({ orderBy: { startedAtUtc: "asc" } }),
    prisma.collectorDeliveryEvent.findMany({ orderBy: { actionAtUtc: "asc" } }),
  ]);
  return {
    shifts: shifts.map((s) => ({
      id: s.id,
      startMs: s.startedAtUtc.getTime(),
      endMs: s.endedAtUtc?.getTime() ?? null,
      endReason: s.endReason,
      locationConsent: s.locationConsent,
    })),
    samples: samples.map((s) => ({
      id: s.id,
      shiftId: s.shiftId,
      atMs: s.observedAtUtc.getTime(),
      trigger: s.trigger,
      status: s.status === "OK" ? "OK" : "UNKNOWN",
      unknownReason: s.unknownReason,
      cellId: s.cellId,
      lat: s.cellCenterLat,
      lon: s.cellCenterLon,
      stateCandidate: s.stateCandidate,
      stateEvidence: s.stateEvidence,
      accuracyM: s.accuracyM,
    })),
    offers: offers.map((o) => ({ id: o.id, firstSeenMs: o.firstSeenAtUtc.getTime(), amountAgorot: o.amountAgorot })),
    trips: trips.map((t) => {
      let amounts: number[] = [];
      try {
        const v = JSON.parse(t.offerAmountsJson);
        amounts = Array.isArray(v) ? v.filter((x): x is number => Number.isInteger(x)) : [];
      } catch {
        amounts = [];
      }
      return { startMs: t.startedAtUtc.getTime(), endMs: t.completedAtUtc?.getTime() ?? null, endEvidence: t.endEvidence, amountsAgorot: amounts };
    }),
    events: events.map((e) => ({ type: e.type, evidence: e.evidence, atMs: e.actionAtUtc.getTime() })),
  };
}

export async function getHotspotView(params: HotspotParams) {
  const [input, settings] = await Promise.all([loadHotspotInput(), getSettings()]);
  const analysis = analyzeHotspots(input, { hour: params.hour });
  const acceptShare = settings.hotspotAcceptShare ?? analysis.acceptShare.share;
  const acceptShareSource: "SETTING" | "OBSERVED" | null =
    settings.hotspotAcceptShare !== null ? "SETTING" : analysis.acceptShare.share !== null ? "OBSERVED" : null;
  const current = params.current ? (analysis.cells.find((c) => c.cellId === params.current) ?? null) : null;
  const assumptions = { avgSpeedKmh: settings.hotspotAvgSpeedKmh, costPerKmAgorot: settings.vehicleCostPerKmAgorot, acceptShare };
  return {
    params,
    analysis,
    hasLocationData: input.samples.length > 0,
    shiftCount: input.shifts.length,
    current,
    assumptions,
    acceptShareSource,
    comparison: current && current.cellId !== UNKNOWN_CELL ? compareCells(current, analysis.cells, assumptions) : [],
    currentByHour: current ? analysis.cellHours.filter((c) => c.cellId === current.cellId) : [],
    presence: presenceByCell(input.shifts, input.samples),
    shiftMinutes: shiftWindows(input.shifts, input.samples).reduce((n, w) => n + (w.endMs - w.startMs) / 60_000, 0),
  };
}

// ---------------------------------------------------------------------------
// Explorer (UI v2)
// ---------------------------------------------------------------------------

export interface ExploreParams {
  filter: TimeFilter;
  thresholdAgorot: number;
}

const DEFAULT_THRESHOLD_AGOROT = 3500;
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

export function parseExploreParams(sp: Record<string, string | string[] | undefined>): ExploreParams {
  const get = (k: string) => {
    const v = sp[k];
    return Array.isArray(v) ? v[0] : v;
  };
  const int = (k: string, lo: number, hi: number) => {
    const n = Number(get(k));
    return get(k) !== undefined && get(k) !== "" && Number.isInteger(n) && n >= lo && n <= hi ? n : null;
  };
  const from = int("from", 0, 23);
  const to = int("to", 1, 24);
  const dv = sp["days"];
  const daysRaw = Array.isArray(dv) ? dv.join(",") : dv;
  const weekdays = daysRaw
    ? daysRaw
        .split(",")
        .filter((x) => x.trim() !== "")
        .map(Number)
        .filter((d, i, a) => Number.isInteger(d) && d >= 0 && d <= 6 && a.indexOf(d) === i)
    : [];
  const df = get("dateFrom");
  const dt = get("dateTo");
  const th = Number(get("above"));
  return {
    filter: {
      fromHour: from ?? 0,
      toHour: from === null ? 24 : (to ?? Math.min(24, from + 1)),
      weekdays,
      dateFrom: df && DATE_RE.test(df) ? df : null,
      dateTo: dt && DATE_RE.test(dt) ? dt : null,
    },
    thresholdAgorot: Number.isFinite(th) && th > 0 && th < 1000 ? Math.round(th * 100) : DEFAULT_THRESHOLD_AGOROT,
  };
}

export async function getExploreView(p: ExploreParams) {
  const [input, settings] = await Promise.all([loadHotspotInput(), getSettings()]);
  const result = explore(input, p.filter, p.thresholdAgorot, settings.vehicleCostPerKmAgorot !== null && settings.hotspotAvgSpeedKmh !== null);
  const cells = result.areas.map((a) => ({ ...a, polygon: cellPolygon(a.cellId), code: shortCellCode(a.cellId) }));
  const dates = [...new Set(input.samples.map((s) => jerusalemParts(s.atMs).date))].sort();
  return { params: p, result, cells, hasLocationData: input.samples.length > 0, dates };
}
