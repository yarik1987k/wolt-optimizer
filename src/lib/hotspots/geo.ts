import { CELL_ID_RE } from "@/lib/offers/collector-format";

/** "g500:<y>:<x>" → grid row/column (y grows northwards, x eastwards). */
export function parseCellId(id: string): { y: number; x: number } | null {
  if (!CELL_ID_RE.test(id)) return null;
  const [, y, x] = id.split(":");
  return { y: Number(y), x: Number(x) };
}

const EARTH_RADIUS_KM = 6371.0088;

/** Great-circle (straight-line) distance between two cell centres, km. */
export function haversineKm(a: { lat: number; lon: number }, b: { lat: number; lon: number }): number {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLon = (b.lon - a.lon) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Same grid as the Collector (g500): 500 m rows of latitude; columns scaled by cos(row-centre latitude). */
export const G500_LAT_STEP_DEG = 500 / 111_320;

/** Corner coordinates [[lon, lat] × 5] of a g500 cell — for drawing it on a map. */
export function cellPolygon(id: string): [number, number][] | null {
  const c = parseCellId(id);
  if (!c) return null;
  const lat0 = c.y * G500_LAT_STEP_DEG;
  const lat1 = lat0 + G500_LAT_STEP_DEG;
  const lonStep = G500_LAT_STEP_DEG / Math.cos(((c.y + 0.5) * G500_LAT_STEP_DEG * Math.PI) / 180);
  const lon0 = c.x * lonStep;
  const lon1 = lon0 + lonStep;
  return [
    [lon0, lat0],
    [lon1, lat0],
    [lon1, lat1],
    [lon0, lat1],
    [lon0, lat0],
  ];
}

/** Short, stable label for a cell when no place name is reliable, e.g. "K7Q". */
export function shortCellCode(id: string): string {
  let h = 2166136261;
  for (const ch of id) h = Math.imul(h ^ ch.charCodeAt(0), 16777619) >>> 0;
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  return Array.from({ length: 3 }, (_, i) => alphabet[(h >>> (i * 5)) % alphabet.length]).join("");
}
