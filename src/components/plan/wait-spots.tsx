"use client";

import { useState } from "react";
import { OpportunityMap } from "@/components/hotspots/opportunity-map";
import { cellPolygon } from "@/lib/hotspots/geo";
import type { WaitSpot } from "@/lib/plan/decisions";

/** Waiting spots on a real map, numbered; the list uses the same numbers and the neighbourhood name from the map. */
export function WaitSpots({ spots }: { spots: WaitSpot[] }) {
  const [names, setNames] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<string | null>(null);
  const shown = spots.slice(0, 6);
  const cells = shown.flatMap((s, i) => {
    const polygon = cellPolygon(s.cellId);
    return polygon ? [{ cellId: s.cellId, polygon, value: s.waits, label: String(i + 1) }] : [];
  });
  return (
    <div className="space-y-3">
      <OpportunityMap
        cells={cells}
        selected={selected}
        compared={[]}
        onSelect={setSelected}
        onNames={(found) => setNames((n) => (Object.keys(found).every((k) => n[k] === found[k]) ? n : { ...n, ...found }))}
      />
      <ol className="space-y-1 text-sm">
        {shown.map((s, i) => (
          <li key={s.cellId} className={selected === s.cellId ? "font-semibold" : ""}>
            <button type="button" className="flex w-full justify-between gap-2 text-start" onClick={() => setSelected(s.cellId)}>
              <span>
                {i + 1}. {names[s.cellId] ?? `מקום ${i + 1}`} · {s.waits} {s.waits === 1 ? "המתנה" : "המתנות"}
              </span>
              <span>{Math.round(s.medianMinutes)} דק׳ עד הצעה</span>
            </button>
          </li>
        ))}
      </ol>
    </div>
  );
}
