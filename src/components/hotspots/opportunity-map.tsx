"use client";

import { useEffect, useRef } from "react";
import * as maplibregl from "maplibre-gl";
import type { GeoJSONSource, Map as MlMap, MapLayerMouseEvent } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";

/**
 * Real geographic map (MapLibre + OpenFreeMap vector tiles, no API key, no account). Only the
 * map tiles are fetched from the network — no user data is sent anywhere. Cells are drawn from
 * their real g500 polygons; colour = the chosen metric; grey = no value for that metric (not ranked).
 */
export interface MapCell {
  cellId: string;
  polygon: [number, number][];
  value: number | null;
  label: string;
}

const STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron";
const STYLE_DARK = "https://tiles.openfreemap.org/styles/dark";
const PLACE_CLASSES = new Set(["neighbourhood", "quarter", "suburb", "village", "town", "city"]);
const NAME_MAX_KM = 0.8;
/** Sequential ramp (one hue, light → dark), as in the app's chart palette. */
export const RAMP = ["#cfe2f3", "#9cc3e6", "#5aa2e6", "#1d6fb8", "#0b3f73"];

let rtlRequested = false;

function centre(poly: [number, number][]): [number, number] {
  return [(poly[0][0] + poly[2][0]) / 2, (poly[0][1] + poly[2][1]) / 2];
}

function geojson(cells: MapCell[], selected: string | null, compared: string[]) {
  const vals = cells.map((c) => c.value).filter((v): v is number => v !== null);
  const lo = Math.min(...vals);
  const hi = Math.max(...vals);
  return {
    type: "FeatureCollection" as const,
    features: cells.map((c) => ({
      type: "Feature" as const,
      id: c.cellId,
      properties: {
        cellId: c.cellId,
        label: c.label,
        hasValue: c.value !== null,
        // Normalised 0..1 within the visible data; a single value sits mid-ramp.
        t: c.value === null ? 0 : hi > lo ? (c.value - lo) / (hi - lo) : 0.5,
        selected: c.cellId === selected,
        compared: compared.includes(c.cellId),
      },
      geometry: { type: "Polygon" as const, coordinates: [c.polygon] },
    })),
  };
}

export function OpportunityMap({
  cells,
  selected,
  compared,
  onSelect,
  onNames,
}: {
  cells: MapCell[];
  selected: string | null;
  compared: string[];
  onSelect: (cellId: string) => void;
  onNames: (names: Record<string, string>) => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const map = useRef<MlMap | null>(null);
  const latest = useRef({ cells, selected, compared, onSelect, onNames });
  latest.current = { cells, selected, compared, onSelect, onNames };

  useEffect(() => {
    if (!box.current) return;
    // The bundler can't resolve MapLibre's module worker; serve the same version's worker from /public.
    if (!(maplibregl.getWorkerUrl() ?? "").startsWith("/vendor/")) maplibregl.setWorkerUrl("/vendor/maplibre-6.13.0/maplibre-gl-worker.mjs");
    if (!rtlRequested && maplibregl.getRTLTextPluginStatus() === "unavailable") {
      rtlRequested = true;
      void maplibregl.setRTLTextPlugin("/vendor/mapbox-gl-rtl-text-0.4.0.js", true);
    }
    const dark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    const m = new maplibregl.Map({ container: box.current, style: dark ? STYLE_DARK : STYLE_LIGHT, attributionControl: { compact: true } });
    map.current = m;
    m.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-left");

    m.on("load", () => {
      const { cells: cs, selected: sel, compared: cmp } = latest.current;
      m.addSource("cells", { type: "geojson", data: geojson(cs, sel, cmp) });
      m.addLayer({
        id: "cells-fill",
        type: "fill",
        source: "cells",
        paint: {
          "fill-color": ["case", ["get", "hasValue"], ["interpolate", ["linear"], ["get", "t"], 0, RAMP[0], 0.25, RAMP[1], 0.5, RAMP[2], 0.75, RAMP[3], 1, RAMP[4]], "#9a9a94"],
          "fill-opacity": ["case", ["get", "hasValue"], 0.62, 0.28],
        },
      });
      m.addLayer({
        id: "cells-line",
        type: "line",
        source: "cells",
        paint: {
          "line-color": ["case", ["get", "selected"], "#e8590c", ["get", "compared"], "#7b2cbf", dark ? "#e8e8e3" : "#3a3a36"],
          "line-width": ["case", ["get", "selected"], 3, ["get", "compared"], 2.5, 1],
        },
      });
      m.addLayer({
        id: "cells-label",
        type: "symbol",
        source: "cells",
        layout: { "text-field": ["get", "label"], "text-size": 12, "text-font": ["Noto Sans Bold"], "text-allow-overlap": false },
        paint: { "text-color": dark ? "#ffffff" : "#111111", "text-halo-color": dark ? "#000000" : "#ffffff", "text-halo-width": 1.5 },
      });
      m.on("click", "cells-fill", (e: MapLayerMouseEvent) => {
        const id = e.features?.[0]?.properties?.cellId;
        if (typeof id === "string") latest.current.onSelect(id);
      });
      m.on("mouseenter", "cells-fill", () => (m.getCanvas().style.cursor = "pointer"));
      m.on("mouseleave", "cells-fill", () => (m.getCanvas().style.cursor = ""));
      fit(m, cs);
      m.once("idle", () => latest.current.onNames(placeNames(m, latest.current.cells)));
    });
    return () => {
      m.remove();
      map.current = null;
    };
  }, []);

  // Data / selection changes → update the source only (no map rebuild).
  useEffect(() => {
    const m = map.current;
    const src = m?.getSource("cells") as GeoJSONSource | undefined;
    if (!m || !src) return;
    src.setData(geojson(cells, selected, compared));
  }, [cells, selected, compared]);

  useEffect(() => {
    const m = map.current;
    if (m?.getSource("cells")) fit(m, cells);
  }, [cells.map((c) => c.cellId).join(",")]); // eslint-disable-line react-hooks/exhaustive-deps

  return <div ref={box} className="h-[420px] w-full overflow-hidden rounded-lg border border-border md:h-[520px]" data-testid="opportunity-map" />;
}

function fit(m: MlMap, cells: MapCell[]) {
  if (!cells.length) return;
  const b = new maplibregl.LngLatBounds();
  for (const c of cells) for (const p of c.polygon) b.extend(p);
  m.fitBounds(b, { padding: 60, maxZoom: 14, duration: 0 });
}

/**
 * Names from the map's own place labels (already-loaded tiles; no geocoding service). A name is used
 * only when a neighbourhood/town label lies within NAME_MAX_KM of the cell centre; otherwise none.
 */
function placeNames(m: MlMap, cells: MapCell[]): Record<string, string> {
  let feats: maplibregl.GeoJSONFeature[] = [];
  try {
    feats = m.querySourceFeatures("openmaptiles", { sourceLayer: "place" });
  } catch {
    return {};
  }
  const places = feats
    .filter((f) => f.geometry.type === "Point" && PLACE_CLASSES.has(String(f.properties?.class)))
    .map((f) => ({
      name: String(f.properties?.["name:he"] ?? f.properties?.name ?? ""),
      at: (f.geometry as GeoJSON.Point).coordinates as [number, number],
      rank: ["neighbourhood", "quarter", "suburb"].includes(String(f.properties?.class)) ? 0 : 1,
    }))
    .filter((p) => p.name);
  const out: Record<string, string> = {};
  for (const c of cells) {
    const [lon, lat] = centre(c.polygon);
    let best: { name: string; d: number; rank: number } | null = null;
    for (const p of places) {
      const dx = (p.at[0] - lon) * 111.32 * Math.cos((lat * Math.PI) / 180);
      const dy = (p.at[1] - lat) * 111.32;
      const d = Math.hypot(dx, dy);
      if (d > NAME_MAX_KM) continue;
      if (!best || p.rank < best.rank || (p.rank === best.rank && d < best.d)) best = { name: p.name, d, rank: p.rank };
    }
    if (best) out[c.cellId] = best.name;
  }
  return out;
}
