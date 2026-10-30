"use client";

import { useMemo, useState } from "react";
import dynamic from "next/dynamic";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import type { AreaMetrics, MapMetric, TimeState } from "@/lib/hotspots/explore";
import { MAP_METRICS, metricValue } from "@/lib/hotspots/explore";
import { RAMP, type MapCell } from "./opportunity-map";

const OpportunityMap = dynamic(() => import("./opportunity-map").then((m) => m.OpportunityMap), {
  ssr: false,
  loading: () => <div className="h-[420px] w-full animate-pulse rounded-lg border border-border bg-border/30 md:h-[520px]" />,
});

export type ExplorerCell = AreaMetrics & { polygon: [number, number][] | null; code: string };

const shekel = (agorot: number | null) => (agorot === null ? "—" : `₪${(agorot / 100).toFixed(agorot % 100 === 0 ? 0 : 2)}`);
const dur = (ms: number) => {
  const m = Math.round(ms / 60_000);
  return m < 60 ? `${m} דק׳` : `${Math.floor(m / 60)} ש׳ ${m % 60} דק׳`;
};

export const METRIC_INFO: Record<MapMetric, { label: string; fmt: (v: number) => string; note: string }> = {
  offers: { label: "הצעות שנצפו", fmt: (v) => String(v), note: "כמה הצעות הוצגו באזור. צפיפות — לא הכנסה." },
  rate: { label: "הצעות לשעת זמינות מאומתת", fmt: (v) => v.toFixed(1), note: "רק זמן שבו הזמינות נצפתה ישירות. בלי זמן כזה — אין ערך." },
  mean: { label: "תשלום ממוצע להצעה", fmt: (v) => shekel(v), note: "ממוצע הסכומים שהוצגו בכרטיסי ההצעה." },
  median: { label: "תשלום חציוני להצעה", fmt: (v) => shekel(v), note: "חצי מההצעות מעל, חצי מתחת." },
  shareAbove: { label: "שיעור הצעות מעל הסף", fmt: (v) => `${Math.round(v * 100)}%`, note: "אחוז ההצעות בסכום הסף ומעלה." },
  income: { label: "הכנסה בפועל לשעת משמרת", fmt: (v) => shekel(v), note: "רק ממשלוחים שהושלמו. בלי כאלה — אין ערך." },
  wait: { label: "זמן המתנה ממוצע להצעה", fmt: (v) => dur(v), note: "זמן זמין מאומת ÷ הצעות. בלי זמן מאומת — אין ערך." },
};

export const STATE_INFO: Record<TimeState, { label: string; color: string }> = {
  AVAILABLE_VERIFIED: { label: "זמין (מאומת)", color: "#1d6fb8" },
  AVAILABLE_ESTIMATED: { label: "זמין (לא מאומת)", color: "#9cc3e6" },
  DELIVERY: { label: "במשלוח", color: "#2b8a3e" },
  OFFLINE: { label: "Offline", color: "#868e96" },
  UNKNOWN: { label: "לא ידוע", color: "#d0d0c8" },
};

const CONF: Record<AreaMetrics["confidence"], { label: string; cls: string }> = {
  NONE: { label: "אין מספיק נתונים", cls: "bg-amber-500/15 text-amber-700 dark:text-amber-300" },
  LOW: { label: "ראשוני", cls: "bg-sky-500/15 text-sky-700 dark:text-sky-300" },
  FAIR: { label: "מדגם סביר", cls: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-300" },
};

export function StateBar({ timeMs }: { timeMs: Record<TimeState, number> }) {
  const total = Object.values(timeMs).reduce((n, v) => n + v, 0);
  if (!total) return <p className="text-sm text-muted">אין זמן בחלון הזה.</p>;
  return (
    <div className="space-y-2">
      <div className="flex h-3 w-full overflow-hidden rounded-full" role="img" aria-label="חלוקת הזמן לפי מצב">
        {(Object.keys(STATE_INFO) as TimeState[]).map((k) =>
          timeMs[k] ? <div key={k} style={{ width: `${(timeMs[k] / total) * 100}%`, background: STATE_INFO[k].color }} title={`${STATE_INFO[k].label}: ${dur(timeMs[k])}`} /> : null,
        )}
      </div>
      <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
        {(Object.keys(STATE_INFO) as TimeState[]).map((k) => (
          <li key={k} className="flex items-center gap-1">
            <span className="inline-block h-2.5 w-2.5 rounded-sm" style={{ background: STATE_INFO[k].color }} />
            {STATE_INFO[k].label}: <span className="font-medium">{dur(timeMs[k])}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Bars({ values, labels, fmt }: { values: number[]; labels: string[]; fmt: (v: number) => string }) {
  const max = Math.max(...values, 1);
  return (
    <div className="flex h-20 items-end gap-0.5" dir="ltr">
      {values.map((v, i) => (
        <div key={i} className="flex h-full flex-1 flex-col justify-end" title={`${labels[i]}: ${fmt(v)}`}>
          <div className="w-full rounded-t-sm bg-primary/80" style={{ height: `${(v / max) * 100}%`, minHeight: v ? 3 : 0 }} />
        </div>
      ))}
    </div>
  );
}

function Details({ a, name, threshold }: { a: ExplorerCell; name: string; threshold: number }) {
  const lo = a.amountsAgorot[0];
  const bins = a.amountsAgorot.length
    ? Array.from({ length: Math.floor((a.amountsAgorot[a.amountsAgorot.length - 1] - Math.floor(lo / 500) * 500) / 500) + 1 }, (_, i) => Math.floor(lo / 500) * 500 + i * 500)
    : [];
  const hist = bins.map((b) => a.amountsAgorot.filter((x) => x >= b && x < b + 500).length);
  const hoursWithData = a.timeByHour.map((t, h) => (t > 0 || a.offersByHour[h] > 0 ? h : -1)).filter((h) => h >= 0);
  const hFrom = hoursWithData.length ? Math.min(...hoursWithData) : 0;
  const hTo = hoursWithData.length ? Math.max(...hoursWithData) + 1 : 0;
  const verified = a.timeMs.AVAILABLE_VERIFIED;
  return (
    <div className="space-y-4" data-testid="area-details">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h3 className="text-lg font-semibold">{name}</h3>
          <p className="text-xs text-muted">ריבוע של כ-500 מ׳ · מזהה {a.code}</p>
        </div>
        <span className={`rounded-full px-2.5 py-1 text-xs font-medium ${CONF[a.confidence].cls}`}>{CONF[a.confidence].label}</span>
      </div>

      <dl className="grid grid-cols-2 gap-2 text-sm sm:grid-cols-3">
        {[
          ["הצעות", String(a.offers)],
          ["ממוצע / חציון", `${shekel(a.meanAgorot)} / ${shekel(a.medianAgorot)}`],
          [`מעל ${shekel(threshold)}`, a.shareAbove === null ? "—" : `${Math.round(a.shareAbove * 100)}%`],
          ["זמינות מאומתת", verified ? dur(verified) : "אין"],
          ["הצעות לשעה (מאומת)", a.ratePerVerifiedHour === null ? "אי אפשר לחשב" : `${a.ratePerVerifiedHour.toFixed(1)} (${a.rateInterval!.lower.toFixed(1)}–${a.rateInterval!.upper.toFixed(1)})`],
          ["הכנסה בפועל לשעה", a.incomePerShiftHourAgorot === null ? "אין משלוחים שהושלמו" : shekel(a.incomePerShiftHourAgorot)],
          ["משמרות שתרמו", String(a.shifts)],
          ["דיוק GPS חציוני", a.medianAccuracyM === null ? "—" : `${a.medianAccuracyM} מ׳`],
          ["זמן עם מיקום", dur(a.measuredMs)],
        ].map(([k, v]) => (
          <div key={k} className="rounded-md bg-border/30 p-2">
            <dt className="text-xs text-muted">{k}</dt>
            <dd className="font-semibold">{v}</dd>
          </div>
        ))}
      </dl>

      <div>
        <h4 className="mb-1 text-sm font-medium">איך התחלק הזמן באזור</h4>
        <StateBar timeMs={a.timeMs} />
      </div>

      {hist.length > 0 && (
        <div>
          <h4 className="mb-1 text-sm font-medium">סכומי ההצעות</h4>
          <Bars values={hist} labels={bins.map((b) => `${shekel(b)}–${shekel(b + 499)}`)} fmt={(v) => `${v} הצעות`} />
          <div className="mt-1 flex justify-between text-xs text-muted" dir="ltr">
            <span>{shekel(bins[0])}</span>
            <span>{shekel(bins[bins.length - 1] + 500)}</span>
          </div>
        </div>
      )}

      {hoursWithData.length > 0 && (
        <div>
          <h4 className="mb-1 text-sm font-medium">מתי היית כאן (זמן עם מיקום לפי שעה)</h4>
          <Bars
            values={a.timeByHour.slice(hFrom, hTo)}
            labels={Array.from({ length: hTo - hFrom }, (_, i) => `${String(hFrom + i).padStart(2, "0")}:00`)}
            fmt={(v) => dur(v)}
          />
          <div className="mt-1 flex justify-between text-xs text-muted" dir="ltr">
            <span>{String(hFrom).padStart(2, "0")}:00</span>
            <span>{String(hTo).padStart(2, "0")}:00</span>
          </div>
        </div>
      )}

      {a.limits.length > 0 && (
        <div className="rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
          <p className="font-medium">מגבלות</p>
          <ul className="list-disc ps-5">
            {a.limits.map((l) => (
              <li key={l}>{l}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function Compare({ cells, names, threshold }: { cells: ExplorerCell[]; names: (c: ExplorerCell) => string; threshold: number }) {
  const rows: [string, (a: ExplorerCell) => string][] = [
    ["הצעות לשעת זמינות מאומתת", (a) => (a.ratePerVerifiedHour === null ? "אי אפשר לחשב" : a.ratePerVerifiedHour.toFixed(1))],
    ["תשלום ממוצע", (a) => shekel(a.meanAgorot)],
    [`הצעות מעל ${shekel(threshold)}`, (a) => (a.shareAbove === null ? "—" : `${Math.round(a.shareAbove * 100)}%`)],
    ["הכנסה בפועל לשעה", (a) => (a.incomePerShiftHourAgorot === null ? "אין משלוחים שהושלמו" : shekel(a.incomePerShiftHourAgorot))],
    ["זמן המתנה להצעה", (a) => (a.waitPerOfferMs === null ? "אין זמן מאומת" : dur(a.waitPerOfferMs))],
    ["גודל המדגם", (a) => `${a.offers} הצעות · ${a.shifts} משמרות · ${dur(a.measuredMs)}`],
    ["רמת ביטחון", (a) => CONF[a.confidence].label],
  ];
  return (
    <div className="overflow-x-auto" data-testid="area-compare">
      <table className="w-full min-w-[420px] text-sm">
        <thead>
          <tr className="border-b border-border">
            <th className="p-2 text-start font-medium text-muted">מדד</th>
            {cells.map((c) => (
              <th key={c.cellId} className="p-2 text-start font-semibold">
                {names(c)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map(([label, f]) => (
            <tr key={label} className="border-b border-border/60">
              <td className="p-2 text-muted">{label}</td>
              {cells.map((c) => (
                <td key={c.cellId} className="p-2">
                  {f(c)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="mt-2 text-xs text-muted">העמודות בסדר שבחרת — לא מדורגות. אף אזור לא מוכרז כמומלץ.</p>
    </div>
  );
}

export function Explorer({ cells, thresholdAgorot }: { cells: ExplorerCell[]; thresholdAgorot: number }) {
  const router = useRouter();
  const pathname = usePathname();
  const sp = useSearchParams();
  const metric = ((MAP_METRICS as readonly string[]).includes(sp.get("metric") ?? "") ? sp.get("metric") : "offers") as MapMetric;
  const [selected, setSelected] = useState<string | null>(cells[0]?.cellId ?? null);
  const [compared, setCompared] = useState<string[]>([]);
  const [placeNames, setPlaceNames] = useState<Record<string, string>>({});

  const nameOf = (c: ExplorerCell) => placeNames[c.cellId] ?? `אזור ${c.code}`;
  const values = cells.map((c) => metricValue(c, metric)).filter((v): v is number => v !== null);
  const mapCells: MapCell[] = useMemo(
    () => cells.filter((c) => c.polygon).map((c) => ({ cellId: c.cellId, polygon: c.polygon!, value: metricValue(c, metric), label: placeNames[c.cellId] ?? c.code })),
    [cells, metric, placeNames],
  );
  const sel = cells.find((c) => c.cellId === selected) ?? null;
  const setMetric = (m: string) => {
    const q = new URLSearchParams(sp.toString());
    q.set("metric", m);
    router.replace(`${pathname}?${q.toString()}`, { scroll: false });
  };
  const toggleCompare = (id: string) => setCompared((c) => (c.includes(id) ? c.filter((x) => x !== id) : c.length >= 3 ? c : [...c, id]));

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          מה לצבוע על המפה
          <select className="rounded-md border border-border bg-surface px-2 py-1.5" value={metric} onChange={(e) => setMetric(e.target.value)} data-testid="metric-select">
            {MAP_METRICS.map((m) => (
              <option key={m} value={m}>
                {METRIC_INFO[m].label}
              </option>
            ))}
          </select>
        </label>
        <p className="text-sm text-muted">{METRIC_INFO[metric].note}</p>
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_380px]">
        <div className="space-y-2">
          <OpportunityMap cells={mapCells} selected={selected} compared={compared} onSelect={setSelected} onNames={setPlaceNames} />
          <div className="flex flex-wrap items-center gap-3 text-xs" data-testid="map-legend">
            <span className="font-medium">{METRIC_INFO[metric].label}:</span>
            {values.length ? (
              <>
                <span>{METRIC_INFO[metric].fmt(Math.min(...values))}</span>
                <span className="flex h-2.5 w-28 overflow-hidden rounded-sm" dir="ltr">
                  {RAMP.map((c) => (
                    <span key={c} className="flex-1" style={{ background: c }} />
                  ))}
                </span>
                <span>{METRIC_INFO[metric].fmt(Math.max(...values))}</span>
              </>
            ) : (
              <span className="text-muted">אין עדיין ערך לאף אזור במדד הזה</span>
            )}
            <span className="flex items-center gap-1">
              <span className="inline-block h-2.5 w-2.5 rounded-sm bg-[#9a9a94]/60" /> אין נתון — לא מדורג
            </span>
            <span className="text-muted">שמות מקומות לפי המפה, כשיש כזה בטווח של 800 מ׳</span>
          </div>
        </div>

        <div className="space-y-3 rounded-lg border border-border bg-surface p-4">
          {sel ? <Details a={sel} name={nameOf(sel)} threshold={thresholdAgorot} /> : <p className="text-sm text-muted">לחצ/י על ריבוע במפה כדי לראות פרטים.</p>}
          {sel && (
            <button
              type="button"
              className="w-full rounded-md border border-border px-3 py-2 text-sm hover:bg-border/30 disabled:opacity-50"
              onClick={() => toggleCompare(sel.cellId)}
              disabled={!compared.includes(sel.cellId) && compared.length >= 3}
              data-testid="compare-toggle"
            >
              {compared.includes(sel.cellId) ? "הסר מההשוואה" : compared.length >= 3 ? "אפשר להשוות עד 3 אזורים" : "הוסף להשוואה"}
            </button>
          )}
        </div>
      </div>

      <div className="rounded-lg border border-border bg-surface p-4">
        <h2 className="mb-2 font-semibold">כל האזורים בחלון הזמן</h2>
        <ul className="divide-y divide-border/60">
          {cells.map((c) => (
            <li key={c.cellId}>
              <button type="button" className={`flex w-full items-center justify-between gap-2 py-2 text-start text-sm ${c.cellId === selected ? "font-semibold" : ""}`} onClick={() => setSelected(c.cellId)}>
                <span>{nameOf(c)}</span>
                <span className="text-muted">
                  {c.offers} הצעות · {dur(c.measuredMs)} · {CONF[c.confidence].label}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </div>

      <div className="rounded-lg border border-border bg-surface p-4">
        <h2 className="mb-1 font-semibold">השוואת אזורים</h2>
        {compared.length >= 2 ? (
          <Compare cells={compared.map((id) => cells.find((c) => c.cellId === id)!).filter(Boolean)} names={nameOf} threshold={thresholdAgorot} />
        ) : (
          <p className="text-sm text-muted">
            בחר/י 2–3 אזורים (לחיצה על ריבוע ← ״הוסף להשוואה״). נבחרו: {compared.length}. ההשוואה היא באותו חלון שעות שנבחר למעלה.
          </p>
        )}
      </div>
    </div>
  );
}
