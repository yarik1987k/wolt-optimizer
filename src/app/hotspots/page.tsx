import { Suspense } from "react";
import { Explorer, StateBar } from "@/components/hotspots/explorer";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { PLANNER_MIN_SHIFTS, type TimeFilter } from "@/lib/hotspots/explore";
import { getExploreView, parseExploreParams } from "@/lib/hotspots/service";

export const dynamic = "force-dynamic";

const pad = (n: number) => String(n).padStart(2, "0");
/** Keeps "18–22" in reading order inside Hebrew text (isolates it as a left-to-right run). */
const ltr = (x: string) => `\u2066${x}\u2069`;
const DAYS = ["א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳"];
const dur = (ms: number) => {
  const m = Math.round(ms / 60_000);
  return m < 60 ? `${m} דק׳` : `${Math.floor(m / 60)} ש׳ ${m % 60} דק׳`;
};

type Search = Record<string, string | string[] | undefined>;

/** Link that changes only the time window (keeps days, dates, threshold and metric). */
function windowHref(sp: Search, from: number | null, to: number | null) {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(sp)) {
    if (k === "from" || k === "to" || v === undefined) continue;
    for (const x of Array.isArray(v) ? v : [v]) q.append(k, x);
  }
  if (from !== null && to !== null) {
    q.set("from", String(from));
    q.set("to", String(to));
  }
  const s = q.toString();
  return s ? `/hotspots?${s}` : "/hotspots";
}

function windowLabel(f: TimeFilter) {
  const hours = f.fromHour === 0 && f.toHour === 24 ? "כל היום" : ltr(`${pad(f.fromHour)}:00–${pad(f.toHour % 24)}:00`);
  const days = f.weekdays.length ? ` · ימים ${f.weekdays.map((d) => DAYS[d]).join(" ")}` : "";
  const dates = f.dateFrom || f.dateTo ? ` · ${f.dateFrom ?? "…"} עד ${f.dateTo ?? "…"}` : "";
  return hours + days + dates;
}

function TimeSelector({ sp, f, threshold, dates }: { sp: Search; f: TimeFilter; threshold: number; dates: string[] }) {
  const presets: [string, number | null, number | null][] = [
    ["כל היום", null, null],
    [`${ltr("18–22")} (משמרת יעד)`, 18, 22],
    [ltr("18–20"), 18, 20],
    [ltr("20–22"), 20, 22],
    [ltr("10–12"), 10, 12],
    [ltr("12–14"), 12, 14],
    [ltr("14–16"), 14, 16],
  ];
  const isAll = f.fromHour === 0 && f.toHour === 24;
  const field = "rounded-md border border-border bg-surface px-2 py-1.5";
  return (
    <Card>
      <CardContent className="space-y-3 p-4">
        <div className="flex flex-wrap gap-2" data-testid="time-presets">
          {presets.map(([label, a, b]) => {
            const active = a === null ? isAll : f.fromHour === a && f.toHour === b;
            return (
              <a
                key={label}
                href={windowHref(sp, a, b)}
                className={`rounded-full border px-3 py-1 text-sm ${active ? "border-primary bg-primary text-primary-foreground" : "border-border hover:bg-border/30"}`}
              >
                {label}
              </a>
            );
          })}
        </div>
        <details>
          <summary className="cursor-pointer text-sm text-muted">חלון מותאם: שעה, ימים, תאריכים, סף ״הצעה גבוהה״</summary>
          <form method="get" action="/hotspots" className="mt-3 flex flex-wrap items-end gap-3 text-sm" aria-label="חלון זמן">
            {typeof sp.metric === "string" && <input type="hidden" name="metric" value={sp.metric} />}
            <label className="flex flex-col gap-1">
              משעה
              <select name="from" defaultValue={isAll ? "" : String(f.fromHour)} className={field}>
                <option value="">כל היום</option>
                {Array.from({ length: 24 }, (_, h) => (
                  <option key={h} value={h}>
                    {pad(h)}:00
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1">
              עד
              <select name="to" defaultValue={isAll ? "" : String(f.toHour)} className={field}>
                <option value="">שעה אחת</option>
                {Array.from({ length: 24 }, (_, i) => i + 1).map((h) => (
                  <option key={h} value={h}>
                    {pad(h % 24)}:00
                  </option>
                ))}
              </select>
            </label>
            <fieldset className="flex flex-col gap-1">
              <legend className="mb-1">ימים (ריק = כל הימים)</legend>
              <div className="flex flex-wrap gap-1">
                {DAYS.map((d, i) => (
                  <label key={d} className="flex items-center gap-1 rounded border border-border px-1.5 py-1">
                    <input type="checkbox" name="days" value={i} defaultChecked={f.weekdays.includes(i)} />
                    {d}
                  </label>
                ))}
              </div>
            </fieldset>
            <label className="flex flex-col gap-1">
              מתאריך
              <input type="date" name="dateFrom" defaultValue={f.dateFrom ?? ""} min={dates[0]} max={dates[dates.length - 1]} className={field} />
            </label>
            <label className="flex flex-col gap-1">
              עד תאריך
              <input type="date" name="dateTo" defaultValue={f.dateTo ?? ""} min={dates[0]} max={dates[dates.length - 1]} className={field} />
            </label>
            <label className="flex flex-col gap-1">
              ״הצעה גבוהה״ מ-₪
              <input type="number" name="above" min={1} max={999} step={1} defaultValue={threshold / 100} className={`w-20 ${field}`} />
            </label>
            <button type="submit" className="rounded-md bg-primary px-4 py-1.5 text-primary-foreground">
              הצג
            </button>
          </form>
        </details>
      </CardContent>
    </Card>
  );
}

export default async function HotspotsPage({ searchParams }: { searchParams: Promise<Search> }) {
  const sp = await searchParams;
  const params = parseExploreParams(sp);
  const v = await getExploreView(params);
  const t = v.result.totals;
  const f = params.filter;

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-semibold">מפת הזדמנויות</h1>
        <p className="text-sm text-muted">איפה ראית הצעות, באיזו שעה ובאיזה מחיר — לפי משמרות שהופעלו ב-Collector. תיעוד בלבד, לא המלצה לאן לנסוע.</p>
      </div>

      {!v.hasLocationData ? (
        <Card data-testid="hotspots-empty">
          <CardHeader>
            <CardTitle>עדיין אין נתוני מיקום</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            <p>
              המפה נבנית רק ממשמרות שהופעלו ב-<strong>Wolt Offer Collector</strong> (״התחלת משמרת״, עם הרשאת מיקום). נשמר רק ריבוע של כ-500 מ׳ — בלי מסלול ובלי
              כתובות.
            </p>
            <p>
              אחרי משמרת: ייצוא מה-Collector וייבוא בעמוד <a className="underline" href="/offers">הצעות</a>.
            </p>
          </CardContent>
        </Card>
      ) : (
        <>
          <TimeSelector sp={sp} f={f} threshold={params.thresholdAgorot} dates={v.dates} />

          <Card data-testid="data-coverage">
            <CardHeader>
              <CardTitle>{windowLabel(f)}</CardTitle>
              <CardDescription>
                {t.shifts} משמרות · {dur(t.shiftMs)} בתוך משמרות · {t.offersInShifts} הצעות עם מיקום
                {t.offersNoCell ? ` (${t.offersNoCell} בלי אזור)` : ""}
                {t.offersOutsideShifts ? ` · ${t.offersOutsideShifts} הצעות נוספות כשהאיסוף לא פעל` : ""}
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              <StateBar timeMs={t.timeMs} />
              <p className="text-sm">
                <span className="font-medium">כיסוי GPS:</span> {dur(t.measuredMs)} עם מיקום תקין
                {t.noFixMs ? `, ${dur(t.noFixMs)} בלי מיקום` : ""}. <span className="text-muted">זמן עם GPS אינו זמן זמינות.</span>
              </p>
              {t.timeMs.AVAILABLE_VERIFIED === 0 && (
                <p className="rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-sm" data-testid="no-verified-note">
                  אין זמן זמינות <strong>מאומת</strong> בחלון הזה, ולכן לא מוצגים ״הצעות לשעה״ ו״זמן המתנה״.
                </p>
              )}
              {t.completedTrips === 0 && <p className="text-sm text-muted">הכנסה בפועל לשעה תופיע רק אחרי משלוחים שהושלמו — עדיין אין.</p>}
            </CardContent>
          </Card>

          {v.cells.length === 0 ? (
            <Card>
              <CardContent className="p-4 text-sm text-muted">אין אזורים בחלון הזמן הזה. נסה/י ״כל היום״.</CardContent>
            </Card>
          ) : (
            <Suspense>
              <Explorer cells={v.cells} thresholdAgorot={params.thresholdAgorot} />
            </Suspense>
          )}

          <Card data-testid="shift-planner">
            <CardHeader>
              <CardTitle>מתכנן משמרת — עדיין לא פעיל</CardTitle>
              <CardDescription>
                בעתיד יופיע כאן תכנון כמו ״18:00–19:00 אזור X ← 19:00–20:30 אזור Y״. הוא יופעל רק כשיש מספיק נתונים מניסויים חוזרים — לא על סמך ניחוש.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="grid gap-2 opacity-50 sm:grid-cols-3" aria-hidden>
                {["18:00–19:00", "19:00–20:30", "20:30–22:00"].map(ltr).map((w) => (
                  <div key={w} className="rounded-md border border-dashed border-border p-3 text-sm">
                    <div className="font-medium">{w}</div>
                    <div className="text-muted">—</div>
                  </div>
                ))}
              </div>
              <p className="text-sm font-medium">מה חסר כדי להפעיל:</p>
              <ul className="space-y-1 text-sm">
                {(
                  [
                    [v.result.planner.shiftsInWindow >= PLANNER_MIN_SHIFTS, `לפחות ${PLANNER_MIN_SHIFTS} משמרות בחלון הזמן (יש ${v.result.planner.shiftsInWindow})`],
                    [v.result.planner.completedTrips > 0, `משלוחים שהושלמו, כדי לדעת הכנסה בפועל (יש ${v.result.planner.completedTrips})`],
                    [t.timeMs.AVAILABLE_VERIFIED > 0, "זמן זמינות מאומת"],
                    [v.result.planner.hasTravelCost, "מהירות ממוצעת ועלות לק״מ בהגדרות (זמן ועלות הגעה לאזור)"],
                  ] as [boolean, string][]
                ).map(([ok, label]) => (
                  <li key={label} className="flex items-center gap-2">
                    <span aria-hidden>{ok ? "✅" : "⬜"}</span>
                    {label}
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
