import { G500_LAT_STEP_DEG } from "@/lib/hotspots/geo";

/**
 * The ~500 m cell of a precise point, matching the Collector's LocationGrid.cellOf (core/LocationGrid.kt):
 * y = floor(lat / latStep), x = floor(lon / lonStep(rowLat)), with the longitude step for the row's latitude.
 */
export function cellOf(lat: number, lon: number): string {
  const y = Math.floor(lat / G500_LAT_STEP_DEG);
  const rowLat = (y + 0.5) * G500_LAT_STEP_DEG;
  const lonStep = G500_LAT_STEP_DEG / Math.cos((rowLat * Math.PI) / 180);
  const x = Math.floor(lon / lonStep);
  return `g500:${y}:${x}`;
}
