import { prisma } from "@/lib/db";
import { haversineKm } from "@/lib/hotspots/geo";

/**
 * Shift routes for the map (after the shift). Precise when the shift has a track (~5 m), otherwise the ~500 m
 * cell centres of the location samples (COARSE: a staircase between squares, not the streets driven).
 * Mirrors the Collector's ShiftRoute (core/ShiftRoute.kt).
 */

export type Precision = "PRECISE" | "COARSE" | "NONE";

export interface RoutePoint {
  t: number;
  lat: number;
  lon: number;
  speed: number | null;
}

export interface RouteSegment {
  delivering: boolean;
  points: [number, number][]; // [lon, lat]
}

export interface RouteMarker {
  kind: "START" | "END" | "PICKUP" | "DROPOFF";
  t: number;
  lon: number;
  lat: number;
}

export interface ShiftRoute {
  shiftId: string;
  precision: Precision;
  segments: RouteSegment[];
  markers: RouteMarker[];
  km: number;
  deliveringKm: number;
  startMs: number | null;
  endMs: number | null;
  points: number;
}

const MARKER_MAX_GAP_MS = 3 * 60_000;

/** Same jitter guard as the Collector: a step under 25 m with both ends slower than 0.5 m/s is noise. */
function stepKm(a: RoutePoint, b: RoutePoint) {
  const d = haversineKm(a, b);
  return d < 0.025 && (a.speed ?? 0) < 0.5 && (b.speed ?? 0) < 0.5 ? 0 : d;
}

export function buildRoute(shiftId: string, precise: RoutePoint[], coarse: RoutePoint[], chains: [number, number][], pickups: number[], dropoffs: number[]): ShiftRoute {
  const precision: Precision = precise.length >= 2 ? "PRECISE" : coarse.length >= 2 ? "COARSE" : "NONE";
  const pts = [...(precision === "PRECISE" ? precise : coarse)].sort((a, b) => a.t - b.t);
  const empty = { shiftId, precision, segments: [], markers: [], km: 0, deliveringKm: 0, startMs: pts[0]?.t ?? null, endMs: pts.at(-1)?.t ?? null, points: pts.length };
  if (pts.length < 2) return empty;
  const delivering = (t: number) => chains.some(([a, b]) => t >= a && t <= b);
  // Runs share their boundary point (continuous line); a step belongs to its start point's state.
  const segments: RouteSegment[] = [];
  let run: RoutePoint[] = [pts[0]];
  let state = delivering(pts[0].t);
  let km = 0;
  let dkm = 0;
  for (let i = 1; i < pts.length; i++) {
    const d = stepKm(pts[i - 1], pts[i]);
    km += d;
    if (state) dkm += d;
    run.push(pts[i]);
    const s = delivering(pts[i].t);
    if (s !== state) {
      segments.push({ delivering: state, points: run.map((p) => [p.lon, p.lat]) });
      run = [pts[i]];
      state = s;
    }
  }
  if (run.length >= 2) segments.push({ delivering: state, points: run.map((p) => [p.lon, p.lat]) });
  const nearest = (t: number) => {
    let best: RoutePoint | null = null;
    for (const p of pts) if (Math.abs(p.t - t) <= MARKER_MAX_GAP_MS && (!best || Math.abs(p.t - t) < Math.abs(best.t - t))) best = p;
    return best;
  };
  const markers: RouteMarker[] = [{ kind: "START", t: pts[0].t, lon: pts[0].lon, lat: pts[0].lat }];
  for (const t of pickups) {
    const p = nearest(t);
    if (p) markers.push({ kind: "PICKUP", t, lon: p.lon, lat: p.lat });
  }
  for (const t of dropoffs) {
    const p = nearest(t);
    if (p) markers.push({ kind: "DROPOFF", t, lon: p.lon, lat: p.lat });
  }
  const last = pts[pts.length - 1];
  markers.push({ kind: "END", t: last.t, lon: last.lon, lat: last.lat });
  return { shiftId, precision, segments, markers, km, deliveringKm: dkm, startMs: pts[0].t, endMs: last.t, points: pts.length };
}

export async function shiftRoute(shiftId: string): Promise<ShiftRoute> {
  const shift = await prisma.collectorShift.findUnique({ where: { id: shiftId } });
  if (!shift) return buildRoute(shiftId, [], [], [], [], []);
  const from = shift.startedAtUtc;
  const to = shift.endedAtUtc ?? shift.factsEndedAtUtc ?? new Date();
  const [track, samples, chains, pickups] = await Promise.all([
    prisma.collectorTrackPoint.findMany({ where: { shiftId }, orderBy: { atUtc: "asc" } }),
    prisma.locationSample.findMany({ where: { shiftId, status: "OK" }, orderBy: { observedAtUtc: "asc" } }),
    prisma.collectorChain.findMany({ where: { startLatestAtUtc: { gte: from, lte: to } } }),
    prisma.collectorDeliveryEvent.findMany({ where: { type: "PICKUP_DONE", actionAtUtc: { gte: from, lte: to } } }),
  ]);
  return buildRoute(
    shiftId,
    track.map((p) => ({ t: p.atUtc.getTime(), lat: p.lat, lon: p.lon, speed: p.speedMps })),
    samples.filter((s) => s.cellCenterLat !== null && s.cellCenterLon !== null).map((s) => ({ t: s.observedAtUtc.getTime(), lat: s.cellCenterLat!, lon: s.cellCenterLon!, speed: null })),
    chains.map((c) => [(c.startEarliestAtUtc ?? c.startLatestAtUtc).getTime(), (c.endLatestAtUtc ?? to).getTime()]),
    pickups.map((e) => e.actionAtUtc.getTime()),
    chains.flatMap((c) => (c.completionSlidesJson ? (JSON.parse(c.completionSlidesJson) as number[]) : [])),
  );
}
