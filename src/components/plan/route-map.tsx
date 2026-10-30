"use client";

import { useEffect, useRef } from "react";
import * as maplibregl from "maplibre-gl";
import type { GeoJSONSource, Map as MlMap } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import type { ShiftRoute } from "@/lib/plan/route";

/**
 * Shift routes on a real map (MapLibre + OpenFreeMap tiles; only tiles are fetched, nothing is sent). The focus
 * route is drawn strong (blue while delivering, grey between deliveries; dashed when coarse); other routes faded.
 */
const STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron";
const STYLE_DARK = "https://tiles.openfreemap.org/styles/dark";
export const COLOR_DELIVERING = "#185FA5";
export const COLOR_BETWEEN = "#888780";
export const COLOR_PICKUP = "#D85A30";
export const COLOR_DROPOFF = "#3B6D11";

let rtlRequested = false;

function lines(routes: ShiftRoute[], focus: string | null) {
  return {
    type: "FeatureCollection" as const,
    features: routes.flatMap((r) =>
      r.segments.map((s) => ({
        type: "Feature" as const,
        properties: { delivering: s.delivering, focus: focus === null || r.shiftId === focus, coarse: r.precision === "COARSE" },
        geometry: { type: "LineString" as const, coordinates: s.points },
      })),
    ),
  };
}

function marks(routes: ShiftRoute[], focus: string | null) {
  return {
    type: "FeatureCollection" as const,
    features: routes
      .filter((r) => focus === null || r.shiftId === focus)
      .flatMap((r) => r.markers.map((m) => ({ type: "Feature" as const, properties: { kind: m.kind }, geometry: { type: "Point" as const, coordinates: [m.lon, m.lat] } }))),
  };
}

function fit(map: MlMap, routes: ShiftRoute[], focus: string | null) {
  const pts = routes.filter((r) => focus === null || r.shiftId === focus).flatMap((r) => r.segments.flatMap((s) => s.points));
  if (pts.length < 2) return;
  const b = new maplibregl.LngLatBounds(pts[0], pts[0]);
  for (const p of pts) b.extend(p);
  map.fitBounds(b, { padding: 50, duration: 0, maxZoom: 15 });
}

export function RouteMap({ routes, focus }: { routes: ShiftRoute[]; focus: string | null }) {
  const box = useRef<HTMLDivElement>(null);
  const map = useRef<MlMap | null>(null);
  const latest = useRef({ routes, focus });
  latest.current = { routes, focus };

  useEffect(() => {
    if (!box.current) return;
    if (!(maplibregl.getWorkerUrl() ?? "").startsWith("/vendor/")) maplibregl.setWorkerUrl("/vendor/maplibre-6.13.0/maplibre-gl-worker.mjs");
    if (!rtlRequested && maplibregl.getRTLTextPluginStatus() === "unavailable") {
      rtlRequested = true;
      void maplibregl.setRTLTextPlugin("/vendor/mapbox-gl-rtl-text-0.4.0.js", true);
    }
    const dark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    const m = new maplibregl.Map({ container: box.current, style: dark ? STYLE_DARK : STYLE_LIGHT, attributionControl: { compact: true }, dragRotate: false });
    map.current = m;
    m.on("load", () => {
      const { routes: rs, focus: f } = latest.current;
      m.addSource("route", { type: "geojson", data: lines(rs, f) });
      m.addSource("marks", { type: "geojson", data: marks(rs, f) });
      m.addLayer({
        id: "route-line",
        type: "line",
        source: "route",
        layout: { "line-cap": "round", "line-join": "round" },
        paint: {
          "line-color": ["case", ["get", "delivering"], COLOR_DELIVERING, COLOR_BETWEEN],
          "line-width": ["case", ["get", "focus"], 4, 2],
          "line-opacity": ["case", ["get", "focus"], 0.95, 0.25],
        },
      });
      m.addLayer({
        id: "marks-dot",
        type: "circle",
        source: "marks",
        paint: {
          "circle-radius": ["match", ["get", "kind"], "START", 7, "END", 7, 6],
          "circle-color": ["match", ["get", "kind"], "PICKUP", COLOR_PICKUP, "DROPOFF", COLOR_DROPOFF, "#2C2C2A"],
          "circle-stroke-color": "#ffffff",
          "circle-stroke-width": 2,
        },
      });
      fit(m, rs, f);
    });
    return () => {
      m.remove();
      map.current = null;
    };
  }, []);

  useEffect(() => {
    const m = map.current;
    if (!m || !m.isStyleLoaded()) return;
    (m.getSource("route") as GeoJSONSource | undefined)?.setData(lines(routes, focus));
    (m.getSource("marks") as GeoJSONSource | undefined)?.setData(marks(routes, focus));
    fit(m, routes, focus);
  }, [routes, focus]);

  return <div ref={box} data-testid="route-map" className="h-[520px] w-full overflow-hidden rounded-lg border border-border" />;
}
